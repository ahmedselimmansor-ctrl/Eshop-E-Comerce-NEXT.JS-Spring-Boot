import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../core/models/product.dart';
import '../../../core/network/api_exception.dart';
import '../../../core/widgets/async_view.dart';
import '../../../core/widgets/money_text.dart';
import '../../cart/data/cart_repository.dart';
import '../data/catalog_repository.dart';

/// One product.
class ProductScreen extends ConsumerStatefulWidget {
  const ProductScreen({super.key, required this.slug});

  final String slug;

  @override
  ConsumerState<ProductScreen> createState() => _ProductScreenState();
}

class _ProductScreenState extends ConsumerState<ProductScreen> {
  String? _variantId;
  int _quantity = 1;
  bool _adding = false;

  @override
  Widget build(BuildContext context) {
    final product = ref.watch(productDetailProvider(widget.slug));

    return Scaffold(
      appBar: AppBar(),
      body: AsyncView(
        value: product,
        onRetry: () => ref.invalidate(productDetailProvider(widget.slug)),
        builder: (data) => _Body(
          product: data,
          variantId: _variantId,
          quantity: _quantity,
          onVariantChanged: (id) => setState(() => _variantId = id),
          onQuantityChanged: (value) => setState(() => _quantity = value),
        ),
      ),
      bottomNavigationBar: product.maybeWhen(
        data: (data) => _AddToCartBar(
          product: data,
          busy: _adding,
          onAdd: () => _addToCart(data),
        ),
        orElse: () => null,
      ),
    );
  }

  Future<void> _addToCart(ProductDetail product) async {
    setState(() => _adding = true);
    final messenger = ScaffoldMessenger.of(context);

    try {
      await ref.read(cartControllerProvider.notifier).add(
            productId: product.id,
            variantId: _variantId,
            quantity: _quantity,
          );

      // The controller records a failed mutation as an error state rather than throwing, so a
      // silent failure would otherwise look exactly like success.
      final state = ref.read(cartControllerProvider);
      if (state.hasError) {
        throw state.error!;
      }

      if (!mounted) return;
      messenger.showSnackBar(
        SnackBar(
          content: Text('${product.name} added to your cart'),
          action: SnackBarAction(label: 'View', onPressed: () => context.go('/cart')),
        ),
      );
    } catch (error) {
      if (!mounted) return;
      messenger.showSnackBar(
        SnackBar(
          content: Text(error is ApiException ? error.detail : 'Could not add this to your cart'),
        ),
      );
    } finally {
      if (mounted) setState(() => _adding = false);
    }
  }
}

class _Body extends StatelessWidget {
  const _Body({
    required this.product,
    required this.variantId,
    required this.quantity,
    required this.onVariantChanged,
    required this.onQuantityChanged,
  });

  final ProductDetail product;
  final String? variantId;
  final int quantity;
  final ValueChanged<String?> onVariantChanged;
  final ValueChanged<int> onQuantityChanged;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);

    return ListView(
      padding: const EdgeInsets.only(bottom: 24),
      children: [
        if (product.images.isNotEmpty)
          AspectRatio(
            aspectRatio: 1,
            child: PageView(
              children: [
                for (final url in product.images)
                  Image.network(
                    url,
                    fit: BoxFit.cover,
                    errorBuilder: (context, error, stackTrace) => ColoredBox(
                      color: theme.colorScheme.surfaceContainerHighest,
                      child: Icon(Icons.image_outlined, color: theme.colorScheme.outline),
                    ),
                  ),
              ],
            ),
          ),

        Padding(
          padding: const EdgeInsets.all(16),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              if (product.brandName != null)
                Text(
                  product.brandName!,
                  style: theme.textTheme.labelMedium?.copyWith(
                    color: theme.colorScheme.onSurfaceVariant,
                  ),
                ),
              Text(product.name, style: theme.textTheme.headlineSmall),
              const SizedBox(height: 8),

              MoneyText(
                product.price,
                compareAt: product.onSale ? product.compareAtPrice : null,
                style: theme.textTheme.headlineSmall?.copyWith(fontWeight: FontWeight.w700),
              ),

              if (product.ratingCount > 0) ...[
                const SizedBox(height: 8),
                Row(
                  children: [
                    Icon(Icons.star_rounded, size: 18, color: theme.colorScheme.tertiary),
                    const SizedBox(width: 4),
                    Text(
                      '${product.ratingAvg.toStringAsFixed(1)} · ${product.ratingCount} reviews',
                      style: theme.textTheme.bodySmall,
                    ),
                  ],
                ),
              ],

              if (product.lowStock) ...[
                const SizedBox(height: 8),
                Text(
                  'Only ${product.availableQuantity} left',
                  style: theme.textTheme.bodySmall?.copyWith(color: theme.colorScheme.error),
                ),
              ],

              if (product.variants.isNotEmpty) ...[
                const SizedBox(height: 16),
                Text('Options', style: theme.textTheme.titleSmall),
                const SizedBox(height: 8),
                Wrap(
                  spacing: 8,
                  runSpacing: 8,
                  children: [
                    for (final variant in product.variants)
                      ChoiceChip(
                        label: Text(variant.label ?? variant.sku),
                        selected: variantId == variant.id,
                        onSelected: variant.inStock
                            ? (selected) => onVariantChanged(selected ? variant.id : null)
                            : null,
                      ),
                  ],
                ),
              ],

              const SizedBox(height: 16),
              Row(
                children: [
                  Text('Quantity', style: theme.textTheme.titleSmall),
                  const Spacer(),
                  _QuantityStepper(
                    quantity: quantity,
                    onChanged: onQuantityChanged,
                    max: product.availableQuantity,
                  ),
                ],
              ),

              if (product.shortDescription != null) ...[
                const SizedBox(height: 20),
                Text(product.shortDescription!, style: theme.textTheme.bodyMedium),
              ],

              if (product.description != null) ...[
                const SizedBox(height: 12),
                Text(product.description!, style: theme.textTheme.bodyMedium),
              ],

              if (product.specifications.isNotEmpty) ...[
                const SizedBox(height: 20),
                Text('Specifications', style: theme.textTheme.titleSmall),
                const SizedBox(height: 8),
                for (final spec in product.specifications)
                  Padding(
                    padding: const EdgeInsets.symmetric(vertical: 4),
                    child: Row(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        SizedBox(
                          width: 140,
                          child: Text(
                            spec.name,
                            style: theme.textTheme.bodySmall?.copyWith(
                              color: theme.colorScheme.onSurfaceVariant,
                            ),
                          ),
                        ),
                        Expanded(child: Text(spec.display, style: theme.textTheme.bodySmall)),
                      ],
                    ),
                  ),
              ],
            ],
          ),
        ),
      ],
    );
  }
}

class _QuantityStepper extends StatelessWidget {
  const _QuantityStepper({required this.quantity, required this.onChanged, this.max});

  final int quantity;
  final ValueChanged<int> onChanged;
  final int? max;

  @override
  Widget build(BuildContext context) {
    final atMax = max != null && quantity >= max!;

    return Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        IconButton.outlined(
          onPressed: quantity > 1 ? () => onChanged(quantity - 1) : null,
          icon: const Icon(Icons.remove),
        ),
        Padding(
          padding: const EdgeInsets.symmetric(horizontal: 12),
          child: Text('$quantity', style: Theme.of(context).textTheme.titleMedium),
        ),
        IconButton.outlined(
          // Capped at what is actually in stock, so the request cannot fail on a limit the screen
          // already knows about.
          onPressed: atMax ? null : () => onChanged(quantity + 1),
          icon: const Icon(Icons.add),
        ),
      ],
    );
  }
}

class _AddToCartBar extends StatelessWidget {
  const _AddToCartBar({required this.product, required this.busy, required this.onAdd});

  final ProductDetail product;
  final bool busy;
  final VoidCallback onAdd;

  @override
  Widget build(BuildContext context) {
    return SafeArea(
      child: Padding(
        padding: const EdgeInsets.all(12),
        child: FilledButton.icon(
          onPressed: product.inStock && !busy ? onAdd : null,
          icon: busy
              ? const SizedBox(
                  width: 16,
                  height: 16,
                  child: CircularProgressIndicator(strokeWidth: 2),
                )
              : const Icon(Icons.add_shopping_cart),
          label: Text(product.inStock ? 'Add to cart' : 'Out of stock'),
          style: FilledButton.styleFrom(minimumSize: const Size.fromHeight(48)),
        ),
      ),
    );
  }
}
