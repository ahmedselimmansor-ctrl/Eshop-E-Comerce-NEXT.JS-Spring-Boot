import 'package:eshop/core/models/order.dart';
import 'package:flutter_test/flutter_test.dart';

/// Parsing and the small amount of logic that hangs off an order.
void main() {
  Map<String, dynamic> orderJson({String status = 'PAID'}) => {
        'id': '0192a000-0000-7000-8000-00000000f001',
        'orderNumber': 'EG-260822-00001',
        'status': status,
        'paymentStatus': 'PENDING',
        'placedAt': '2026-08-22T10:00:00Z',
        'items': [
          {
            'id': 'i1',
            'name': 'Anker PowerCore 20000',
            'quantity': 2,
            'fulfilledQuantity': 1,
            'unitPrice': {'amount': '1899.00', 'currency': 'EGP'},
            'lineTotal': {'amount': '3798.00', 'currency': 'EGP'},
          },
        ],
        'totals': {
          'subtotal': {'amount': '3798.00', 'currency': 'EGP'},
          'grandTotal': {'amount': '3858.00', 'currency': 'EGP'},
        },
        'timeline': [
          {'status': 'PENDING_PAYMENT', 'at': '2026-08-22T10:00:00Z'},
          {'status': 'PAID', 'at': '2026-08-22T10:05:00Z', 'note': 'COD confirmed'},
        ],
        'shippingAddress': {'recipientName': 'Sara Ahmed', 'formatted': 'Cairo'},
      };

  group('Order', () {
    test('parses items, totals and timeline', () {
      final order = Order.fromJson(orderJson());

      expect(order.orderNumber, 'EG-260822-00001');
      expect(order.items, hasLength(1));
      expect(order.items.single.fulfilledQuantity, 1);
      expect(order.totals.grandTotal.amount, '3858.00');
      expect(order.timeline, hasLength(2));
      expect(order.timeline.last.note, 'COD confirmed');
    });

    test('counts units rather than lines', () {
      expect(Order.fromJson(orderJson()).itemCount, 2);
    });

    test('defaults absent totals to zero rather than throwing', () {
      final json = orderJson()..['totals'] = <String, dynamic>{};
      final order = Order.fromJson(json);

      expect(order.totals.tax.isZero, isTrue);
      expect(order.totals.codFee.isZero, isTrue);
    });

    test('knows which statuses are settled', () {
      // Mirrors the server's terminal set; anything else is still in flight.
      for (final status in ['DELIVERED', 'CANCELLED', 'REFUNDED', 'FAILED']) {
        expect(
          Order.fromJson(orderJson(status: status)).isSettled,
          isTrue,
          reason: '$status is terminal',
        );
      }
      for (final status in ['PENDING_PAYMENT', 'PAID', 'PROCESSING', 'SHIPPED']) {
        expect(
          Order.fromJson(orderJson(status: status)).isSettled,
          isFalse,
          reason: '$status is still in flight',
        );
      }
    });

    test('allows cancellation only before anything has shipped', () {
      for (final status in ['PENDING_PAYMENT', 'PAID', 'PROCESSING']) {
        expect(Order.fromJson(orderJson(status: status)).isCancellable, isTrue);
      }
      // Once it has left the warehouse it is a return, not a cancellation.
      for (final status in ['SHIPPED', 'DELIVERED', 'CANCELLED']) {
        expect(Order.fromJson(orderJson(status: status)).isCancellable, isFalse);
      }
    });
  });

  group('Address', () {
    test('reads the wire field name for the default flag', () {
      final address = Address.fromJson({
        'id': 'a1',
        'recipientName': 'Sara',
        'phone': '+201001234567',
        'governorate': 'القاهرة',
        'city': 'مدينة نصر',
        // AddressResponse calls it `defaultShipping`; the `is` prefix is a Dart convention only.
        'defaultShipping': true,
      });

      expect(address.isDefaultShipping, isTrue);
    });

    test('prefers the server-formatted line', () {
      final address = Address.fromJson({
        'id': 'a1',
        'recipientName': 'Sara Ahmed',
        'phone': '+201001234567',
        'governorate': 'القاهرة',
        'city': 'مدينة نصر',
        'formatted': '12 شارع عباس العقاد، مدينة نصر، القاهرة',
      });

      expect(address.summary, contains('عباس العقاد'));
    });

    test('builds a summary from the parts when none was formatted', () {
      final address = Address.fromJson({
        'id': 'a1',
        'recipientName': 'Sara Ahmed',
        'phone': '+201001234567',
        'governorate': 'القاهرة',
        'city': 'مدينة نصر',
        'street': '12 شارع عباس العقاد',
      });

      expect(address.summary, contains('مدينة نصر'));
      expect(address.summary, contains('القاهرة'));
    });
  });

  group('ShippingMethod', () {
    test('collapses an identical day range to a single figure', () {
      final method = ShippingMethod.fromJson({
        'id': 'm1',
        'name': 'Express',
        'cost': {'amount': '80.00', 'currency': 'EGP'},
        'estimatedDaysMin': 1,
        'estimatedDaysMax': 1,
      });

      expect(method.estimate, '1');
    });

    test('shows a range when the days differ', () {
      final method = ShippingMethod.fromJson({
        'id': 'm1',
        'name': 'Standard',
        'cost': {'amount': '40.00', 'currency': 'EGP'},
        'estimatedDaysMin': 2,
        'estimatedDaysMax': 3,
      });

      expect(method.estimate, '2-3');
    });

    test('has no estimate when the server did not give one', () {
      final method = ShippingMethod.fromJson({
        'id': 'm1',
        'name': 'Standard',
        'cost': {'amount': '40.00', 'currency': 'EGP'},
      });

      expect(method.estimate, isNull);
      expect(method.price.amount, '40.00');
    });
  });
}
