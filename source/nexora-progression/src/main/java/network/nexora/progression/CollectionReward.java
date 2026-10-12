package network.nexora.progression;

import java.util.Objects;

public record CollectionReward(String id, RewardType type, String targetId, long amount) {
    public CollectionReward {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("Reward ID is required");
        Objects.requireNonNull(type);
        if (targetId == null || targetId.isBlank()) throw new IllegalArgumentException("Reward target is required");
        if (amount < 0) throw new IllegalArgumentException("Reward amount cannot be negative");
    }
}
