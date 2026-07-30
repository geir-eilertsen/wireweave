# Authentication and access

Back to [README](../README.md).

How sign-in is set up, how roles and per-service access rules work, and how the Users page manages identities.

---

## Setting up sign-in (Google and/or GitHub)

Vaier delegates authentication to Google and/or GitHub and owns authorization itself. oauth2-proxy (mandatory infrastructure — it always starts with the stack) is the forward-auth gatekeeper; behind it, the **Dex** identity broker federates whichever provider(s) you configure:

```
Traefik → oauth2-proxy → Dex ─┬→ Google
                              └→ GitHub
```

Each provider is independently optional — configure Google, GitHub, or both. `dex-init` only adds a connector for a provider once both its client id and its client secret are set; **at least one provider is required**, or the stack refuses to start Dex (rather than starting it with no way to sign in). With only one provider configured, Dex skips its connector-selection screen and sign-in goes straight there.

Configure the providers you want — at least one. The sign-in page offers a button per configured provider, so an install with only Google credentials never shows a GitHub button. Both providers hand the user back to **Dex** (not oauth2-proxy), so register their redirect URIs at Dex:

- **Google** — create an OAuth 2.0 Web application client in the [Google Cloud console](https://console.cloud.google.com/apis/credentials), set its authorized redirect URI to `https://dex.yourdomain.com/callback`, and put the client id and secret in `.env` as `VAIER_OIDC_GOOGLE_CLIENT_ID` / `VAIER_OIDC_GOOGLE_CLIENT_SECRET`.
- **GitHub** — register an OAuth App in [GitHub developer settings](https://github.com/settings/developers), set its authorization callback URL to `https://dex.yourdomain.com/callback`, and put the client id and secret in `.env` as `VAIER_OIDC_GITHUB_CLIENT_ID` / `VAIER_OIDC_GITHUB_CLIENT_SECRET`. Any GitHub account may sign in — Vaier's pending → admin-approval gate decides who's actually let in.

Set `VAIER_ADMIN_EMAIL` to the email that should become the first admin. The oauth2-proxy session cookie secret (`VAIER_OAUTH2_COOKIE_SECRET`) and the oauth2-proxy↔Dex shared secret (`VAIER_DEX_CLIENT_SECRET`) are **generated for you by `install.sh`** into `.env` — you don't author them. If you ever hand-write `.env` without them, generate them yourself, or Dex won't start:

```bash
printf 'VAIER_DEX_CLIENT_SECRET=%s\nVAIER_OAUTH2_COOKIE_SECRET=%s\n' \
  "$(openssl rand -hex 32)" "$(openssl rand -base64 32)" >> .env
```

Once `docker compose ps` shows every service as `Up`, open `https://vaier.yourdomain.com` and sign in with the account you set as `VAIER_ADMIN_EMAIL`. Vaier seeds that identity as the first admin, so you land straight in the console.

Anyone else who signs in for the first time is recorded as a **pending** access request — authenticated but blocked until you approve them on the **Users** page. Promote them to **user** (or **admin**) there.

The oauth2-proxy sign-in and error pages — and the Dex broker's own screens — all share Vaier's dark theme, so the sign-in hand-off (Google or GitHub) feels seamless end to end.

---

## Access management

Manage who can sign in from the **Users** page: each Google or GitHub identity is an access entry with a **role** (pending → user → admin) and free-form per-service **access groups**. Approve or deny newcomers, promote admins, and gate individual services by group. Each person's card shows their provider photo (GitHub picture, else Gravatar, else a coloured monogram) with a small corner glyph for the identity provider (Google or GitHub) they last signed in with.

When someone signs in for the first time, Vaier records them as a **pending** access request (authenticated but blocked) and denies access until an admin approves them. The moment that pending entry is created, Vaier emails every admin so the request doesn't sit unseen — the mail names the email and links straight to the **Users** page to approve or deny. It reuses the same SMTP configuration as the other alerts, so with SMTP unconfigured (or no admins to notify) it stays silent, and the send is fire-and-forget so it never slows the sign-in check.

Admin-vs-user is decided **only by the role** (pending → user → admin) — promote an entry with the role control. **Access groups** are a separate, per-service concept: free-form tags (e.g. `devs`, `family`) that gate individual services. Each Social service can carry an **access rule** — a set of *allowed groups* — and a user reaches the service if their entry holds **at least one** of them (any-of). Admins reach everything; pending identities reach nothing. The names `admins` and `users` are never access groups; the group picker won't suggest or accept them.

The console is admin-only, so Vaier keeps a **last-admin protection** invariant: the access store always holds at least one admin. Revoking or demoting the sole remaining admin is refused (the Access page disables those controls with an inline note, and the API answers `409 Conflict`), and on startup the configured administrator (`VAIER_ADMIN_EMAIL`) is restored to admin whenever no admin exists — promoting an existing entry in place or creating one — so the console can never be locked out for everyone.

Vaier also captures each identity's Google **display name** (the provider's `name` claim, forwarded by oauth2-proxy) and shows it on the **Users** page with the email beneath it — so an admin recognises who's asking by name, not just by address. A pre-approved entry stays nameless until its first sign-in fills the name in; later sign-ins keep it current, and it's never wiped if a sign-in arrives without one. The same captured name follows the identity into the Vaier console — which always runs on Social login — greeting them in the topbar with their provider photo when one is available (the same GitHub-picture-else-Gravatar chain as the Users cards), falling back to their name text (or email until a name is known) when no photo loads.

The **Users** page is this single list of social identities. Vaier no longer manages local password accounts and has no self-service profile page — each identity's name and email are owned by Google and shown read-only; only the role and access groups are edited here.

Social login is the sole runtime auth gateway: **Authelia has been fully removed** — both the running service and the last of its Java code — and every gated service authenticates via Google or GitHub. There is no `authelia` auth mode; the two modes are Public and Social.

---

## Per-service auth mode

Each published service card carries an **auth mode** picker — **Public** (no sign-in) or **Social** (Google or GitHub sign-in via oauth2-proxy, with Vaier deciding who's approved). Change it any time; the change rewrites only that route's Traefik middleware chain.

## Per-service access rules

For a **Social** published service you can restrict *which* signed-in users get through. Open the published service's entry in the **Explorer** and use the **Allowed groups** chip picker to name the groups allowed to reach it. Suggestions come from the groups already assigned to your access entries, and you can free-type a new group name. Leave it empty and any signed-in, approved user can reach the service; add one or more groups and only users holding at least one of them (plus every admin) get in. A service with a non-empty rule shows a **restricted** badge so you can see at a glance it isn't open to every approved user. Rules apply only in Social auth mode — switch a service to Public and the control disappears.

Rules are keyed by the service's host, so path-scoped services that share one subdomain currently share a single rule (a known limitation for now).
