package html2md.html;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

public final class Element extends Node {

    private final String tag;
    private final Map<String, String> attrs;
    private final List<Node> children = new ArrayList<>();

    public Element(String tag, Map<String, String> attrs) {
        this.tag = tag;
        this.attrs = attrs;
    }

    public String tag() {
        return tag;
    }

    /** Attribute value, or {@code ""} when absent. */
    public String attr(String name) {
        return attrs.getOrDefault(name, "");
    }

    public List<Node> children() {
        return children;
    }

    public void add(Node child) {
        children.add(child);
    }

    /**
     * Concatenated text of all descendants, whitespace untouched — the
     * equivalent of DOM {@code textContent}. Callers decide what whitespace
     * means: the renderer collapses it for prose but relies on it being
     * intact here for {@code <pre>} blocks.
     */
    public String text() {
        StringBuilder sb = new StringBuilder();
        collectText(this, sb);
        return sb.toString();
    }

    private static void collectText(Element el, StringBuilder sb) {
        for (Node child : el.children) {
            if (child instanceof TextNode t) {
                sb.append(t.text());
            } else if (child instanceof Element e) {
                collectText(e, sb);
            }
        }
    }

    /** First descendant (depth-first, document order) with the given tag, or null. */
    public Element findFirst(String wanted) {
        for (Node child : children) {
            if (child instanceof Element e) {
                if (e.tag.equals(wanted)) {
                    return e;
                }
                Element found = e.findFirst(wanted);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    public List<Element> findAll(String wanted) {
        List<Element> out = new ArrayList<>();
        collectAll(this, wanted, out);
        return out;
    }

    private static void collectAll(Element el, String wanted, List<Element> out) {
        for (Node child : el.children) {
            if (child instanceof Element e) {
                if (e.tag.equals(wanted)) {
                    out.add(e);
                }
                collectAll(e, wanted, out);
            }
        }
    }

    public List<Element> childElements(String wanted) {
        List<Element> out = new ArrayList<>();
        for (Node child : children) {
            if (child instanceof Element e && e.tag.equals(wanted)) {
                out.add(e);
            }
        }
        return out;
    }

    /** Removes matching elements from the whole subtree. */
    public void prune(Predicate<Element> doomed) {
        children.removeIf(child -> child instanceof Element e && doomed.test(e));
        for (Node child : children) {
            if (child instanceof Element e) {
                e.prune(doomed);
            }
        }
    }

    @Override
    public String toString() {
        return "<" + tag + ">";
    }
}
