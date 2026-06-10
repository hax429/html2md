package html2md.html;

import java.util.HashMap;
import java.util.Map;

/**
 * Decodes HTML character references: named ({@code &amp;}) and numeric
 * ({@code &#x27;}, {@code &#39;}).
 *
 * <p>The full HTML5 table [1] defines 2,231 names; carrying it all would be
 * a generated file longer than the rest of this program. The map below is
 * the working set actually seen in prose — XML's five, typographic
 * punctuation, currency and math signs. Anything else either arrives as a
 * numeric reference (always decoded) or as a literal UTF-8 character, which
 * needs no decoding at all. An unknown name passes through unchanged
 * ({@code &nosuch;} stays {@code &nosuch;}) — that is also what the spec
 * does with names it can't match.
 *
 * <p>Two mappings are easy to get wrong and deliberate here:
 * {@code &nbsp;} decodes to a real U+00A0 (not a plain space — it must still
 * prevent line wrapping downstream), and {@code &shy;} to U+00AD, left for
 * the renderer to strip.
 *
 * <p>[1] https://html.spec.whatwg.org/multipage/named-characters.html
 */
public final class Entities {

    private static final Map<String, String> NAMED = new HashMap<>();

    static {
        NAMED.put("amp", "&");
        NAMED.put("lt", "<");
        NAMED.put("gt", ">");
        NAMED.put("quot", "\"");
        NAMED.put("apos", "'");
        NAMED.put("nbsp", " ");
        NAMED.put("shy", "­");
        NAMED.put("copy", "©");
        NAMED.put("reg", "®");
        NAMED.put("trade", "™");
        NAMED.put("hellip", "…");
        NAMED.put("mdash", "—");
        NAMED.put("ndash", "–");
        NAMED.put("lsquo", "‘");
        NAMED.put("rsquo", "’");
        NAMED.put("ldquo", "“");
        NAMED.put("rdquo", "”");
        NAMED.put("laquo", "«");
        NAMED.put("raquo", "»");
        NAMED.put("times", "×");
        NAMED.put("divide", "÷");
        NAMED.put("deg", "°");
        NAMED.put("middot", "·");
        NAMED.put("bull", "•");
        NAMED.put("sect", "§");
        NAMED.put("para", "¶");
        NAMED.put("euro", "€");
        NAMED.put("pound", "£");
        NAMED.put("yen", "¥");
        NAMED.put("cent", "¢");
        NAMED.put("plusmn", "±");
        NAMED.put("frac12", "½");
        NAMED.put("frac14", "¼");
        NAMED.put("frac34", "¾");
        NAMED.put("sup1", "¹");
        NAMED.put("sup2", "²");
        NAMED.put("sup3", "³");
        NAMED.put("dagger", "†");
        NAMED.put("Dagger", "‡");
        NAMED.put("permil", "‰");
        NAMED.put("larr", "←");
        NAMED.put("uarr", "↑");
        NAMED.put("rarr", "→");
        NAMED.put("darr", "↓");
        NAMED.put("harr", "↔");
    }

    private Entities() {}

    public static String decode(String s) {
        int amp = s.indexOf('&');
        if (amp < 0) {
            return s;
        }
        StringBuilder out = new StringBuilder(s.length());
        int pos = 0;
        while (amp >= 0) {
            out.append(s, pos, amp);
            int end = findEnd(s, amp);
            String decoded = end > 0 ? decodeReference(s.substring(amp + 1, end)) : null;
            if (decoded != null) {
                out.append(decoded);
                pos = end + 1;
            } else {
                out.append('&');
                pos = amp + 1;
            }
            amp = s.indexOf('&', pos);
        }
        out.append(s, pos, s.length());
        return out.toString();
    }

    /**
     * Index of the terminating {@code ;}, or -1 if this {@code &} starts no
     * reference. The 32-character window is not arbitrary: the longest name
     * in the HTML5 table is {@code &CounterClockwiseContourIntegral;} at 31
     * characters after the ampersand. Beyond that, no semicolon can ever
     * complete a reference, so scanning further would only slow down text
     * that happens to contain a bare {@code &}.
     */
    private static int findEnd(String s, int amp) {
        int limit = Math.min(s.length(), amp + 32);
        for (int i = amp + 1; i < limit; i++) {
            char c = s.charAt(i);
            if (c == ';') {
                return i > amp + 1 ? i : -1;
            }
            if (!Character.isLetterOrDigit(c) && c != '#') {
                return -1;
            }
        }
        return -1;
    }

    private static String decodeReference(String body) {
        if (body.startsWith("#")) {
            try {
                int cp = (body.length() > 1 && (body.charAt(1) == 'x' || body.charAt(1) == 'X'))
                        ? Integer.parseInt(body.substring(2), 16)
                        : Integer.parseInt(body.substring(1));
                // &#0; is a parse error the spec maps to U+FFFD; surrogates
                // and out-of-range values are likewise unrepresentable.
                // Leaving the reference as literal text is the honest
                // fallback for a text converter.
                if (Character.isValidCodePoint(cp) && cp != 0) {
                    return new String(Character.toChars(cp));
                }
            } catch (NumberFormatException ignored) {
                // "&#xZZ;" or an overflowing number: fall through, keep the literal text
            }
            return null;
        }
        return NAMED.get(body);
    }
}
