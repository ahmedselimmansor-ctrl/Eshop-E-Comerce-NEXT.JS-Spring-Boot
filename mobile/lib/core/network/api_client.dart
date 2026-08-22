import 'dart:async';

import 'package:dio/dio.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';

import 'api_exception.dart';

/// The HTTP client every repository goes through.
///
/// Two things here differ from the web client and are worth stating, because both are consequences
/// of being a native app rather than a browser:
///
/// * **The refresh token is stored in the platform keychain**, not in a cookie - a native app has
///   no cookie jar, and the keychain (Keychain on iOS, EncryptedSharedPreferences on Android) is
///   the only storage that survives a rooted device with its contents still encrypted.
/// * **Refresh is serialised.** A screen that fires four requests at once will get four 401s when
///   the access token expires; without a lock, each would start its own refresh, and three of them
///   would present a token the first has already rotated - which the backend correctly treats as
///   replay and responds to by revoking the whole family. In other words, the naive version logs
///   the customer out at random.
class ApiClient {
  ApiClient({required String baseUrl, FlutterSecureStorage? storage})
      : _storage = storage ?? const FlutterSecureStorage(),
        _dio = Dio(
          BaseOptions(
            baseUrl: baseUrl,
            connectTimeout: const Duration(seconds: 10),
            receiveTimeout: const Duration(seconds: 20),
            sendTimeout: const Duration(seconds: 20),
            headers: {'Accept': 'application/json'},
            // Every status is "successful" as far as Dio is concerned; the interceptor below
            // decides what is an error, so failures arrive as one exception type rather than as a
            // mix of DioException and manual status checks.
            validateStatus: (_) => true,
          ),
        ) {
    _dio.interceptors.add(_AuthInterceptor(this));
  }

  static const _accessTokenKey = 'eshop.accessToken';
  static const _refreshTokenKey = 'eshop.refreshToken';

  final Dio _dio;
  final FlutterSecureStorage _storage;

  /// Held in memory for the process lifetime; re-obtained from the refresh token on a cold start.
  String? _accessToken;

  /// Serialises concurrent refreshes. The second and subsequent callers await the first's result
  /// rather than starting their own.
  Completer<bool>? _refreshInFlight;

  String? get accessToken => _accessToken;

  /// The locale sent as `Accept-Language`. Set from the app's own language selection.
  String locale = 'ar';

  Future<void> restoreSession() async {
    final refreshToken = await _storage.read(key: _refreshTokenKey);
    if (refreshToken == null) return;
    await _refresh();
  }

  Future<void> saveSession({required String accessToken, required String refreshToken}) async {
    _accessToken = accessToken;
    await _storage.write(key: _refreshTokenKey, value: refreshToken);
  }

  Future<void> clearSession() async {
    _accessToken = null;
    await _storage.delete(key: _refreshTokenKey);
    await _storage.delete(key: _accessTokenKey);
  }

  // ------------------------------------------------------------------ verbs --

  Future<T> get<T>(
    String path, {
    Map<String, dynamic>? query,
    required T Function(dynamic json) parse,
  }) async {
    final response = await _dio.get<dynamic>(path, queryParameters: query);
    return parse(_unwrap(response));
  }

  Future<T> post<T>(
    String path, {
    Object? body,
    Map<String, dynamic>? query,
    Map<String, String>? headers,
    required T Function(dynamic json) parse,
  }) async {
    final response = await _dio.post<dynamic>(
      path,
      data: body,
      queryParameters: query,
      options: headers == null ? null : Options(headers: headers),
    );
    return parse(_unwrap(response));
  }

  Future<void> postVoid(String path, {Object? body, Map<String, String>? headers}) async {
    final response = await _dio.post<dynamic>(
      path,
      data: body,
      options: headers == null ? null : Options(headers: headers),
    );
    _unwrap(response);
  }

  Future<T> patch<T>(String path, {Object? body, required T Function(dynamic json) parse}) async {
    final response = await _dio.patch<dynamic>(path, data: body);
    return parse(_unwrap(response));
  }

  Future<void> delete(String path) async {
    _unwrap(await _dio.delete<dynamic>(path));
  }

  /// Turns a non-2xx response into an [ApiException] carrying the parsed problem document, so
  /// callers branch on the stable `code` rather than on an HTTP status or a message.
  dynamic _unwrap(Response<dynamic> response) {
    final status = response.statusCode ?? 0;
    if (status >= 200 && status < 300) {
      return response.data;
    }
    throw ApiException.fromProblem(status, response.data);
  }

  // ---------------------------------------------------------------- refresh --

  /// Exchanges the stored refresh token for a new pair.
  ///
  /// Returns `false` when the session is genuinely over - the token was revoked, expired, or
  /// replayed - at which point the caller signs the customer out.
  Future<bool> _refresh() async {
    // A refresh is already running: wait for its outcome instead of starting a competing one.
    final inFlight = _refreshInFlight;
    if (inFlight != null) return inFlight.future;

    final completer = Completer<bool>();
    _refreshInFlight = completer;

    try {
      final refreshToken = await _storage.read(key: _refreshTokenKey);
      if (refreshToken == null) {
        completer.complete(false);
        return false;
      }

      // A bare Dio instance, not `_dio`: routing this through the interceptor would let a 401 on
      // the refresh call trigger another refresh, recursively.
      final response = await Dio(BaseOptions(baseUrl: _dio.options.baseUrl)).post<dynamic>(
        '/api/v1/auth/refresh',
        data: {'refreshToken': refreshToken},
        options: Options(validateStatus: (_) => true),
      );

      final status = response.statusCode ?? 0;
      if (status < 200 || status >= 300) {
        await clearSession();
        completer.complete(false);
        return false;
      }

      final payload = response.data as Map<String, dynamic>;
      final tokens = payload['tokens'] as Map<String, dynamic>;

      await saveSession(
        accessToken: tokens['accessToken'] as String,
        // Rotated on every use - the old one is now revoked, and presenting it again would be
        // treated as theft.
        refreshToken: tokens['refreshToken'] as String,
      );

      completer.complete(true);
      return true;
    } catch (_) {
      await clearSession();
      completer.complete(false);
      return false;
    } finally {
      _refreshInFlight = null;
    }
  }
}

class _AuthInterceptor extends Interceptor {
  _AuthInterceptor(this._client);

  final ApiClient _client;

  @override
  void onRequest(RequestOptions options, RequestInterceptorHandler handler) {
    final token = _client.accessToken;
    if (token != null) {
      options.headers['Authorization'] = 'Bearer $token';
    }
    options.headers['Accept-Language'] = _client.locale;
    handler.next(options);
  }

  @override
  Future<void> onResponse(
    Response<dynamic> response,
    ResponseInterceptorHandler handler,
  ) async {
    // Only a 401 is retried, and only once. `_retried` on the request's extra map is what stops a
    // token that is rejected for a reason other than expiry from looping.
    final isUnauthorised = response.statusCode == 401;
    final alreadyRetried = response.requestOptions.extra['_retried'] == true;
    final isAuthCall = response.requestOptions.path.startsWith('/api/v1/auth/');

    if (!isUnauthorised || alreadyRetried || isAuthCall) {
      handler.next(response);
      return;
    }

    final refreshed = await _client._refresh();
    if (!refreshed) {
      handler.next(response);
      return;
    }

    try {
      final options = response.requestOptions..extra['_retried'] = true;
      options.headers['Authorization'] = 'Bearer ${_client.accessToken}';

      final retried = await Dio(BaseOptions(baseUrl: options.baseUrl)).fetch<dynamic>(options);
      handler.resolve(retried);
    } catch (_) {
      handler.next(response);
    }
  }
}
