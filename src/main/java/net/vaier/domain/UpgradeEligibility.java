package net.vaier.domain;

import java.util.List;

/**
 * Whether Vaier may offer to upgrade a container's image — and, when it may not, why not. The verdict
 * is stated rather than implied so the Explorer can say a plain reason instead of quietly withholding
 * a button.
 *
 * <p>The decision is the domain's and is taken once, per container, on the machine the container was
 * scraped from: it needs to know whether that machine is the <b>Vaier server</b>, which a container's
 * name alone can never say.
 */
public enum UpgradeEligibility {

    /** Compose-managed, and the operator's own: Vaier can recreate this service faithfully. */
    UPGRADABLE,

    /**
     * Started with plain {@code docker run}, or carrying compose labels Vaier will not act on. Vaier
     * cannot recreate it without knowing how it was started, and a recreate that silently drops
     * config is worse than no action at all.
     */
    NOT_COMPOSE_MANAGED,

    /**
     * Part of <b>Vaier's own stack</b> on the Vaier server, whose images are pinned by a Vaier release
     * and move with it. A per-container upgrade here would be a second, conflicting upgrade path for
     * the same images.
     */
    VAIER_OWN_STACK,

    /**
     * The machine's Docker is out of Vaier's reach: its SSH user cannot drive Docker there, so every
     * command an upgrade would run dies on a permission denial. A fact about the <b>machine</b>, worn by
     * each of its containers because that is the question the Explorer asks.
     *
     * <p>Withheld <em>early</em>, on purpose. The container scrape reads Docker's API over the tunnel and
     * needs no group at all, so such a machine looks entirely healthy and would offer an Upgrade on every
     * container it has, each one doomed. A good error message is the backstop, never the plan.
     */
    NO_DOCKER_ACCESS;

    /** Whether the Upgrade action may be offered at all. Only one verdict says yes. */
    public boolean upgradable() {
        return this == UPGRADABLE;
    }

    /**
     * Why Vaier will not upgrade {@code containerName}, in the operator's own words — the sentence a refused
     * upgrade carries back. It lives on the verdict so the reason the Explorer withholds a button and the
     * reason a refused request gives are always the same sentence.
     *
     * @throws IllegalStateException on {@link #UPGRADABLE}, which is not a refusal and has nothing to say
     */
    public String refusal(String containerName) {
        return switch (this) {
            case UPGRADABLE -> throw new IllegalStateException(
                "An upgradable container is not refused: " + containerName);
            case NOT_COMPOSE_MANAGED -> "Vaier holds no compose coordinates for " + containerName
                + ", so it does not know how it was started and will not recreate it.";
            case NO_DOCKER_ACCESS -> "Vaier cannot run Docker commands on this machine, so it cannot"
                + " recreate " + containerName + " — usually because the user Vaier signs in as is not in"
                + " the machine's docker group.";
            case VAIER_OWN_STACK -> containerName + " is part of Vaier's own stack, which is pinned by a"
                + " Vaier release and upgraded with Vaier itself.";
        };
    }

    /**
     * The Vaier server's own container scrape, each container carrying its verdict. This is the one
     * machine whose containers are measured against {@link VaierServerCatalogue}.
     *
     * @param access what Vaier last saw of this machine's {@link DockerCommandAccess}
     */
    public static List<DockerService> judgeVaierServerContainers(List<DockerService> containers,
                                                                 DockerCommandAccess access) {
        return judge(containers, true, access);
    }

    /**
     * A scrape of one machine the operator owns — a VPN peer or a LAN server — each container carrying
     * its verdict. Nothing here is Vaier's own stack, however it is named: a peer running its own
     * {@code traefik} or {@code redis} is the operator's container and theirs to upgrade.
     */
    public static List<DockerService> judgeOperatorContainers(List<DockerService> containers,
                                                              DockerCommandAccess access) {
        return judge(containers, false, access);
    }

    private static List<DockerService> judge(List<DockerService> containers, boolean onVaierServer,
                                             DockerCommandAccess access) {
        if (containers == null) {
            return List.of();
        }
        return containers.stream()
            .map(container -> container.withUpgradeEligibility(decide(container, onVaierServer, access)))
            .toList();
    }

    /**
     * Whose container it is comes first: Vaier's own stack is refused however readable its labels are,
     * because the reason it is refused has nothing to do with how it was started.
     */
    private static UpgradeEligibility decide(DockerService container, boolean onVaierServer,
                                             DockerCommandAccess access) {
        if (onVaierServer && VaierServerCatalogue.isVaierOwnStack(container.containerName())) {
            return VAIER_OWN_STACK;
        }
        if (container.composeCoordinates() == null) {
            return NOT_COMPOSE_MANAGED;
        }
        // The container's own permanent blocker is stated ahead of the machine's: fixing the docker group
        // would not make a hand-started container upgradable, so the reason shown is the one that lasts.
        if (access != null && access.refused()) {
            return NO_DOCKER_ACCESS;
        }
        // UNKNOWN keeps the button, and that is not the same rule as ContainerUpgrade's "no verdict is
        // never permission". There, an absent verdict means Vaier does not know how to recreate the
        // container at all; here it means nobody has swept this machine yet — the state of the whole fleet
        // for the minutes after a restart — and Vaier holds no evidence the upgrade would fail. Withhold
        // only on a verdict actually taken; the runtime diagnostic is the backstop if it does fail.
        return UPGRADABLE;
    }
}
