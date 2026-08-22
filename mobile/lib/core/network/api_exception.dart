/// A failed API call, carrying the RFC 9457 problem document the backend returned.
///
/// Callers switch on [code] — the stable machine value from `docs/02-API-CONTRACT.md` — never on
/// [detail], which is prose the backend rewords and translates freely. Matching on a message is a
/// bug waiting for the next copy change.
class ApiException implements Exception {
  ApiException({
    required this.status,
    required this.code,
    required this.detail,
    this.violations = const [],
    this.requestId,
  });

  /// Builds from a problem document, tolerating a response that is not one — a gateway timeout or
  /// a proxy error page is HTML, and it still has to arrive as the same exception type.
  factory ApiException.fromProblem(int status, dynamic body) {
    if (body is! Map<String, dynamic>) {
      return ApiException(
        status: status,
        code: _codeForStatus(status),
        detail: 'حدث خطأ غير متوقع',
      );
    }

    final rawViolations = body['errors'];
    final violations = rawViolations is List
        ? rawViolations
            .whereType<Map<String, dynamic>>()
            .map(FieldViolation.fromJson)
            .toList(growable: false)
        : const <FieldViolation>[];

    return ApiException(
      status: status,
      code: body['code'] as String? ?? _codeForStatus(status),
      detail: body['detail'] as String? ?? body['title'] as String? ?? 'حدث خطأ غير متوقع',
      violations: violations,
      requestId: body['requestId'] as String?,
    );
  }

  final int status;
  final String code;
  final String detail;
  final List<FieldViolation> violations;

  /// Echoed from the response. Worth showing in a support screen — it is what turns a customer's
  /// screenshot into a single log query.
  final String? requestId;

  bool isAnyOf(List<String> codes) => codes.contains(code);

  /// Whether repeating the identical request could plausibly succeed.
  bool get isRetryable => status == 429 || status >= 500;

  /// Whether the session is over and the customer needs to sign in again.
  bool get requiresReauthentication =>
      code == 'TOKEN_EXPIRED' || code == 'TOKEN_INVALID' || code == 'TOKEN_REUSE_DETECTED';

  /// Field errors keyed by field name, for inline form validation.
  Map<String, String> get fieldErrors => {
        for (final violation in violations)
          if (violation.message != null) violation.field: violation.message!,
      };

  static String _codeForStatus(int status) => switch (status) {
        400 => 'VALIDATION_ERROR',
        401 => 'UNAUTHENTICATED',
        403 => 'FORBIDDEN',
        404 => 'NOT_FOUND',
        409 => 'CONFLICT',
        429 => 'RATE_LIMITED',
        503 => 'SERVICE_UNAVAILABLE',
        _ => 'INTERNAL_ERROR',
      };

  @override
  String toString() => 'ApiException($status $code: $detail)';
}

class FieldViolation {
  const FieldViolation({required this.field, this.code, this.message});

  factory FieldViolation.fromJson(Map<String, dynamic> json) => FieldViolation(
        field: json['field'] as String? ?? '',
        code: json['code'] as String?,
        message: json['message'] as String?,
      );

  final String field;
  final String? code;
  final String? message;
}
