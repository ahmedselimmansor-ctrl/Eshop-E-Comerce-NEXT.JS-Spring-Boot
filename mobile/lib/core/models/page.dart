/// One page of results, mirroring the API's `PageResponse`.
class Paged<T> {
  const Paged({
    required this.content,
    required this.number,
    required this.size,
    required this.totalElements,
    required this.totalPages,
  });

  final List<T> content;
  final int number;
  final int size;
  final int totalElements;
  final int totalPages;

  bool get hasMore => number + 1 < totalPages;

  /// The page metadata is nested under `page`, so a caller cannot mistake a field of the payload
  /// for a field of a result.
  factory Paged.fromJson(dynamic json, T Function(Map<String, dynamic>) item) {
    final map = json as Map<String, dynamic>;
    final meta = (map['page'] ?? const <String, dynamic>{}) as Map<String, dynamic>;

    return Paged(
      content: ((map['content'] ?? const []) as List<dynamic>)
          .map((entry) => item(entry as Map<String, dynamic>))
          .toList(growable: false),
      number: (meta['number'] ?? 0) as int,
      size: (meta['size'] ?? 0) as int,
      totalElements: (meta['totalElements'] ?? 0) as int,
      totalPages: (meta['totalPages'] ?? 0) as int,
    );
  }

  static Paged<T> empty<T>() =>
      Paged<T>(content: const [], number: 0, size: 0, totalElements: 0, totalPages: 0);
}
