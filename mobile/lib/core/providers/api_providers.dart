import 'package:flutter/widgets.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../config/app_config.dart';
import '../network/api_client.dart';
import '../../features/settings/locale_controller.dart';

/// The single [ApiClient] for the process.
///
/// One instance, because it owns the access token and the lock that serialises refreshes - two
/// clients would each hold their own, and the second would present a token the first had already
/// rotated, which the backend treats as replay and answers by revoking the session.
final apiClientProvider = Provider<ApiClient>((ref) {
  final client = ApiClient(baseUrl: AppConfig.apiBaseUrl);

  // Keeps Accept-Language in step with the app's language, so switching to English does not leave
  // the next page of products rendering in Arabic.
  ref.listen<Locale>(
    localeControllerProvider,
    (previous, next) => client.locale = next.languageCode,
    fireImmediately: true,
  );

  return client;
});
