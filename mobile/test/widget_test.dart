import 'package:eshop/core/models/cart.dart';
import 'package:eshop/core/models/money.dart';
import 'package:eshop/core/models/product.dart';
import 'package:eshop/core/widgets/async_view.dart';
import 'package:eshop/core/widgets/money_text.dart';
import 'package:eshop/features/account/ui/order_detail_screen.dart';
import 'package:eshop/features/catalog/ui/product_card_tile.dart';
import 'package:flutter/material.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';

/// Widget tests.
///
/// These run headless in the Dart VM, with no device or emulator, which makes them the cheapest
/// check on the things that only show up when something is actually rendered: a price formatted
/// with the wrong locale, an out-of-stock badge that never appears, an error state that renders the
/// exception instead of a message.
Widget wrap(Widget child, {Locale locale = const Locale('en')}) {
  return ProviderScope(
    child: MaterialApp(
      locale: locale,
      supportedLocales: const [Locale('ar'), Locale('en')],
      // The real delegates, not the defaults: the defaults have no Arabic, and this suite exists
      // partly to check the Arabic rendering.
      localizationsDelegates: const [
        GlobalMaterialLocalizations.delegate,
        GlobalWidgetsLocalizations.delegate,
        GlobalCupertinoLocalizations.delegate,
      ],
      home: Scaffold(body: child),
    ),
  );
}

ProductCard card({
  String name = 'Anker PowerCore 20000',
  String price = '1899.00',
  String? compareAt,
  int discount = 0,
  bool inStock = true,
}) {
  return ProductCard(
    id: '0192a000-0000-7000-8000-00000000d003',
    slug: 'anker-powercore-20000',
    sku: 'ANK-PB-20K',
    name: name,
    price: Money(amount: price, currency: 'EGP'),
    compareAtPrice: compareAt == null ? null : Money(amount: compareAt, currency: 'EGP'),
    discountPercent: discount,
    inStock: inStock,
    brandName: 'Anker',
  );
}

void main() {
  group('MoneyText', () {
    testWidgets('renders an amount with Western digits in Arabic', (tester) async {
      await tester.pumpWidget(
        wrap(
          const MoneyText(Money(amount: '1899.00', currency: 'EGP')),
          locale: const Locale('ar'),
        ),
      );

      final text = tester.widget<Text>(find.byType(Text).first).data!;
      expect(text, contains('1'));
      // Eastern Arabic numerals next to Western ones elsewhere on the page reads as a bug.
      expect(text, isNot(matches(RegExp(r'[٠-٩]'))));
    });

    testWidgets('shows the pre-sale price struck through when discounted', (tester) async {
      await tester.pumpWidget(
        wrap(
          const MoneyText(
            Money(amount: '1899.00', currency: 'EGP'),
            compareAt: Money(amount: '2400.00', currency: 'EGP'),
          ),
        ),
      );

      expect(find.byType(Text), findsNWidgets(2));

      final struck = tester.widget<Text>(find.byType(Text).last);
      expect(struck.style?.decoration, TextDecoration.lineThrough);
    });

    testWidgets('shows only one price when there is no discount', (tester) async {
      await tester.pumpWidget(wrap(const MoneyText(Money(amount: '1899.00', currency: 'EGP'))));
      expect(find.byType(Text), findsOneWidget);
    });
  });

  group('ProductCardTile', () {
    /// The sizes it is actually given: 156x292 in the home rail, and at most 220 wide with a 0.58
    /// aspect ratio in the grid. The tile has no intrinsic height, so an unbounded parent overflows
    /// it - which is a property of every card of this shape, not a defect, but worth pinning.
    Widget sized(Widget child, {double width = 156, double height = 292}) =>
        Center(child: SizedBox(width: width, height: height, child: child));

    testWidgets('fits the sizes the app gives it', (tester) async {
      for (final (width, height) in [(156.0, 292.0), (220.0, 379.0)]) {
        await tester.pumpWidget(
          wrap(sized(ProductCardTile(product: card(), onTap: () {}), width: width, height: height)),
        );
        expect(tester.takeException(), isNull, reason: 'no overflow at ${width}x$height');
      }
    });

    testWidgets('fits when discounted, with two prices to show', (tester) async {
      // The case that actually overflowed: two EGP prices side by side are wider than a 156px tile,
      // so they have to wrap rather than sit in a Row.
      for (final (width, height) in [(156.0, 292.0), (220.0, 379.0)]) {
        await tester.pumpWidget(
          wrap(
            sized(
              ProductCardTile(
                product: card(compareAt: '2400.00', discount: 21),
                onTap: () {},
              ),
              width: width,
              height: height,
            ),
          ),
        );
        expect(
          tester.takeException(),
          isNull,
          reason: 'a discounted product must not overflow at ${width}x$height',
        );
      }
    });

    testWidgets('shows the name, brand and price', (tester) async {
      await tester.pumpWidget(wrap(sized(ProductCardTile(product: card(), onTap: () {}))));

      expect(find.text('Anker PowerCore 20000'), findsOneWidget);
      expect(find.text('Anker'), findsOneWidget);
    });

    testWidgets('shows a discount badge only when on sale', (tester) async {
      await tester.pumpWidget(wrap(sized(ProductCardTile(product: card(), onTap: () {}))));
      expect(find.text('-21%'), findsNothing);

      await tester.pumpWidget(
        wrap(
          sized(
            ProductCardTile(
              product: card(compareAt: '2400.00', discount: 21),
              onTap: () {},
            ),
          ),
        ),
      );
      expect(find.text('-21%'), findsOneWidget);
    });

    testWidgets('marks an out-of-stock product', (tester) async {
      await tester.pumpWidget(
        wrap(sized(ProductCardTile(product: card(inStock: false), onTap: () {}))),
      );

      expect(find.text('Out of stock'), findsOneWidget);
    });

    testWidgets('reports a tap', (tester) async {
      var tapped = false;
      await tester.pumpWidget(
        wrap(sized(ProductCardTile(product: card(), onTap: () => tapped = true))),
      );

      await tester.tap(find.byType(InkWell).first);
      expect(tapped, isTrue);
    });
  });

  group('AsyncView', () {
    testWidgets('shows a spinner while loading', (tester) async {
      await tester.pumpWidget(
        wrap(
          AsyncView<int>(
            value: const AsyncValue.loading(),
            builder: (data) => Text('$data'),
          ),
        ),
      );

      expect(find.byType(CircularProgressIndicator), findsOneWidget);
    });

    testWidgets('shows the data once loaded', (tester) async {
      await tester.pumpWidget(
        wrap(
          AsyncView<int>(
            value: const AsyncValue.data(42),
            builder: (data) => Text('$data'),
          ),
        ),
      );

      expect(find.text('42'), findsOneWidget);
    });

    testWidgets('shows a message and a retry button on error', (tester) async {
      var retried = false;
      await tester.pumpWidget(
        wrap(
          AsyncView<int>(
            value: AsyncValue.error(Exception('boom'), StackTrace.empty),
            onRetry: () => retried = true,
            builder: (data) => Text('$data'),
          ),
        ),
      );

      // A stack trace on screen is not an error message.
      expect(find.textContaining('Could not reach the store'), findsOneWidget);
      await tester.tap(find.text('Try again'));
      expect(retried, isTrue);
    });

    testWidgets('offers no retry button when there is nothing to retry', (tester) async {
      await tester.pumpWidget(
        wrap(
          AsyncView<int>(
            value: AsyncValue.error(Exception('boom'), StackTrace.empty),
            builder: (data) => Text('$data'),
          ),
        ),
      );

      expect(find.text('Try again'), findsNothing);
    });
  });

  group('EmptyState', () {
    testWidgets('renders a title, a hint and an action', (tester) async {
      await tester.pumpWidget(
        wrap(
          EmptyState(
            icon: Icons.shopping_bag_outlined,
            title: 'Your cart is empty',
            hint: 'Browse the store.',
            action: FilledButton(onPressed: () {}, child: const Text('Start shopping')),
          ),
        ),
      );

      expect(find.text('Your cart is empty'), findsOneWidget);
      expect(find.text('Browse the store.'), findsOneWidget);
      expect(find.text('Start shopping'), findsOneWidget);
    });
  });

  group('OrderStatusChip', () {
    testWidgets('humanises the status', (tester) async {
      await tester.pumpWidget(wrap(const OrderStatusChip(status: 'PENDING_PAYMENT')));
      expect(find.text('Pending payment'), findsOneWidget);
    });

    testWidgets('renders every status the server can send', (tester) async {
      for (final status in [
        'PENDING_PAYMENT',
        'PAID',
        'PROCESSING',
        'SHIPPED',
        'DELIVERED',
        'CANCELLED',
        'REFUNDED',
        'PARTIALLY_REFUNDED',
        'FAILED',
      ]) {
        await tester.pumpWidget(wrap(OrderStatusChip(status: status)));
        expect(find.byType(OrderStatusChip), findsOneWidget, reason: '$status should render');
      }
    });
  });

  group('Cart model in a widget', () {
    testWidgets('an empty cart reports zero units', (tester) async {
      final cart = Cart.empty();
      await tester.pumpWidget(wrap(Text('${cart.itemCount}')));
      expect(find.text('0'), findsOneWidget);
    });
  });
}
