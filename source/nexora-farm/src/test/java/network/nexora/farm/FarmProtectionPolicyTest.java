package network.nexora.farm;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FarmProtectionPolicyTest {
    @Test void onlyConfiguredCropsAreHarvestable() {
        assertTrue(FarmProtectionPolicy.isCrop(Material.WHEAT));
        assertTrue(FarmProtectionPolicy.mayHarvest(Material.WHEAT, 7, 7));
        assertFalse(FarmProtectionPolicy.mayHarvest(Material.WHEAT, 6, 7));
        assertTrue(FarmProtectionPolicy.mayHarvest(Material.SUGAR_CANE, null, null));
        assertFalse(FarmProtectionPolicy.mayHarvest(Material.FARMLAND, null, null));
        assertFalse(FarmProtectionPolicy.mayHarvest(Material.GRASS_BLOCK, null, null));
    }

    @Test void harvestedFieldCropsAreReplantedOnlyWhenMature() {
        assertTrue(FarmProtectionPolicy.shouldReplant(Material.WHEAT, 7, 7));
        assertTrue(FarmProtectionPolicy.shouldReplant(Material.CARROTS, 7, 7));
        assertTrue(FarmProtectionPolicy.shouldReplant(Material.NETHER_WART, 3, 3));
        assertFalse(FarmProtectionPolicy.shouldReplant(Material.WHEAT, 6, 7));
        assertFalse(FarmProtectionPolicy.shouldReplant(Material.MELON, null, null));
    }
}
