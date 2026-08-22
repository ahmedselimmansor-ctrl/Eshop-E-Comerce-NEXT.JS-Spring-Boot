import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../core/models/cart.dart';
import '../../../core/models/money.dart';
import '../../../core/widgets/async_view.dart';
import '../../../core/widgets/money_text.dart';
import '../data/cart_repository.dart';

/// The cart.
///
/// Totals come from the server and are rendered as received - the device never adds anything up,
/// because tax, shipping and the cash-on-delivery fee are all server-side rules.
class CartScreen extends ConsumerWidget {
  const CartScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final cart = ref.watch(cartControllerProvider);

    return Scaffold(
      appBar: AppBar(title: const Text('Cart')),
      body: AsyncView(
        value: cart,
        onRetry: () => ref.invalidate(cartControllerProvider),
        builder: (data) => data.isEmpty
            ? EmptyState(
                icon: Icons.shopping_bag_outlined,
                title: 'Your cart is empty',
                hint: 'Browse the store and add something you like.',
                action: FilledButton(
                  onPressed: () => context.go('/'),
                  child: const Text('Start shopping'),
                ),
              )
            : _CartBody(cart: data),
      ),
      bottomNavigationBar: cart.maybeWhen(
        data: (data) => data.isEmpty ? null : _CheckoutBar(cart: data),
        orElse: () => null,
      ),
    );
  }
}

class _CartBody extends ConsumerWidget {
  const _CartBody({required this.cart});

  final Cart cart;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final theme = Theme.of(context);

    return ListView(
      padding: const EdgeInsets.all(12),
      children: [
        for (final item in cart.items) ...[
          _CartLine(item: item),
          const Divider(height: 24),
        ],

        _CouponField(cart: cart),
        const SizedBox(height: 16),

        _TotalsRow(label: 'Subtotal', value: cart.totals.subtotal),
        if (!cart.totals.discount.isZero)
          _TotalsRow(
            label: cart.couponCode == null ? 'Discount' : 'Discount (${cart.couponCode})',
            value: cart.totals.discount,
            negative: true,
          ),
        _TotalsRow(label: 'Tax', value: cart.totals.tax),
        _TotalsRow(label: 'Shipping', value: cart.totals.shipping),
        if (!cart.totals.codFee.isZero)
          _TotalsRow(label: 'Cash on delivery', value: cart.totals.codFee),

        const Divider(height: 24),
        _TotalsRow(label: 'Total', value: cart.totals.grandTotal, emphasise: true),

        const SizedBox(height: 8),
        Text(
          'Shipping is calculated for your governorate at checkout.',
          style: theme.textTheme.bodySmall?.copyWith(color: theme.colorScheme.onSurfaceVariant),
        ),
      ],
    );
  }
}

class _CartLine extends ConsumerWidget {
  const _CartLine({required this.item});

  final CartItem item;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final theme = Theme.of(context);
    final controller = ref.read(cartControllerProvider.notifier);

    return Row(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        ClipRRect(
          borderRadius: BorderRadius.circular(8),
          child: SizedBox(
            width: 72,
            height: 72,
            child: item.imageUrl == null
                ? ColoredBox(
                    color: theme.colorScheme.surfaceContainerHighest,
                    child: Icon(Icons.image_outlined, color: theme.colorScheme.outline),
                  )
                : Image.network(
                    item.imageUrl!,
                    fit: BoxFit.cover,
                    errorBuilder: (context, error, stackTrace) => ColoredBox(
                      color: theme.colorScheme.surfaceContainerHighest,
                      child: Icon(Icons.image_outlined, color: theme.colorScheme.outline),
                    ),
                  ),
          ),
        ),
        const SizedBox(width: 12),

        Expanded(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(item.name, maxLines: 2, overflow: TextOverflow.ellipsis),
              if (item.variantLabel != null)
                Text(
                  item.variantLabel!,
                  style: theme.textTheme.labelSmall?.copyWith(
                    color: theme.colorScheme.onSurfaceVariant,
                  ),
                ),
              const SizedBox(height: 4),
              MoneyText(item.lineTotal, style: theme.textTheme.titleSmall),

              // Stock can run out after a line is added; saying so here is better than a failure
              // at checkout with no explanation of which line caused it.
              if (item.exceedsStock)
                Text(
                  'Only ${item.availableQuantity} left - reduce the quantity to continue',
                  style: theme.textTheme.labelSmall?.copyWith(color: theme.colorScheme.error),
                ),

              const SizedBox(height: 4),
              Row(
                children: [
                  IconButton(
                    visualDensity: VisualDensity.compact,
                    onPressed: () => controller.setQuantity(item.id, item.quantity - 1),
                    icon: const Icon(Icons.remove_circle_outline),
                  ),
                  Text('${item.quantity}', style: theme.textTheme.titleSmall),
                  IconButton(
                    visualDensity: VisualDensity.compact,
                    onPressed: () => controller.setQuantity(item.id, item.quantity + 1),
                    icon: const Icon(Icons.add_circle_outline),
                  ),
                  const Spacer(),
                  IconButton(
                    visualDensity: VisualDensity.compact,
                    tooltip: 'Remove',
                    onPressed: () => controller.remove(item.id),
                    icon: const Icon(Icons.delete_outline),
                  ),
                ],
              ),
            ],
          ),
        ),
      ],
    );
  }
}

class _CouponField extends ConsumerStatefulWidget {
  const _CouponField({required this.cart});

  final Cart cart;

  @override
  ConsumerState<_CouponField> createState() => _CouponFieldState();
}

class _CouponFieldState extends ConsumerState<_CouponField> {
  final _controller = TextEditingController();

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final applied = widget.cart.couponCode;

    if (applied != null) {
      return Row(
        children: [
          Chip(
            label: Text(applied),
            onDeleted: () => ref.read(cartControllerProvider.notifier).removeCoupon(),
          ),
        ],
      );
    }

    return Row(
      children: [
        Expanded(
          child: TextField(
            controller: _controller,
            textCapitalization: TextCapitalization.characters,
            decoration: const InputDecoration(
              labelText: 'Coupon code',
              isDense: true,
              border: OutlineInputBorder(),
            ),
          ),
        ),
        const SizedBox(width: 8),
        OutlinedButton(
          onPressed: () {
            final code = _controller.text.trim();
            if (code.isNotEmpty) {
              ref.read(cartControllerProvider.notifier).applyCoupon(code);
            }
          },
          child: const Text('Apply'),
        ),
      ],
    );
  }
}

class _TotalsRow extends StatelessWidget {
  const _TotalsRow({
    required this.label,
    required this.value,
    this.negative = false,
    this.emphasise = false,
  });

  final String label;
  final Money value;
  final bool negative;
  final bool emphasise;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final style = emphasise
        ? theme.textTheme.titleMedium?.copyWith(fontWeight: FontWeight.w700)
        : theme.textTheme.bodyMedium;

    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 3),
      child: Row(
        mainAxisAlignment: MainAxisAlignment.spaceBetween,
        children: [
          Text(label, style: emphasise ? style : theme.textTheme.bodyMedium),
          Row(
            children: [
              if (negative) Text('− ', style: style),
              MoneyText(value, style: style),
            ],
          ),
        ],
      ),
    );
  }
}

class _CheckoutBar extends StatelessWidget {
  const _CheckoutBar({required this.cart});

  final Cart cart;

  @override
  Widget build(BuildContext context) {
    final blocked = cart.items.any((item) => item.exceedsStock);

    return SafeArea(
      child: Padding(
        padding: const EdgeInsets.all(12),
        child: FilledButton(
          onPressed: blocked ? null : () => context.go('/checkout'),
          style: FilledButton.styleFrom(minimumSize: const Size.fromHeight(48)),
          child: Text(
            blocked
                ? 'Adjust quantities to continue'
                : 'Checkout · ${formatMoney(cart.totals.grandTotal, locale: Localizations.localeOf(context).languageCode)}',
          ),
        ),
      ),
    );
  }
}
