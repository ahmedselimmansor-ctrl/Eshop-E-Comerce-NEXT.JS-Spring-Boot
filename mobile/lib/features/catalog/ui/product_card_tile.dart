import 'package:flutter/material.dart';

import '../../../core/models/product.dart';
import '../../../core/widgets/money_text.dart';

/// One product in a grid or a horizontal rail.
class ProductCardTile extends StatelessWidget {
  const ProductCardTile({super.key, required this.product, required this.onTap, this.width});

  final ProductCard product;
  final VoidCallback onTap;
  final double? width;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);

    return SizedBox(
      width: width,
      child: InkWell(
        onTap: onTap,
        borderRadius: BorderRadius.circular(12),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          mainAxisSize: MainAxisSize.min,
          children: [
            AspectRatio(
              aspectRatio: 1,
              child: Stack(
                fit: StackFit.expand,
                children: [
                  ClipRRect(
                    borderRadius: BorderRadius.circular(12),
                    child: _Image(url: product.imageUrl),
                  ),
                  if (product.onSale)
                    PositionedDirectional(
                      top: 8,
                      start: 8,
                      child: _Badge(
                        label: '-${product.discountPercent}%',
                        color: theme.colorScheme.error,
                      ),
                    ),
                  if (!product.inStock)
                    PositionedDirectional(
                      bottom: 8,
                      start: 8,
                      child: _Badge(label: 'Out of stock', color: theme.colorScheme.outline),
                    ),
                ],
              ),
            ),
            const SizedBox(height: 8),

            if (product.brandName != null)
              Text(
                product.brandName!,
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: theme.textTheme.labelSmall?.copyWith(
                  color: theme.colorScheme.onSurfaceVariant,
                ),
              ),

            Text(
              product.name,
              maxLines: 2,
              overflow: TextOverflow.ellipsis,
              style: theme.textTheme.bodyMedium,
            ),
            const SizedBox(height: 4),

            MoneyText(
              product.price,
              compareAt: product.onSale ? product.compareAtPrice : null,
              style: theme.textTheme.titleSmall?.copyWith(fontWeight: FontWeight.w700),
            ),

            if (product.ratingCount > 0) ...[
              const SizedBox(height: 2),
              Row(
                children: [
                  Icon(Icons.star_rounded, size: 14, color: theme.colorScheme.tertiary),
                  const SizedBox(width: 2),
                  Text(
                    '${product.ratingAvg.toStringAsFixed(1)} (${product.ratingCount})',
                    style: theme.textTheme.labelSmall?.copyWith(
                      color: theme.colorScheme.onSurfaceVariant,
                    ),
                  ),
                ],
              ),
            ],
          ],
        ),
      ),
    );
  }
}

class _Image extends StatelessWidget {
  const _Image({this.url});

  final String? url;

  @override
  Widget build(BuildContext context) {
    final placeholder = ColoredBox(
      color: Theme.of(context).colorScheme.surfaceContainerHighest,
      child: Icon(
        Icons.image_outlined,
        color: Theme.of(context).colorScheme.outline,
      ),
    );

    if (url == null || url!.isEmpty) return placeholder;

    return Image.network(
      url!,
      fit: BoxFit.cover,
      // A broken image URL must not take the tile down with it.
      errorBuilder: (context, error, stackTrace) => placeholder,
      loadingBuilder: (context, child, progress) =>
          progress == null ? child : placeholder,
    );
  }
}

class _Badge extends StatelessWidget {
  const _Badge({required this.label, required this.color});

  final String label;
  final Color color;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
      decoration: BoxDecoration(color: color, borderRadius: BorderRadius.circular(6)),
      child: Text(
        label,
        style: Theme.of(context)
            .textTheme
            .labelSmall
            ?.copyWith(color: Theme.of(context).colorScheme.onError, fontWeight: FontWeight.w700),
      ),
    );
  }
}
