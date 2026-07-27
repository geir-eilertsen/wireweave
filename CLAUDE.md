# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project goals

I am basically tired of maintaining a VPN server with reverse proxy pointing to all my docker hosts with containers on different ports. This project will make it very easy to
- **Maintain a VPN server** with WireGuard and Traefik
- **Create and maintain VPN clients** by providing docker compose files and other client config that can be used to connect to the VPN server
- **Create a reverse proxy** with Let's Encrypt and Traefik
- **Create DNS records** with AWS Route53
- **Manage DNS records** with AWS Route53
- **Manage containers remotely** with Docker
- **Manage users** with Google social login (oauth2-proxy) and Vaier's own access store
- **Web interface for managing everything** with Vaier
- **Self-generated dashboard for linking to all my services** with Vaier

## Architecture

**Hexagonal architecture** (Ports & Adapters) with four layers:

- **Domain** (`domain/`) — Business logic, entities, and port interfaces. No Spring dependencies.
- **Application** (`application/`) — Use case interfaces and service implementations that orchestrate domain logic.
- **Infrastructure** (`adapter/driven/`) — Adapter implementations for external systems (AWS Route53, Docker API, WireGuard, Traefik, oauth2-proxy).
- **Web** (`rest/`) — REST controllers. DTOs are defined as inner Java `record` classes within controllers.

### Naming Conventions

| Pattern | Example |
|---------|---------|
| Port interfaces | `For*` (e.g., `ForGettingVpnClients`, `ForPersistingDnsRecords`) |
| Use case interfaces | `*UseCase` — one per use case, narrow (e.g., `CreatePeerUseCase`, `DeletePeerUseCase`) |
| Service implementations | `*Service` — **one per domain concept**, implements many `*UseCase` interfaces (e.g., `VpnService`, `UserService`, `PublishingService`) |
| Adapters | `*Adapter` (e.g., `Route53DnsAdapter`, `WireGuardVpnAdapter`) |

### One service per domain, not per use case

Keep `*UseCase` interfaces narrow and one-per-use-case — they are the ports controllers depend on, and narrow interfaces keep controller tests small. But group their **implementations** by domain concept: `VpnService`, `UserService`, `DnsService`, `ReverseProxyService`, `ContainerService`, `SettingsService`, `PublishingService`. One `@Service` class implements every use case in its domain.

When adding a new use case, do NOT create a new `*Service` class unless the use case belongs to a genuinely new domain. Add the method to the existing domain service.

Cross-domain orchestration (e.g., `VpnService.deletePeer` cascading into `PublishingService.deleteService` when a peer with published services is removed) must go via the `*UseCase` interface, never a direct class-to-class dependency. This preserves the hex boundary and avoids circular dependencies.

**Cross-domain *reads* are different from cross-domain *writes*, and the old advice here was wrong.** For years this file said "for a cross-domain query, add a driven port" — and that produced a pervasive bug: the domain that *owns* the data implemented the `For*` port itself (`MachineService implements ForGettingMachines`, `ContainerService implements ForDiscoveringPeerContainers`, …). **A `*Service` must never implement a driven `For*` port.** Driven ports are outbound boundaries — only an `*Adapter` in `adapter/driven/` sits on the implementing side. A service implementing one is a service-to-service dependency in a port costume: the coupling the "services never call use cases" rule forbids, just relabeled, and it makes Spring **bean cycles** possible (the cycle is the tell). Provide a cross-domain read one of two ways instead:
- **Driving-edge composition** (preferred when the consumer is a controller): the controller injects the several `*UseCase`s it needs and hands their results to a **pure-domain assembler** that owns the decision.
- **A real adapter** (when the consumer is another service or the domain): move the port's implementation into an `*Adapter` in `adapter/driven/` that composes the lower-level ports; the owning service and every other consumer then *inject* the port. The adapter may call domain factories to build the read model — the decisions stay in the domain.

This is stricter than the write-orchestration exception above (which is about *injecting* a `*UseCase` for a genuine cascade, and does not license a service to *implement* a driven port). See `hexagonal-architecture` skill rule 3 and hex-checker rule 9.

### No Database

All state is file-based (WireGuard/Traefik YAML configs, the `access.yml` social-login store), cloud-based (Route53), or ephemeral (oauth2-proxy's own signed cookie session). No SQL database or ORM.

### Strict layer isolation

Application services must never import from an unrelated use case interface just to share a constant or utility. If two unrelated services happen to need the same string value (e.g. a container name and a subdomain that are both "vaier"), keep them as separate literals in their own contexts — forced sharing via an unrelated interface creates spaghetti dependencies that violate the hexagonal architecture. Only introduce shared constants when the concepts are genuinely the same and the coupling is intentional.

## Use Lombok, never hand-written getters/setters

Project Lombok is on the classpath — use `@Data`, `@Builder`, etc. Never hand-write a getter, setter,
`equals`, `hashCode`, or `toString`.

## Import types, never write them fully qualified

Every type reference in Java source is an unqualified name backed by an `import`. Never write
`net.vaier.domain.MachineId machineId`, `java.util.Optional<String>`, or
`org.mockito.Mockito.mock(...)` inline — **including in tests**, and including a type used exactly once.

A fully-qualified name inline is almost always a shortcut taken by whoever was editing: it lets a
mechanical find-and-replace land without also touching the import block. The cost lands on every later
reader, and it makes the import block stop being a truthful summary of what a file depends on — which is
the first thing you read when judging whether a class sits on the right side of a port.

A handful of pre-existing inline FQNs remain in the codebase (e.g. `TerminalService`,
`MachineSshTargetAdapter`). They are debt, not precedent: convert them when you are already editing that
line, and never add new ones.

Two narrow exceptions: an unavoidable name collision between two imported types, and a fully-qualified
name inside a Javadoc `{@link}` where the type is not otherwise imported.

## Docker Stack

Authelia and its Redis session store were decommissioned (#305) in favour of Google social login via oauth2-proxy. Do not reintroduce them.

### Sub-image version pinning

All upstream images in `docker-compose.yml` are pinned to specific versions — **no floating `:latest` tags**. To change one, use the `bump-subimage` skill: bumping is an ask-the-dev-first workflow with its own version-cut and drift-check rules.

## Required Environment Variables

| Variable | Description |
|----------|-------------|
| `VAIER_AWS_KEY` | AWS access key for Route53 |
| `VAIER_AWS_SECRET` | AWS secret key for Route53 |
| `VAIER_DOMAIN` | Base domain name |
| `ACME_EMAIL` | Let's Encrypt email |

## Test-driven development

This project follows strict TDD. Always write a failing test before writing any implementation code:

1. Write a test that captures the expected behaviour — it must fail before any implementation exists
2. Write the minimum implementation to make the test pass
3. Refactor if needed, keeping tests green

Never write implementation code without a corresponding test written first. PRs that add features without prior failing tests are not acceptable.

## Keeping docs in sync

After any change to the feature set — new features, changed behaviour, removed functionality, renamed concepts — update `README.md`, `PRD.md`, and `UBIQUITOUS_LANGUAGE.md` before committing:

- **README.md** — user-facing; update feature tables, workflow descriptions, and any affected quick-start steps
- **PRD.md** — planning document; mark implemented items ✅, update planned items, and add backlog entries for anything new that was discussed
- **UBIQUITOUS_LANGUAGE.md** — vocabulary; add new terms, update definitions when behaviour changes, retire terms that no longer apply

All three documents must always reflect the actual state of the codebase. Stale docs are treated as bugs.

## Ubiquitous language is authoritative

`UBIQUITOUS_LANGUAGE.md` is the source of truth for vocabulary in this project. Apply it like a rule, not a reference:

- **Before introducing a new term** (in code, commits, issues, PRs, UI copy, conversation), check `UBIQUITOUS_LANGUAGE.md`. If a term already exists for the concept, use it exactly — don't invent a synonym.
- **Before adding a new concept**, decide its canonical name *first* and add an entry to `UBIQUITOUS_LANGUAGE.md` in the same change. The name lands in the document and the code together.
- **When the codebase and this document disagree**, the codebase wins and the document gets updated.
- Watch especially for near-synonyms (e.g. "host" vs "machine", "client" vs "peer", "subdomain" vs "service"). Pick one, retire the other.

## After changing code

After any code change, build and deploy to the local Docker Compose stack using the `deploy-vaier`
skill (it carries the exact build tag and the gotchas). Then ask the user to verify the fix works.

If the user confirms the fix is good:
1. Commit the changes to git.
2. If the change was triggered by a GitHub issue, include `Closes #<issue-number>` in the commit message — GitHub will close the issue automatically when pushed to main.
3. **Do NOT push** — only commit locally. The user will push when ready.
