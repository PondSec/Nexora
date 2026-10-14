package network.nexora.progression;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class CollectionDefinitionTest {
    private static CollectionDefinition collection() {
        return new CollectionDefinition("cobblestone", "mining", "minecraft:cobblestone",
                List.of(50L, 100L, 250L, 1_000L),
                Map.of(2, List.of(new CollectionReward("stone-platform", RewardType.RECIPE_UNLOCK, "stone-platform-recipe", 0)),
                        4, List.of(new CollectionReward("mining-xp", RewardType.SKILL_XP, "mining", 10))));
    }

    @Test void tierMathIsCumulativeAndRewardsCanBeReadThroughCurrentTier() {
        CollectionDefinition collection = collection();
        assertEquals(0, collection.tierFor(49));
        assertEquals(1, collection.tierFor(50));
        assertEquals(2, collection.tierFor(100));
        assertEquals(4, collection.tierFor(1_000));
        assertEquals(List.of("stone-platform", "mining-xp"),
                collection.rewardsThrough(4).stream().map(CollectionReward::id).toList());
    }

    @Test void invalidThresholdsAndRewardTierAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new CollectionDefinition("x", "mining", "minecraft:stone",
                List.of(50L, 50L), Map.of()));
        assertThrows(IllegalArgumentException.class, () -> new CollectionDefinition("x", "mining", "minecraft:stone",
                List.of(50L), Map.of(2, List.of())));
    }
}
