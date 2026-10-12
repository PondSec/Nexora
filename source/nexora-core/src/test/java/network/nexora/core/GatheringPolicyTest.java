package network.nexora.core;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GatheringPolicyTest {
    @Test void oresAndTheirResourcesShareCollectionIdentity() {
        for (var pair : new Material[][]{{Material.COAL_ORE,Material.COAL},{Material.DEEPSLATE_IRON_ORE,Material.IRON_INGOT},{Material.REDSTONE_ORE,Material.REDSTONE},{Material.LAPIS_ORE,Material.LAPIS_LAZULI}}) {
            assertEquals(GatheringPolicy.collectionId(pair[0]),GatheringPolicy.collectionId(pair[1]));
            assertEquals("mining",GatheringPolicy.skill(pair[0]));
        }
    }
    @Test void allFishAndNewWoodsHaveCorrectSkills() {
        for(var fish:new Material[]{Material.COD,Material.SALMON,Material.PUFFERFISH,Material.TROPICAL_FISH})assertEquals("fishing",GatheringPolicy.skill(fish));
        for(var wood:new Material[]{Material.MANGROVE_LOG,Material.CHERRY_LOG,Material.PALE_OAK_LOG,Material.BAMBOO_BLOCK})assertEquals("foraging",GatheringPolicy.skill(wood));
        assertEquals("farming",GatheringPolicy.skill(Material.CARROTS));
        assertEquals("combat",GatheringPolicy.skill(Material.SPIDER_EYE));
        assertNull(GatheringPolicy.collectionId(Material.PAPER));
    }
}
