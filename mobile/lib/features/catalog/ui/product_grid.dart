import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';

import '../../../core/models/product.dart';
import 'product_card_tile.dart';

/// A grid of products, used by search and by a category listing.
class ProductGrid extends StatelessWidget {
  const ProductGrid({super.key, required this.products, this.padding});

  final List<ProductCard> products;
  final EdgeInsetsGeometry? padding;

  @override
  Widget build(BuildContext context) {
    return GridView.builder(
      padding: padding ?? const EdgeInsets.all(12),
      gridDelegate: const SliverGridDelegateWithMaxCrossAxisExtent(
        // Max extent rather than a fixed count, so a tablet gets more columns instead of four
        // enormous cards.
        maxCrossAxisExtent: 220,
        mainAxisSpacing: 16,
        crossAxisSpacing: 12,
        childAspectRatio: 0.58,
      ),
      itemCount: products.length,
      itemBuilder: (context, index) {
        final product = products[index];
        return ProductCardTile(
          product: product,
          onTap: () => context.push('/products/${product.slug}'),
        );
      },
    );
  }
}
