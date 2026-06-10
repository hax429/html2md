package html2md.html;

import java.util.Map;

/**
 * Lexical tokens produced by {@link HtmlTokenizer}. The tokenizer flattens a
 * document into this stream; {@link HtmlParser} rebuilds the tree from it.
 *
 * <p>Sealed so the parser's switch is exhaustive: adding a token type here
 * is a compile error at every consumer until it is handled. These mirror
 * the five token kinds of the WHATWG tokenizer (its sixth, EOF, is simply
 * the end of the list).
 */
public sealed interface Token {

    /**
     * @param name lowercased tag name
     * @param attrs lowercased names → decoded values, in source order; first
     *              occurrence wins for duplicates
     * @param selfClosing the literal {@code />} spelling — meaningful in
     *              HTML only on foreign (SVG/MathML) elements, but recorded
     *              so the parser can avoid pushing {@code <div/>} from
     *              hand-written markup
     */
    record StartTag(String name, Map<String, String> attrs, boolean selfClosing) implements Token {}

    record EndTag(String name) implements Token {}

    /** Character data with entities already decoded. */
    record Text(String text) implements Token {}

    /** Kept as a token (rather than dropped in the lexer) for debuggability; the parser discards it. */
    record Comment(String text) implements Token {}

    record Doctype(String text) implements Token {}
}
