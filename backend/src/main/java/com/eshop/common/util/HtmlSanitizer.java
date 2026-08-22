package com.eshop.common.util;

import org.jsoup.Jsoup;
import org.jsoup.safety.Safelist;

/**
 * Strips dangerous markup from text that will be rendered as HTML.
 *
 * <p>Sanitising on the way <em>in</em> rather than on the way out means the database holds only safe
 * content, so a template that forgets to escape, an export, an email, or a mobile client rendering
 * the same field cannot each reintroduce the hole independently.
 *
 * <p>Applied to product descriptions (rich text, admin-authored) and review bodies (plain text,
 * customer-authored). The two use different policies: an administrator has a legitimate reason to
 * write a formatted description; a reviewer does not need any markup at all.
 */
public final class HtmlSanitizer {

    /**
     * What a product description may contain: basic formatting, lists, tables, links, and images.
     * No {@code <script>}, no {@code <style>}, no {@code <iframe>}, no event handlers — jsoup's
     * allow-list model drops anything not named here rather than trying to blacklist attacks.
     */
    private static final Safelist RICH_TEXT = Safelist.relaxed()
            .addAttributes("a", "target", "rel")
            .addAttributes("img", "loading", "width", "height")
            .addProtocols("img", "src", "https", "data")
            .addProtocols("a", "href", "https", "mailto");

    private HtmlSanitizer() {
    }

    /** Sanitises admin-authored rich text, preserving safe formatting. */
    public static String richText(String html) {
        if (html == null || html.isBlank()) {
            return html;
        }
        String cleaned = Jsoup.clean(html, RICH_TEXT);
        // Every surviving external link opens in a new tab without handing the target window a
        // reference back to ours (reverse tabnabbing).
        return cleaned.replace("<a href=", "<a rel=\"noopener noreferrer nofollow\" target=\"_blank\" href=");
    }

    /** Reduces customer-authored input to plain text, dropping all markup. */
    public static String plainText(String input) {
        if (input == null || input.isBlank()) {
            return input;
        }
        return Jsoup.clean(input, Safelist.none()).trim();
    }

    /** Plain text, truncated on a word boundary — for meta descriptions and previews. */
    public static String excerpt(String html, int maxLength) {
        String text = plainText(html);
        if (text == null || text.length() <= maxLength) {
            return text;
        }
        String truncated = text.substring(0, maxLength);
        int lastSpace = truncated.lastIndexOf(' ');
        return (lastSpace > maxLength / 2 ? truncated.substring(0, lastSpace) : truncated) + "…";
    }
}
