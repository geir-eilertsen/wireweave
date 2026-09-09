package net.vaier.domain;

import java.util.List;
import java.util.Locale;

/**
 * Which machine the model meant (#360). A name is how the fleet read names machines and how the operator
 * talks, so it is accepted in any case, spacing or punctuation; but a name is not an identity — two
 * machines may share one — so the id is accepted too, and a shared name is refused with the ids to choose
 * from.
 */
public record MachineReference(String said) {

    public Machine resolve(List<Machine> fleet) {
        String wanted = said == null ? "" : said.trim();
        if (wanted.isEmpty()) {
            throw new IllegalArgumentException("Say which machine.");
        }
        for (Machine machine : fleet) {
            if (machine.id() != null && machine.id().value().equals(wanted)) {
                return machine;
            }
        }
        List<Machine> named = fleet.stream()
            .filter(machine -> machine.name() != null && key(machine.name()).equals(key(wanted)))
            .toList();
        if (named.size() == 1) {
            return named.get(0);
        }
        if (named.isEmpty()) {
            throw new IllegalArgumentException("Vaier has no machine called \"" + wanted
                + "\". Machines are named exactly as the fleet read gives them.");
        }
        throw new IllegalArgumentException(named.size() + " machines are called \"" + wanted
            + "\"; say which by id: " + String.join(", ", named.stream().map(m -> m.id().value()).toList()));
    }

    private static String key(String name) {
        return name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }
}
