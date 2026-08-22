import 'package:flutter/widgets.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../../core/config/app_config.dart';

/// The app's language.
///
/// Persisted, because a customer who switched to English should not find the app in Arabic after
/// the next cold start. Stored in SharedPreferences rather than secure storage - a language
/// preference is not a secret.
class LocaleController extends Notifier<Locale> {
  static const _key = 'eshop.locale';

  @override
  Locale build() {
    // Starts from the default and upgrades asynchronously once preferences load. Blocking the
    // first frame on disk I/O to avoid a single rebuild is not a trade worth making.
    _restore();
    return AppConfig.defaultLocale;
  }

  Future<void> _restore() async {
    final preferences = await SharedPreferences.getInstance();
    final saved = preferences.getString(_key);
    if (saved != null && saved != state.languageCode) {
      state = Locale(saved);
    }
  }

  Future<void> setLocale(Locale locale) async {
    state = locale;
    final preferences = await SharedPreferences.getInstance();
    await preferences.setString(_key, locale.languageCode);
  }

  void toggle() {
    setLocale(state.languageCode == 'ar' ? const Locale('en') : const Locale('ar'));
  }
}

final localeControllerProvider =
    NotifierProvider<LocaleController, Locale>(LocaleController.new);
