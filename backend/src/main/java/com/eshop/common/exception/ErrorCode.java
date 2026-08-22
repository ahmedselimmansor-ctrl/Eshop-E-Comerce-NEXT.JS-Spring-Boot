package com.eshop.common.exception;

import org.springframework.http.HttpStatus;

/**
 * The stable error vocabulary shared with every client.
 *
 * <p>Web, admin, and mobile all branch on {@code code}; {@code detail} is human-readable prose that
 * may be reworded or translated at any time. Adding a value here is a contract change and must be
 * mirrored in {@code docs/02-API-CONTRACT.md} and {@code web/packages/api-client}.
 */
public enum ErrorCode {

    // ---- 400 -------------------------------------------------------------
    VALIDATION_ERROR(HttpStatus.BAD_REQUEST, "خطأ في البيانات المُدخلة", "Validation failed"),
    MALFORMED_REQUEST(HttpStatus.BAD_REQUEST, "صيغة الطلب غير صحيحة", "Malformed request"),
    UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "نوع الملف غير مدعوم", "Unsupported media type"),
    PAYLOAD_TOO_LARGE(HttpStatus.PAYLOAD_TOO_LARGE, "حجم الملف كبير جداً", "Payload too large"),

    // ---- 401 / 403 -------------------------------------------------------
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "يجب تسجيل الدخول", "Authentication required"),
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "البريد الإلكتروني أو كلمة المرور غير صحيحة", "Invalid credentials"),
    ACCOUNT_LOCKED(HttpStatus.UNAUTHORIZED, "تم قفل الحساب مؤقتاً بسبب محاولات دخول متكررة", "Account temporarily locked"),
    ACCOUNT_SUSPENDED(HttpStatus.FORBIDDEN, "تم إيقاف هذا الحساب", "Account suspended"),
    EMAIL_NOT_VERIFIED(HttpStatus.FORBIDDEN, "يجب تفعيل البريد الإلكتروني أولاً", "Email not verified"),
    TOKEN_EXPIRED(HttpStatus.UNAUTHORIZED, "انتهت صلاحية الجلسة", "Token expired"),
    TOKEN_INVALID(HttpStatus.UNAUTHORIZED, "رمز الجلسة غير صالح", "Invalid token"),
    TOKEN_REUSE_DETECTED(HttpStatus.UNAUTHORIZED, "تم إنهاء جميع الجلسات لأسباب أمنية", "Token reuse detected; all sessions revoked"),
    FORBIDDEN(HttpStatus.FORBIDDEN, "ليس لديك صلاحية للقيام بهذا الإجراء", "Insufficient permissions"),

    // ---- 404 / 409 -------------------------------------------------------
    NOT_FOUND(HttpStatus.NOT_FOUND, "العنصر المطلوب غير موجود", "Resource not found"),
    CONFLICT(HttpStatus.CONFLICT, "تعارض في البيانات", "Conflict"),
    DUPLICATE_RESOURCE(HttpStatus.CONFLICT, "هذا العنصر موجود بالفعل", "Resource already exists"),
    EMAIL_ALREADY_REGISTERED(HttpStatus.CONFLICT, "هذا البريد الإلكتروني مسجل بالفعل", "Email already registered"),
    PHONE_ALREADY_REGISTERED(HttpStatus.CONFLICT, "رقم الهاتف مسجل بالفعل", "Phone already registered"),

    // ---- cart & catalog --------------------------------------------------
    CART_EMPTY(HttpStatus.CONFLICT, "السلة فارغة", "Cart is empty"),
    CART_LIMIT_EXCEEDED(HttpStatus.CONFLICT, "تجاوزت الحد الأقصى لعدد المنتجات في السلة", "Cart item limit exceeded"),
    OUT_OF_STOCK(HttpStatus.CONFLICT, "الكمية المطلوبة غير متوفرة", "Insufficient stock"),
    PRODUCT_UNAVAILABLE(HttpStatus.CONFLICT, "هذا المنتج غير متاح للبيع حالياً", "Product not available"),
    PRICE_CHANGED(HttpStatus.CONFLICT, "تغيّر سعر أحد المنتجات، برجاء مراجعة السلة", "Price changed since the item was added"),

    // ---- promotions ------------------------------------------------------
    COUPON_INVALID(HttpStatus.CONFLICT, "كود الخصم غير صالح", "Invalid coupon"),
    COUPON_EXPIRED(HttpStatus.CONFLICT, "انتهت صلاحية كود الخصم", "Coupon expired"),
    COUPON_NOT_STARTED(HttpStatus.CONFLICT, "كود الخصم لم يبدأ بعد", "Coupon not yet active"),
    COUPON_USAGE_EXCEEDED(HttpStatus.CONFLICT, "تم استخدام كود الخصم بالحد الأقصى", "Coupon usage limit reached"),
    COUPON_MIN_ORDER(HttpStatus.CONFLICT, "قيمة الطلب أقل من الحد الأدنى لاستخدام الكود", "Order below coupon minimum"),
    COUPON_NOT_APPLICABLE(HttpStatus.CONFLICT, "كود الخصم لا ينطبق على منتجات السلة", "Coupon does not apply to these items"),

    // ---- checkout, orders, payments --------------------------------------
    ORDER_NOT_CANCELLABLE(HttpStatus.CONFLICT, "لا يمكن إلغاء الطلب في حالته الحالية", "Order cannot be cancelled in its current state"),
    ORDER_ALREADY_PAID(HttpStatus.CONFLICT, "تم دفع هذا الطلب بالفعل", "Order already paid"),
    INVALID_STATUS_TRANSITION(HttpStatus.CONFLICT, "لا يمكن تغيير حالة الطلب إلى الحالة المطلوبة", "Invalid order status transition"),
    SHIPPING_UNAVAILABLE(HttpStatus.CONFLICT, "الشحن غير متاح لهذه المنطقة", "Shipping unavailable for this area"),
    COD_NOT_SUPPORTED(HttpStatus.CONFLICT, "الدفع عند الاستلام غير متاح لهذا الطلب", "Cash on delivery not available"),
    PAYMENT_FAILED(HttpStatus.BAD_GATEWAY, "فشلت عملية الدفع", "Payment failed"),
    PAYMENT_ALREADY_PROCESSED(HttpStatus.CONFLICT, "تمت معالجة هذه العملية بالفعل", "Payment already processed"),
    PAYMENT_PROVIDER_ERROR(HttpStatus.BAD_GATEWAY, "تعذر الاتصال ببوابة الدفع", "Payment provider unreachable"),
    REFUND_EXCEEDS_PAYMENT(HttpStatus.CONFLICT, "قيمة الاسترداد أكبر من المبلغ المدفوع", "Refund exceeds captured amount"),

    // ---- infrastructure --------------------------------------------------
    RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "عدد كبير من المحاولات، برجاء المحاولة لاحقاً", "Too many requests"),
    IDEMPOTENCY_CONFLICT(HttpStatus.CONFLICT, "نفس المفتاح استُخدم بطلب مختلف", "Idempotency key reused with a different payload"),
    IDEMPOTENCY_IN_PROGRESS(HttpStatus.CONFLICT, "الطلب قيد المعالجة بالفعل", "A request with this idempotency key is still in progress"),
    SEARCH_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "خدمة البحث غير متاحة مؤقتاً", "Search temporarily unavailable"),
    STORAGE_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "تعذر رفع الملف", "Storage operation failed"),
    SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "الخدمة غير متاحة مؤقتاً", "Service temporarily unavailable"),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "حدث خطأ غير متوقع", "An unexpected error occurred");

    private final HttpStatus status;
    private final String messageAr;
    private final String messageEn;

    ErrorCode(HttpStatus status, String messageAr, String messageEn) {
        this.status = status;
        this.messageAr = messageAr;
        this.messageEn = messageEn;
    }

    public HttpStatus status() {
        return status;
    }

    public String messageAr() {
        return messageAr;
    }

    public String messageEn() {
        return messageEn;
    }

    /** Picks the message for the caller's {@code Accept-Language}, defaulting to Arabic. */
    public String message(String locale) {
        return "en".equalsIgnoreCase(locale) ? messageEn : messageAr;
    }

    /** The {@code type} URI advertised in the RFC 9457 problem document. */
    public String typeUri() {
        return "https://eshop.eg/errors/" + name().toLowerCase().replace('_', '-');
    }
}
