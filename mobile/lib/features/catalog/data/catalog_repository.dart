import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/models/page.dart';
import '../../../core/models/product.dart';
import '../../../core/network/api_client.dart';
import '../../../core/providers/api_providers.dart';

/// Reads the catalog.
///
/// Every method returns already-parsed models, so no widget ever sees a `Map<String, dynamic>`; a
/// field the API renames then breaks in one place with a clear error rather than silently becoming
/// null somewhere in the widget tree.
class CatalogRepository {
  CatalogRepository(this._client);

  final ApiClient _client;

  Future<Paged<ProductCard>> products({
    String? query,
    String? categorySlug,
    String? brandSlug,
    String sort = 'RELEVANCE',
    int page = 0,
    int size = 20,
  }) {
    return _client.get(
      '/api/v1/products',
      query: {
        if (query != null && query.isNotEmpty) 'q': query,
        if (categorySlug != null) 'category': categorySlug,
        if (brandSlug != null) 'brand': brandSlug,
        'sort': sort,
        'page': page,
        'size': size,
      },
      parse: (json) => Paged.fromJson(json, ProductCard.fromJson),
    );
  }

  Future<ProductDetail> productBySlug(String slug) {
    return _client.get(
      '/api/v1/products/$slug',
      parse: (json) => ProductDetail.fromJson(json as Map<String, dynamic>),
    );
  }

  /// The nested tree, for navigation. `/categories` returns the same set flat, which is what a
  /// picker wants and what a menu does not.
  Future<List<Category>> categoryTree() {
    return _client.get(
      '/api/v1/categories/tree',
      parse: (json) => (json as List<dynamic>)
          .map((entry) => Category.fromJson(entry as Map<String, dynamic>))
          .toList(growable: false),
    );
  }

  Future<Paged<ProductCard>> featured({int size = 10}) {
    return products(sort: 'BEST_SELLING', size: size);
  }
}

final catalogRepositoryProvider = Provider<CatalogRepository>(
  (ref) => CatalogRepository(ref.watch(apiClientProvider)),
);

/// The home screen's product rail.
final featuredProductsProvider = FutureProvider<List<ProductCard>>((ref) async {
  final page = await ref.watch(catalogRepositoryProvider).featured();
  return page.content;
});

final categoryTreeProvider = FutureProvider<List<Category>>(
  (ref) => ref.watch(catalogRepositoryProvider).categoryTree(),
);

final productDetailProvider =
    FutureProvider.family<ProductDetail, String>((ref, slug) async {
  return ref.watch(catalogRepositoryProvider).productBySlug(slug);
});

/// A product listing, keyed by its filters so two different lists do not share a cache entry.
final productListProvider =
    FutureProvider.family<Paged<ProductCard>, ProductQuery>((ref, query) async {
  return ref.watch(catalogRepositoryProvider).products(
        query: query.search,
        categorySlug: query.categorySlug,
        brandSlug: query.brandSlug,
        sort: query.sort,
        page: query.page,
      );
});

/// The identity of a listing request.
///
/// Value equality matters: Riverpod's `family` caches on it, and without `==` every rebuild would
/// look like a new query and refetch.
class ProductQuery {
  const ProductQuery({
    this.search,
    this.categorySlug,
    this.brandSlug,
    this.sort = 'RELEVANCE',
    this.page = 0,
  });

  final String? search;
  final String? categorySlug;
  final String? brandSlug;
  final String sort;
  final int page;

  ProductQuery copyWith({String? search, String? sort, int? page}) => ProductQuery(
        search: search ?? this.search,
        categorySlug: categorySlug,
        brandSlug: brandSlug,
        sort: sort ?? this.sort,
        page: page ?? this.page,
      );

  @override
  bool operator ==(Object other) =>
      other is ProductQuery &&
      other.search == search &&
      other.categorySlug == categorySlug &&
      other.brandSlug == brandSlug &&
      other.sort == sort &&
      other.page == page;

  @override
  int get hashCode => Object.hash(search, categorySlug, brandSlug, sort, page);
}
