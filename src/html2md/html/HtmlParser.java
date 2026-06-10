package html2md.html;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.Set;

/**
 * Builds an element tree from the token stream using a stack of open
 * elements — the same shape as the WHATWG tree-construction algorithm [1],
 * minus its insertion modes, foster parenting, and the adoption agency
 * algorithm, none of which change where <em>text</em> lands often enough to
 * matter for a converter.
 *
 * <p>The one piece of the real algorithm this must get right is optional
 * end tags [2]: valid HTML may omit {@code </p>}, {@code </li>},
 * {@code </td>} and friends, and real pages omit them constantly. Without
 * the implicit-close table below, {@code <ul><li>a<li>b</ul>} would parse as
 * {@code b} nested <em>inside</em> {@code a} instead of as two siblings —
 * and every Wikipedia list would come out one deeper than it is.
 *
 * <p>References:
 * <ol>
 *   <li>https://html.spec.whatwg.org/multipage/parsing.html#tree-construction</li>
 *   <li>https://html.spec.whatwg.org/multipage/syntax.html#optional-tags</li>
 * </ol>
 */
public final class HtmlParser {

    /**
     * Void elements (HTML §13.1.2) have no content and no end tag, ever —
     * they must never be pushed onto the stack or every {@code <br>} would
     * swallow the rest of its parent. {@code <img>} appearing as
     * {@code <img/>} is handled separately via the self-closing flag.
     */
    private static final Set<String> VOID = Set.of(
            "area", "base", "br", "col", "embed", "hr", "img", "input",
            "link", "meta", "param", "source", "track", "wbr");

    /**
     * Start tags that end an open paragraph — the spec's list of elements
     * whose start tag triggers "close a p element" in the in-body insertion
     * mode. Effectively: anything that cannot live inside a paragraph.
     */
    private static final Set<String> CLOSES_P = Set.of(
            "address", "article", "aside", "blockquote", "details", "div", "dl",
            "fieldset", "figure", "figcaption", "footer", "form",
            "h1", "h2", "h3", "h4", "h5", "h6", "header", "hr", "main", "nav",
            "ol", "p", "pre", "section", "table", "ul");

    /**
     * key = an element that may be open; value = incoming start tags that
     * implicitly close it. Each entry mirrors one optional-end-tag rule from
     * HTML §13.1.2.4: a new {@code <li>} ends the previous one, table cells
     * end each other, {@code <dd>}/{@code <dt>} alternate freely, and a
     * {@code <body>} start tag closes a still-open {@code <head>} on pages
     * that omit {@code </head>}.
     */
    private static final Map<String, Set<String>> IMPLICITLY_CLOSED_BY = Map.ofEntries(
            Map.entry("p", CLOSES_P),
            Map.entry("li", Set.of("li")),
            Map.entry("dt", Set.of("dt", "dd")),
            Map.entry("dd", Set.of("dt", "dd")),
            Map.entry("td", Set.of("td", "th", "tr")),
            Map.entry("th", Set.of("td", "th", "tr")),
            Map.entry("tr", Set.of("tr")),
            Map.entry("thead", Set.of("tbody", "tfoot")),
            Map.entry("tbody", Set.of("tbody", "tfoot")),
            Map.entry("option", Set.of("option", "optgroup")),
            Map.entry("head", Set.of("body")));

    private HtmlParser() {}

    public static Element parse(String html) {
        Element root = new Element("#root", Map.of());
        Deque<Element> stack = new ArrayDeque<>();
        stack.push(root);

        for (Token token : new HtmlTokenizer(html).tokenize()) {
            switch (token) {
                case Token.StartTag tag -> {
                    closeImplicitly(stack, root, tag.name());
                    Element el = new Element(tag.name(), tag.attrs());
                    stack.peek().add(el);
                    if (!VOID.contains(tag.name()) && !tag.selfClosing()) {
                        stack.push(el);
                    }
                }
                case Token.EndTag tag -> popThrough(stack, root, tag.name());
                case Token.Text text -> stack.peek().add(new TextNode(text.text()));
                case Token.Comment ignored -> { }
                case Token.Doctype ignored -> { }
            }
        }
        return root;
    }

    private static void closeImplicitly(Deque<Element> stack, Element root, String incoming) {
        while (stack.peek() != root
                && IMPLICITLY_CLOSED_BY.getOrDefault(stack.peek().tag(), Set.of()).contains(incoming)) {
            stack.pop();
        }
    }

    /**
     * Pops to (and including) the nearest open element with this tag.
     *
     * <p>Two recovery behaviors live here. A stray end tag with no matching
     * open element ({@code "<p>hello</b></p>"}) is ignored entirely — popping
     * anything for it would silently re-parent the rest of the document.
     * And when the match is found deeper in the stack, everything above it
     * closes too ({@code "<b><i>x</b>"} closes the {@code <i>}): that is the
     * cheap approximation of the spec's adoption agency algorithm — the
     * formatting elements lose their tail, but text never moves or
     * disappears.
     */
    private static void popThrough(Deque<Element> stack, Element root, String tag) {
        boolean open = stack.stream().anyMatch(el -> el != root && el.tag().equals(tag));
        if (!open) {
            return;
        }
        while (stack.peek() != root) {
            if (stack.pop().tag().equals(tag)) {
                return;
            }
        }
    }
}
