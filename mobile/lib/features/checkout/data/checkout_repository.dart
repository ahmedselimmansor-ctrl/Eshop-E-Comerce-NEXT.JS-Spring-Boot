import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/models/order.dart';
import '../../../core/network/api_client.dart';
import '../../../core/providers/api_providers.dart';

/// Placing an order.
class CheckoutRepository {
  CheckoutRepository(this._client);

  final ApiClient _client;

  /// Delivery options for a governorate, priced by the server.
  ///
  /// The governorate must be the Arabic name, because that is how the shipping zones store it. An
  /// English spelling matches no zone and comes back as an empty list, which reads as "we do not
  /// deliver here" rather than as the encoding mistake it is.
  Future<List<ShippingMethod>> shippingMethods(String governorate) {
    return _client.get(
      '/api/v1/checkout/shipping-methods',
      query: {'governorate': governorate},
      parse: (json) => (json as List<dynamic>)
          .map((entry) => ShippingMethod.fromJson(entry as Map<String, dynamic>))
          .toList(growable: false),
    );
  }

  /// Places the order.
  ///
  /// [idempotencyKey] must be generated once per checkout attempt and reused on retry. A customer on
  /// a patchy mobile connection taps "Pay", the request succeeds, the response is lost, and the app
  /// retries: without the key that is a second order and a second charge.
  Future<Order> placeOrder({
    required String idempotencyKey,
    required String shippingAddressId,
    required String shippingMethodId,
    String paymentProvider = 'COD',
    String paymentMethod = 'COD',
    String? couponCode,
  }) {
    return _client.post(
      '/api/v1/checkout/orders',
      headers: {'Idempotency-Key': idempotencyKey},
      body: {
        'shippingAddressId': shippingAddressId,
        'shippingMethodId': shippingMethodId,
        'paymentProvider': paymentProvider,
        'paymentMethod': paymentMethod,
        if (couponCode != null && couponCode.isNotEmpty) 'couponCode': couponCode,
      },
      parse: (json) {
        final payload = json as Map<String, dynamic>;
        return Order.fromJson(payload['order'] as Map<String, dynamic>);
      },
    );
  }
}

final checkoutRepositoryProvider = Provider<CheckoutRepository>(
  (ref) => CheckoutRepository(ref.watch(apiClientProvider)),
);

final shippingMethodsProvider = FutureProvider.family<List<ShippingMethod>, String>(
  (ref, governorate) => ref.watch(checkoutRepositoryProvider).shippingMethods(governorate),
);
