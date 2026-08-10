package net.vaier.domain;

import java.util.Collection;

/**
 * What to call a machine's backup store where an operator reads it — the list under the backup server, which
 * is the page you go to when you want a file back.
 *
 * <p>A store is a directory on the backup server named after the machine it holds. That was unambiguous only
 * while machine names had to be unique; since they no longer do (§6.22), the machine's name alone can name
 * two different stores, and two rows both reading "NAS" on a restore screen is the same "two things wearing
 * one label" failure the identity work removed from the code, arriving on the screen instead. Choosing the
 * wrong store is not a cosmetic mistake — it is restoring the wrong house's data.
 *
 * <p>So the rule: <b>a name that identifies its machine is left alone</b> — the common case must stay quiet —
 * and <b>a name two machines share says where the machine is</b>. The address is the disambiguator rather
 * than the store's own directory name, because an operator knows which box answers on which address and
 * nobody knows what {@code NAS-2} means.
 *
 * <p>Lives in the domain rather than in the browser because it is a decision, and because the same question
 * is asked from more than one surface — two implementations would eventually disagree about which store is
 * which, which is the failure itself.
 */
public final class BackupStoreLabel {

    /** Separator between the name and what tells it apart. A middle dot, not a dash: names contain dashes. */
    private static final String SEPARATOR = " · ";

    private BackupStoreLabel() {}

    /**
     * The label for {@code machine}'s store, given the fleet it sits in.
     *
     * @param fleet every machine, so the label can tell whether this one's name is its own. A machine that
     *              is not in the list is simply unambiguous — nothing else is claiming its name.
     */
    public static String of(Machine machine, Collection<Machine> fleet) {
        String name = machine.name();
        if (!nameIsShared(machine, fleet)) {
            return name;
        }
        // Something that distinguishes them, and never nothing. A peer has no LAN address, so it falls back
        // to its identity — ugly on purpose, because the alternative is two identical rows and a restore
        // from the wrong machine.
        String distinguisher = machine.lanAddress() == null || machine.lanAddress().isBlank()
            ? machine.id().value()
            : machine.lanAddress();
        return name + SEPARATOR + distinguisher;
    }

    /**
     * Whether another machine wears this one's name. Compared as a person reads it — trimmed and
     * case-insensitively — because " nas " and "NAS" are one label on a screen however much the strings
     * differ, and it is the reading that gets an operator into the wrong store.
     */
    private static boolean nameIsShared(Machine machine, Collection<Machine> fleet) {
        if (fleet == null || machine.name() == null) {
            return false;
        }
        String mine = machine.name().trim();
        return fleet.stream()
            .filter(other -> !other.id().equals(machine.id()))
            .anyMatch(other -> other.name() != null && other.name().trim().equalsIgnoreCase(mine));
    }
}
