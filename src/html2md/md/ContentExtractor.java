package html2md.md;

import html2md.html.Element;

import java.util.List;
import java.util.Set;

/**
 * Heuristic main-content selection for the {@code --extract} flag — the
 * lightweight cousin of Readability-style extractors [1]: instead of scoring
 * text density, it trusts the page's own semantics. Prefer a
 * {@code <article>} (more specific) over {@code <main>} (at most one per
 * page, but often wraps chrome too); otherwise fall back to {@code <body>}.
 *
 * <p>[1] Mozilla Readability / the original arc90 readability.js
 */
public final class ContentExtractor {

    /**
     * Sectioning elements that hold page chrome rather than content. The
     * matching ARIA landmark roles below catch the same chrome on pages
     * that build it from {@code <div role="navigation">} instead of
     * semantic tags — and on pages that do both, like Wikipedia, whose
     * toolbars live <em>inside</em> {@code <main>}.
     */
    private static final Set<String> CHROME = Set.of("nav", "header", "footer", "aside");

    private static final Set<String> NOISE_ROLES = Set.of(
            "navigation", "banner", "contentinfo", "complementary", "search");

    /**
     * A candidate region must hold at least this much text to be trusted.
     * Guards against pages that use {@code <article>} for teaser cards or
     * ship an empty {@code <main>} shell that JavaScript fills in later —
     * picking those would silently discard the whole document.
     */
    private static final int MIN_TEXT_LENGTH = 100;

    private ContentExtractor() {}

    public static Element extract(Element root) {
        Element scope = null;
        for (String tag : List.of("article", "main")) {
            Element candidate = root.findFirst(tag);
            if (candidate != null && candidate.text().strip().length() >= MIN_TEXT_LENGTH) {
                scope = candidate;
                break;
            }
        }
        if (scope == null) {
            Element body = root.findFirst("body");
            scope = body != null ? body : root;
        }
        // Sites routinely nest chrome inside <main> (Wikipedia puts its whole
        // toolbar there), so prune within the chosen region as well.
        scope.prune(el -> CHROME.contains(el.tag()) || NOISE_ROLES.contains(el.attr("role")));
        return scope;
    }
}
