import 'package:flutter/material.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import 'core/config/app_config.dart';
import 'core/router/app_router.dart';
import 'core/theme/app_theme.dart';
import 'features/settings/locale_controller.dart';

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();

  runApp(const ProviderScope(child: EshopApp()));
}

class EshopApp extends ConsumerWidget {
  const EshopApp({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final locale = ref.watch(localeControllerProvider);
    final router = ref.watch(appRouterProvider);

    return MaterialApp.router(
      title: 'Eshop',
      debugShowCheckedModeBanner: false,

      routerConfig: router,

      theme: AppTheme.light(locale),
      darkTheme: AppTheme.dark(locale),
      themeMode: ThemeMode.system,

      locale: locale,
      supportedLocales: AppConfig.supportedLocales,
      localizationsDelegates: const [
        GlobalMaterialLocalizations.delegate,
        GlobalWidgetsLocalizations.delegate,
        GlobalCupertinoLocalizations.delegate,
      ],

      /// Text direction follows the locale automatically through `Directionality`, which Flutter
      /// derives from `locale`. Everything below it must therefore use directional properties
      /// (`EdgeInsetsDirectional`, `AlignmentDirectional`, `start`/`end`) rather than left/right -
      /// hard-coded sides are the single most common source of a broken Arabic layout.
      builder: (context, child) {
        return MediaQuery.withClampedTextScaling(
          // Respects the platform's accessibility text size, but bounds it: past ~1.4× the checkout
          // summary stops fitting on a phone, and an unreadable layout serves nobody.
          minScaleFactor: 0.9,
          maxScaleFactor: 1.4,
          child: child ?? const SizedBox.shrink(),
        );
      },
    );
  }
}
