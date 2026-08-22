import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../core/models/cart.dart';
import '../../../core/models/money.dart';
import '../../../core/network/api_exception.dart';
import '../../../core/widgets/async_view.dart';
import '../../../core/widgets/money_text.dart';
import '../../account/data/account_repository.dart';
import '../../auth/data/auth_repository.dart';
import '../../cart/data/cart_repository.dart';
import '../data/checkout_repository.dart';

/// Checkout: pick an address, pick a delivery option, pay.
class CheckoutScreen extends ConsumerStatefulWidget {
  const CheckoutScreen({super.key});

  @override
  ConsumerState<CheckoutScreen> createState() => _CheckoutScreenState();
}

class _CheckoutScreenState extends ConsumerState<CheckoutScreen> {
  String? _addressId;
  String? _shippingMethodId;
  bool _placing = false;

  /// Generated once per screen, not per tap.
  ///
  /// That is the whole point of it: a customer who taps "Place order", loses the connection, and
  /// taps again must send the same key, so the server replays the first order instead of creating a
  /// second one and charging twice.
  final String _idempotencyKey = _newKey();

  static String _newKey() =>
      'mobile-${DateTime.now().microsecondsSinceEpoch}-${identityHashCode(Object())}';

  @override
  Widget build(BuildContext context) {
    final signedIn = ref.watch(isSignedInProvider);
    if (!signedIn) {
      return const _SignInFirst();
    }

    final cart = ref.watch(cartControllerProvider);
    final addresses = ref.watch(myAddressesProvider);

    return Scaffold(
      appBar: AppBar(title: const Text('Checkout')),
      body: AsyncView(
        value: addresses,
        onRetry: () => ref.invalidate(myAddressesProvider),
        builder: (list) {
          if (list.isEmpty) {
            return EmptyState(
              icon: Icons.location_off_outlined,
              title: 'No delivery address',
              hint: 'Add one to continue.',
              action: FilledButton(
                onPressed: () => context.push('/account/addresses'),
                child: const Text('Add an address'),
              ),
            );
          }

          final selected = _addressId ??
              list.firstWhere((a) => a.isDefaultShipping, orElse: () => list.first).id;
          final address = list.firstWhere((a) => a.id == selected);

          return ListView(
            padding: const EdgeInsets.all(12),
            children: [
              const _Section(title: 'Deliver to'),
              for (final entry in list)
                RadioListTile<String>(
                  value: entry.id,
                  groupValue: selected,
                  onChanged: (value) => setState(() {
                    _addressId = value;
                    // The zone changes with the governorate, so the chosen method may no longer
                    // apply. Clearing it forces a re-pick rather than sending a stale id.
                    _shippingMethodId = null;
                  }),
                  title: Text(entry.recipientName),
                  subtitle: Text(entry.summary),
                ),

              const SizedBox(height: 12),
              const _Section(title: 'Delivery'),
              _ShippingOptions(
                governorate: address.governorate,
                selected: _shippingMethodId,
                onChanged: (value) => setState(() => _shippingMethodId = value),
              ),

              const SizedBox(height: 12),
              const _Section(title: 'Payment'),
              const ListTile(
                leading: Icon(Icons.payments_outlined),
                title: Text('Cash on delivery'),
                subtitle: Text('Pay the courier when your order arrives.'),
              ),

              const SizedBox(height: 12),
              cart.maybeWhen(
                data: (data) => _Totals(cart: data),
                orElse: () => const SizedBox.shrink(),
              ),

              const SizedBox(height: 20),
              FilledButton(
                onPressed: _shippingMethodId == null || _placing
                    ? null
                    : () => _placeOrder(selected, _shippingMethodId!),
                style: FilledButton.styleFrom(minimumSize: const Size.fromHeight(48)),
                child: _placing
                    ? const SizedBox(
                        width: 18,
                        height: 18,
                        child: CircularProgressIndicator(strokeWidth: 2),
                      )
                    : const Text('Place order'),
              ),
            ],
          );
        },
      ),
    );
  }

  Future<void> _placeOrder(String addressId, String methodId) async {
    setState(() => _placing = true);
    final messenger = ScaffoldMessenger.of(context);

    try {
      final order = await ref.read(checkoutRepositoryProvider).placeOrder(
            idempotencyKey: _idempotencyKey,
            shippingAddressId: addressId,
            shippingMethodId: methodId,
          );

      // The cart is emptied server-side by checkout; refetch rather than assuming.
      ref.invalidate(cartControllerProvider);
      ref.invalidate(myOrdersProvider);

      if (!mounted) return;
      context.go('/orders/${order.orderNumber}');
    } catch (error) {
      if (!mounted) return;
      messenger.showSnackBar(
        SnackBar(
          content: Text(
            error is ApiException ? error.detail : 'Could not place your order',
          ),
        ),
      );
    } finally {
      if (mounted) setState(() => _placing = false);
    }
  }
}

class _ShippingOptions extends ConsumerWidget {
  const _ShippingOptions({
    required this.governorate,
    required this.selected,
    required this.onChanged,
  });

  final String governorate;
  final String? selected;
  final ValueChanged<String> onChanged;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final methods = ref.watch(shippingMethodsProvider(governorate));

    return AsyncView(
      value: methods,
      loading: const Padding(
        padding: EdgeInsets.all(16),
        child: Center(child: CircularProgressIndicator()),
      ),
      onRetry: () => ref.invalidate(shippingMethodsProvider(governorate)),
      builder: (list) {
        if (list.isEmpty) {
          return const ListTile(
            leading: Icon(Icons.local_shipping_outlined),
            title: Text('No delivery options'),
            subtitle: Text('We do not deliver to this governorate yet.'),
          );
        }

        return Column(
          children: [
            for (final method in list)
              RadioListTile<String>(
                value: method.id,
                groupValue: selected,
                // This screen pays by cash on delivery, so a courier that does not collect cash
                // cannot fulfil it. Disabled rather than hidden: "not available with cash on
                // delivery" explains itself, a missing row does not.
                onChanged: method.supportsCod ? (value) => onChanged(value!) : null,
                title: Text(method.name),
                subtitle: !method.supportsCod
                    ? const Text('Not available with cash on delivery')
                    : method.estimate == null
                        ? null
                        : Text('${method.estimate} business days'),
                secondary: MoneyText(method.price),
              ),
          ],
        );
      },
    );
  }
}

class _Totals extends StatelessWidget {
  const _Totals({required this.cart});

  final Cart cart;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final totals = cart.totals;

    Widget row(String label, Money value, {bool bold = false}) => Padding(
          padding: const EdgeInsets.symmetric(vertical: 3),
          child: Row(
            mainAxisAlignment: MainAxisAlignment.spaceBetween,
            children: [
              Text(label, style: bold ? theme.textTheme.titleMedium : null),
              MoneyText(
                value,
                style: bold
                    ? theme.textTheme.titleMedium?.copyWith(fontWeight: FontWeight.w700)
                    : null,
              ),
            ],
          ),
        );

    return Column(
      children: [
        row('Subtotal', totals.subtotal),
        row('Tax', totals.tax),
        row('Shipping', totals.shipping),
        if (!totals.codFee.isZero) row('Cash on delivery', totals.codFee),
        const Divider(),
        row('Total', totals.grandTotal, bold: true),
      ],
    );
  }
}

class _Section extends StatelessWidget {
  const _Section({required this.title});

  final String title;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 8),
      child: Text(title, style: Theme.of(context).textTheme.titleSmall),
    );
  }
}

class _SignInFirst extends StatelessWidget {
  const _SignInFirst();

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Checkout')),
      body: EmptyState(
        icon: Icons.lock_outline,
        title: 'Sign in to continue',
        hint: 'Your cart is saved and will still be here.',
        action: FilledButton(
          onPressed: () => context.push('/sign-in?redirect=/checkout'),
          child: const Text('Sign in'),
        ),
      ),
    );
  }
}
