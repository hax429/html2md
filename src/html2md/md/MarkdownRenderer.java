package html2md.md;

import html2md.html.Element;
import html2md.html.Node;
import html2md.html.TextNode;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Walks the element tree and emits Markdown. The traversal has two modes that
 * mirror HTML's content model [1]:
 *
 * <ul>
 *   <li><b>block</b> — {@link #blocks(Element)} turns a container's children
 *       into a list of Markdown blocks; runs of inline content between block
 *       elements become implicit paragraphs (the same trick CSS uses when it
 *       wraps loose inline content in anonymous block boxes [2]);</li>
 *   <li><b>inline</b> — {@link #inline(List)} renders phrasing content with
 *       whitespace collapsed and Markdown metacharacters escaped.</li>
 * </ul>
 *
 * Container constructs (blockquotes, list items) render their contents
 * recursively, then transform the resulting text (prefix every line with
 * {@code "> "}, indent continuation lines, …). That keeps arbitrary nesting
 * — lists inside quotes inside lists — correct without any shared mutable
 * state: each level only ever manipulates the finished string of the level
 * below it.
 *
 * <p>The output dialect is CommonMark [3] plus the GFM tables and
 * strikethrough extensions [4]. Where the spec allows several spellings
 * (ATX vs. setext headings, {@code -} vs. {@code *} bullets, fenced vs.
 * indented code) this renderer always picks one, so output is deterministic
 * and diffs are stable.
 *
 * <p>References:
 * <ol>
 *   <li>HTML flow vs. phrasing content:
 *       https://html.spec.whatwg.org/multipage/dom.html#kinds-of-content</li>
 *   <li>CSS 2.2 §9.2.1.1 anonymous block boxes</li>
 *   <li>CommonMark 0.31.2: https://spec.commonmark.org/0.31.2/</li>
 *   <li>GFM: https://github.github.com/gfm/ (§4.10 tables, §6.5 strikethrough)</li>
 * </ol>
 */
public final class MarkdownRenderer {

    /**
     * Elements whose entire subtree carries nothing worth rendering: code and
     * styling ({@code script}, {@code style}), metadata, form widgets, and
     * replaced/embedded content. Children of {@code video}/{@code object}/…
     * are spec-defined <em>fallback</em> content — shown only when the embed
     * fails — so dropping them matches what a working browser displays.
     */
    private static final Set<String> SKIP = Set.of(
            "script", "style", "noscript", "template", "head", "title", "meta",
            "link", "base", "svg", "canvas", "iframe", "object", "embed",
            "input", "button", "select", "textarea", "datalist", "dialog",
            "audio", "video", "source", "track", "map", "area", "slot");

    /**
     * Roughly HTML's flow-content-that-is-not-phrasing: elements that start a
     * new line and therefore must terminate any inline run in progress.
     * Includes legacy ({@code center}) and newer ({@code search}, {@code
     * hgroup}) tags so they degrade to transparent containers rather than
     * being mistaken for inline content.
     */
    private static final Set<String> BLOCK = Set.of(
            "address", "article", "aside", "blockquote", "details", "div", "dl",
            "dt", "dd", "fieldset", "figure", "figcaption", "footer", "form",
            "h1", "h2", "h3", "h4", "h5", "h6", "header", "hr", "li", "main",
            "nav", "ol", "p", "pre", "section", "summary", "table", "tbody",
            "td", "tfoot", "th", "thead", "tr", "ul", "caption", "center",
            "hgroup", "search");

    /**
     * Characters backslash-escaped in body text. CommonMark §2.4 allows
     * escaping any ASCII punctuation, but escaping it all makes the output
     * unreadable; this is the minimal set that can change document structure
     * when it appears in plain prose ({@code *}/{@code _} open emphasis,
     * {@code `} opens a code span, {@code [}/{@code ]} delimit links, and
     * {@code \} introduces escapes). Context-specific characters are handled
     * at their site instead: {@code |} only inside table cells, {@code #}/
     * {@code >} only matter at line starts where stripped indentation makes
     * collisions rare.
     */
    private static final String ESCAPABLE = "\\`*_[]";

    private final URI base;

    /** @param base URL that relative hrefs/srcs resolve against; null leaves them as-is. */
    public MarkdownRenderer(URI base) {
        this.base = base;
    }

    public String render(Element root) {
        // Fragments and pre-extracted regions have no <body>; render whatever
        // we were given. A trailing newline makes the output a valid POSIX
        // text file and concatenation-safe.
        Element body = root.findFirst("body");
        List<String> blocks = blocks(body != null ? body : root);
        return blocks.isEmpty() ? "" : String.join("\n\n", blocks) + "\n";
    }

    // ------------------------------------------------------------------
    // Block mode
    // ------------------------------------------------------------------

    private List<String> blocks(Element container) {
        List<String> out = new ArrayList<>();
        List<Node> inlineRun = new ArrayList<>();
        for (Node child : container.children()) {
            if (child instanceof Element el && SKIP.contains(el.tag())) {
                continue;
            }
            if (child instanceof Element el && BLOCK.contains(el.tag())) {
                flushInlineRun(inlineRun, out);
                renderBlock(el, out);
            } else {
                inlineRun.add(child);
            }
        }
        flushInlineRun(inlineRun, out);
        return out;
    }

    private void flushInlineRun(List<Node> run, List<String> out) {
        if (run.isEmpty()) {
            return;
        }
        String text = inline(run).strip();
        run.clear();
        if (!text.isEmpty()) {
            out.add(text);
        }
    }

    private void renderBlock(Element el, List<String> out) {
        switch (el.tag()) {
            case "h1", "h2", "h3", "h4", "h5", "h6" -> {
                // ATX headings (CommonMark §4.2) are single-line by
                // definition, so any <br> inside the heading flattens to a
                // space. The level rides on the tag name's digit.
                String text = inline(el.children()).strip().replace("\n", " ");
                if (!text.isEmpty()) {
                    out.add("#".repeat(el.tag().charAt(1) - '0') + " " + text);
                }
            }
            case "p", "caption", "summary", "address", "center" -> flushParagraph(el, out);
            case "figcaption" -> {
                String text = inline(el.children()).strip();
                if (!text.isEmpty()) {
                    out.add("*" + text + "*");
                }
            }
            case "dt" -> {
                String text = inline(el.children()).strip();
                if (!text.isEmpty()) {
                    out.add("**" + text + "**");
                }
            }
            case "blockquote" -> {
                // Render the quote's contents as a complete sub-document,
                // then mark every line (CommonMark §5.1). Nested quotes need
                // no special casing: the inner pass already produced "> ",
                // and this pass turns it into "> > ".
                String inner = String.join("\n\n", blocks(el));
                if (!inner.isEmpty()) {
                    out.add(prefixLines(inner, "> "));
                }
            }
            case "pre" -> out.add(codeBlock(el));
            case "ul" -> addIfNotEmpty(out, list(el, false));
            case "ol" -> addIfNotEmpty(out, list(el, true));
            case "hr" -> out.add("---"); // thematic break; safe because setext headings are never emitted
            case "table" -> addIfNotEmpty(out, table(el));
            default -> out.addAll(blocks(el)); // div, section, dd, figure, …: transparent containers
        }
    }

    private void flushParagraph(Element el, List<String> out) {
        // A <p> normally holds only inline content, but real pages put lists
        // and other blocks inside; fall back to container handling for those.
        boolean hasBlockChild = el.children().stream()
                .anyMatch(c -> c instanceof Element e && BLOCK.contains(e.tag()));
        if (hasBlockChild) {
            out.addAll(blocks(el));
            return;
        }
        String text = inline(el.children()).strip();
        if (!text.isEmpty()) {
            out.add(text);
        }
    }

    private static void addIfNotEmpty(List<String> out, String block) {
        if (!block.isEmpty()) {
            out.add(block);
        }
    }

    private String list(Element listEl, boolean ordered) {
        int counter = 1;
        if (ordered) {
            try {
                counter = Integer.parseInt(listEl.attr("start"));
            } catch (NumberFormatException ignored) {
                // no/bad start attribute: count from 1
            }
        }
        List<String> items = new ArrayList<>();
        for (Element li : listEl.childElements("li")) {
            String marker = ordered ? counter++ + ". " : "- ";
            String body = joinItemBlocks(blocks(li));
            if (body.isEmpty()) {
                continue;
            }
            // CommonMark §5.2: continuation lines belong to a list item iff
            // they are indented to the first character after the marker — so
            // the indent width must equal the marker width ("- " → 2 spaces,
            // "10. " → 4). Anything less re-opens the outer level.
            items.add(marker + indentContinuation(body, " ".repeat(marker.length())));
        }
        return String.join("\n", items);
    }

    /**
     * Joins a list item's blocks, keeping a nested list tight against the
     * text above it. CommonMark §5.3 makes a list "loose" — every item gets
     * wrapped in {@code <p>} on re-render — when any blank line separates its
     * parts, so a blank line before a sublist would inflate the whole list's
     * spacing. Genuine multi-paragraph items keep their blank line.
     */
    private static String joinItemBlocks(List<String> parts) {
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (!sb.isEmpty()) {
                sb.append(isListBlock(part) ? "\n" : "\n\n");
            }
            sb.append(part);
        }
        return sb.toString();
    }

    private static boolean isListBlock(String block) {
        return block.startsWith("- ") || block.matches("(?s)\\d+\\. .*");
    }

    /** Indents every line after the first, so multi-line items stay inside their list item. */
    private static String indentContinuation(String text, String indent) {
        return text.replace("\n", "\n" + indent).replaceAll("(?m)^" + indent + "$", "");
    }

    private static String prefixLines(String text, String prefix) {
        String prefixed = prefix + text.replace("\n", "\n" + prefix);
        return prefixed.replaceAll("(?m) +$", ""); // blank quoted lines: "> " -> ">"
    }

    private String codeBlock(Element pre) {
        String content = pre.text().replace("\r\n", "\n");
        // HTML itself ignores a newline immediately after the <pre> start tag
        // (the spec bakes this into serialization, §13.1.2.5), so authors
        // writing "<pre>\ncode" don't mean a leading blank line. Mirror that.
        if (content.startsWith("\n")) {
            content = content.substring(1);
        }
        content = content.stripTrailing();
        // Fenced code (CommonMark §4.5): the closing fence must be at least
        // as long as the opener, so the fence simply outgrows any backtick
        // run in the content. Fences beat indented code blocks here because
        // they survive re-indentation (e.g. when nested into a list item).
        String fence = "```";
        while (content.contains(fence)) {
            fence += "`";
        }
        return fence + language(pre) + "\n" + content + "\n" + fence;
    }

    /**
     * Picks the fence's info string from {@code class="language-x"} on the
     * inner {@code <code>} (or the {@code <pre>} itself). The
     * {@code language-} prefix is the convention the HTML spec itself
     * recommends for marking up code samples and is what highlight.js,
     * Prism, and Rouge all emit; {@code lang-} is Google prettify's older
     * variant, still common on legacy pages.
     */
    private static String language(Element pre) {
        Element code = pre.findFirst("code");
        for (Element candidate : code != null ? List.of(code, pre) : List.of(pre)) {
            for (String cls : candidate.attr("class").split("\\s+")) {
                if (cls.startsWith("language-")) {
                    return cls.substring("language-".length());
                }
                if (cls.startsWith("lang-")) {
                    return cls.substring("lang-".length());
                }
            }
        }
        return "";
    }

    /**
     * GFM pipe table (GFM §4.10). Constraints of that format drive every
     * choice here: cells are single-line (so {@code <br>} flattens to a
     * space), a header row is mandatory (one is synthesized when the HTML
     * has none), and {@code |} is the only character that needs escaping
     * beyond the normal inline set. {@code colspan}/{@code rowspan} have no
     * pipe-table equivalent and are flattened.
     */
    private String table(Element tableEl) {
        List<List<String>> rows = new ArrayList<>();
        boolean firstRowIsHeader = false;
        // findAll instead of direct children: rows usually sit inside
        // thead/tbody/tfoot wrappers, often parser-inserted ones.
        for (Element tr : tableEl.findAll("tr")) {
            List<String> cells = new ArrayList<>();
            boolean sawTh = false;
            for (Node child : tr.children()) {
                if (child instanceof Element cell
                        && (cell.tag().equals("td") || cell.tag().equals("th"))) {
                    sawTh |= cell.tag().equals("th");
                    cells.add(inline(cell.children()).strip()
                            .replace("\n", " ").replace("|", "\\|"));
                }
            }
            if (!cells.isEmpty()) {
                if (rows.isEmpty()) {
                    firstRowIsHeader = sawTh;
                }
                rows.add(cells);
            }
        }
        if (rows.isEmpty()) {
            return "";
        }
        int cols = rows.stream().mapToInt(List::size).max().orElse(0);
        StringBuilder sb = new StringBuilder();
        List<String> header = firstRowIsHeader ? rows.remove(0) : List.of();
        appendRow(sb, header, cols);
        appendRow(sb, List.of(), cols, "---");
        for (List<String> row : rows) {
            appendRow(sb, row, cols);
        }
        return sb.toString().stripTrailing();
    }

    private static void appendRow(StringBuilder sb, List<String> cells, int cols) {
        appendRow(sb, cells, cols, "");
    }

    private static void appendRow(StringBuilder sb, List<String> cells, int cols, String filler) {
        sb.append('|');
        for (int i = 0; i < cols; i++) {
            String cell = i < cells.size() && !cells.get(i).isEmpty() ? cells.get(i) : filler;
            sb.append(' ').append(cell.isEmpty() ? " " : cell).append(" |");
        }
        sb.append('\n');
    }

    // ------------------------------------------------------------------
    // Inline mode
    // ------------------------------------------------------------------

    private String inline(List<Node> nodes) {
        StringBuilder sb = new StringBuilder();
        for (Node node : nodes) {
            inlineNode(node, sb);
        }
        return sb.toString();
    }

    private void inlineNode(Node node, StringBuilder sb) {
        if (node instanceof TextNode text) {
            appendCollapsed(sb, text.text());
            return;
        }
        Element el = (Element) node;
        switch (el.tag()) {
            case String t when SKIP.contains(t) -> { }
            case "br" -> hardBreak(sb);
            case "strong", "b" -> wrap(sb, el, "**");
            case "em", "i", "cite", "dfn", "var" -> wrap(sb, el, "*");
            case "del", "s", "strike" -> wrap(sb, el, "~~");
            case "code", "kbd", "samp", "tt" -> codeSpan(sb, el);
            case "a" -> link(sb, el);
            case "img" -> image(sb, el);
            case "q" -> wrapLiteral(sb, el, "“", "”");
            case "wbr" -> { }
            case String t when BLOCK.contains(t) -> {
                // Block element in inline position (e.g. <div> inside <a>):
                // keep its text, separated by spaces.
                appendCollapsed(sb, " ");
                for (Node child : el.children()) {
                    inlineNode(child, sb);
                }
                appendCollapsed(sb, " ");
            }
            default -> { // span, abbr, small, mark, sub, sup, ins, u, time, …
                for (Node child : el.children()) {
                    inlineNode(child, sb);
                }
            }
        }
    }

    /**
     * Appends text with HTML whitespace collapsing and Markdown escaping.
     * This emulates CSS {@code white-space: normal} processing: any run of
     * document whitespace (the five characters HTML counts as such) renders
     * as a single space, and leading whitespace at the start of a line box —
     * here, an empty builder — vanishes entirely. Source formatting like
     * indentation and line wrapping disappears; only author-intended spaces
     * survive.
     */
    private static void appendCollapsed(StringBuilder sb, String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '­') {
                continue; // soft hyphen: an invisible hint in HTML, line noise in Markdown
            }
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\f') {
                if (!sb.isEmpty() && !endsWithSpace(sb)) {
                    sb.append(' ');
                }
            } else {
                if (ESCAPABLE.indexOf(c) >= 0) {
                    sb.append('\\');
                }
                sb.append(c);
            }
        }
    }

    private static boolean endsWithSpace(StringBuilder sb) {
        char last = sb.charAt(sb.length() - 1);
        return last == ' ' || last == '\n';
    }

    /**
     * {@code <br>} becomes a bare newline. Strictly that is a CommonMark
     * <em>soft</em> break (§6.8) — the hard-break spellings are
     * trailing-double-space, which editors silently strip, or a trailing
     * backslash, which reads as noise — but most renderers (GFM comments,
     * many static site generators) honor single newlines as breaks, and the
     * worst case is degrading to the space the text would have had anyway.
     * A leading {@code <br>} is dropped: a paragraph can't start with a
     * line break.
     */
    private void hardBreak(StringBuilder sb) {
        while (!sb.isEmpty() && sb.charAt(sb.length() - 1) == ' ') {
            sb.setLength(sb.length() - 1); // trailing space + \n would accidentally spell a hard break
        }
        if (!sb.isEmpty()) {
            sb.append('\n');
        }
    }

    /**
     * Wraps an element's inline rendering in emphasis-style delimiters.
     *
     * <p>CommonMark's flanking rules (§6.2) say an opening {@code **} must
     * not be followed by whitespace and a closing one must not be preceded
     * by it — {@code ** x **} is not emphasis, it's literal asterisks. HTML
     * has no such rule ({@code <b> x </b>} is fine), so boundary whitespace
     * inside the element is moved <em>outside</em> the markers:
     * {@code a<b> x </b>b} renders as {@code "a **x** b"}.
     */
    private void wrap(StringBuilder sb, Element el, String delim) {
        // Seed with a non-space sentinel so a leading space inside the
        // element survives collapsing and can be moved outside the markers.
        StringBuilder tmp = new StringBuilder("\u0000");
        for (Node child : el.children()) {
            inlineNode(child, tmp);
        }
        String inner = tmp.substring(1);
        String core = inner.strip();
        if (core.isEmpty()) {
            appendCollapsed(sb, inner); // <b> </b>: keep the space, drop the markers
            return;
        }
        if (inner.startsWith(" ")) {
            appendCollapsed(sb, " ");
        }
        sb.append(delim).append(core).append(delim);
        if (inner.endsWith(" ")) {
            sb.append(' ');
        }
    }

    private void wrapLiteral(StringBuilder sb, Element el, String open, String close) {
        String core = inline(el.children()).strip();
        if (!core.isEmpty()) {
            sb.append(open).append(core).append(close);
        }
    }

    /**
     * Code span (CommonMark §6.1). Two consequences of the spec's "backtick
     * string" rule: the delimiter must be one backtick longer than the
     * longest run inside the content, and when the content contains
     * backticks at all it gets space padding — the spec strips exactly one
     * leading and trailing space, so {@code `` `x` ``} round-trips while
     * {@code ```x```} would be ambiguous to readers. Markdown escaping does
     * not apply inside code spans; the raw text is used as-is.
     */
    private void codeSpan(StringBuilder sb, Element el) {
        String text = el.text().replaceAll("\\s+", " ").strip();
        if (text.isEmpty()) {
            return;
        }
        String delim = "`".repeat(longestBacktickRun(text) + 1);
        String pad = text.contains("`") ? " " : "";
        sb.append(delim).append(pad).append(text).append(pad).append(delim);
    }

    private static int longestBacktickRun(String text) {
        int max = 0;
        int run = 0;
        for (int i = 0; i < text.length(); i++) {
            run = text.charAt(i) == '`' ? run + 1 : 0;
            max = Math.max(max, run);
        }
        return max;
    }

    private void link(StringBuilder sb, Element el) {
        String inner = inline(el.children()).strip();
        String href = el.attr("href").strip();
        // Targets that are meaningless outside the live page — script
        // handlers and same-page fragments (the heading they point at gets
        // a different anchor in the converted document, if any) — unlink
        // to plain text rather than emitting a dead link.
        if (href.isEmpty() || href.startsWith("javascript:") || href.startsWith("#")) {
            sb.append(inner);
            return;
        }
        String url = resolve(href);
        if (inner.isEmpty()) {
            inner = url; // image-only or icon links: show the destination
        }
        sb.append('[').append(inner).append("](").append(wrapUrl(url)).append(')');
    }

    private void image(StringBuilder sb, Element el) {
        String src = el.attr("src").strip();
        if (src.isEmpty()) {
            // Lazy-loading libraries park the real URL in data-src and put a
            // 1px placeholder (or nothing) in src until JS swaps them.
            src = el.attr("data-src").strip();
        }
        if (src.isEmpty() || src.startsWith("data:")) {
            return; // a base64 data: URI would dump kilobytes of noise into the text
        }
        String alt = el.attr("alt").replaceAll("\\s+", " ").strip()
                .replace("[", "\\[").replace("]", "\\]");
        sb.append("![").append(alt).append("](").append(wrapUrl(resolve(src))).append(')');
    }

    /**
     * Resolves a relative reference against the page URL per RFC 3986 §5
     * (which {@link URI#resolve} implements), so {@code ../style.css} and
     * {@code //cdn.example.com/x} both come out absolute. Markdown has no
     * notion of a base URL, so relative links would silently break the
     * moment the output file is moved.
     */
    private String resolve(String url) {
        if (base == null) {
            return url;
        }
        try {
            return base.resolve(url).toString();
        } catch (IllegalArgumentException e) {
            return url; // not RFC 3986 syntax (raw spaces, braces, …): pass through untouched
        }
    }

    /**
     * CommonMark §6.3: a link destination containing spaces must be wrapped
     * in angle brackets, and an unbalanced {@code )} would end the link
     * early. Bracket only when needed — {@code <…>} is the uglier spelling.
     */
    private static String wrapUrl(String url) {
        return url.contains(" ") || url.contains("(") || url.contains(")")
                ? "<" + url + ">"
                : url;
    }
}
