package net.fjordomatic.domain;

import java.util.List;

/**
 * Whether Fjord may offer to update a container's image — and, when it may not, why not. The verdict
 * is stated rather than implied so the Explorer can say a plain reason instead of quietly withholding
 * a button.
 *
 * <p>The decision is the domain's and is taken once, per container, on the machine the container was
 * scraped from: it needs to know whether that machine is the <b>Fjord server</b>, which a container's
 * name alone can never say.
 */
public enum ContainerUpdateEligibility {

    /** Compose-managed, and the operator's own: Fjord can recreate this service faithfully. */
    UPDATABLE,

    /**
     * Started with plain {@code docker run}, or carrying compose labels Fjord will not act on. Fjord
     * cannot recreate it without knowing how it was started, and a recreate that silently drops
     * config is worse than no action at all.
     */
    NOT_COMPOSE_MANAGED,

    /**
     * Part of <b>Fjord's own stack</b> on the Fjord server, whose images are pinned by a Fjord release
     * and move with it. A per-container update here would be a second, conflicting update path for
     * the same images.
     */
    FJORD_OWN_STACK,

    /**
     * The machine's Docker is out of Fjord's reach: its SSH user cannot drive Docker there, so every
     * command an update would run dies on a permission denial. A fact about the <b>machine</b>, worn by
     * each of its containers because that is the question the Explorer asks.
     *
     * <p>Withheld <em>early</em>, on purpose. The container scrape reads Docker's API over the tunnel and
     * needs no group at all, so such a machine looks entirely healthy and would offer an Update on every
     * container it has, each one doomed. A good error message is the backstop, never the plan.
     */
    NO_DOCKER_ACCESS;

    /** Whether the Update action may be offered at all. Only one verdict says yes. */
    public boolean updatable() {
        return this == UPDATABLE;
    }

    /**
     * Why Fjord will not update {@code containerName}, in the operator's own words — the sentence a refused
     * update carries back. It lives on the verdict so the reason the Explorer withholds a button and the
     * reason a refused request gives are always the same sentence.
     *
     * @throws IllegalStateException on {@link #UPDATABLE}, which is not a refusal and has nothing to say
     */
    public String refusal(String containerName) {
        return switch (this) {
            case UPDATABLE -> throw new IllegalStateException(
                "An updatable container is not refused: " + containerName);
            case NOT_COMPOSE_MANAGED -> "Fjord holds no compose coordinates for " + containerName
                + ", so it does not know how it was started and will not recreate it.";
            case NO_DOCKER_ACCESS -> "Fjord cannot run Docker commands on this machine, so it cannot"
                + " recreate " + containerName + " — usually because the user Fjord signs in as is not in"
                + " the machine's docker group.";
            case FJORD_OWN_STACK -> containerName + " is part of Fjord's own stack, which is pinned by a"
                + " Fjord release and updated with Fjord itself.";
        };
    }

    /**
     * The Fjord server's own container scrape, each container carrying its verdict. This is the one
     * machine whose containers are measured against {@link FjordServerCatalogue}.
     *
     * @param access what Fjord last saw of this machine's {@link DockerCommandAccess}
     */
    public static List<DockerService> judgeFjordServerContainers(List<DockerService> containers,
                                                                 DockerCommandAccess access) {
        return judge(containers, true, access);
    }

    /**
     * A scrape of one machine the operator owns — a VPN peer or a LAN server — each container carrying
     * its verdict. Nothing here is Fjord's own stack, however it is named: a peer running its own
     * {@code traefik} or {@code redis} is the operator's container and theirs to update.
     */
    public static List<DockerService> judgeOperatorContainers(List<DockerService> containers,
                                                              DockerCommandAccess access) {
        return judge(containers, false, access);
    }

    private static List<DockerService> judge(List<DockerService> containers, boolean onFjordServer,
                                             DockerCommandAccess access) {
        if (containers == null) {
            return List.of();
        }
        return containers.stream()
            .map(container -> container.withUpdateEligibility(decide(container, onFjordServer, access)))
            .toList();
    }

    /**
     * Whose container it is comes first: Fjord's own stack is refused however readable its labels are,
     * because the reason it is refused has nothing to do with how it was started.
     */
    private static ContainerUpdateEligibility decide(DockerService container, boolean onFjordServer,
                                                     DockerCommandAccess access) {
        if (onFjordServer && FjordServerCatalogue.isFjordOwnStack(container.containerName())) {
            return FJORD_OWN_STACK;
        }
        if (container.composeCoordinates() == null) {
            return NOT_COMPOSE_MANAGED;
        }
        // The container's own permanent blocker is stated ahead of the machine's: fixing the docker group
        // would not make a hand-started container updatable, so the reason shown is the one that lasts.
        if (access != null && access.refused()) {
            return NO_DOCKER_ACCESS;
        }
        // UNKNOWN keeps the button, and that is not the same rule as ContainerUpdate's "no verdict is
        // never permission". There, an absent verdict means Fjord does not know how to recreate the
        // container at all; here it means nobody has swept this machine yet — the state of the whole fleet
        // for the minutes after a restart — and Fjord holds no evidence the update would fail. Withhold
        // only on a verdict actually taken; the runtime diagnostic is the backstop if it does fail.
        return UPDATABLE;
    }
}
