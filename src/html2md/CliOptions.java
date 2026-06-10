package html2md;

import java.util.ArrayList;
import java.util.List;

/**
 * Parsed command line. Throws {@link UsageException} on anything malformed;
 * {@link html2md.Main} turns that into the usage text and exit code 2 — the
 * convention (grep, diff, curl) that lets scripts distinguish "you called me
 * wrong" from "the work failed" (exit 1).
 *
 * <p>Follows GNU-ish conventions by hand: long and short flags, {@code --}
 * ends option parsing (so a file literally named {@code -e} is reachable),
 * and a bare {@code -} is a positional argument meaning stdin, not a flag.
 */
public final class CliOptions {

    public static final String USAGE = """
            usage: html2md [options] <url | file | ->

            Convert a web page (or local HTML file, or stdin with "-") to Markdown.

            options:
              -o, --output <file>   write Markdown to a file instead of stdout
              -e, --extract         keep only the main content (<main>/<article> heuristic)
                  --title           prepend the page <title> as a top-level heading
              -t, --timeout <sec>   HTTP timeout in seconds (default 20)
              -h, --help            show this help
            """;

    public static final class UsageException extends Exception {
        public UsageException(String message) {
            super(message);
        }
    }

    public final String input;
    public final String outputFile;   // null = stdout
    public final boolean extract;
    public final boolean includeTitle;
    public final int timeoutSeconds;
    public final boolean helpRequested;

    private CliOptions(String input, String outputFile, boolean extract,
                       boolean includeTitle, int timeoutSeconds, boolean helpRequested) {
        this.input = input;
        this.outputFile = outputFile;
        this.extract = extract;
        this.includeTitle = includeTitle;
        this.timeoutSeconds = timeoutSeconds;
        this.helpRequested = helpRequested;
    }

    public static CliOptions parse(String[] args) throws UsageException {
        String outputFile = null;
        boolean extract = false;
        boolean includeTitle = false;
        int timeoutSeconds = 20;
        List<String> positional = new ArrayList<>();

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            switch (arg) {
                case "-h", "--help" -> {
                    return new CliOptions(null, null, false, false, 0, true);
                }
                case "-o", "--output" -> outputFile = valueOf(arg, args, ++i);
                case "-e", "--extract" -> extract = true;
                case "--title" -> includeTitle = true;
                case "-t", "--timeout" -> timeoutSeconds = parseTimeout(valueOf(arg, args, ++i));
                case "--" -> {
                    for (i++; i < args.length; i++) {
                        positional.add(args[i]);
                    }
                }
                default -> {
                    if (arg.startsWith("-") && !arg.equals("-")) {
                        throw new UsageException("unknown option: " + arg);
                    }
                    positional.add(arg);
                }
            }
        }
        if (positional.isEmpty()) {
            throw new UsageException("missing input: pass a URL, a file path, or - for stdin");
        }
        if (positional.size() > 1) {
            throw new UsageException("expected one input, got: " + String.join(", ", positional));
        }
        return new CliOptions(positional.get(0), outputFile, extract, includeTitle, timeoutSeconds, false);
    }

    private static String valueOf(String flag, String[] args, int index) throws UsageException {
        if (index >= args.length) {
            throw new UsageException(flag + " requires a value");
        }
        return args[index];
    }

    private static int parseTimeout(String value) throws UsageException {
        try {
            int seconds = Integer.parseInt(value);
            if (seconds <= 0) {
                throw new UsageException("timeout must be positive, got: " + value);
            }
            return seconds;
        } catch (NumberFormatException e) {
            throw new UsageException("timeout must be a number of seconds, got: " + value);
        }
    }
}
