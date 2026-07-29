package net.vaier.adapter.driven;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yaml.snakeyaml.Yaml;
import static org.assertj.core.api.Assertions.assertThat;

class DockerComposeStructureTest {

    @SuppressWarnings("unchecked")
    private Map<String, String> vaierLabels() throws Exception {
        Map<String, Object> compose = (Map<String, Object>) new Yaml()
            .load(Files.readString(Path.of("docker-compose.yml")));
        Map<String, Object> services = (Map<String, Object>) compose.get("services");
        Map<String, Object> vaier = (Map<String, Object>) services.get("vaier");
        List<String> labels = (List<String>) vaier.get("labels");
        Map<String, String> byKey = new LinkedHashMap<>();
        for (String label : labels) {
            int eq = label.indexOf('=');
            if (eq > 0) {
                byKey.put(label.substring(0, eq), label.substring(eq + 1));
            }
        }
        return byKey;
    }

    // --- Public, viewer-adaptive launchpad: three-tier routing on the console host ---

    @Test
    void publicRouter_servesTheLaunchpadShellAndAssetsWithNoAuthMiddleware() throws Exception {
        Map<String, String> labels = vaierLabels();
        String rule = labels.get("traefik.http.routers.vaier-public.rule");
        String mw = labels.get("traefik.http.routers.vaier-public.middlewares");

        // The launchpad shell + assets + public data must be anonymously reachable.
        assertThat(rule).contains("Path(`/`)");
        assertThat(rule).contains("Path(`/launchpad.html`)");
        assertThat(rule).contains("Path(`/styles.css`)");
        // avatar.js is loaded by the public launchpad shell; it must be anonymously reachable too,
        // or a non-admin viewer 403s on it, VaierAvatar never loads, and the topbar breaks.
        assertThat(rule).contains("Path(`/avatar.js`)");
        assertThat(rule).contains("PathPrefix(`/icon`)");
        assertThat(rule).contains("Path(`/launchpad/services`)");
        // The launchpad's public live-update stream — signal-only, so anonymous viewers get live
        // tile refreshes without the private-subdomain payload the full SSE stream carries.
        assertThat(rule).contains("Path(`/launchpad/events`)");

        // But no admin surface may be whitelisted as public.
        assertThat(rule).doesNotContain("admin.html");
        assertThat(rule).doesNotContain("/access");
        assertThat(rule).doesNotContain("services-authenticated");
        assertThat(rule).doesNotContain("/users/me");

        // Public tier carries the offline middleware only — never any auth link.
        assertThat(mw).isEqualTo("vaier-down");
        assertThat(mw).doesNotContain("oauth2");
        assertThat(mw).doesNotContain("authz");
    }

    @Test
    void identityRouter_carriesOnlyOauth2AuthnForTheViewerAdaptiveEndpoints() throws Exception {
        Map<String, String> labels = vaierLabels();
        String rule = labels.get("traefik.http.routers.vaier-identity.rule");
        String mw = labels.get("traefik.http.routers.vaier-identity.middlewares");
        String priority = labels.get("traefik.http.routers.vaier-identity.priority");

        // The viewer-adaptive endpoints: the two data/identity APIs plus the launchpad's live-update
        // SSE stream. The SSE payload carries service subdomains, so it belongs behind oauth2-authn
        // (authenticated non-admins get it; anonymous get a clean 401) — never on the public tier.
        assertThat(rule).contains("Path(`/launchpad/services-authenticated`)");
        assertThat(rule).contains("Path(`/users/me`)");
        assertThat(rule).contains("Path(`/published-services/events`)");

        // oauth2-authn injects identity when a session exists and 401s anonymous — but NO
        // forced-login redirect (oauth2-signin) and NO admin gate (vaier-authz).
        assertThat(mw).contains("oauth2-authn@file");
        assertThat(mw).contains("vaier-down");
        assertThat(mw).doesNotContain("oauth2-signin");
        assertThat(mw).doesNotContain("vaier-authz");

        // Must out-rank the admin catch-all so these paths aren't swept into the full auth chain.
        assertThat(priority).isEqualTo("250");
    }

    @Test
    void adminCatchAll_stillEnforcesTheFullSocialChainWithAuthz() throws Exception {
        Map<String, String> labels = vaierLabels();
        String rule = labels.get("traefik.http.routers.vaier.rule");
        String mw = labels.get("traefik.http.routers.vaier.middlewares");
        String priority = labels.get("traefik.http.routers.vaier.priority");

        // The catch-all still matches the whole host (admin.html + all admin APIs land here).
        assertThat(rule).isEqualTo("Host(`vaier.${VAIER_DOMAIN}`)");
        // And it still carries the full chain including the admin-enforcing vaier-authz.
        assertThat(mw).contains("oauth2-signin@file");
        assertThat(mw).contains("oauth2-authn@file");
        assertThat(mw).contains("vaier-authz@file");
        // Lowest priority of the real routers, so the specific public/identity/oauth2 routers win.
        assertThat(priority).isEqualTo("100");
    }

    @Test
    @SuppressWarnings("unchecked")
    void autheliaAndRedisAreDecommissioned_noLongerInTheStack() throws Exception {
        // Every gated service moved to social login (#305). Authelia and its Redis session store
        // are removed from the running stack, along with their init sidecars.
        Map<String, Object> compose = (Map<String, Object>) new Yaml()
            .load(Files.readString(Path.of("docker-compose.yml")));
        Map<String, Object> services = (Map<String, Object>) compose.get("services");

        assertThat(services)
            .as("Authelia and Redis are decommissioned and must not appear in the stack")
            .doesNotContainKeys("authelia", "authelia-init", "redis", "redis-init");
    }

    @Test
    @SuppressWarnings("unchecked")
    void oauth2Proxy_isMandatoryInfrastructure_notBehindAProfile() throws Exception {
        // With Authelia gone, oauth2-proxy is the sole auth gateway — it must always start with
        // `docker compose up -d`, so neither it nor its init may be gated behind the `social` profile.
        Map<String, Object> compose = (Map<String, Object>) new Yaml()
            .load(Files.readString(Path.of("docker-compose.yml")));
        Map<String, Object> services = (Map<String, Object>) compose.get("services");
        Map<String, Object> oauth2Proxy = (Map<String, Object>) services.get("oauth2-proxy");
        Map<String, Object> oauth2ProxyInit = (Map<String, Object>) services.get("oauth2-proxy-init");

        assertThat(oauth2Proxy).as("oauth2-proxy must always start").doesNotContainKey("profiles");
        assertThat(oauth2ProxyInit).as("oauth2-proxy-init must always start").doesNotContainKey("profiles");
    }

    // --- #305 follow-up: Dex OIDC broker federates Google + GitHub behind oauth2-proxy ---

    @Test
    @SuppressWarnings("unchecked")
    void dex_isMandatoryVersionPinnedInfrastructure_onPort5556() throws Exception {
        // Dex is the identity broker behind oauth2-proxy (federates Google + GitHub). Like
        // oauth2-proxy it is the sole auth path, so it is mandatory infrastructure (no profile),
        // version-pinned (no floating :latest), and Traefik routes to it on Dex's HTTP port 5556.
        Map<String, Object> compose = (Map<String, Object>) new Yaml()
            .load(Files.readString(Path.of("docker-compose.yml")));
        Map<String, Object> services = (Map<String, Object>) compose.get("services");
        Map<String, Object> dex = (Map<String, Object>) services.get("dex");

        assertThat(dex).as("dex service must exist").isNotNull();
        assertThat((String) dex.get("image"))
            .as("dex image must be version-pinned").contains("dexidp/dex:v2.45.1");
        assertThat(dex).as("dex must always start — no profile gate").doesNotContainKey("profiles");

        List<String> labels = (List<String>) dex.get("labels");
        Map<String, String> byKey = new LinkedHashMap<>();
        for (String label : labels) {
            int eq = label.indexOf('=');
            if (eq > 0) {
                byKey.put(label.substring(0, eq), label.substring(eq + 1));
            }
        }
        assertThat(byKey.get("traefik.http.services.dex.loadbalancer.server.port"))
            .as("Traefik must route to Dex on its HTTP port 5556").isEqualTo("5556");
    }

    @Test
    @SuppressWarnings("unchecked")
    void dexInit_isMandatoryInfrastructure_notBehindAProfile() throws Exception {
        // dex-init renders Dex's config (mirrors oauth2-proxy-init). It must always run so Dex has
        // a config on every start — no profile gate.
        Map<String, Object> compose = (Map<String, Object>) new Yaml()
            .load(Files.readString(Path.of("docker-compose.yml")));
        Map<String, Object> services = (Map<String, Object>) compose.get("services");
        Map<String, Object> dexInit = (Map<String, Object>) services.get("dex-init");

        assertThat(dexInit).as("dex-init service must exist").isNotNull();
        assertThat(dexInit).as("dex-init must always run — no profile gate").doesNotContainKey("profiles");
    }

    @Test
    @SuppressWarnings("unchecked")
    void oauth2ProxyAlphaRender_pointsAtTheDexIssuer_notGoogleDirect() throws Exception {
        // oauth2-proxy no longer talks to Google directly — it federates through Dex via a generic
        // OIDC provider. The rendered alpha.yaml lives in the gitignored runtime dir, so the
        // committed source of truth is the heredoc oauth2-proxy-init renders it from.
        Map<String, Object> compose = (Map<String, Object>) new Yaml()
            .load(Files.readString(Path.of("docker-compose.yml")));
        Map<String, Object> services = (Map<String, Object>) compose.get("services");
        Map<String, Object> init = (Map<String, Object>) services.get("oauth2-proxy-init");
        String render = String.join("\n", (List<String>) init.get("command"));

        assertThat(render).as("provider must be generic oidc, brokered by Dex").contains("provider: oidc");
        assertThat(render).as("issuer must be Dex").contains("issuerURL: https://dex.$${VAIER_DOMAIN}");
        assertThat(render).as("must no longer talk to Google directly").doesNotContain("provider: google");
    }

    @Test
    @SuppressWarnings("unchecked")
    void oauth2ProxyRender_allowListsConnectorIdSoTheSelectorJumpsStraightToTheProvider() throws Exception {
        // The two sign-in buttons pass connector_id=google|github. oauth2-proxy only forwards a
        // user-supplied login param when it matches an `allow` rule — without it, Dex would show its
        // own second chooser instead of jumping straight to the picked provider. Guard the allow-list.
        Map<String, Object> compose = (Map<String, Object>) new Yaml()
            .load(Files.readString(Path.of("docker-compose.yml")));
        Map<String, Object> services = (Map<String, Object>) compose.get("services");
        Map<String, Object> init = (Map<String, Object>) services.get("oauth2-proxy-init");
        String render = String.join("\n", (List<String>) init.get("command"));

        assertThat(render).as("connector_id must be an allow-listed login param")
            .contains("name: connector_id, allow:");
        assertThat(render).contains("value: google").contains("value: github");
    }

    @Test
    @SuppressWarnings("unchecked")
    void dexRender_inlinesAllSecrets_becauseDexHasNoFileBasedSecretOption() throws Exception {
        // Dex honours clientSecretFile on only a handful of connectors — NOT the google/github/oidc
        // ones — and staticClients have no file option at all. Referencing a file silently yields an
        // empty secret ("client_secret is missing"). So all three secrets render inline into the
        // 0600, dex-owned, gitignored config.yaml. Guard against a regression back to file refs.
        Map<String, Object> compose = (Map<String, Object>) new Yaml()
            .load(Files.readString(Path.of("docker-compose.yml")));
        Map<String, Object> services = (Map<String, Object>) compose.get("services");
        Map<String, Object> dexInit = (Map<String, Object>) services.get("dex-init");
        String render = String.join("\n", (List<String>) dexInit.get("command"));

        assertThat(render).as("static client secret inlined").contains("secret: $${VAIER_DEX_CLIENT_SECRET}");
        assertThat(render).as("google connector secret inlined").contains("clientSecret: $${VAIER_OIDC_GOOGLE_CLIENT_SECRET}");
        assertThat(render).as("github connector secret inlined").contains("clientSecret: $${VAIER_OIDC_GITHUB_CLIENT_SECRET}");
        assertThat(render).as("Dex connectors/clients cannot read a secret from a file")
            .doesNotContain("clientSecretFile").doesNotContain("secretFile");
    }

    // --- #332: each identity provider is independently optional, not both-mandatory ---

    private record DexInitResult(int exitCode, String stdout, String stderr) {}

    @SuppressWarnings("unchecked")
    private String dexInitScript() throws Exception {
        Map<String, Object> compose = (Map<String, Object>) new Yaml()
            .load(Files.readString(Path.of("docker-compose.yml")));
        Map<String, Object> services = (Map<String, Object>) compose.get("services");
        Map<String, Object> dexInit = (Map<String, Object>) services.get("dex-init");
        List<String> command = (List<String>) dexInit.get("command");
        // command is ["sh", "-c", "<script>"] — the script itself is the last element.
        return command.get(command.size() - 1);
    }

    // Runs the ACTUAL rendered dex-init script under sh, so these tests pin real shell behaviour
    // rather than a regex over the YAML. Two test-only substitutions make that practical without
    // requiring root in the test JVM: /dex/config is redirected to a temp dir (the real path is
    // root-owned), and `chown` is stubbed to a no-op via a PATH-prepended shim (unprivileged users
    // cannot chown to an arbitrary uid). Neither substitution touches the script's own logic.
    private DexInitResult runDexInit(Path tempDir, Map<String, String> providerEnv) throws Exception {
        // docker-compose itself collapses the $${...} escaping to a single $ before the shell ever
        // sees the command — we bypass compose entirely here, so that collapse has to happen in the
        // test too, or the shell reads a literal "$$" as its own PID special parameter.
        String script = dexInitScript()
            .replace("$$", "$")
            .replace("/dex/config", tempDir.toString());

        Path stubBin = Files.createDirectories(tempDir.resolve("stub-bin"));
        Path chownStub = stubBin.resolve("chown");
        Files.writeString(chownStub, "#!/bin/sh\nexit 0\n");
        chownStub.toFile().setExecutable(true);

        ProcessBuilder builder = new ProcessBuilder("sh", "-c", script);
        Map<String, String> env = builder.environment();
        env.put("PATH", stubBin + File.pathSeparator + env.get("PATH"));
        env.put("VAIER_DOMAIN", "example.com");
        env.put("VAIER_DEX_CLIENT_SECRET", providerEnv.getOrDefault("VAIER_DEX_CLIENT_SECRET", "dex-shared-secret"));
        env.put("VAIER_OIDC_GOOGLE_CLIENT_ID", providerEnv.getOrDefault("VAIER_OIDC_GOOGLE_CLIENT_ID", ""));
        env.put("VAIER_OIDC_GOOGLE_CLIENT_SECRET", providerEnv.getOrDefault("VAIER_OIDC_GOOGLE_CLIENT_SECRET", ""));
        env.put("VAIER_OIDC_GITHUB_CLIENT_ID", providerEnv.getOrDefault("VAIER_OIDC_GITHUB_CLIENT_ID", ""));
        env.put("VAIER_OIDC_GITHUB_CLIENT_SECRET", providerEnv.getOrDefault("VAIER_OIDC_GITHUB_CLIENT_SECRET", ""));

        Process process = builder.start();
        boolean finished = process.waitFor(10, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new IllegalStateException("dex-init script did not finish within 10s");
        }
        String stdout = new String(process.getInputStream().readAllBytes());
        String stderr = new String(process.getErrorStream().readAllBytes());
        return new DexInitResult(process.exitValue(), stdout, stderr);
    }

    @Test
    @SuppressWarnings("unchecked")
    void dexInit_emitsOnlyTheGoogleConnector_whenOnlyGoogleCredentialsAreSet(@TempDir Path tempDir) throws Exception {
        DexInitResult result = runDexInit(tempDir, Map.of(
            "VAIER_OIDC_GOOGLE_CLIENT_ID", "google-id",
            "VAIER_OIDC_GOOGLE_CLIENT_SECRET", "google-secret"));

        assertThat(result.exitCode()).as("stderr: %s", result.stderr()).isEqualTo(0);
        String rendered = Files.readString(tempDir.resolve("config.yaml"));
        Map<String, Object> configYaml = (Map<String, Object>) new Yaml().load(rendered);
        List<Map<String, Object>> connectors = (List<Map<String, Object>>) configYaml.get("connectors");

        assertThat(connectors).hasSize(1);
        assertThat(connectors.get(0).get("type")).isEqualTo("google");
    }

    @Test
    @SuppressWarnings("unchecked")
    void dexInit_emitsOnlyTheGithubConnector_whenOnlyGithubCredentialsAreSet(@TempDir Path tempDir) throws Exception {
        DexInitResult result = runDexInit(tempDir, Map.of(
            "VAIER_OIDC_GITHUB_CLIENT_ID", "github-id",
            "VAIER_OIDC_GITHUB_CLIENT_SECRET", "github-secret"));

        assertThat(result.exitCode()).as("stderr: %s", result.stderr()).isEqualTo(0);
        String rendered = Files.readString(tempDir.resolve("config.yaml"));
        Map<String, Object> configYaml = (Map<String, Object>) new Yaml().load(rendered);
        List<Map<String, Object>> connectors = (List<Map<String, Object>>) configYaml.get("connectors");

        assertThat(connectors).hasSize(1);
        assertThat(connectors.get(0).get("type")).isEqualTo("github");
    }

    @Test
    @SuppressWarnings("unchecked")
    void dexInit_emitsBothConnectors_whenBothProvidersAreSet(@TempDir Path tempDir) throws Exception {
        DexInitResult result = runDexInit(tempDir, Map.of(
            "VAIER_OIDC_GOOGLE_CLIENT_ID", "google-id",
            "VAIER_OIDC_GOOGLE_CLIENT_SECRET", "google-secret",
            "VAIER_OIDC_GITHUB_CLIENT_ID", "github-id",
            "VAIER_OIDC_GITHUB_CLIENT_SECRET", "github-secret"));

        assertThat(result.exitCode()).as("stderr: %s", result.stderr()).isEqualTo(0);
        String rendered = Files.readString(tempDir.resolve("config.yaml"));
        Map<String, Object> configYaml = (Map<String, Object>) new Yaml().load(rendered);
        List<Map<String, Object>> connectors = (List<Map<String, Object>>) configYaml.get("connectors");

        assertThat(connectors).hasSize(2);
        assertThat(connectors.stream().map(c -> c.get("type"))).containsExactlyInAnyOrder("google", "github");
    }

    @Test
    void dexInit_failsFastNamingTheMissingVariables_whenNoProviderIsConfigured(@TempDir Path tempDir) throws Exception {
        DexInitResult result = runDexInit(tempDir, Map.of());

        assertThat(result.exitCode()).as("must fail fast, never render two empty connectors").isNotEqualTo(0);
        assertThat(result.stderr())
            .contains("VAIER_OIDC_GOOGLE_CLIENT_ID")
            .contains("VAIER_OIDC_GOOGLE_CLIENT_SECRET")
            .contains("VAIER_OIDC_GITHUB_CLIENT_ID")
            .contains("VAIER_OIDC_GITHUB_CLIENT_SECRET");
        assertThat(Files.exists(tempDir.resolve("config.yaml")))
            .as("must never write a config with two empty connectors").isFalse();
    }

    @Test
    void dexInit_failsFast_whenDexClientSecretIsBlank(@TempDir Path tempDir) throws Exception {
        Map<String, String> providerEnv = new LinkedHashMap<>();
        providerEnv.put("VAIER_DEX_CLIENT_SECRET", "");
        providerEnv.put("VAIER_OIDC_GOOGLE_CLIENT_ID", "google-id");
        providerEnv.put("VAIER_OIDC_GOOGLE_CLIENT_SECRET", "google-secret");

        DexInitResult result = runDexInit(tempDir, providerEnv);

        assertThat(result.exitCode())
            .as("a blank shared secret must fail fast, never crash-loop Dex behind the login wall")
            .isNotEqualTo(0);
        assertThat(result.stderr()).contains("VAIER_DEX_CLIENT_SECRET");
        assertThat(Files.exists(tempDir.resolve("config.yaml"))).isFalse();
    }

    @Test
    @SuppressWarnings("unchecked")
    void oauth2ProxyRender_extractsFederatedClaimsLeavesSoTheProviderHeadersPopulate() throws Exception {
        // oauth2-proxy only injects a claim into a header if it is first extracted into the session
        // via additionalClaims, stored under the FULL dotted key; the injection then does a flat
        // lookup of that same key. Without the two federated_claims leaves here, X-Auth-Request-
        // Connector[-Uid] ship empty and the Users provider badge + photo never populate. The strings
        // in additionalClaims must be byte-identical to the claimSource.claim values.
        Map<String, Object> compose = (Map<String, Object>) new Yaml()
            .load(Files.readString(Path.of("docker-compose.yml")));
        Map<String, Object> services = (Map<String, Object>) compose.get("services");
        Map<String, Object> init = (Map<String, Object>) services.get("oauth2-proxy-init");
        String render = String.join("\n", (List<String>) init.get("command"));

        assertThat(render).as("federated:id scope is the Dex-side prerequisite for federated_claims")
            .contains("scope: openid email profile federated:id");
        assertThat(render).as("both federated_claims leaves must be extracted for injection")
            .contains("additionalClaims: [name, federated_claims.connector_id, federated_claims.user_id]");
        assertThat(render).as("connector header injected from the connector_id leaf")
            .contains("X-Auth-Request-Connector, values: [{claimSource: {claim: federated_claims.connector_id}}]");
        assertThat(render).as("connector uid header injected from the user_id leaf")
            .contains("X-Auth-Request-Connector-Uid, values: [{claimSource: {claim: federated_claims.user_id}}]");
    }

    @Test
    @SuppressWarnings("unchecked")
    void httpEntrypointRedirectsToHttps_soBareHostnameVisitsDoNotHitABare404() throws Exception {
        // Every Vaier router is bound to the `websecure` (:443) entrypoint only. A browser given a
        // schemeless hostname (`vaier.example.com`) requests `http://` on :80 — which matches no
        // router and gets Traefik's default "404 page not found". The `web` entrypoint must globally
        // redirect to `websecure`. This coexists with the Let's Encrypt HTTP-01 challenge that also
        // lives on `web`: Traefik serves the ACME challenge at higher priority than the redirect.
        Map<String, Object> compose = (Map<String, Object>) new Yaml()
            .load(Files.readString(Path.of("docker-compose.yml")));
        Map<String, Object> services = (Map<String, Object>) compose.get("services");
        Map<String, Object> traefik = (Map<String, Object>) services.get("traefik");
        List<String> command = (List<String>) traefik.get("command");

        assertThat(command)
            .as("http://<host> must 308 to https, not fall through to Traefik's default 404")
            .contains("--entrypoints.web.http.redirections.entrypoint.to=websecure")
            .contains("--entrypoints.web.http.redirections.entrypoint.scheme=https");

        // The ACME HTTP-01 challenge must still run on `web` — the redirect doesn't replace it.
        assertThat(command).contains("--certificatesresolvers.letsencrypt.acme.httpchallenge.entrypoint=web");
    }

    @Test
    @SuppressWarnings("unchecked")
    void wireguardMasquerade_usesInterfaceNameAgnosticRuleForVpnEgress() throws Exception {
        // The linuxserver/wireguard wg0.conf PostUp masquerades only on `-o eth+`. On a
        // Vaier server whose primary NIC is not named eth* (e.g. AWS EC2's ens5, or when
        // wireguard runs with host networking) that rule is a silent no-op, so traffic
        // from a LAN behind a server peer that egresses a non-WG interface keeps its
        // original source and the destination has no return route — it drops. #248.
        //
        // The wireguard-masquerade sidecar must therefore install a name-agnostic rule
        // that masquerades anything leaving a non-wg0 interface (`! -o wg0`), regardless
        // of the kernel's name for that interface.
        Map<String, Object> compose = (Map<String, Object>) new Yaml()
            .load(Files.readString(Path.of("docker-compose.yml")));
        Map<String, Object> services = (Map<String, Object>) compose.get("services");
        Map<String, Object> masquerade = (Map<String, Object>) services.get("wireguard-masquerade");
        List<String> entrypoint = (List<String>) masquerade.get("entrypoint");
        String script = String.join("\n", entrypoint);

        assertThat(script)
            .as("masquerade sidecar must NAT VPN egress by NOT matching wg0, not by guessing the NIC name")
            .contains("! -o wg0 -j MASQUERADE");
    }

    @Test
    @SuppressWarnings("unchecked")
    void traefikEntrypoint_waitsForInfraDnsToResolvePublicly_beforeExecSoTheFirstAcmeAttemptDoesNotBurnLeQuota() throws Exception {
        // Fresh-install race. In auto-DNS mode Vaier creates the vaier/oauth2/dex Route53 records at boot,
        // but Traefik — started by the SAME `up` — would ask Let's Encrypt for certs before those names
        // resolve. LE's validator then gets NXDOMAIN, and Traefik's tight retry burst trips LE's "5 failed
        // authorizations per hostname per hour" limit: issuance locks out for an hour and the stack sits on
        // Traefik's self-signed default cert (browsers show ERR_CERT_AUTHORITY_INVALID).
        //
        // The fix lives in Traefik's OWN entrypoint: it holds until the three infra names resolve on a
        // PUBLIC resolver (the class LE queries — not the container's split-horizon/VPC view) BEFORE it
        // execs traefik, and thus before any ACME. It deliberately is NOT a separate gate service that
        // traefik depends_on: `vaier` depends_on `traefik: service_started`, which fires the instant this
        // container starts — so Vaier creates the records WHILE the entrypoint waits here. A completion-
        // gated sidecar would instead deadlock (vaier waits for traefik waits for the gate waits for the
        // records vaier never got to create). Fail-open on a timeout so broken DNS never leaves the box
        // without a reverse proxy forever.
        Map<String, Object> compose = (Map<String, Object>) new Yaml()
            .load(Files.readString(Path.of("docker-compose.yml")));
        Map<String, Object> services = (Map<String, Object>) compose.get("services");
        Map<String, Object> traefik = (Map<String, Object>) services.get("traefik");

        List<String> entrypoint = (List<String>) traefik.get("entrypoint");
        String script = String.join("\n", entrypoint);

        // Waits on all three infra hostnames, built from the base domain...
        assertThat(script)
            .as("entrypoint must wait on the three infra hostnames")
            .contains("vaier oauth2 dex");
        // ...against a PUBLIC resolver, so the wait matches Let's Encrypt's view, not the split-horizon VPC one.
        assertThat(script)
            .as("must query a public resolver, not the container's local/VPC resolver LE never sees")
            .contains("1.1.1.1");
        // ...and the wait must run BEFORE `exec traefik` — otherwise ACME still fires against dead DNS.
        assertThat(script.indexOf("1.1.1.1"))
            .as("the DNS wait must precede `exec traefik`, or Traefik asks ACME before the wait")
            .isGreaterThanOrEqualTo(0)
            .isLessThan(script.indexOf("exec traefik"));
        // ...and fail-open so a genuinely broken DNS never leaves the box permanently proxy-less.
        assertThat(script.toLowerCase())
            .as("the wait must fail-open on a timeout")
            .contains("timed out");

        // The entrypoint needs the base domain to build the hostnames it waits on.
        Map<String, Object> env = (Map<String, Object>) traefik.get("environment");
        assertThat(env)
            .as("traefik needs VAIER_DOMAIN in its environment to build the infra hostnames")
            .containsKey("VAIER_DOMAIN");
    }

    @Test
    @SuppressWarnings("unchecked")
    void traefikCarriesInternalAliasesForInfraHostnames_soServiceToServiceCallsSkipPublicDnsAndItsNegativeCache() throws Exception {
        // oauth2-proxy (and Vaier) run OIDC discovery against https://dex.<domain> BY ITS PUBLIC NAME — the
        // issuer must match, so an internal http://dex:5556 shortcut is not an option. On a fresh install the
        // Route53 records don't exist until Vaier boots, so a container that resolves dex.<domain> too early
        // gets NXDOMAIN and poisons its resolver's NEGATIVE cache (Route53 SOA negative TTL ~15 min) — which
        // crash-looped oauth2-proxy on "no such host" long after the record existed. Aliasing each infra
        // hostname onto Traefik makes Docker's embedded DNS answer them from its own registry (straight to
        // Traefik) before ever forwarding externally: no public DNS, no negative cache. Traefik then
        // terminates TLS with the real cert and routes on. This is the internal-resolution complement to the
        // entrypoint's public-DNS ACME wait.
        Map<String, Object> compose = (Map<String, Object>) new Yaml()
            .load(Files.readString(Path.of("docker-compose.yml")));
        Map<String, Object> services = (Map<String, Object>) compose.get("services");
        Map<String, Object> traefik = (Map<String, Object>) services.get("traefik");

        Object networks = traefik.get("networks");
        assertThat(networks)
            .as("traefik must join vaier-network in long form so it can carry aliases")
            .isInstanceOf(Map.class);
        Map<String, Object> vaierNet = (Map<String, Object>) ((Map<String, Object>) networks).get("vaier-network");
        assertThat(vaierNet).as("traefik must still be on vaier-network").isNotNull();

        List<String> aliases = (List<String>) vaierNet.get("aliases");
        assertThat(aliases)
            .as("the three infra hostnames must resolve to Traefik from inside the stack")
            .contains("vaier.${VAIER_DOMAIN}", "oauth2.${VAIER_DOMAIN}", "dex.${VAIER_DOMAIN}");
    }

}
