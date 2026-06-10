package html2md;

import html2md.fetch.Fetcher;
import html2md.html.Element;
import html2md.html.HtmlParser;
import html2md.md.ContentExtractor;
import html2md.md.MarkdownRenderer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

/**
 * Entry point: wires the pipeline together
 * (load → parse → [extract] → render → write) and owns all process-level
 * concerns — exit codes, stderr formatting, stdin/stdout. Nothing below
 * this class ever calls {@code System.exit} or prints, which keeps the
 * library packages testable and reusable.
 */
public final class Main {

    /** @param base page URL for resolving relative links; null for file/stdin input, which has none */
    private record Source(String html, URI base) {}

    public static void main(String[] args) {
        CliOptions options;
        try {
            options = CliOptions.parse(args);
        } catch (CliOptions.UsageException e) {
            System.err.println("html2md: " + e.getMessage());
            System.err.println(CliOptions.USAGE);
            System.exit(2);
            return;
        }
        if (options.helpRequested) {
            System.out.print(CliOptions.USAGE);
            return;
        }
        try {
            run(options);
        } catch (IOException | UncheckedIOException e) {
            System.err.println("html2md: " + e.getMessage());
            System.exit(1);
        } catch (InterruptedException e) {
            // Re-assert the flag (swallowing it would hide the interrupt
            // from any enclosing JVM embedding) and bail quietly.
            Thread.currentThread().interrupt();
            System.exit(1);
        }
    }

    private static void run(CliOptions options) throws IOException, InterruptedException {
        Source source = load(options);
        Element root = HtmlParser.parse(source.html());

        StringBuilder markdown = new StringBuilder();
        if (options.includeTitle) {
            Element title = root.findFirst("title");
            if (title != null) {
                String text = title.text().replaceAll("\\s+", " ").strip();
                if (!text.isEmpty()) {
                    markdown.append("# ").append(text).append("\n\n");
                }
            }
        }

        Element scope = options.extract ? ContentExtractor.extract(root) : root;
        markdown.append(new MarkdownRenderer(source.base()).render(scope));

        if (options.outputFile != null) {
            Files.writeString(Path.of(options.outputFile), markdown, StandardCharsets.UTF_8);
        } else {
            System.out.print(markdown);
        }
    }

    private static Source load(CliOptions options) throws IOException, InterruptedException {
        String input = options.input;
        if (input.equals("-")) {
            return new Source(new String(System.in.readAllBytes(), StandardCharsets.UTF_8), null);
        }
        if (input.startsWith("http://") || input.startsWith("https://")) {
            URI uri;
            try {
                uri = new URI(input);
            } catch (URISyntaxException e) {
                throw new IOException("not a valid URL: " + input);
            }
            Fetcher.Page page = Fetcher.fetch(uri, Duration.ofSeconds(options.timeoutSeconds));
            return new Source(page.html(), page.finalUri());
        }
        // Everything that isn't "-" or an http(s) URL is treated as a file
        // path. The error spells out the URL rule because the most likely
        // way to land here is typing "example.com" without a scheme.
        Path path = Path.of(input);
        if (!Files.isRegularFile(path)) {
            throw new IOException("no such file: " + input
                    + " (URLs must start with http:// or https://)");
        }
        return new Source(Files.readString(path, StandardCharsets.UTF_8), null);
    }
}
