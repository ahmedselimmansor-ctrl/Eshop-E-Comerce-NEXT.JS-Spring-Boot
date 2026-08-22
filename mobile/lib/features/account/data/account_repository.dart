import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/models/order.dart';
import '../../../core/models/page.dart';
import '../../../core/network/api_client.dart';
import '../../../core/providers/api_providers.dart';

/// The signed-in customer's own data: orders and addresses.
class AccountRepository {
  AccountRepository(this._client);

  final ApiClient _client;

  Future<Paged<Order>> orders({int page = 0, int size = 20}) {
    return _client.get(
      '/api/v1/me/orders',
      query: {'page': page, 'size': size},
      parse: (json) => Paged.fromJson(json, Order.fromJson),
    );
  }

  Future<Order> order(String orderNumber) {
    return _client.get(
      '/api/v1/me/orders/$orderNumber',
      parse: (json) => Order.fromJson(json as Map<String, dynamic>),
    );
  }

  Future<Order> cancelOrder(String orderNumber, String reason) {
    return _client.post(
      '/api/v1/me/orders/$orderNumber/cancel',
      body: {'reason': reason},
      parse: (json) => Order.fromJson(json as Map<String, dynamic>),
    );
  }

  Future<List<Address>> addresses() {
    return _client.get(
      '/api/v1/me/addresses',
      parse: (json) => (json as List<dynamic>)
          .map((entry) => Address.fromJson(entry as Map<String, dynamic>))
          .toList(growable: false),
    );
  }

  Future<Address> addAddress({
    required String recipientName,
    required String phone,
    required String governorate,
    required String city,
    required String street,
    String? building,
    bool isDefault = false,
  }) {
    return _client.post(
      '/api/v1/me/addresses',
      body: {
        'recipientName': recipientName,
        'phone': phone,
        'governorate': governorate,
        'city': city,
        'street': street,
        if (building != null && building.isNotEmpty) 'building': building,
        'countryCode': 'EG',
        'defaultShipping': isDefault,
        'defaultBilling': isDefault,
      },
      parse: (json) => Address.fromJson(json as Map<String, dynamic>),
    );
  }
}

final accountRepositoryProvider = Provider<AccountRepository>(
  (ref) => AccountRepository(ref.watch(apiClientProvider)),
);

final myOrdersProvider = FutureProvider<List<Order>>((ref) async {
  final page = await ref.watch(accountRepositoryProvider).orders();
  return page.content;
});

final orderDetailProvider = FutureProvider.family<Order, String>(
  (ref, orderNumber) => ref.watch(accountRepositoryProvider).order(orderNumber),
);

final myAddressesProvider = FutureProvider<List<Address>>(
  (ref) => ref.watch(accountRepositoryProvider).addresses(),
);
