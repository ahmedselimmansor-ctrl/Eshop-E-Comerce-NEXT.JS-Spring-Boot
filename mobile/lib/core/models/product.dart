import 'money.dart';

/// A product as it appears in a listing.
///
/// Mirrors the API's `ProductCardResponse`: `name` is already resolved for the requested locale, so
/// nothing here needs to know which language is in use.
class ProductCard {
  const ProductCard({
    required this.id,
    required this.slug,
    required this.sku,
    required this.name,
    required this.price,
    this.compareAtPrice,
    this.imageUrl,
    this.brandName,
    this.discountPercent = 0,
    this.ratingAvg = 0,
    this.ratingCount = 0,
    this.inStock = true,
  });

  final String id;
  final String slug;
  final String sku;
  final String name;
  final Money price;

  /// The pre-sale price, struck through when present.
  final Money? compareAtPrice;

  final String? imageUrl;
  final String? brandName;
  final int discountPercent;
  final double ratingAvg;
  final int ratingCount;
  final bool inStock;

  bool get onSale => compareAtPrice != null && discountPercent > 0;

  factory ProductCard.fromJson(Map<String, dynamic> json) => ProductCard(
        id: json['id'] as String,
        slug: json['slug'] as String,
        sku: (json['sku'] ?? '') as String,
        name: (json['name'] ?? '') as String,
        price: Money.fromJson(json['price'] as Map<String, dynamic>),
        compareAtPrice: json['compareAtPrice'] == null
            ? null
            : Money.fromJson(json['compareAtPrice'] as Map<String, dynamic>),
        imageUrl: json['imageUrl'] as String?,
        brandName: json['brandName'] as String?,
        discountPercent: (json['discountPercent'] ?? 0) as int,
        ratingAvg: _toDouble(json['ratingAvg']),
        ratingCount: (json['ratingCount'] ?? 0) as int,
        inStock: (json['inStock'] ?? true) as bool,
      );
}

/// A product's own page: everything the card has, plus description, images and variants.
class ProductDetail {
  const ProductDetail({
    required this.id,
    required this.slug,
    required this.sku,
    required this.name,
    required this.price,
    required this.images,
    required this.variants,
    required this.specifications,
    this.compareAtPrice,
    this.shortDescription,
    this.description,
    this.brandName,
    this.categoryName,
    this.discountPercent = 0,
    this.ratingAvg = 0,
    this.ratingCount = 0,
    this.inStock = true,
    this.availableQuantity,
  });

  final String id;
  final String slug;
  final String sku;
  final String name;
  final Money price;
  final Money? compareAtPrice;
  final String? shortDescription;
  final String? description;
  final String? brandName;
  final String? categoryName;
  final int discountPercent;
  final double ratingAvg;
  final int ratingCount;
  final bool inStock;
  final int? availableQuantity;
  final List<String> images;
  final List<ProductVariant> variants;
  final List<ProductSpecification> specifications;

  bool get onSale => compareAtPrice != null && discountPercent > 0;

  /// Low enough to be worth telling the customer about, but not zero.
  bool get lowStock =>
      inStock && availableQuantity != null && availableQuantity! > 0 && availableQuantity! <= 5;

  factory ProductDetail.fromJson(Map<String, dynamic> json) => ProductDetail(
        id: json['id'] as String,
        slug: json['slug'] as String,
        sku: (json['sku'] ?? '') as String,
        name: (json['name'] ?? '') as String,
        price: Money.fromJson(json['price'] as Map<String, dynamic>),
        compareAtPrice: json['compareAtPrice'] == null
            ? null
            : Money.fromJson(json['compareAtPrice'] as Map<String, dynamic>),
        shortDescription: json['shortDescription'] as String?,
        description: json['description'] as String?,
        brandName: (json['brand'] as Map<String, dynamic>?)?['name'] as String?,
        categoryName: (json['category'] as Map<String, dynamic>?)?['name'] as String?,
        discountPercent: (json['discountPercent'] ?? 0) as int,
        ratingAvg: _toDouble(json['ratingAvg']),
        ratingCount: (json['ratingCount'] ?? 0) as int,
        inStock: (json['inStock'] ?? true) as bool,
        availableQuantity: json['availableQuantity'] as int?,
        images: ((json['images'] ?? const []) as List<dynamic>)
            .map((image) => (image as Map<String, dynamic>)['url'] as String)
            .toList(growable: false),
        variants: ((json['variants'] ?? const []) as List<dynamic>)
            .map((variant) => ProductVariant.fromJson(variant as Map<String, dynamic>))
            .toList(growable: false),
        specifications: ((json['specifications'] ?? const []) as List<dynamic>)
            .map((spec) => ProductSpecification.fromJson(spec as Map<String, dynamic>))
            .toList(growable: false),
      );
}

class ProductVariant {
  const ProductVariant({
    required this.id,
    required this.sku,
    required this.price,
    this.label,
    this.inStock = true,
  });

  final String id;
  final String sku;
  final Money price;
  final String? label;
  final bool inStock;

  factory ProductVariant.fromJson(Map<String, dynamic> json) => ProductVariant(
        id: json['id'] as String,
        sku: (json['sku'] ?? '') as String,
        price: Money.fromJson(json['price'] as Map<String, dynamic>),
        label: json['label'] as String?,
        inStock: (json['inStock'] ?? true) as bool,
      );
}

class ProductSpecification {
  const ProductSpecification({required this.name, required this.value, this.unit});

  final String name;
  final String value;
  final String? unit;

  String get display => unit == null || unit!.isEmpty ? value : '$value $unit';

  factory ProductSpecification.fromJson(Map<String, dynamic> json) => ProductSpecification(
        name: (json['name'] ?? '') as String,
        value: (json['value'] ?? '') as String,
        unit: json['unit'] as String?,
      );
}

class Category {
  const Category({
    required this.id,
    required this.slug,
    required this.name,
    this.imageUrl,
    this.children = const [],
  });

  final String id;
  final String slug;
  final String name;
  final String? imageUrl;
  final List<Category> children;

  factory Category.fromJson(Map<String, dynamic> json) => Category(
        id: json['id'] as String,
        slug: json['slug'] as String,
        name: (json['name'] ?? '') as String,
        imageUrl: json['imageUrl'] as String?,
        children: ((json['children'] ?? const []) as List<dynamic>)
            .map((child) => Category.fromJson(child as Map<String, dynamic>))
            .toList(growable: false),
      );
}

/// Ratings arrive as a JSON number, which may decode as either `int` or `double`.
double _toDouble(dynamic value) => switch (value) {
      null => 0,
      final num number => number.toDouble(),
      final String text => double.tryParse(text) ?? 0,
      _ => 0,
    };
