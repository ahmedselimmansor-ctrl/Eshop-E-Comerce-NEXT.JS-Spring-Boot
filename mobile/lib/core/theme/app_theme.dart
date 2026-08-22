import 'package:flutter/material.dart';

/// The app's visual language, matching the web storefront's tokens.
///
/// The same deep teal, the same reserved orange for discounts. A customer moving between the site
/// and the app should not feel they have changed brand.
abstract final class AppTheme {
  static const _brand = Color(0xFF0E7C86);
  static const _sale = Color(0xFFE85D2C);

  /// IBM Plex Sans Arabic for Arabic, the platform default for English.
  ///
  /// Arabic set in a Latin-first font falls back per glyph and renders unevenly — mismatched
  /// weights, wrong joins, inconsistent baselines.
  /// Null in both languages: the platform's own font is used.
  ///
  /// The Arabic family this was written for is not vendored in the repository — see the note in
  /// pubspec.yaml. Naming a font that is not bundled does not fail loudly; Flutter silently falls
  /// back, which looks like the family was applied when it was not.
  static String? _fontFamily(Locale locale) => null;

  static ThemeData light(Locale locale) => _build(Brightness.light, locale);
  static ThemeData dark(Locale locale) => _build(Brightness.dark, locale);

  static ThemeData _build(Brightness brightness, Locale locale) {
    final scheme = ColorScheme.fromSeed(
      seedColor: _brand,
      brightness: brightness,
      error: const Color(0xFFD93025),
    );

    return ThemeData(
      useMaterial3: true,
      colorScheme: scheme,
      fontFamily: _fontFamily(locale),

      // Arabic needs more line height than Latin at the same size: taller ascenders and
      // descenders, and diacritics that sit above the line.
      // `apply` has no heightDelta, so the line height is set per style. Arabic needs more of it
      // than Latin at the same size: taller ascenders and descenders, and diacritics above the line.
      textTheme: locale.languageCode == 'ar' ? _arabicTextTheme(brightness) : null,

      appBarTheme: AppBarTheme(
        centerTitle: false,
        elevation: 0,
        scrolledUnderElevation: 1,
        backgroundColor: scheme.surface,
        foregroundColor: scheme.onSurface,
      ),

      filledButtonTheme: FilledButtonThemeData(
        style: FilledButton.styleFrom(
          // 48dp — comfortably above the 44dp minimum tap target, which matters most on the
          // add-to-cart and place-order buttons.
          minimumSize: const Size.fromHeight(48),
          shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(10)),
        ),
      ),

      inputDecorationTheme: InputDecorationTheme(
        filled: true,
        border: OutlineInputBorder(
          borderRadius: BorderRadius.circular(10),
          borderSide: BorderSide.none,
        ),
        contentPadding: const EdgeInsetsDirectional.symmetric(horizontal: 16, vertical: 14),
      ),

      cardTheme: CardTheme(
        elevation: 0,
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.circular(12),
          side: BorderSide(color: scheme.outlineVariant),
        ),
      ),

      extensions: const [_SaleColor(_sale)],
    );
  }

  /// Material's default text theme with a looser line height throughout.
  ///
  /// 1.45 rather than Material's ~1.2: at the default height, Arabic diacritics on one line collide
  /// with the ascenders of the line below, which is legible in a screenshot and tiring to read.
  static TextTheme _arabicTextTheme(Brightness brightness) {
    final base = brightness == Brightness.dark
        ? Typography.material2021().white
        : Typography.material2021().black;

    TextStyle? loosen(TextStyle? style) => style?.copyWith(height: 1.45);

    return base.copyWith(
      displayLarge: loosen(base.displayLarge),
      displayMedium: loosen(base.displayMedium),
      displaySmall: loosen(base.displaySmall),
      headlineLarge: loosen(base.headlineLarge),
      headlineMedium: loosen(base.headlineMedium),
      headlineSmall: loosen(base.headlineSmall),
      titleLarge: loosen(base.titleLarge),
      titleMedium: loosen(base.titleMedium),
      titleSmall: loosen(base.titleSmall),
      bodyLarge: loosen(base.bodyLarge),
      bodyMedium: loosen(base.bodyMedium),
      bodySmall: loosen(base.bodySmall),
      labelLarge: loosen(base.labelLarge),
      labelMedium: loosen(base.labelMedium),
      labelSmall: loosen(base.labelSmall),
    );
  }
}

/// Discount colour, reached through the theme so it is not hard-coded at each badge.
@immutable
class _SaleColor extends ThemeExtension<_SaleColor> {
  const _SaleColor(this.color);

  final Color color;

  @override
  ThemeExtension<_SaleColor> copyWith({Color? color}) => _SaleColor(color ?? this.color);

  @override
  ThemeExtension<_SaleColor> lerp(ThemeExtension<_SaleColor>? other, double t) {
    if (other is! _SaleColor) return this;
    return _SaleColor(Color.lerp(color, other.color, t) ?? color);
  }

}
