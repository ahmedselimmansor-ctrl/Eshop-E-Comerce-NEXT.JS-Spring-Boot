import 'money.dart';

/// The cart, as the server computes it.
///
/// Totals are never recomputed on the device. Tax, shipping, the cash-on-delivery fee and coupon
/// rules all live on the server, and a client that added them up itself would show one figure on
/// the cart screen and be charged another at checkout the moment either side changed.
class Cart {
  const Cart({
    required this.id,
    required this.items,
    required this.totals,
    this.couponCode,
  });

  final String id;
  final List<CartItem> items;
  final CartTotals totals;
  final String? couponCode;

  bool get isEmpty => items.isEmpty;

  int get itemCount => items.fold(0, (sum, item) => sum + item.quantity);

  factory Cart.fromJson(Map<String, dynamic> json) => Cart(
        id: (json['id'] ?? '') as String,
        items: ((json['items'] ?? const []) as List<dynamic>)
            .map((item) => CartItem.fromJson(item as Map<String, dynamic>))
            .toList(growable: false),
        totals: CartTotals.fromJson((json['totals'] ?? const <String, dynamic>{}) as Map<String, dynamic>),
        couponCode: json['couponCode'] as String?,
      );

  static Cart empty() => Cart(id: '', items: const [], totals: CartTotals.zero());
}

class CartItem {
  const CartItem({
    required this.id,
    required this.productId,
    required this.name,
    required this.quantity,
    required this.unitPrice,
    required this.lineTotal,
    this.variantId,
    this.variantLabel,
    this.slug,
    this.imageUrl,
    this.availableQuantity,
  });

  final String id;
  final String productId;
  final String? variantId;
  final String name;
  final String? variantLabel;
  final String? slug;
  final String? imageUrl;
  final int quantity;
  final Money unitPrice;
  final Money lineTotal;
  final int? availableQuantity;

  /// Stock ran out, or dropped below this line's quantity, after it was added.
  bool get exceedsStock => availableQuantity != null && quantity > availableQuantity!;

  factory CartItem.fromJson(Map<String, dynamic> json) => CartItem(
        id: json['id'] as String,
        productId: json['productId'] as String,
        variantId: json['variantId'] as String?,
        name: (json['name'] ?? '') as String,
        variantLabel: json['variantLabel'] as String?,
        slug: json['slug'] as String?,
        imageUrl: json['imageUrl'] as String?,
        quantity: (json['quantity'] ?? 0) as int,
        unitPrice: Money.fromJson(json['unitPrice'] as Map<String, dynamic>),
        lineTotal: Money.fromJson(json['lineTotal'] as Map<String, dynamic>),
        availableQuantity: json['availableQuantity'] as int?,
      );
}

class CartTotals {
  const CartTotals({
    required this.subtotal,
    required this.discount,
    required this.tax,
    required this.shipping,
    required this.codFee,
    required this.grandTotal,
  });

  final Money subtotal;
  final Money discount;
  final Money tax;
  final Money shipping;
  final Money codFee;
  final Money grandTotal;

  factory CartTotals.fromJson(Map<String, dynamic> json) {
    Money read(String key) => json[key] == null
        ? const Money(amount: '0.00', currency: 'EGP')
        : Money.fromJson(json[key] as Map<String, dynamic>);

    return CartTotals(
      subtotal: read('subtotal'),
      discount: read('discount'),
      tax: read('tax'),
      shipping: read('shipping'),
      codFee: read('codFee'),
      grandTotal: read('grandTotal'),
    );
  }

  static CartTotals zero() => const CartTotals(
        subtotal: Money(amount: '0.00', currency: 'EGP'),
        discount: Money(amount: '0.00', currency: 'EGP'),
        tax: Money(amount: '0.00', currency: 'EGP'),
        shipping: Money(amount: '0.00', currency: 'EGP'),
        codFee: Money(amount: '0.00', currency: 'EGP'),
        grandTotal: Money(amount: '0.00', currency: 'EGP'),
      );
}
