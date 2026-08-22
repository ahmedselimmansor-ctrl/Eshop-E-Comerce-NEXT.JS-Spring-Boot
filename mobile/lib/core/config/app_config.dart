import 'package:flutter/widgets.dart';

/// Build-time configuration.
///
/// Read from `--dart-define`, not from a checked-in file: the API host differs per environment, and
/// baking it into source means three branches that differ only in a URL.
///
///   flutter run --dart-define=API_BASE_URL=http://10.0.2.2:8080
///
/// (10.0.2.2 is how the Android emulator reaches the host machine's localhost.)
abstract final class AppConfig {
  static const apiBaseUrl = String.fromEnvironment(
    'API_BASE_URL',
    defaultValue: 'http://10.0.2.2:8080',
  );

  static const cdnBaseUrl = String.fromEnvironment('CDN_BASE_URL', defaultValue: '');

  /// Arabic first - it is the default, and the order here is what `supportedLocales` resolves
  /// against when the device asks for a language neither entry matches exactly.
  static const supportedLocales = [Locale('ar'), Locale('en')];

  static const defaultLocale = Locale('ar');
  static const currencyCode = 'EGP';

  /// Where the customer is returned after a Paymob or Fawry payment page. Registered as an app
  /// link so the browser hands control back rather than leaving them on a web page.
  static const paymentReturnUrl = 'eshop://checkout/callback';
}
