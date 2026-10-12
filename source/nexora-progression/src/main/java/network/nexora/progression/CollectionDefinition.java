package network.nexora.progression;

import java.util.List;
import java.util.Map;
import java.util.Objects;

public record CollectionDefinition(String id, String category, String resourceId,
                                   List<Long> tierThresholds, Map<Integer, List<CollectionReward>> rewards) {
    public CollectionDefinition {
        if (id == null || id.isBlank() || category == null || category.isBlank()
                || resourceId == null || resourceId.isBlank()) throw new IllegalArgumentException("Collection identity is required");
        Objects.requireNonNull(tierThresholds);
        tierThresholds = List.copyOf(tierThresholds);
        long previous = 0;
        for (long threshold : tierThresholds) {
            if (threshold <= previous) throw new IllegalArgumentException("Tier thresholds must be positive and increasing");
            previous = threshold;
        }
        int tierCount = tierThresholds.size();
        rewards = rewards.entrySet().stream().collect(java.util.stream.Collectors.toUnmodifiableMap(
                Map.Entry::getKey, entry -> List.copyOf(entry.getValue())));
        if (rewards.keySet().stream().anyMatch(tier -> tier < 1 || tier > tierCount))
            throw new IllegalArgumentException("Reward refers to a nonexistent collection tier");
    }

    public int tierFor(long totalResources) {
        if (totalResources < 0) throw new IllegalArgumentException("Collection count cannot be negative");
        int tier = 0;
        while (tier < tierThresholds.size() && totalResources >= tierThresholds.get(tier)) tier++;
        return tier;
    }

    public List<CollectionReward> rewardsThrough(int tier) {
        if (tier < 0 || tier > tierThresholds.size()) throw new IllegalArgumentException("Invalid tier");
        return java.util.stream.IntStream.rangeClosed(1, tier).boxed()
                .flatMap(level -> rewards.getOrDefault(level, List.of()).stream()).toList();
    }
}
