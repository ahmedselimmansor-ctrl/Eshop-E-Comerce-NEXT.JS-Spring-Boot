import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/widgets/async_view.dart';
import '../data/catalog_repository.dart';
import 'product_grid.dart';

/// Everything in one category.
class CategoryScreen extends ConsumerWidget {
  const CategoryScreen({super.key, required this.slug});

  final String slug;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final query = ProductQuery(categorySlug: slug);
    final results = ref.watch(productListProvider(query));

    return Scaffold(
      appBar: AppBar(
        // The slug until the first page lands, then the category's real name from the tree.
        title: Text(
          ref.watch(categoryTreeProvider).maybeWhen(
                data: (categories) => _nameFor(categories, slug) ?? slug,
                orElse: () => slug,
              ),
        ),
      ),
      body: AsyncView(
        value: results,
        onRetry: () => ref.invalidate(productListProvider(query)),
        builder: (page) => page.content.isEmpty
            ? const EmptyState(
                icon: Icons.inventory_2_outlined,
                title: 'Nothing here yet',
                hint: 'This category has no products at the moment.',
              )
            : ProductGrid(products: page.content),
      ),
    );
  }

  /// Depth-first, because the tree nests and the slug may name a child.
  static String? _nameFor(List<dynamic> categories, String slug) {
    for (final category in categories) {
      if (category.slug == slug) return category.name as String;
      final found = _nameFor(category.children as List<dynamic>, slug);
      if (found != null) return found;
    }
    return null;
  }
}
