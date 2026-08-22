/// An amount with its currency, mirroring the API's `Money`.
///
/// The amount is kept as the string the server sent rather than parsed into a `double`. Binary
/// floating point cannot represent 0.1, so a price that arrives as "1899.00" and is formatted from
/// a double can render as 1898.99 — a class of bug that is invisible in testing and infuriating in
/// production. Parsing happens only where arithmetic is genuinely needed.
class Money {
  const Money({required this.amount, required this.currency});

  final String amount;
  final String currency;

  factory Money.fromJson(Map<String, dynamic> json) => Money(
        amount: (json['amount'] ?? '0').toString(),
        currency: (json['currency'] ?? 'EGP').toString(),
      );

  Map<String, dynamic> toJson() => {'amount': amount, 'currency': currency};

  double get asDouble => double.tryParse(amount) ?? 0;

  bool get isZero => asDouble == 0;

  @override
  String toString() => '$amount $currency';

  @override
  bool operator ==(Object other) =>
      other is Money && other.amount == amount && other.currency == currency;

  @override
  int get hashCode => Object.hash(amount, currency);
}
