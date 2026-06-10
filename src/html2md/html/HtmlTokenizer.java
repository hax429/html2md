package html2md.html;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A single-pass HTML tokenizer: a hand-rolled approximation of the WHATWG
 * tokenization algorithm [1], covering the states that matter for content
 * extraction and skipping the ones that only matter for executing pages
 * (no character-reference-in-attribute edge cases, no CDATA, no parse
 * errors — the spec's recovery <em>behavior</em> is kept, its error
 * <em>reporting</em> is not).
 *
 * <p>It is deliberately forgiving: malformed markup never throws, it just
 * degrades into text or gets skipped, mirroring how browsers treat
 * real-world HTML. Concretely:
 *
 * <ul>
 *   <li>a {@code <} that opens nothing is character data (spec: "data state",
 *       anything-else branch);</li>
 *   <li>{@code <!doctype …>}, {@code <?php …>} and other {@code <!}/{@code <?}
 *       constructs ride the spec's "bogus comment state" to the next
 *       {@code >};</li>
 *   <li>unterminated comments and tags run to end of input instead of
 *       failing.</li>
 * </ul>
 *
 * <p>Entities are decoded here (in text and attribute values), so everything
 * downstream works with plain strings and never sees {@code &amp;}.
 *
 * <p>[1] https://html.spec.whatwg.org/multipage/parsing.html#tokenization
 */
public final class HtmlTokenizer {

    private final String src;
    private int pos;

    public HtmlTokenizer(String src) {
        this.src = src;
    }

    public List<Token> tokenize() {
        List<Token> out = new ArrayList<>();
        while (pos < src.length()) {
            if (src.charAt(pos) != '<') {
                out.add(readText());
            } else if (lookingAt("<!--")) {
                out.add(readComment());
            } else if (lookingAt("</")) {
                Token tag = readEndTag();
                if (tag != null) {
                    out.add(tag);
                }
            } else if (lookingAt("<!") || lookingAt("<?")) {
                skipBogusMarkup();
            } else if (pos + 1 < src.length() && Character.isLetter(src.charAt(pos + 1))) {
                Token.StartTag tag = readStartTag();
                out.add(tag);
                if (!tag.selfClosing() && isRawText(tag.name())) {
                    readRawText(tag.name(), out);
                }
            } else {
                // "<" followed by anything that can't start markup (a digit,
                // space, another "<", EOF): character data. This is why
                // "<p>1 < 2</p>" parses instead of erroring.
                out.add(new Token.Text("<"));
                pos++;
            }
        }
        return out;
    }

    /**
     * Elements whose content the spec lexes in a special mode where markup
     * is inert and only the matching end tag terminates: {@code script}/
     * {@code style} are <em>raw text</em> elements (entities NOT decoded —
     * {@code &amp;&amp;} in JavaScript must stay literal), {@code title}/
     * {@code textarea} are <em>RCDATA</em> (entities decoded, tags inert).
     * Without this mode, {@code document.write('<p>hi</p>')} inside a script
     * would leak a paragraph into the document.
     */
    private static boolean isRawText(String tag) {
        return tag.equals("script") || tag.equals("style") || tag.equals("title") || tag.equals("textarea");
    }

    private boolean lookingAt(String prefix) {
        return src.startsWith(prefix, pos);
    }

    private Token.Text readText() {
        int start = pos;
        int lt = src.indexOf('<', pos);
        pos = lt < 0 ? src.length() : lt;
        return new Token.Text(Entities.decode(src.substring(start, pos)));
    }

    private Token.Comment readComment() {
        int start = pos + 4;
        int end = src.indexOf("-->", start);
        if (end < 0) {
            pos = src.length();
            return new Token.Comment(src.substring(start));
        }
        pos = end + 3;
        return new Token.Comment(src.substring(start, end));
    }

    /** Doctype declarations and processing instructions carry no content we keep. */
    private void skipBogusMarkup() {
        int end = src.indexOf('>', pos);
        pos = end < 0 ? src.length() : end + 1;
    }

    private Token.EndTag readEndTag() {
        pos += 2;
        String name = readTagName();
        int end = src.indexOf('>', pos);
        pos = end < 0 ? src.length() : end + 1;
        return name.isEmpty() ? null : new Token.EndTag(name);
    }

    private Token.StartTag readStartTag() {
        pos++;
        String name = readTagName();
        Map<String, String> attrs = new LinkedHashMap<>();
        boolean selfClosing = false;
        while (pos < src.length()) {
            skipWhitespace();
            if (pos >= src.length()) {
                break;
            }
            char c = src.charAt(pos);
            if (c == '>') {
                pos++;
                break;
            }
            if (c == '/') {
                pos++;
                if (pos < src.length() && src.charAt(pos) == '>') {
                    pos++;
                    selfClosing = true;
                    break;
                }
                continue;
            }
            readAttribute(attrs);
        }
        return new Token.StartTag(name, attrs, selfClosing);
    }

    private void readAttribute(Map<String, String> attrs) {
        int start = pos;
        while (pos < src.length() && !isWhitespace(src.charAt(pos))
                && src.charAt(pos) != '=' && src.charAt(pos) != '>' && src.charAt(pos) != '/') {
            pos++;
        }
        if (pos == start) {
            pos++; // stray character ('<', a lone quote, …): consume one so the loop can never stall
            return;
        }
        String name = src.substring(start, pos).toLowerCase(Locale.ROOT);
        skipWhitespace();
        String value = "";
        if (pos < src.length() && src.charAt(pos) == '=') {
            pos++;
            skipWhitespace();
            value = readAttributeValue();
        }
        // Duplicate attribute: the spec keeps the FIRST occurrence and drops
        // the rest ("duplicate-attribute" parse error), hence putIfAbsent.
        attrs.putIfAbsent(name, value);
    }

    private String readAttributeValue() {
        if (pos >= src.length()) {
            return "";
        }
        char quote = src.charAt(pos);
        int start;
        int end;
        if (quote == '"' || quote == '\'') {
            start = ++pos;
            end = src.indexOf(quote, pos);
            if (end < 0) {
                end = src.length();
            }
            pos = Math.min(end + 1, src.length());
        } else {
            start = pos;
            while (pos < src.length() && !isWhitespace(src.charAt(pos)) && src.charAt(pos) != '>') {
                pos++;
            }
            end = pos;
        }
        return Entities.decode(src.substring(start, end));
    }

    /** Consumes everything up to {@code </name>} as a single text token. */
    private void readRawText(String name, List<Token> out) {
        String closer = "</" + name;
        int search = pos;
        int end;
        while (true) {
            end = indexOfIgnoreCase(closer, search);
            if (end < 0) {
                end = src.length(); // unterminated <script>: rest of document is script, per spec EOF handling
                break;
            }
            // The spec's "appropriate end tag" check: "</script" only counts
            // when followed by whitespace, '>', or '/'. This is what makes
            // the string "</scripty" inside actual JavaScript harmless.
            int after = end + closer.length();
            char c = after < src.length() ? src.charAt(after) : '>';
            if (isWhitespace(c) || c == '>' || c == '/') {
                break;
            }
            search = end + 1;
        }
        String raw = src.substring(pos, end);
        if (name.equals("title") || name.equals("textarea")) {
            raw = Entities.decode(raw);
        }
        if (!raw.isEmpty()) {
            out.add(new Token.Text(raw));
        }
        pos = end;
        if (pos < src.length()) {
            int gt = src.indexOf('>', pos);
            pos = gt < 0 ? src.length() : gt + 1;
            out.add(new Token.EndTag(name));
        }
    }

    private int indexOfIgnoreCase(String needle, int from) {
        int max = src.length() - needle.length();
        for (int i = from; i <= max; i++) {
            if (src.regionMatches(true, i, needle, 0, needle.length())) {
                return i;
            }
        }
        return -1;
    }

    private String readTagName() {
        int start = pos;
        while (pos < src.length() && (Character.isLetterOrDigit(src.charAt(pos)) || src.charAt(pos) == '-')) {
            pos++;
        }
        return src.substring(start, pos).toLowerCase(Locale.ROOT);
    }

    private void skipWhitespace() {
        while (pos < src.length() && isWhitespace(src.charAt(pos))) {
            pos++;
        }
    }

    private static boolean isWhitespace(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\f';
    }
}
