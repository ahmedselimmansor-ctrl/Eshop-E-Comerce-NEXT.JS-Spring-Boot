import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/models/money.dart';
import '../../../core/models/order.dart';
import '../../../core/widgets/async_view.dart';
import '../../../core/widgets/money_text.dart';
import '../data/account_repository.dart';

/// One order, after it has been placed.
class OrderDetailScreen extends ConsumerWidget {
  const OrderDetailScreen({super.key, required this.orderNumber});

  final String orderNumber;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final order = ref.watch(orderDetailProvider(orderNumber));

    return Scaffold(
      appBar: AppBar(title: Text(orderNumber)),
      body: AsyncView(
        value: order,
        onRetry: () => ref.invalidate(orderDetailProvider(orderNumber)),
        builder: (data) => _Body(order: data),
      ),
    );
  }
}

class _Body extends StatelessWidget {
  const _Body({required this.order});

  final Order order;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);

    return ListView(
      padding: const EdgeInsets.all(16),
      children: [
        OrderStatusChip(status: order.status),
        const SizedBox(height: 16),

        Text('Items', style: theme.textTheme.titleSmall),
        const SizedBox(height: 8),
        for (final item in order.items)
          Padding(
            padding: const EdgeInsets.symmetric(vertical: 6),
            child: Row(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(item.name),
                      Text(
                        '${item.quantity} x ',
                        style: theme.textTheme.bodySmall?.copyWith(
                          color: theme.colorScheme.onSurfaceVariant,
                        ),
                      ),
                    ],
                  ),
                ),
                MoneyText(item.lineTotal),
              ],
            ),
          ),

        const Divider(height: 24),
        _Row(label: 'Subtotal', value: order.totals.subtotal),
        if (!order.totals.discount.isZero)
          _Row(label: 'Discount', value: order.totals.discount, negative: true),
        _Row(label: 'Tax', value: order.totals.tax),
        _Row(label: 'Shipping', value: order.totals.shipping),
        if (!order.totals.codFee.isZero)
          _Row(label: 'Cash on delivery', value: order.totals.codFee),
        const Divider(),
        _Row(label: 'Total', value: order.totals.grandTotal, emphasise: true),

        const SizedBox(height: 24),
        Text('Delivery', style: theme.textTheme.titleSmall),
        const SizedBox(height: 8),
        Text((order.shippingAddress['recipientName'] ?? '') as String),
        Text(
          (order.shippingAddress['formatted'] ?? '') as String,
          style: theme.textTheme.bodySmall,
        ),
        if (order.shippingMethodName != null)
          Padding(
            padding: const EdgeInsets.only(top: 6),
            child: Text(order.shippingMethodName!, style: theme.textTheme.bodySmall),
          ),

        if (order.timeline.isNotEmpty) ...[
          const SizedBox(height: 24),
          Text('Progress', style: theme.textTheme.titleSmall),
          const SizedBox(height: 8),
          for (final event in order.timeline)
            Padding(
              padding: const EdgeInsets.symmetric(vertical: 4),
              child: Row(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Container(
                    margin: const EdgeInsetsDirectional.only(top: 6, end: 10),
                    width: 8,
                    height: 8,
                    decoration: BoxDecoration(
                      color: theme.colorScheme.primary,
                      shape: BoxShape.circle,
                    ),
                  ),
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(humaniseStatus(event.status)),
                        if (event.note != null)
                          Text(event.note!, style: theme.textTheme.bodySmall),
                      ],
                    ),
                  ),
                ],
              ),
            ),
        ],
      ],
    );
  }
}

class _Row extends StatelessWidget {
  const _Row({
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
          Text(label, style: style),
          Row(
            children: [
              if (negative) Text('- ', style: style),
              MoneyText(value, style: style),
            ],
          ),
        ],
      ),
    );
  }
}

/// Turns `PENDING_PAYMENT` into `Pending payment`.
String humaniseStatus(String status) {
  if (status.isEmpty) return status;
  final words = status.toLowerCase().replaceAll('_', ' ');
  return words[0].toUpperCase() + words.substring(1);
}

/// The order's status, coloured by what it means for the customer.
class OrderStatusChip extends StatelessWidget {
  const OrderStatusChip({super.key, required this.status});

  final String status;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;

    final (Color background, Color foreground) = switch (status) {
      'DELIVERED' => (Colors.green.shade700, Colors.white),
      'SHIPPED' || 'PROCESSING' || 'PAID' => (scheme.primary, scheme.onPrimary),
      'CANCELLED' || 'FAILED' => (scheme.error, scheme.onError),
      'REFUNDED' || 'PARTIALLY_REFUNDED' => (Colors.orange.shade800, Colors.white),
      _ => (scheme.surfaceContainerHighest, scheme.onSurfaceVariant),
    };

    return Align(
      alignment: AlignmentDirectional.centerStart,
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 5),
        decoration: BoxDecoration(color: background, borderRadius: BorderRadius.circular(20)),
        child: Text(
          humaniseStatus(status),
          style: Theme.of(context)
              .textTheme
              .labelMedium
              ?.copyWith(color: foreground, fontWeight: FontWeight.w600),
        ),
      ),
    );
  }
}
