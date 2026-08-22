import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/models/cart.dart';
import '../../../core/network/api_client.dart';
import '../../../core/providers/api_providers.dart';

/// Reads and mutates the cart.
///
/// Every mutation returns the whole cart rather than an acknowledgement, and the controller below
/// replaces its state with it. That is deliberate: adding a line can change the shipping band, the
/// tax, and whether a coupon still applies, so anything less than the full cart leaves the screen
/// showing totals the server no longer agrees with.
class CartRepository {
  CartRepository(this._client);

  final ApiClient _client;

  Cart _parse(dynamic json) => Cart.fromJson(json as Map<String, dynamic>);

  Future<Cart> fetch() => _client.get('/api/v1/cart', parse: _parse);

  Future<Cart> addItem({required String productId, String? variantId, int quantity = 1}) {
    return _client.post(
      '/api/v1/cart/items',
      body: {
        'productId': productId,
        if (variantId != null) 'variantId': variantId,
        'quantity': quantity,
      },
      parse: _parse,
    );
  }

  Future<Cart> updateQuantity({required String itemId, required int quantity}) {
    return _client.patch(
      '/api/v1/cart/items/$itemId',
      body: {'quantity': quantity},
      parse: _parse,
    );
  }

  Future<Cart> removeItem(String itemId) async {
    await _client.delete('/api/v1/cart/items/$itemId');
    return fetch();
  }

  Future<Cart> applyCoupon(String code) {
    return _client.post('/api/v1/cart/coupon', body: {'code': code}, parse: _parse);
  }

  Future<Cart> removeCoupon() async {
    await _client.delete('/api/v1/cart/coupon');
    return fetch();
  }
}

final cartRepositoryProvider = Provider<CartRepository>(
  (ref) => CartRepository(ref.watch(apiClientProvider)),
);

/// The cart, held as async state so every screen sees the same one.
///
/// A badge in the app bar and the cart screen itself both watch this, so a line added from a
/// product page updates the badge without either screen knowing about the other.
class CartController extends AsyncNotifier<Cart> {
  @override
  Future<Cart> build() => ref.watch(cartRepositoryProvider).fetch();

  Future<void> add({required String productId, String? variantId, int quantity = 1}) async {
    await _run(
      (repository) => repository.addItem(
        productId: productId,
        variantId: variantId,
        quantity: quantity,
      ),
    );
  }

  Future<void> setQuantity(String itemId, int quantity) async {
    if (quantity <= 0) {
      await remove(itemId);
      return;
    }
    await _run((repository) => repository.updateQuantity(itemId: itemId, quantity: quantity));
  }

  Future<void> remove(String itemId) => _run((repository) => repository.removeItem(itemId));

  Future<void> applyCoupon(String code) =>
      _run((repository) => repository.applyCoupon(code));

  Future<void> removeCoupon() => _run((repository) => repository.removeCoupon());

  /// Runs a mutation, keeping the previous cart visible while it is in flight.
  ///
  /// `AsyncValue.guard` turns a thrown [ApiException] into an error state rather than an unhandled
  /// exception, so a rejected coupon shows a message instead of crashing the screen.
  Future<void> _run(Future<Cart> Function(CartRepository repository) action) async {
    final repository = ref.read(cartRepositoryProvider);
    state = await AsyncValue.guard(() => action(repository));
  }
}

final cartControllerProvider =
    AsyncNotifierProvider<CartController, Cart>(CartController.new);

/// Just the badge count, so the app bar does not rebuild on every totals change.
final cartItemCountProvider = Provider<int>((ref) {
  return ref.watch(cartControllerProvider).valueOrNull?.itemCount ?? 0;
});
