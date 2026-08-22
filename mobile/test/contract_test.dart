import 'dart:convert';
import 'dart:io';

import 'package:eshop/core/models/cart.dart';
import 'package:eshop/core/models/order.dart';
import 'package:eshop/core/models/page.dart';
import 'package:eshop/core/models/product.dart';
import 'package:flutter_test/flutter_test.dart';

/// The API contract, checked against responses the server actually produced.
///
/// The fixtures in `test/fixtures/` were captured from a running API, not written by hand, and that
/// is the whole value of them. A model written from memory reads `price` where the server says
/// `cost`, and the result is not an error but a price silently rendering as 0.00 EGP on a
/// customer's phone. Three such mismatches existed in this codebase before these tests.
dynamic fixture(String name) =>
    jsonDecode(File('test/fixtures/$name.json').readAsStringSync());

void main() {
  group('product listing', () {
    test('parses a real page of products', () {
      final page = Paged.fromJson(fixture('sf-products'), ProductCard.fromJson);

      expect(page.content, isNotEmpty);
      expect(page.totalElements, greaterThan(0));

      for (final product in page.content) {
        expect(product.id, isNotEmpty);
        expect(product.slug, isNotEmpty);
        expect(product.name, isNotEmpty, reason: 'a blank name renders as an empty tile');
        expect(product.price.amount, isNotEmpty);
        expect(product.price.currency, 'EGP');
      }
    });

    test('the seeded draft product is absent from the storefront listing', () {
      final page = Paged.fromJson(fixture('sf-products'), ProductCard.fromJson);
      expect(page.content.map((p) => p.sku), isNot(contains('SAM-S25-512')));
    });
  });

  group('product detail', () {
    test('parses a real product', () {
      final product = ProductDetail.fromJson(fixture('sf-detail') as Map<String, dynamic>);

      expect(product.name, isNotEmpty);
      expect(product.price.amount, isNotEmpty);
      expect(product.images, isNotEmpty, reason: 'the seed gives every product an image');
    });
  });

  group('cart', () {
    test('parses a real cart with a line in it', () {
      final cart = Cart.fromJson(fixture('sf-cart') as Map<String, dynamic>);

      expect(cart.totals.subtotal.amount, isNotEmpty);
      expect(cart.totals.grandTotal.amount, isNotEmpty);
    });
  });

  group('order', () {
    test('parses a real placed order', () {
      final order = Order.fromJson(fixture('mb-order') as Map<String, dynamic>);

      expect(order.orderNumber, startsWith('EG-'));
      expect(order.status, 'PENDING_PAYMENT');
      expect(order.items, isNotEmpty);
      expect(order.timeline, isNotEmpty);

      // Totals the server computed. If any of these parse as zero, a field was renamed.
      expect(order.totals.subtotal.asDouble, greaterThan(0));
      expect(order.totals.grandTotal.asDouble, greaterThan(0));
      expect(
        order.totals.grandTotal.asDouble,
        greaterThan(order.totals.subtotal.asDouble),
        reason: 'shipping and the cash-on-delivery fee are added on top',
      );
    });

    test('reads the shipping address snapshot', () {
      final order = Order.fromJson(fixture('mb-order') as Map<String, dynamic>);
      expect(order.shippingAddress['recipientName'], isNotNull);
    });
  });

  group('address', () {
    test('parses a real saved address, including the default flag', () {
      final address = Address.fromJson(fixture('mb-addr') as Map<String, dynamic>);

      expect(address.recipientName, 'Sara Ahmed');
      expect(address.governorate, 'القاهرة');
      // `defaultShipping` on the wire. Reading `isDefaultShipping` here silently returned false and
      // checkout never preselected the customer's default address.
      expect(address.isDefaultShipping, isTrue);
      expect(address.summary, isNotEmpty);
    });
  });

  group('shipping methods', () {
    test('parses real quotes with a non-zero price', () {
      final methods = (fixture('mb-ship') as List<dynamic>)
          .map((entry) => ShippingMethod.fromJson(entry as Map<String, dynamic>))
          .toList();

      expect(methods, isNotEmpty, reason: 'Cairo is in the seeded Greater Cairo zone');

      for (final method in methods) {
        expect(method.name, isNotEmpty);
        // The field is `cost`, not `price`. Reading the wrong one gave every option 0.00 EGP,
        // which looks like free delivery rather than like a bug.
        expect(
          method.price.asDouble,
          greaterThan(0),
          reason: '${method.name} should have a real delivery cost',
        );
      }
    });
  });
}
