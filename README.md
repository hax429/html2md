# html2md

A webpage-to-Markdown converter in plain Java. No frameworks, no libraries —
the HTML tokenizer, tree parser, and Markdown renderer are all written from
scratch on the JDK alone.

```
$ html2md https://example.com
# Example Domain

This domain is for use in documentation examples without needing permission.

[Learn more](https://iana.org/domains/example)
```

## Build & run

Requires JDK 17+ and `make` (or run the `javac`/`jar` lines from the Makefile
by hand).

```sh
make jar        # builds html2md.jar
make test       # compiles and runs the test suite (41 cases, no JUnit)

java -jar html2md.jar [options] <url | file | ->
```

### Options

| Flag | Effect |
| --- | --- |
| `-o, --output <file>` | write Markdown to a file instead of stdout |
| `-e, --extract` | keep only the main content (`<main>`/`<article>` heuristic, chrome pruned) |
| `--title` | prepend the page `<title>` as a top-level heading |
| `-t, --timeout <sec>` | HTTP timeout in seconds (default 20) |
| `-h, --help` | usage |

Input can be an `http(s)` URL, a local file path, or `-` for stdin.
Exit codes: `0` success, `1` runtime failure (network, I/O), `2` usage error.

```sh
java -jar html2md.jar --extract --title https://en.wikipedia.org/wiki/Markdown -o markdown.md
```

## Architecture

The pipeline is four stages, each its own package, each consuming only the
output of the previous one:

```
URL/file ── fetch ──> String ── tokenize ──> List<Token> ── parse ──> Element tree ── render ──> Markdown
            Fetcher              HtmlTokenizer               HtmlParser                MarkdownRenderer
```

- **`html2md.fetch.Fetcher`** — `java.net.http` with redirects, a timeout, and
  charset detection (Content-Type header, then `<meta charset>` sniffing,
  then UTF-8). Returns the post-redirect URI so relative links resolve
  against the right base.

- **`html2md.html.HtmlTokenizer`** — single-pass lexer producing a flat stream
  of `StartTag` / `EndTag` / `Text` / `Comment` / `Doctype` tokens (a sealed
  interface of records). It is forgiving the way browsers are: a stray `<` is
  text, unterminated constructs run to EOF, attributes may be unquoted or
  valueless. `script`/`style` bodies are consumed as raw text so markup inside
  them can't leak into the document. Entities (named and numeric) are decoded
  here, so everything downstream sees plain strings.

- **`html2md.html.HtmlParser`** — turns the token stream into an `Element`
  tree using an open-element stack. Real pages omit close tags constantly, so
  it carries a table of implicit-close rules (the useful subset of the HTML5
  algorithm): a `<p>` closes an open `<p>`, `<li>` closes `<li>`, `<td>`
  closes `<td>`, and so on. Void elements (`<br>`, `<img>`, …) never go on
  the stack; stray end tags are dropped.

- **`html2md.md.MarkdownRenderer`** — recursive descent over the tree with two
  modes mirroring HTML's content model. *Block* mode turns a container's
  children into a list of blocks (runs of inline content become implicit
  paragraphs); *inline* mode collapses whitespace and escapes Markdown
  metacharacters. Container constructs render their contents recursively and
  then transform the text — blockquotes prefix every line with `> `, list
  items indent continuation lines by the marker width — which makes arbitrary
  nesting (lists in quotes in lists) fall out for free. Handles headings,
  nested/ordered lists, tables (with header detection), fenced code blocks
  with language inference from `class="language-…"`, emphasis with boundary
  whitespace promotion (`<b> x </b>` → `**x**`, space outside), code spans
  with adaptive backtick fences, links/images with relative-URL resolution,
  and hard breaks.

- **`html2md.md.ContentExtractor`** (`--extract`) — prefers a sufficiently
  large `<article>`/`<main>`, falls back to `<body>`, then prunes
  `nav`/`header`/`footer`/`aside` and ARIA landmark roles
  (`navigation`, `banner`, `contentinfo`, …) from the chosen region.

- **`html2md.CliOptions` / `Main`** — flag parsing with real error messages,
  input dispatch (URL vs. file vs. stdin), exit codes.

## Tests

`test/html2md/Tests.java` is a self-contained runner (no JUnit): 41 cases
covering entity decoding, malformed-HTML recovery (unclosed `<p>`/`<li>`,
stray end tags, raw-text leakage), every block construct, inline emphasis
edge cases, escaping, relative-URL resolution, and the content-extraction
heuristic. `make test` exits non-zero on any failure.

## Known limitations

- No JavaScript: pages that render client-side convert to whatever is in the
  initial HTML.
- Named entity table covers the common typographic set, not all 2,000+ HTML5
  names (numeric references always work).
- Tables flatten `colspan`/`rowspan`; alignment hints are ignored.
- The adoption-agency algorithm for misnested formatting tags
  (`<b>a<i>b</b>c</i>`) is approximated by the stack discipline, not
  implemented in full.
