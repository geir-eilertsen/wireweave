package net.vaier.rest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The launchpad is the fleet's front door, and the one page a phone can reach before it belongs to the
 * fleet at all — so it is where the <b>Vaier app</b> is handed out (#359).
 *
 * <p>There is no JS test harness in this project, so — as with {@code ExplorerShellTest} — the invariants
 * are asserted on the shipped page itself.
 */
class LaunchpadPageTest {

    private static String launchpad() throws IOException {
        return Files.readString(Path.of("src/main/resources/static/launchpad.html"));
    }

    @Test
    void theAndroidApp_isOfferedToEveryVisitorSignedInOrNot() throws IOException {
        // A phone fetches the app before it can sign in, so gating this behind a session would be a
        // locked door with the key behind it. The nav painter must offer it on both branches.
        String page = launchpad();

        assertThat(page).contains("href=\"/app/android/vaier.apk\"");
        assertThat(page).contains("<span class=\"link-label\">Android</span>");
        assertThat(page).as("saved, not navigated to").contains("download");

        // Painted by one function called outside the signed-in / anonymous branch, so neither can lose it.
        assertThat(page).contains("paintAndroidLink(");
        int painter = page.indexOf("async function paintAndroidLink(");
        assertThat(painter).isPositive();
    }

    @Test
    void theAndroidButton_isPaintedOnlyWhenThereIsAnAppToServe() throws IOException {
        // Vaier never offers what it cannot serve: an image built without the package answers 404 here,
        // and the button is simply absent rather than painted over a dead link.
        String page = launchpad();
        int from = page.indexOf("async function paintAndroidLink(");
        String painter = page.substring(from, page.indexOf("\n        }", from));

        assertThat(painter).contains("method: 'HEAD'");
        assertThat(painter).contains("/app/android/vaier.apk");
        assertThat(painter).as("only a 200 paints it").contains("res.ok");
        assertThat(painter).as("a failed probe is silence, never a broken button").contains("catch");
    }

    @Test
    void theAndroidItem_wearsTheSameTopbarClothesAsEveryOtherNavLink() throws IOException {
        // One nav, one shape. A bespoke button here would read as a different product.
        String page = launchpad();

        assertThat(page).contains("const ICON_ANDROID");
        assertThat(page).containsPattern("ICON_ANDROID\\s*=\\s*'<svg viewBox=\"0 0 16 16\"");
        int from = page.indexOf("async function paintAndroidLink(");
        String painter = page.substring(from, page.indexOf("\n        }", from));
        assertThat(painter).contains("class=\"topbar-item\"");
        assertThat(painter).contains("ICON_ANDROID");
    }

    @Test
    void theLaunchpadNeverPollsForTheApp() throws IOException {
        // The backend polls and SSE pushes; the browser asks once, on load.
        String page = launchpad();
        int from = page.indexOf("async function paintAndroidLink(");
        String painter = page.substring(from, page.indexOf("\n        }", from));

        assertThat(painter).doesNotContain("setInterval");
    }
}
