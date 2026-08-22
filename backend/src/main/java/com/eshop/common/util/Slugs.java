package com.eshop.common.util;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * URL slug generation for a bilingual Arabic/English catalog.
 *
 * <p>Arabic is kept as Arabic rather than transliterated: {@code /هواتف-محمولة} is a valid,
 * indexable path once percent-encoded, and it is what an Arabic-speaking shopper recognises in a
 * shared link. What <em>is</em> stripped is the part of Arabic orthography that makes two visually
 * identical strings compare differently - tashkeel (diacritics), tatweel (ـ), and the alef/yaa/taa
 * marbuta variants - so that "مُحَمَّد" and "محمد" cannot produce two competing slugs.
 */
public final class Slugs {

    private static final Pattern DIACRITICS = Pattern.compile("[\\u064B-\\u065F\\u0670\\u06D6-\\u06ED]");
    private static final Pattern TATWEEL = Pattern.compile("\\u0640");
    private static final Pattern LATIN_MARKS = Pattern.compile("\\p{InCombiningDiacriticalMarks}+");
    private static final Pattern SEPARATORS = Pattern.compile("[\\s_/\\\\|+.,]+");
    private static final Pattern DISALLOWED = Pattern.compile("[^\\p{IsArabic}a-z0-9-]");
    private static final Pattern DASHES = Pattern.compile("-{2,}");

    private static final int MAX_LENGTH = 140;

    private Slugs() {
    }

    /**
     * Builds a slug from arbitrary text. Returns an empty string when nothing usable survives, so
     * callers must decide on a fallback (typically the SKU or the entity id).
     */
    public static String slugify(String input) {
        if (input == null || input.isBlank()) {
            return "";
        }

        String value = input.trim().toLowerCase(Locale.ROOT);

        // Decompose so Latin accents split into base letter + combining mark, then drop the marks:
        // "Café" → "cafe" rather than "caf".
        value = Normalizer.normalize(value, Normalizer.Form.NFD);
        value = LATIN_MARKS.matcher(value).replaceAll("");

        value = DIACRITICS.matcher(value).replaceAll("");
        value = TATWEEL.matcher(value).replaceAll("");
        value = normalizeArabicLetters(value);

        value = SEPARATORS.matcher(value).replaceAll("-");
        value = DISALLOWED.matcher(value).replaceAll("");
        value = DASHES.matcher(value).replaceAll("-");

        value = trimDashes(value);

        if (value.length() > MAX_LENGTH) {
            value = value.substring(0, MAX_LENGTH);
            value = trimDashes(value);
        }
        return value;
    }

    /**
     * Slug plus a short disambiguating suffix, for the second "iPhone 15 Pro" in the catalog.
     *
     * @param taken predicate that answers "is this slug already used?"
     */
    public static String slugifyUnique(String input, String fallback, Predicate<String> taken) {
        String base = slugify(input);
        if (base.isEmpty()) {
            base = slugify(fallback);
        }
        if (base.isEmpty()) {
            base = "item";
        }
        if (!taken.test(base)) {
            return base;
        }
        for (int suffix = 2; suffix < 1000; suffix++) {
            String candidate = truncateForSuffix(base, suffix) + "-" + suffix;
            if (!taken.test(candidate)) {
                return candidate;
            }
        }
        // Practically unreachable; falls back to something guaranteed unique.
        return base + "-" + System.nanoTime();
    }

    /** Unifies the Arabic letter forms that users type interchangeably. */
    private static String normalizeArabicLetters(String value) {
        StringBuilder out = new StringBuilder(value.length());
        for (char c : value.toCharArray()) {
            out.append(switch (c) {
                case 'أ', 'إ', 'آ', 'ٱ' -> 'ا';
                case 'ى' -> 'ي';
                case 'ة' -> 'ه';
                case 'ؤ' -> 'و';
                case 'ئ' -> 'ي';
                default -> c;
            });
        }
        return out.toString();
    }

    private static String trimDashes(String value) {
        int start = 0;
        int end = value.length();
        while (start < end && value.charAt(start) == '-') {
            start++;
        }
        while (end > start && value.charAt(end - 1) == '-') {
            end--;
        }
        return value.substring(start, end);
    }

    private static String truncateForSuffix(String base, int suffix) {
        int room = MAX_LENGTH - (String.valueOf(suffix).length() + 1);
        return base.length() <= room ? base : trimDashes(base.substring(0, room));
    }

    /** Reserved path segments that must never be claimed by a category or product slug. */
    public static final Set<String> RESERVED = Set.of(
            "api", "admin", "auth", "login", "register", "cart", "checkout", "search",
            "account", "orders", "wishlist", "static", "_next", "media", "assets", "sitemap.xml", "robots.txt");

    public static boolean isReserved(String slug) {
        return RESERVED.contains(slug);
    }
}
