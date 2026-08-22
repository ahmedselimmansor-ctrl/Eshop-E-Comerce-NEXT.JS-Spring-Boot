import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/models/order.dart';
import '../../../core/network/api_client.dart';
import '../../../core/providers/api_providers.dart';

/// Sign in, register, sign out.
///
/// The tokens never surface here. [ApiClient] writes the refresh token straight into the platform
/// keychain and keeps the access token in memory, so nothing above this layer can accidentally log
/// one, persist one somewhere less protected, or hold a copy that outlives a rotation.
class AuthRepository {
  AuthRepository(this._client);

  final ApiClient _client;

  Future<Account> login({required String emailOrPhone, required String password}) async {
    final response = await _client.post<Map<String, dynamic>>(
      '/api/v1/auth/login',
      body: {'emailOrPhone': emailOrPhone, 'password': password},
      parse: (json) => json as Map<String, dynamic>,
    );
    return _storeSession(response);
  }

  Future<Account> register({
    required String email,
    required String password,
    required String firstName,
    String? lastName,
    String? phone,
  }) async {
    final response = await _client.post<Map<String, dynamic>>(
      '/api/v1/auth/register',
      body: {
        'email': email,
        'password': password,
        'firstName': firstName,
        if (lastName != null && lastName.isNotEmpty) 'lastName': lastName,
        if (phone != null && phone.isNotEmpty) 'phone': phone,
        'acceptTerms': true,
      },
      parse: (json) => json as Map<String, dynamic>,
    );
    return _storeSession(response);
  }

  Future<void> requestPasswordReset(String email) {
    return _client.postVoid('/api/v1/auth/forgot-password', body: {'email': email});
  }

  Future<void> logout() async {
    // Told to the server first so the refresh-token family is revoked there, not merely forgotten
    // here. A token still valid on the server is a live session on any device that holds it.
    try {
      await _client.postVoid('/api/v1/auth/logout');
    } catch (_) {
      // Signing out must succeed locally even with no connection.
    }
    await _client.clearSession();
  }

  Future<Account> _storeSession(Map<String, dynamic> payload) async {
    final tokens = payload['tokens'] as Map<String, dynamic>;
    await _client.saveSession(
      accessToken: tokens['accessToken'] as String,
      refreshToken: tokens['refreshToken'] as String,
    );
    return Account.fromJson(payload['user'] as Map<String, dynamic>);
  }
}

final authRepositoryProvider = Provider<AuthRepository>(
  (ref) => AuthRepository(ref.watch(apiClientProvider)),
);

/// Who is signed in, or null.
///
/// Starts from whatever the keychain holds, so a returning customer is not asked to sign in again
/// on a cold start.
class SessionController extends AsyncNotifier<Account?> {
  @override
  Future<Account?> build() async {
    final client = ref.watch(apiClientProvider);
    await client.restoreSession();
    if (client.accessToken == null) return null;

    try {
      return await client.get<Account>(
        '/api/v1/me',
        parse: (json) => Account.fromJson(json as Map<String, dynamic>),
      );
    } catch (_) {
      // The stored token was revoked or expired beyond refresh; treat it as signed out rather than
      // as an error the customer has to dismiss.
      return null;
    }
  }

  Future<void> login({required String emailOrPhone, required String password}) async {
    state = const AsyncValue.loading();
    state = await AsyncValue.guard(
      () => ref.read(authRepositoryProvider).login(
            emailOrPhone: emailOrPhone,
            password: password,
          ),
    );
  }

  Future<void> register({
    required String email,
    required String password,
    required String firstName,
    String? lastName,
    String? phone,
  }) async {
    state = const AsyncValue.loading();
    state = await AsyncValue.guard(
      () => ref.read(authRepositoryProvider).register(
            email: email,
            password: password,
            firstName: firstName,
            lastName: lastName,
            phone: phone,
          ),
    );
  }

  Future<void> logout() async {
    await ref.read(authRepositoryProvider).logout();
    state = const AsyncValue.data(null);
  }
}

final sessionControllerProvider =
    AsyncNotifierProvider<SessionController, Account?>(SessionController.new);

/// Whether there is a signed-in customer, for screens that only need the yes or no.
final isSignedInProvider = Provider<bool>(
  (ref) => ref.watch(sessionControllerProvider).valueOrNull != null,
);
