package network.nexora.progression;

import java.util.Map;

/** Versioned gameplay metadata, shared by every consumer of the existing registry. */
public record ItemProperties(int version, int stackLimit, long npcValueMinor,
                             Map<String,Double> stats, Map<String,Integer> skillRequirements,
                             Map<String,Integer> collectionRequirements, String ability) {
    public static final ItemProperties DEFAULT = new ItemProperties(1,64,0,Map.of(),Map.of(),Map.of(),null);
    public ItemProperties {
        if (version < 1 || stackLimit < 1 || stackLimit > 64 || npcValueMinor < 0) throw new IllegalArgumentException("Invalid item metadata");
        stats = Map.copyOf(stats); skillRequirements = Map.copyOf(skillRequirements); collectionRequirements = Map.copyOf(collectionRequirements);
        if (stats.values().stream().anyMatch(v -> !Double.isFinite(v) || v < 0)) throw new IllegalArgumentException("Invalid item stat");
        if (skillRequirements.values().stream().anyMatch(v -> v < 1 || v > 50) || collectionRequirements.values().stream().anyMatch(v -> v < 1))
            throw new IllegalArgumentException("Invalid item requirement");
    }
}
