import 'package:flutter/material.dart';
import 'package:intl/intl.dart';

import '../models/money.dart';

/// Formats a [Money] for display.
///
/// Digits are forced to Western (`0-9`) even in Arabic. Egyptian storefronts and bank statements
/// use them, and Eastern Arabic numerals on a price next to Western ones in the totals is the sort
/// of inconsistency that makes a store look unfinished.
String formatMoney(Money money, {String locale = 'en'}) {
  final format = NumberFormat.currency(
    locale: locale == 'ar' ? 'ar_EG' : 'en_EG',
    symbol: money.currency == 'EGP' ? (locale == 'ar' ? 'ج.م' : 'EGP') : money.currency,
    decimalDigits: 2,
  );
  // ar_EG renders Eastern Arabic numerals; map them back to Western digits.
  return format.format(money.asDouble).replaceAllMapped(
        RegExp(r'[\u0660-\u0669]'),
        (match) => String.fromCharCode(match.group(0)!.codeUnitAt(0) - 0x0660 + 0x30),
      );
}

/// A price, with the pre-sale price struck through beside it when the product is discounted.
class MoneyText extends StatelessWidget {
  const MoneyText(
    this.price, {
    super.key,
    this.compareAt,
    this.style,
    this.compareStyle,
  });

  final Money price;
  final Money? compareAt;
  final TextStyle? style;
  final TextStyle? compareStyle;

  @override
  Widget build(BuildContext context) {
    final locale = Localizations.localeOf(context).languageCode;
    final theme = Theme.of(context);

    final primary = Text(
      formatMoney(price, locale: locale),
      style: style ?? theme.textTheme.titleMedium?.copyWith(fontWeight: FontWeight.w600),
    );

    if (compareAt == null) return primary;

    // Wrap, not Row. Two EGP prices side by side are about 170 logical pixels, and a product tile in
    // the home rail is 156 wide, so a Row overflows for every discounted product - visibly, with the
    // striped overflow banner in debug and a clipped price in release. Wrap lays them out on one
    // line where there is room and drops the struck-through price below where there is not.
    return Wrap(
      crossAxisAlignment: WrapCrossAlignment.center,
      spacing: 6,
      children: [
        primary,
        Text(
          formatMoney(compareAt!, locale: locale),
          style: compareStyle ??
              theme.textTheme.bodySmall?.copyWith(
                decoration: TextDecoration.lineThrough,
                color: theme.colorScheme.onSurfaceVariant,
              ),
        ),
      ],
    );
  }
}
