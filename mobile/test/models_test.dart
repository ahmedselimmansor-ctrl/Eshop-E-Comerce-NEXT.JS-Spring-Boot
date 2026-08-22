import 'package:eshop/core/models/cart.dart';
import 'package:eshop/core/models/money.dart';
import 'package:eshop/core/models/page.dart';
import 'package:eshop/core/models/product.dart';
import 'package:flutter_test/flutter_test.dart';

/// Parsing tests for the API models.
///
/// These are the parts of the mobile app that can be verified without a device, and they are also
/// where the app is most likely to break silently: a renamed field becomes a null, and a null in a
/// widget tree shows up as a blank space rather than an error.
void main() {
  group('Money', () {
    test('keeps the server\'s amount as text', () {
      final money = Money.fromJson({'amount': '1899.00', 'currency': 'EGP'});

      // Not parsed to a double on the way in: 1899.00 survives, whereas a double round-trip is
      // where prices start rendering as 1898.99.
      expect(money.amount, '1899.00');
      expect(money.currency, 'EGP');
      expect(money.asDouble, 1899.0);
    });

    test('defaults a missing currency to EGP', () {
      expect(Money.fromJson({'amount': '10.00'}).currency, 'EGP');
    });

    test('compares by value', () {
      expect(
        Money.fromJson({'amount': '5.00', 'currency': 'EGP'}),
        Money.fromJson({'amount': '5.00', 'currency': 'EGP'}),
      );
    });
  });

  group('Paged', () {
    test('reads metadata from the nested page object', () {
      final page = Paged.fromJson({
        'content': [
          {
            'id': 'p1',
            'slug': 'a-product',
            'sku': 'SKU-1',
            'name': 'A product',
            'price': {'amount': '100.00', 'currency': 'EGP'},
          },
        ],
        'page': {'number': 1, 'size': 20, 'totalElements': 41, 'totalPages': 3},
      }, ProductCard.fromJson);

      expect(page.content, hasLength(1));
      expect(page.number, 1);
      expect(page.totalElements, 41);
      // Pages are zero-indexed: page 1 of 3 leaves page 2 still to come.
      expect(page.hasMore, isTrue);
    });

    test('reports no more pages on the last one', () {
      // Zero-indexed, so the final page of three is number 2 — the off-by-one that would otherwise
      // make an infinite scroll request a page that does not exist.
      final last = Paged.fromJson({
        'content': <dynamic>[],
        'page': {'number': 2, 'size': 20, 'totalElements': 41, 'totalPages': 3},
      }, ProductCard.fromJson);

      expect(last.hasMore, isFalse);
    });
  });

  group('ProductCard', () {
    test('reads a discounted product', () {
      final product = ProductCard.fromJson({
        'id': 'p1',
        'slug': 'anker-powercore-20000',
        'sku': 'ANK-PB-20K',
        'name': 'Anker PowerCore 20000',
        'price': {'amount': '1899.00', 'currency': 'EGP'},
        'compareAtPrice': {'amount': '2400.00', 'currency': 'EGP'},
        'discountPercent': 21,
        'ratingAvg': 4.5,
        'ratingCount': 12,
        'inStock': true,
      });

      expect(product.onSale, isTrue);
      expect(product.compareAtPrice?.amount, '2400.00');
      expect(product.ratingAvg, 4.5);
    });

    test('is not on sale without a discount percentage', () {
      final product = ProductCard.fromJson({
        'id': 'p1',
        'slug': 's',
        'sku': 'SKU',
        'name': 'Plain',
        'price': {'amount': '100.00', 'currency': 'EGP'},
      });

      expect(product.onSale, isFalse);
      expect(product.inStock, isTrue, reason: 'absent inStock should not read as out of stock');
    });

    test('accepts a rating that arrives as an int', () {
      // JSON has one number type; 5 and 5.0 both decode from the same payload shape.
      final product = ProductCard.fromJson({
        'id': 'p1',
        'slug': 's',
        'sku': 'SKU',
        'name': 'Plain',
        'price': {'amount': '1.00', 'currency': 'EGP'},
        'ratingAvg': 5,
      });

      expect(product.ratingAvg, 5.0);
    });
  });

  group('ProductDetail', () {
    test('flattens images and reads the nested brand name', () {
      final product = ProductDetail.fromJson({
        'id': 'p1',
        'slug': 's',
        'sku': 'SKU',
        'name': 'Phone',
        'price': {'amount': '32999.00', 'currency': 'EGP'},
        'brand': {'id': 'b1', 'slug': 'samsung', 'name': 'Samsung'},
        'images': [
          {'url': 'https://example.test/1.jpg'},
          {'url': 'https://example.test/2.jpg'},
        ],
        'availableQuantity': 3,
        'inStock': true,
      });

      expect(product.brandName, 'Samsung');
      expect(product.images, hasLength(2));
      expect(product.lowStock, isTrue);
    });

    test('is not low stock when plenty remains', () {
      final product = ProductDetail.fromJson({
        'id': 'p1',
        'slug': 's',
        'sku': 'SKU',
        'name': 'Phone',
        'price': {'amount': '1.00', 'currency': 'EGP'},
        'availableQuantity': 40,
        'inStock': true,
      });

      expect(product.lowStock, isFalse);
    });
  });

  group('Cart', () {
    test('sums quantities rather than counting lines', () {
      final cart = Cart.fromJson({
        'id': 'c1',
        'items': [
          {
            'id': 'i1',
            'productId': 'p1',
            'name': 'A',
            'quantity': 2,
            'unitPrice': {'amount': '100.00', 'currency': 'EGP'},
            'lineTotal': {'amount': '200.00', 'currency': 'EGP'},
          },
          {
            'id': 'i2',
            'productId': 'p2',
            'name': 'B',
            'quantity': 3,
            'unitPrice': {'amount': '50.00', 'currency': 'EGP'},
            'lineTotal': {'amount': '150.00', 'currency': 'EGP'},
          },
        ],
        'totals': {
          'subtotal': {'amount': '350.00', 'currency': 'EGP'},
          'grandTotal': {'amount': '390.00', 'currency': 'EGP'},
        },
      });

      expect(cart.itemCount, 5, reason: 'the badge counts units, not lines');
      expect(cart.isEmpty, isFalse);
      expect(cart.totals.grandTotal.amount, '390.00');
      // Absent totals default to zero rather than throwing.
      expect(cart.totals.tax.isZero, isTrue);
    });

    test('flags a line that now exceeds available stock', () {
      final cart = Cart.fromJson({
        'id': 'c1',
        'items': [
          {
            'id': 'i1',
            'productId': 'p1',
            'name': 'A',
            'quantity': 5,
            'availableQuantity': 2,
            'unitPrice': {'amount': '10.00', 'currency': 'EGP'},
            'lineTotal': {'amount': '50.00', 'currency': 'EGP'},
          },
        ],
        'totals': <String, dynamic>{},
      });

      expect(cart.items.single.exceedsStock, isTrue);
    });

    test('an empty cart has no items and zero totals', () {
      final cart = Cart.empty();

      expect(cart.isEmpty, isTrue);
      expect(cart.itemCount, 0);
      expect(cart.totals.grandTotal.isZero, isTrue);
    });
  });
}
