package net.vaier.domain;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Whether Vaier will hand a file to the browser to display, and as what media type. A security decision as
 * much as a convenience one: an inline response is served from Vaier's own origin, against the operator's
 * signed-in session, so the allowlist is the whole guarantee.
 */
class ViewableFileTest {

    @Test
    void anImage_isViewable_asItsOwnImageType() {
        assertThat(ViewableFile.of("holiday.png", false)).contains(new ViewableFile("holiday.png", "image/png"));
        assertThat(ViewableFile.of("holiday.jpg", false).orElseThrow().mediaType()).isEqualTo("image/jpeg");
        assertThat(ViewableFile.of("holiday.jpeg", false).orElseThrow().mediaType()).isEqualTo("image/jpeg");
        assertThat(ViewableFile.of("spin.gif", false).orElseThrow().mediaType()).isEqualTo("image/gif");
        assertThat(ViewableFile.of("shot.webp", false).orElseThrow().mediaType()).isEqualTo("image/webp");
        assertThat(ViewableFile.of("old.bmp", false).orElseThrow().mediaType()).isEqualTo("image/bmp");
        assertThat(ViewableFile.of("favicon.ico", false).orElseThrow().mediaType()).isEqualTo("image/x-icon");
    }

    @Test
    void aPdf_isViewable_asItsOwnType() {
        assertThat(ViewableFile.of("invoice.pdf", false).orElseThrow().mediaType()).isEqualTo("application/pdf");
    }

    @Test
    void aTextIshFile_isViewable_asPlainTextAndNeverAsItsOwnType() {
        // Every text-ish extension collapses to text/plain. An .xml served as application/xml or a .js served
        // as application/javascript renders identically in a browser tab but hands the parser back a document
        // type that can carry behaviour; text/plain cannot.
        for (String filename : new String[]{"notes.txt", "README.md", "syslog.log", "compose.json",
            "docker-compose.yml", "config.yaml", "pom.xml", "export.csv", "Main.java", "app.js",
            "site.css", "setup.sh", "sshd.conf", "app.properties", "schema.sql", "Config.toml"}) {
            assertThat(ViewableFile.of(filename, false).orElseThrow().mediaType())
                .as(filename)
                .isEqualTo("text/plain;charset=utf-8");
        }
    }

    @Test
    void commonAudioAndVideo_areViewable() {
        assertThat(ViewableFile.of("song.mp3", false).orElseThrow().mediaType()).isEqualTo("audio/mpeg");
        assertThat(ViewableFile.of("clip.mp4", false).orElseThrow().mediaType()).isEqualTo("video/mp4");
        assertThat(ViewableFile.of("clip.webm", false).orElseThrow().mediaType()).isEqualTo("video/webm");
        assertThat(ViewableFile.of("sound.ogg", false).orElseThrow().mediaType()).isEqualTo("audio/ogg");
        assertThat(ViewableFile.of("beep.wav", false).orElseThrow().mediaType()).isEqualTo("audio/wav");
    }

    @Test
    void theExtensionMatchIsCaseInsensitive() {
        assertThat(ViewableFile.of("HOLIDAY.PNG", false).orElseThrow().mediaType()).isEqualTo("image/png");
        assertThat(ViewableFile.of("Invoice.Pdf", false).orElseThrow().mediaType()).isEqualTo("application/pdf");
    }

    @Test
    void markupThatCanCarryScript_isNeverViewable_whateverItsCase() {
        // The crux. Inline on Vaier's origin, a rendered .html or .svg runs script against the operator's
        // signed-in session. An uppercase extension is the oldest way past a naive allowlist.
        for (String filename : new String[]{"page.html", "page.htm", "logo.svg", "doc.xhtml", "saved.mhtml",
            "PAGE.HTML", "LOGO.SVG", "Logo.Svg", "sheet.xslt"}) {
            assertThat(ViewableFile.of(filename, false)).as(filename).isEmpty();
        }
    }

    @Test
    void anUnknownOrExtensionlessFile_isNotViewable() {
        assertThat(ViewableFile.of("backup.tar.gz", false)).isEmpty();
        assertThat(ViewableFile.of("vmlinuz", false)).isEmpty();
        assertThat(ViewableFile.of("archive.zip", false)).isEmpty();
        // A double extension is judged by its last one — the one the browser would act on.
        assertThat(ViewableFile.of("notes.txt.html", false)).isEmpty();
        assertThat(ViewableFile.of("page.html.txt", false).orElseThrow().mediaType())
            .isEqualTo("text/plain;charset=utf-8");
    }

    @Test
    void aDirectory_isNeverViewable_evenNamedLikeAnImage() {
        assertThat(ViewableFile.of("photos.png", true)).isEmpty();
        assertThat(ViewableFile.of("stuff", true)).isEmpty();
    }

    @Test
    void require_answersTheViewableFile_orRefusesWithAnOperatorReadableSentence() {
        assertThat(ViewableFile.require("holiday.png", false).mediaType()).isEqualTo("image/png");

        assertThatThrownBy(() -> ViewableFile.require("page.html", false))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("page.html")
            .hasMessageContaining("Download it instead");
        assertThatThrownBy(() -> ViewableFile.require("photos.png", true))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aViewablesContentSecurityPolicy_sandboxesEverythingExceptAPdf() {
        // Everything the browser is handed inline is sandboxed — no script, no plugins and no forms — the belt
        // to the allowlist's braces. The sandbox keeps `allow-same-origin` because a bare one gives the
        // document an opaque origin, and `'self'` matches no opaque origin: `media-src 'self'` then refused
        // the very bytes being viewed, so a video rendered a player with nothing to play. Nothing can exploit
        // the kept origin — there is no `allow-scripts`, and `default-src 'none'` blocks script regardless.
        String imagePolicy = ViewableFile.require("holiday.png", false).contentSecurityPolicy();
        assertThat(imagePolicy).startsWith("sandbox allow-same-origin;").contains("default-src 'none'");
        assertThat(imagePolicy).contains("media-src 'self'");
        assertThat(ViewableFile.require("notes.txt", false).contentSecurityPolicy()).isEqualTo(imagePolicy);

        // Except a PDF: the browsers' built-in viewers are themselves documents, and a sandboxed opaque
        // origin stops them loading — the file downloads or the tab stays blank. So a PDF keeps a policy,
        // just the tightest one that still renders.
        String pdfPolicy = ViewableFile.require("invoice.pdf", false).contentSecurityPolicy();
        assertThat(pdfPolicy).doesNotContain("sandbox");
        assertThat(pdfPolicy).contains("default-src 'none'");
    }

    @Test
    void aBlankName_isNotViewable_ratherThanAnError() {
        assertThat(ViewableFile.of("", false)).isEqualTo(Optional.empty());
        assertThat(ViewableFile.of(null, false)).isEmpty();
        assertThat(ViewableFile.of(".", false)).isEmpty();
        assertThat(ViewableFile.of(".bashrc", false)).isEmpty();
    }
}
