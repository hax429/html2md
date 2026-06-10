package html2md.fetch;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Downloads a page over HTTP(S) and decodes it with the advertised charset.
 *
 * <p>Charset detection follows the same precedence a browser uses, minus
 * BOM sniffing: the {@code Content-Type} header wins, then a {@code <meta
 * charset>} found by prescanning the first bytes of the body (HTML
 * §13.2.3.2 — the spec's prescan also stops early, at 1024 bytes), then
 * UTF-8, which is both the spec default and what ~98% of the web serves.
 *
 * <p>{@code Redirect.NORMAL} follows 3xx chains but refuses HTTPS→HTTP
 * downgrades. The post-redirect URI is returned because it — not the URI
 * the user typed — is the correct base for resolving the page's relative
 * links (think {@code http://example.com} → {@code https://www.example.com}).
 */
public final class Fetcher {

    /** @param finalUri the URI after redirects — the right base for resolving relative links */
    public record Page(String html, URI finalUri) {}

    /**
     * Matches {@code charset=utf-8} in a Content-Type header as well as
     * {@code <meta charset="utf-8">} / {@code <meta http-equiv … content=
     * "text/html; charset=utf-8">} in markup — one pattern serves both the
     * header and the prescan because the attribute grammar is the same.
     */
    private static final Pattern HEADER_CHARSET =
            Pattern.compile("charset=\"?([\\w-]+)", Pattern.CASE_INSENSITIVE);

    private Fetcher() {}

    public static Page fetch(URI uri, Duration timeout) throws IOException, InterruptedException {
        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(timeout)
                .build();
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(timeout)
                .header("User-Agent", "html2md/1.0 (Java; +cli)")
                .header("Accept", "text/html,application/xhtml+xml;q=0.9,*/*;q=0.5")
                .GET()
                .build();
        // Bytes, not a String body handler: the charset isn't known until
        // the headers (or the body itself) have been inspected.
        HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() >= 400) {
            throw new IOException("HTTP " + response.statusCode() + " for " + uri);
        }
        String contentType = response.headers().firstValue("content-type").orElse("");
        Charset charset = detectCharset(contentType, response.body());
        return new Page(new String(response.body(), charset), response.uri());
    }

    static Charset detectCharset(String contentTypeHeader, byte[] body) {
        Charset fromHeader = parseCharset(contentTypeHeader);
        if (fromHeader != null) {
            return fromHeader;
        }
        // No header charset: sniff <meta charset=…> in the prologue. Decoding
        // those bytes as ISO-8859-1 is safe for the purpose — it maps every
        // byte to exactly one char, so the ASCII text of the meta tag comes
        // through intact regardless of what encoding the page really uses.
        String prologue = new String(body, 0, Math.min(body.length, 2048), StandardCharsets.ISO_8859_1);
        Charset fromMeta = parseCharset(prologue);
        return fromMeta != null ? fromMeta : StandardCharsets.UTF_8;
    }

    private static Charset parseCharset(String text) {
        Matcher m = HEADER_CHARSET.matcher(text);
        if (m.find()) {
            try {
                return Charset.forName(m.group(1));
            } catch (IllegalArgumentException ignored) {
                // unknown charset name: fall through to default
            }
        }
        return null;
    }
}
