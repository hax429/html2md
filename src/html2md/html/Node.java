package html2md.html;

/**
 * Base type for the two things a parsed document contains: elements and
 * text. Comments and doctypes never make it past the parser, so the DOM's
 * remaining node kinds aren't modeled. Sealed, so renderer code can do an
 * exhaustive {@code instanceof} split with no default branch to forget.
 */
public abstract sealed class Node permits Element, TextNode {
}
