# Monitoring and alerts

Back to [README](../README.md).

Disk-pressure watching and its early-warning forecast, container image drift detection, and the email notifications that tie them together.

---

## Email notifications

SMTP-powered admin alerts when any server-type machine (VPN server peers and LAN servers) goes up or down, when a filesystem on any SSH-reachable machine behind the VPN — including the Vaier host itself — fills past its threshold, when a container's image newly has an **update available**, or when someone signs in for the first time and lands as a pending access request awaiting approval.

---

## Host disk monitoring

Vaier watches disk usage on every SSH-capable machine it holds a credential for — every machine behind the VPN, and the Vaier host itself, watched over SSH-to-self exactly like any other machine — running `df` over SSH on a periodic cadence and emailing every admin user when a **filesystem** fills past its threshold. A **recovery** email follows once it drops back below, and Vaier only emails on a boundary crossing (not on every poll), so a filesystem hovering just over the line won't spam you.

**Every filesystem, not just the root one.** A machine's disks are read whole: `df` reports each mounted filesystem and Vaier watches all of them. This matters more than it sounds — on a Synology NAS, `/` is a fixed-size ~2 GB DSM system partition that is 88% full by design and never moves, while `/volume1` is the 12 TB volume holding every backup. Watching only `/` means watching the one filesystem that can never tell you anything, while the one that matters fills to 100% in silence. Kernel and in-memory mounts (tmpfs, proc, sysfs, cgroup, squashfs, overlay…) and the bind-mount aliases a Docker storage driver leaves behind are skipped — they aren't disks, and reporting a volume nine times would let it raise nine alerts.

**Readings carry size, not just a percentage.** Alerts read *"[Vaier] NAS /volume1 is at 91% full (10.8 TiB, 1.0 TiB free)"* — the mount, the size and the free space, in the same binary units `df -h` prints. "NAS is at 88%" was a number nobody could act on.

This reuses the same SMTP configuration as the up/down machine alerts (Settings → *Email notifications*), so it needs no extra mail setup. With SMTP unconfigured, monitoring is silent.

**Threshold** — the alert fires when usage rises above the configured percentage (default **85%**). Adjust it in Settings; valid range is 1–99. This is the **fleet-wide fallback**: it governs every filesystem that hasn't been given one of its own.

**Watching and muting a filesystem** — no single rule fits a whole fleet, so each filesystem on each machine carries its own **watch**, set from its machine's **disk** entry in the **Explorer**: leave it watched at the fleet-wide threshold, give it a threshold of its own (`/` on the NAS is fine at 95%), or **mute** it entirely (a system partition that's near-full by design). The default is **watched, at the fleet-wide threshold** — nothing is ever silently unwatched, so a new volume that appears on a machine nags rather than hides, and muting is always something you chose. Only your exceptions are stored (in `vaier/config/disk-watches.yml`); no file means every filesystem is watched at the fleet-wide threshold. The Explorer and the alert email ask the same question of the same code, so they can never disagree about whether a disk is in trouble.

**Requirements** — a machine is watched only once it has a stored **host credential** (the same vault the web terminal uses) and SSH access enabled. A host that's unreachable or whose `df` fails is quietly skipped — never mistaken for a full disk. Machines without a stored credential or with SSH access turned off are left alone, so there's no failed-auth noise. To have the Vaier host itself watched, store a host credential for it just like any other machine.

### Disk-fill forecast (early warning)

Beyond the level threshold above, Vaier fits a line through each *filesystem's* recent `df` readings and projects its **runway**: the time until it reaches 100% at the current fill rate. When the runway drops under a fixed **24-hour horizon** *while the filesystem is still below its threshold*, admins get a one-time early-warning email naming the machine, the mount point, its current usage, the fill rate (%/h) and the projected runway.

It's a trend alarm ("this filesystem *will be* full"), distinct from the level alarm above ("this filesystem *is* full"): a filling filesystem pages once as a forecast and then, when it crosses the threshold, the disk-pressure alert takes over — never both at once. An all-clear follows on a *genuine recovery* — it drains, or its fill slows so the projected runway rises back over the horizon, all while still below the threshold. The hand-off case (it simply climbs past the threshold) raises **no** all-clear: the disk-pressure alert already speaks for it, so you're never sent a contradictory "cleared" and "is full" at the same poll. A flat or draining filesystem has no forecast, and a failed `df` records no sample, so a transient blip can't fabricate a warning.

---

## Update available

Vaier tells you when a container runs an image its registry has since moved on from. Once a day it compares the digest the running container's image actually has against the digest that registry serves for the **very same tag** — a difference means an **update available**. Any Registry v2 host works: `ghcr.io` and `lscr.io` alongside Docker Hub, with no account, token or config to supply. **Vaier never pulls and never restarts anything** — detection is read-only, and updating stays your call.

When an image *newly* goes out of date, every admin gets **one rollup email** listing what changed — each line names the image *and the machine it runs on* (e.g. `vaultwarden/server:latest on Apalveien 5`), so you know which host to act on rather than just which image (three images going stale in one sweep is one mail; nothing changed is no mail). The same tag on two machines is tracked separately: it can read out of date on one and up to date on another, and each is alerted on its own. Unlike the disk and machine alerts, an image already stale the first time Vaier looks *is* reported — that's the incident this exists for.

What Vaier can't tell, it says: an unreachable or rate-limited registry, a locally-built image, or an image pinned to an exact digest all read as **unknown** — never as up to date, never as out of date. In the **Explorer**, a container with an update available wears a small yellow mark, in the tree and in its machine's container list, so you spot it while scanning. The mark is advisory — red stays reserved for down. **Unknown draws no mark at all** (a grey smudge on every row would just teach you to ignore it), so no mark is *not* a promise that an image is current; where that matters, a container's Inspector names which of the three verdicts it is, in words, including "Vaier cannot tell".

Pulled something and don't want to wait for tomorrow's sweep? **Check the registries now**, on a machine's container list, re-reads the containers and re-asks every registry, ignoring anything Vaier remembered — both halves matter, or the check could confirm the very mark you pressed it to clear. It's fleet-wide, still read-only, and if you just checked it says so rather than pretending to look again. Daily rather than continuous because manifest requests are rate-limited. Covers the Vaier server's own containers and those on your VPN **server peers**; LAN-server containers read as unknown for now.
