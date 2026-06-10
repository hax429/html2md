package html2md.html;

public final class TextNode extends Node {

    private final String text;

    public TextNode(String text) {
        this.text = text;
    }

    public String text() {
        return text;
    }

    @Override
    public String toString() {
        return "#text(" + text + ")";
    }
}
