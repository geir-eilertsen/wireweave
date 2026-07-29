package net.vaier.domain;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * A file Vaier will hand to the browser to <b>display</b>, and the media type it is handed as — the domain's
 * answer to "can this be opened rather than saved?".
 *
 * <p>This is a security decision before it is a convenience one, which is why it lives here rather than in a
 * service, a controller or the browser. An inline response is served from <em>Vaier's own origin</em>, to a
 * browser carrying the operator's signed-in session. Whatever the file turns out to be, the browser executes
 * it with Vaier's cookies in reach — so an arbitrary file from an arbitrary fleet machine rendered inline is
 * stored-XSS surface. The allowlist below is the whole guarantee, and it is an allowlist by construction:
 * anything not named here is not viewable, and downloads instead.
 *
 * <p><b>Why HTML and SVG are missing on purpose.</b> {@code html}, {@code htm}, {@code xhtml}, {@code mhtml}
 * and {@code svg} are the file types a browser executes script from. A machine's filesystem is full of them
 * (a web root, a saved page, an exported chart) and any of them could have been written by something other
 * than the operator. Rendered inline they run against the operator's Vaier session. They are refused, listed
 * explicitly in {@link #NEVER_INLINE} so that adding one to {@link #MEDIA_TYPES} by accident still fails
 * closed, and they download exactly as they always did. Their absence is the feature, not an oversight.
 *
 * <p><b>Why text-ish files are all {@code text/plain}.</b> An {@code .xml}, {@code .json} or {@code .js}
 * served as its own type renders identically in a tab but hands the browser a document type that can carry
 * behaviour. {@code text/plain} cannot, so every text-ish extension collapses to it — one uniform answer
 * rather than a per-type judgement call that has to stay right forever.
 */
public record ViewableFile(String filename, String mediaType) {

    /** Text-ish files are all served as this, never as their own type. */
    private static final String PLAIN_TEXT = "text/plain;charset=utf-8";

    private static final String PDF = "application/pdf";

    /**
     * The extensions a browser may execute script from. Never inline, whatever else says otherwise: consulted
     * before {@link #MEDIA_TYPES} so a future addition there cannot quietly re-open the hole.
     */
    private static final Set<String> NEVER_INLINE = Set.of(
        "html", "htm", "xhtml", "xht", "shtml", "svg", "svgz", "mhtml", "mht", "xsl", "xslt");

    /** The allowlist: extension (lower-case, no dot) to the media type it is served as. Nothing else renders. */
    private static final Map<String, String> MEDIA_TYPES = buildMediaTypes();

    private static Map<String, String> buildMediaTypes() {
        Map<String, String> types = new HashMap<>();
        types.put("png", "image/png");
        types.put("jpg", "image/jpeg");
        types.put("jpeg", "image/jpeg");
        types.put("gif", "image/gif");
        types.put("webp", "image/webp");
        types.put("bmp", "image/bmp");
        types.put("ico", "image/x-icon");
        types.put("pdf", PDF);
        types.put("mp3", "audio/mpeg");
        types.put("wav", "audio/wav");
        types.put("ogg", "audio/ogg");
        types.put("flac", "audio/flac");
        types.put("mp4", "video/mp4");
        types.put("webm", "video/webm");
        for (String textish : new String[]{
            "txt", "md", "markdown", "log", "json", "yml", "yaml", "xml", "csv", "tsv", "ini", "conf", "cfg",
            "properties", "env", "sql", "sh", "bash", "zsh", "py", "rb", "pl", "php", "java", "js", "mjs",
            "cjs", "ts", "css", "scss", "c", "h", "cpp", "hpp", "go", "rs", "kt", "swift", "toml", "diff",
            "patch", "service"}) {
            types.put(textish, PLAIN_TEXT);
        }
        return Map.copyOf(types);
    }

    /**
     * How a viewable file is sandboxed on the way to the browser: no script, no plugins, no forms, and no
     * subresource that is not the file itself. Belt to the allowlist's braces — even a file that somehow got
     * through with the wrong type has nothing left to do.
     *
     * <p>{@code allow-same-origin} is on the sandbox <b>deliberately</b>, and it is not a loosening. A bare
     * {@code sandbox} gives the document an <em>opaque</em> origin, and {@code 'self'} matches no opaque
     * origin — so {@code media-src 'self'} blocked the very bytes being viewed. An image survived that (a
     * top-level image <em>is</em> the navigation response, not a subresource) but audio and video did not:
     * the browser wraps them in a synthetic document whose {@code <video>} element fetches the media as a
     * subresource, which was then refused. The symptom was a player with nothing to play. Keeping the origin
     * costs nothing here: without {@code allow-scripts} — and under {@code default-src 'none'} — no script
     * can run to make use of it, and the allowlist never serves markup.
     */
    private static final String SANDBOXED_POLICY =
        "sandbox allow-same-origin; default-src 'none'; img-src 'self' data:; media-src 'self'; "
            + "style-src 'unsafe-inline'";

    /**
     * A PDF's policy. {@code sandbox} is dropped here <b>deliberately</b>: the browsers' built-in PDF viewers
     * are documents of their own that the sandbox's opaque origin stops from loading, so a sandboxed PDF ends
     * as a blank tab or a silent download rather than a rendered page. The rest of the policy stands — this is
     * the tightest one that still renders, not a dropped header.
     */
    private static final String PDF_POLICY = "default-src 'none'; object-src 'self'; frame-src 'self'";

    /**
     * The viewable file {@code filename} is, or empty when Vaier will not display it — an unlisted extension,
     * no extension at all, markup that can carry script, or a directory (which has no bytes to render and is
     * a zip when it is downloaded).
     */
    public static Optional<ViewableFile> of(String filename, boolean directory) {
        if (directory || filename == null || filename.isBlank()) {
            return Optional.empty();
        }
        String extension = extensionOf(filename);
        if (extension.isEmpty() || NEVER_INLINE.contains(extension)) {
            return Optional.empty();
        }
        return Optional.ofNullable(MEDIA_TYPES.get(extension))
            .map(mediaType -> new ViewableFile(filename, mediaType));
    }

    /**
     * The viewable file {@code filename} is, or a refusal. Asking to display something Vaier will not display
     * is a bad request, not a fallback: quietly serving it inline anyway is exactly the hole
     * {@link #NEVER_INLINE} exists to close, and quietly serving it as an attachment would answer a question
     * the caller did not ask.
     *
     * @throws IllegalArgumentException when the file is not viewable — a {@code 400} carrying this sentence
     */
    public static ViewableFile require(String filename, boolean directory) {
        return of(filename, directory).orElseThrow(() -> new IllegalArgumentException(
            "Vaier will not display \"" + filename + "\" in the browser. Download it instead."));
    }

    /**
     * The {@code Content-Security-Policy} this file is served under — the sandboxed policy for everything
     * except a PDF, which needs {@link #PDF_POLICY} to render at all.
     */
    public String contentSecurityPolicy() {
        return PDF.equals(mediaType) ? PDF_POLICY : SANDBOXED_POLICY;
    }

    /**
     * The last extension of a filename, lower-cased and without its dot — the one a browser would act on, so
     * {@code notes.txt.html} is judged as HTML. A dotfile ({@code .bashrc}) has no extension: its dot opens
     * the name rather than separating a type off the end of it.
     */
    private static String extensionOf(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot <= 0 || dot == filename.length() - 1
            ? "" : filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
