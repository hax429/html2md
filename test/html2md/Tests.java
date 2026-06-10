package html2md;

import html2md.html.Element;
import html2md.html.Entities;
import html2md.html.HtmlParser;
import html2md.md.ContentExtractor;
import html2md.md.MarkdownRenderer;

import java.net.URI;

/**
 * Self-contained test runner — no framework, run with {@code make test}.
 * Each case converts an HTML snippet and compares the exact Markdown output.
 */
public final class Tests {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) {
        tokenizerAndParser();
        blocks();
        inline();
        listsAndTables();
        documents();

        System.out.printf("%n%d passed, %d failed%n", passed, failed);
        System.exit(failed == 0 ? 0 : 1);
    }

    // ------------------------------------------------------------------

    static void tokenizerAndParser() {
        eq("entities: named and numeric",
                "AT&T 'x' © ½", Entities.decode("AT&amp;T &#x27;x&#39; &copy; &frac12;"));
        eq("entities: unknown stays literal",
                "&nosuch; &", Entities.decode("&nosuch; &"));

        md("unclosed p auto-closes",
                "<p>one<p>two",
                "one\n\ntwo\n");
        md("stray end tag ignored, case-insensitive tags",
                "<P>hello</B></P>",
                "hello\n");
        md("comments and doctype dropped",
                "<!DOCTYPE html><!-- hi --><p>text</p>",
                "text\n");
        md("script and style bodies never leak",
                "<p>a</p><script>if (1 < 2) document.write('<p>x</p>');</script><style>p { color: red }</style><p>b</p>",
                "a\n\nb\n");
        md("attributes: unquoted, valueless, single-quoted",
                "<p id=top hidden class='x y'>ok</p>",
                "ok\n");
        md("lone < treated as text",
                "<p>1 < 2</p>",
                "1 < 2\n");
    }

    static void blocks() {
        md("headings h1-h6",
                "<h1>One</h1><h3>Three</h3><h6>Six</h6>",
                "# One\n\n### Three\n\n###### Six\n");
        md("paragraph whitespace collapses",
                "<p>spread\n  over\t lines</p>",
                "spread over lines\n");
        md("blockquote with two paragraphs",
                "<blockquote><p>first</p><p>second</p></blockquote>",
                "> first\n>\n> second\n");
        md("nested blockquote",
                "<blockquote><p>outer</p><blockquote><p>inner</p></blockquote></blockquote>",
                "> outer\n>\n> > inner\n");
        md("pre keeps whitespace and gets fenced with language",
                "<pre><code class=\"language-java\">int x = 1;\n  int y = 2;</code></pre>",
                "```java\nint x = 1;\n  int y = 2;\n```\n");
        md("fence grows past backticks in content",
                "<pre>a ``` b</pre>",
                "````\na ``` b\n````\n");
        md("hr",
                "<p>a</p><hr><p>b</p>",
                "a\n\n---\n\nb\n");
        md("bare text between blocks becomes a paragraph",
                "<h2>T</h2>loose text<p>p</p>",
                "## T\n\nloose text\n\np\n");
        md("div is a transparent container",
                "<div><div><p>deep</p></div></div>",
                "deep\n");
        md("figure with figcaption",
                "<figure><img src=\"x.png\" alt=\"pic\"><figcaption>A pic</figcaption></figure>",
                "![pic](x.png)\n\n*A pic*\n");
        md("definition list",
                "<dl><dt>Term</dt><dd>Meaning</dd></dl>",
                "**Term**\n\nMeaning\n");
    }

    static void inline() {
        md("bold italic strikethrough code",
                "<p><b>b</b> <em>i</em> <s>s</s> <code>c</code></p>",
                "**b** *i* ~~s~~ `c`\n");
        md("emphasis boundary spaces move outside markers",
                "<p>a<strong> bold </strong>b</p>",
                "a **bold** b\n");
        md("nested emphasis",
                "<p><strong>really <em>very</em> bold</strong></p>",
                "**really *very* bold**\n");
        md("code span containing backticks",
                "<p><code>a ` b</code></p>",
                "`` a ` b ``\n");
        md("markdown metacharacters escaped in text",
                "<p>2 * 3 [x] _y_</p>",
                "2 \\* 3 \\[x\\] \\_y\\_\n");
        md("br is a line break within the paragraph",
                "<p>one<br>two</p>",
                "one\ntwo\n");
        md("link with text",
                "<p><a href=\"https://example.com/a\">text</a></p>",
                "[text](https://example.com/a)\n");
        md("anchor-only and empty links keep just the text",
                "<p><a href=\"#frag\">jump</a> and <a>plain</a></p>",
                "jump and plain\n");
        md("image alt and src",
                "<p><img src=\"/img/cat.png\" alt=\"a cat\"></p>",
                "![a cat](/img/cat.png)\n");
        md("span and small are transparent",
                "<p><span>a <small>b</small></span> c</p>",
                "a b c\n");

        String resolved = new MarkdownRenderer(URI.create("https://example.com/docs/page.html"))
                .render(HtmlParser.parse("<p><a href=\"../other\">rel</a></p>"));
        eq("relative href resolves against base",
                "[rel](https://example.com/other)\n", resolved);
    }

    static void listsAndTables() {
        md("unordered list",
                "<ul><li>one</li><li>two</li></ul>",
                "- one\n- two\n");
        md("ordered list with start attribute",
                "<ol start=\"3\"><li>c</li><li>d</li></ol>",
                "3. c\n4. d\n");
        md("nested list indents under its parent item",
                "<ul><li>a<ul><li>a1</li><li>a2</li></ul></li><li>b</li></ul>",
                "- a\n  - a1\n  - a2\n- b\n");
        md("unclosed li still nests correctly",
                "<ul><li>a<li>b</ul>",
                "- a\n- b\n");
        md("multi-paragraph list item indents continuation",
                "<ul><li><p>first</p><p>second</p></li></ul>",
                "- first\n\n  second\n");
        md("table with th header",
                "<table><tr><th>Name</th><th>Age</th></tr><tr><td>Ada</td><td>36</td></tr></table>",
                "| Name | Age |\n| --- | --- |\n| Ada | 36 |\n");
        md("table without header row gets a blank header",
                "<table><tr><td>a</td><td>b</td></tr></table>",
                "|   |   |\n| --- | --- |\n| a | b |\n");
        md("pipes escaped inside cells",
                "<table><tr><th>x</th></tr><tr><td>a|b</td></tr></table>",
                "| x |\n| --- |\n| a\\|b |\n");
    }

    static void documents() {
        md("full document: head skipped, body rendered",
                "<html><head><title>T</title><meta charset=utf-8></head>"
                        + "<body><h1>Hi</h1><p>Body</p></body></html>",
                "# Hi\n\nBody\n");

        Element root = HtmlParser.parse(
                "<html><head><title>  My   Page </title></head><body><p>x</p></body></html>");
        eq("title text accessible for --title",
                "My Page", root.findFirst("title").text().replaceAll("\\s+", " ").strip());

        Element page = HtmlParser.parse(
                "<body><nav>Menu Menu</nav><article><h1>Story</h1><p>"
                        + "Long enough body text to be considered the real content of this page, "
                        + "definitely more than the threshold.</p></article><footer>(c)</footer></body>");
        String extracted = new MarkdownRenderer(null).render(ContentExtractor.extract(page));
        eq("--extract picks the article and drops nav/footer",
                true, extracted.startsWith("# Story") && !extracted.contains("Menu"));
    }

    // ------------------------------------------------------------------

    private static void md(String name, String html, String expected) {
        eq(name, expected, new MarkdownRenderer(null).render(HtmlParser.parse(html)));
    }

    private static void eq(String name, Object expected, Object actual) {
        if (expected.equals(actual)) {
            passed++;
            System.out.println("  ok  " + name);
        } else {
            failed++;
            System.out.println("FAIL  " + name);
            System.out.println("      expected: " + show(expected));
            System.out.println("      actual:   " + show(actual));
        }
    }

    private static String show(Object value) {
        return value instanceof String s ? "\"" + s.replace("\n", "\\n") + "\"" : String.valueOf(value);
    }
}
