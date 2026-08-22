import 'money.dart';

/// An order as the customer sees it.
class Order {
  const Order({
    required this.id,
    required this.orderNumber,
    required this.status,
    required this.paymentStatus,
    required this.items,
    required this.totals,
    required this.timeline,
    required this.placedAt,
    this.shippingAddress = const {},
    this.shippingMethodName,
    this.couponCode,
  });

  final String id;
  final String orderNumber;
  final String status;
  final String paymentStatus;
  final List<OrderItem> items;
  final OrderTotals totals;
  final List<OrderEvent> timeline;
  final String placedAt;
  final Map<String, dynamic> shippingAddress;
  final String? shippingMethodName;
  final String? couponCode;

  /// Terminal states, mirroring the server's own set. Nothing further happens to these.
  static const _settled = {'DELIVERED', 'CANCELLED', 'REFUNDED', 'FAILED'};

  bool get isSettled => _settled.contains(status);

  /// The customer may cancel only before anything has left the warehouse.
  bool get isCancellable =>
      const {'PENDING_PAYMENT', 'PAID', 'PROCESSING'}.contains(status);

  int get itemCount => items.fold(0, (sum, item) => sum + item.quantity);

  factory Order.fromJson(Map<String, dynamic> json) => Order(
        id: (json['id'] ?? '') as String,
        orderNumber: (json['orderNumber'] ?? '') as String,
        status: (json['status'] ?? '') as String,
        paymentStatus: (json['paymentStatus'] ?? '') as String,
        items: ((json['items'] ?? const []) as List<dynamic>)
            .map((item) => OrderItem.fromJson(item as Map<String, dynamic>))
            .toList(growable: false),
        totals: OrderTotals.fromJson(
          (json['totals'] ?? const <String, dynamic>{}) as Map<String, dynamic>,
        ),
        timeline: ((json['timeline'] ?? const []) as List<dynamic>)
            .map((entry) => OrderEvent.fromJson(entry as Map<String, dynamic>))
            .toList(growable: false),
        placedAt: (json['placedAt'] ?? '') as String,
        shippingAddress:
            (json['shippingAddress'] ?? const <String, dynamic>{}) as Map<String, dynamic>,
        shippingMethodName: json['shippingMethodName'] as String?,
        couponCode: json['couponCode'] as String?,
      );
}

class OrderItem {
  const OrderItem({
    required this.id,
    required this.name,
    required this.quantity,
    required this.unitPrice,
    required this.lineTotal,
    this.sku,
    this.slug,
    this.imageUrl,
    this.variantLabel,
    this.fulfilledQuantity = 0,
  });

  final String id;
  final String name;
  final String? sku;
  final String? slug;
  final String? imageUrl;
  final String? variantLabel;
  final int quantity;
  final int fulfilledQuantity;
  final Money unitPrice;
  final Money lineTotal;

  factory OrderItem.fromJson(Map<String, dynamic> json) => OrderItem(
        id: (json['id'] ?? '') as String,
        name: (json['name'] ?? '') as String,
        sku: json['sku'] as String?,
        slug: json['slug'] as String?,
        imageUrl: json['imageUrl'] as String?,
        variantLabel: json['variantLabel'] as String?,
        quantity: (json['quantity'] ?? 0) as int,
        fulfilledQuantity: (json['fulfilledQuantity'] ?? 0) as int,
        unitPrice: Money.fromJson(json['unitPrice'] as Map<String, dynamic>),
        lineTotal: Money.fromJson(json['lineTotal'] as Map<String, dynamic>),
      );
}

class OrderTotals {
  const OrderTotals({
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

  factory OrderTotals.fromJson(Map<String, dynamic> json) {
    Money read(String key) => json[key] == null
        ? const Money(amount: '0.00', currency: 'EGP')
        : Money.fromJson(json[key] as Map<String, dynamic>);

    return OrderTotals(
      subtotal: read('subtotal'),
      discount: read('discount'),
      tax: read('tax'),
      shipping: read('shipping'),
      codFee: read('codFee'),
      grandTotal: read('grandTotal'),
    );
  }
}

class OrderEvent {
  const OrderEvent({required this.status, required this.at, this.note});

  final String status;
  final String at;
  final String? note;

  factory OrderEvent.fromJson(Map<String, dynamic> json) => OrderEvent(
        status: (json['status'] ?? '') as String,
        at: (json['at'] ?? '') as String,
        note: json['note'] as String?,
      );
}

/// A saved delivery address.
class Address {
  const Address({
    required this.id,
    required this.recipientName,
    required this.phone,
    required this.governorate,
    required this.city,
    this.street,
    this.building,
    this.formatted,
    this.isDefaultShipping = false,
  });

  final String id;
  final String recipientName;
  final String phone;
  final String governorate;
  final String city;
  final String? street;
  final String? building;
  final String? formatted;
  final bool isDefaultShipping;

  String get summary =>
      formatted ?? [street, building, city, governorate].whereType<String>().join('، ');

  factory Address.fromJson(Map<String, dynamic> json) => Address(
        id: json['id'] as String,
        recipientName: (json['recipientName'] ?? '') as String,
        phone: (json['phone'] ?? '') as String,
        governorate: (json['governorate'] ?? '') as String,
        city: (json['city'] ?? '') as String,
        street: json['street'] as String?,
        building: json['building'] as String?,
        formatted: json['formatted'] as String?,
        // `defaultShipping` on the wire; the `is` prefix is a Dart convention, not the API's.
        isDefaultShipping: (json['defaultShipping'] ?? false) as bool,
      );
}

/// A delivery option for a governorate, priced by the server.
class ShippingMethod {
  const ShippingMethod({
    required this.id,
    required this.name,
    required this.price,
    this.estimatedDaysMin,
    this.estimatedDaysMax,
    this.supportsCod = true,
  });

  final String id;
  final String name;
  final Money price;
  final int? estimatedDaysMin;
  final int? estimatedDaysMax;

  /// Not every courier collects cash, so a method that does not must not be offered with COD.
  final bool supportsCod;

  String? get estimate {
    if (estimatedDaysMin == null) return null;
    if (estimatedDaysMax == null || estimatedDaysMax == estimatedDaysMin) {
      return '$estimatedDaysMin';
    }
    return '$estimatedDaysMin-$estimatedDaysMax';
  }

  factory ShippingMethod.fromJson(Map<String, dynamic> json) => ShippingMethod(
        id: json['id'] as String,
        name: (json['name'] ?? '') as String,
        // `cost`, not `price`: that is what ShippingOption is called on the wire.
        price: json['cost'] == null
            ? const Money(amount: '0.00', currency: 'EGP')
            : Money.fromJson(json['cost'] as Map<String, dynamic>),
        estimatedDaysMin: (json['estimatedDaysMin'] as num?)?.toInt(),
        estimatedDaysMax: (json['estimatedDaysMax'] as num?)?.toInt(),
        supportsCod: (json['supportsCod'] ?? true) as bool,
      );
}

/// The signed-in customer.
class Account {
  const Account({
    required this.id,
    required this.email,
    this.fullName,
    this.phone,
    this.emailVerified = false,
  });

  final String id;
  final String email;
  final String? fullName;
  final String? phone;
  final bool emailVerified;

  factory Account.fromJson(Map<String, dynamic> json) => Account(
        id: json['id'] as String,
        email: (json['email'] ?? '') as String,
        fullName: json['fullName'] as String?,
        phone: json['phone'] as String?,
        emailVerified: (json['emailVerified'] ?? false) as bool,
      );
}
