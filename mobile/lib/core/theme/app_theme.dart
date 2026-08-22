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
  static String? _fontFamily(Locale locale) =>
      locale.languageCode == 'ar' ? 'IBMPlexSansArabic' : null;

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
      textTheme: locale.languageCode == 'ar'
          ? Typography.material2021().black.apply(fontSizeFactor: 1.0, heightDelta: 0.15)
          : null,

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

      extensions: [_SaleColor(_sale)],
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
