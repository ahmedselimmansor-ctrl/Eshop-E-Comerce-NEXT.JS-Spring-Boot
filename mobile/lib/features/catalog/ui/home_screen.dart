import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../core/models/product.dart';
import '../../../core/widgets/async_view.dart';
import '../../cart/data/cart_repository.dart';
import '../data/catalog_repository.dart';
import 'product_card_tile.dart';

/// The storefront's landing screen: categories, then a rail of products.
class HomeScreen extends ConsumerWidget {
  const HomeScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final categories = ref.watch(categoryTreeProvider);
    final featured = ref.watch(featuredProductsProvider);

    return Scaffold(
      appBar: AppBar(
        title: const Text('Eshop'),
        actions: [
          IconButton(
            icon: const Icon(Icons.search),
            onPressed: () => context.go('/search'),
            tooltip: 'Search',
          ),
          const _CartButton(),
        ],
      ),
      body: RefreshIndicator(
        onRefresh: () async {
          ref.invalidate(categoryTreeProvider);
          ref.invalidate(featuredProductsProvider);
          await ref.read(featuredProductsProvider.future);
        },
        child: ListView(
          padding: const EdgeInsets.symmetric(vertical: 12),
          children: [
            _SectionHeader(
              title: 'Categories',
              onSeeAll: () => context.go('/search'),
            ),
            SizedBox(
              height: 108,
              child: AsyncView(
                value: categories,
                onRetry: () => ref.invalidate(categoryTreeProvider),
                builder: (data) => _CategoryRail(categories: data),
              ),
            ),
            const SizedBox(height: 16),

            _SectionHeader(
              title: 'Popular right now',
              onSeeAll: () => context.go('/search'),
            ),
            SizedBox(
              height: 292,
              child: AsyncView(
                value: featured,
                onRetry: () => ref.invalidate(featuredProductsProvider),
                builder: (data) => data.isEmpty
                    ? const EmptyState(
                        icon: Icons.inventory_2_outlined,
                        title: 'Nothing to show yet',
                        hint: 'Products will appear here once the catalog is populated.',
                      )
                    : _ProductRail(products: data),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _CategoryRail extends StatelessWidget {
  const _CategoryRail({required this.categories});

  final List<Category> categories;

  @override
  Widget build(BuildContext context) {
    if (categories.isEmpty) {
      return const SizedBox.shrink();
    }

    return ListView.separated(
      scrollDirection: Axis.horizontal,
      padding: const EdgeInsetsDirectional.only(start: 16, end: 16),
      itemCount: categories.length,
      separatorBuilder: (context, index) => const SizedBox(width: 12),
      itemBuilder: (context, index) {
        final category = categories[index];
        return SizedBox(
          width: 84,
          child: InkWell(
            onTap: () => context.go('/categories/${category.slug}'),
            borderRadius: BorderRadius.circular(12),
            child: Column(
              children: [
                CircleAvatar(
                  radius: 28,
                  backgroundColor: Theme.of(context).colorScheme.surfaceContainerHighest,
                  child: Icon(
                    Icons.category_outlined,
                    color: Theme.of(context).colorScheme.primary,
                  ),
                ),
                const SizedBox(height: 6),
                Text(
                  category.name,
                  maxLines: 2,
                  textAlign: TextAlign.center,
                  overflow: TextOverflow.ellipsis,
                  style: Theme.of(context).textTheme.labelSmall,
                ),
              ],
            ),
          ),
        );
      },
    );
  }
}

class _ProductRail extends StatelessWidget {
  const _ProductRail({required this.products});

  final List<ProductCard> products;

  @override
  Widget build(BuildContext context) {
    return ListView.separated(
      scrollDirection: Axis.horizontal,
      padding: const EdgeInsetsDirectional.only(start: 16, end: 16),
      itemCount: products.length,
      separatorBuilder: (context, index) => const SizedBox(width: 12),
      itemBuilder: (context, index) {
        final product = products[index];
        return ProductCardTile(
          product: product,
          width: 156,
          onTap: () => context.go('/products/${product.slug}'),
        );
      },
    );
  }
}

class _SectionHeader extends StatelessWidget {
  const _SectionHeader({required this.title, this.onSeeAll});

  final String title;
  final VoidCallback? onSeeAll;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsetsDirectional.only(start: 16, end: 8, top: 8, bottom: 8),
      child: Row(
        mainAxisAlignment: MainAxisAlignment.spaceBetween,
        children: [
          Text(title, style: Theme.of(context).textTheme.titleMedium),
          if (onSeeAll != null)
            TextButton(onPressed: onSeeAll, child: const Text('See all')),
        ],
      ),
    );
  }
}

/// The cart icon, with a badge that follows the shared cart state.
class _CartButton extends ConsumerWidget {
  const _CartButton();

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final count = ref.watch(cartItemCountProvider);

    return IconButton(
      tooltip: 'Cart',
      onPressed: () => context.go('/cart'),
      icon: Badge(
        isLabelVisible: count > 0,
        label: Text('$count'),
        child: const Icon(Icons.shopping_bag_outlined),
      ),
    );
  }
}
