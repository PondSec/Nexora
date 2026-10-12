package network.nexora.progression;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DefaultContentCatalogTest {
    @Test void retainsEveryCollectionCurrentlyWrittenByCoreAndRegistersBothCompressionTiers() {
        ContentRegistry catalog = DefaultContentCatalog.create();
        assertEquals(58, catalog.collections().size());
        assertEquals(152, catalog.recipes().size());
        assertTrue(catalog.collections().containsKey("cobblestone"));
        assertTrue(catalog.collections().containsKey("wheat"));
        assertTrue(catalog.collections().containsKey("birch_wood"));
        assertTrue(catalog.collections().containsKey("feather"));
        assertTrue(catalog.collections().containsKey("leather"));
        assertTrue(catalog.collections().containsKey("quartz"));
        assertTrue(catalog.recipes().containsKey("nexora:compress_cobblestone"));
        assertTrue(catalog.recipes().containsKey("nexora:double_compress_cobblestone"));
    }

    @Test void registersCollectionUnlockedArmorAndWeaponsAsCraftableTaggedEquipment() {
        ContentRegistry catalog = DefaultContentCatalog.create();
        assertEquals("minecraft:iron_helmet", catalog.items().get("nexora:farmer_helmet").materialKey());
        assertEquals("minecraft:diamond_chestplate", catalog.items().get("nexora:miner_chestplate").materialKey());
        assertEquals("minecraft:netherite_sword", catalog.items().get("nexora:ender_sword").materialKey());
        assertEquals(32L, catalog.recipes().get("nexora:farmer_helmet").ingredients().get("nexora:enchanted_wheat"));
        assertEquals(7, catalog.recipes().get("nexora:ender_sword").collectionRequirements().get("obsidian"));
        assertEquals(TradingClassification.BOTH, catalog.items().get("nexora:ember_blade").tradingClassification());
        assertTrue(catalog.validate().isEmpty());
    }

    @Test void collectionDefinitionsExposeRecipeUnlocksAtTheirRequiredTiers() {
        ContentRegistry catalog = DefaultContentCatalog.create();
        var wheat = catalog.collections().get("wheat");
        assertTrue(wheat.rewards().get(6).stream().anyMatch(reward -> reward.targetId().equals("nexora:farmer_helmet")));
        var obsidian = catalog.collections().get("obsidian");
        assertTrue(obsidian.rewards().get(7).stream().anyMatch(reward -> reward.targetId().equals("nexora:ender_sword")));
        assertEquals(RewardType.WEAPON_RECIPE_UNLOCK, obsidian.rewards().get(7).stream()
                .filter(reward -> reward.targetId().equals("nexora:ender_sword")).findFirst().orElseThrow().type());
    }

    @Test void compressionRecipesUseStableIdsExactInputsAndTheConfiguredThresholds() {
        ContentRegistry catalog = DefaultContentCatalog.create();
        RecipeDefinition enchanted = catalog.recipes().get("nexora:compress_cobblestone");
        RecipeDefinition doubleEnchanted = catalog.recipes().get("nexora:double_compress_cobblestone");
        assertEquals("nexora:enchanted_cobblestone", enchanted.outputItemId());
        assertEquals(1, enchanted.outputAmount());
        assertEquals(160L, enchanted.ingredients().get("minecraft:cobblestone"));
        assertEquals(4, enchanted.requiredTier());
        assertEquals(160L, doubleEnchanted.ingredients().get("nexora:enchanted_cobblestone"));
        assertEquals(8, doubleEnchanted.requiredTier());
        assertEquals(4, catalog.collections().get("cobblestone").tierFor(1_000));
        assertEquals(8, catalog.collections().get("cobblestone").tierFor(25_000));
    }

    @Test void infiniteRocketAndAllFiveComponentsHaveExactInputsAndLateGameUnlocks() {
        ContentRegistry catalog = DefaultContentCatalog.create();
        assertEquals(5L, java.util.List.of("condensed_pyrocell", "eternal_fuel_cell", "astral_navigation_core",
                "aerodynamic_stabilizer", "reinforced_rocket_chassis").stream()
                .filter(id -> catalog.items().containsKey("nexora:" + id)).count());
        RecipeDefinition rocket = catalog.recipes().get("nexora:infinite_rocket");
        assertEquals("nexora:infinite_rocket", rocket.outputItemId());
        assertEquals("MYTHIC", catalog.items().get("nexora:infinite_rocket").rarity());
        assertEquals("minecraft:firework_rocket", catalog.items().get("nexora:infinite_rocket").materialKey());
        assertEquals(TradingClassification.BAZAAR_STANDARDIZED_ITEM,
                catalog.items().get("nexora:infinite_rocket").tradingClassification());
        assertTrue(catalog.items().get("nexora:infinite_rocket").bazaarAllowed());
        assertTrue(catalog.items().get("nexora:infinite_rocket").auctionAllowed());
        assertEquals(2L, rocket.ingredients().get("nexora:condensed_pyrocell"));
        assertEquals(2L, rocket.ingredients().get("nexora:eternal_fuel_cell"));
        assertEquals(16L, rocket.ingredients().get("nexora:enchanted_redstone"));
        assertEquals(13, rocket.collectionRequirements().size());
        assertEquals(10, rocket.collectionRequirements().get("gunpowder"));
        assertEquals(9, rocket.collectionRequirements().get("diamond"));
        assertEquals(8, catalog.recipes().get("nexora:condensed_pyrocell").collectionRequirements().get("gunpowder"));
        assertEquals(7, catalog.recipes().get("nexora:condensed_pyrocell").collectionRequirements().get("blaze_rod"));
        assertTrue(catalog.validate().isEmpty());
    }

    @Test void costCalculatorExpandsNestedEnchantedAndComponentRecipesWithoutOverflow() {
        ContentRegistry catalog = DefaultContentCatalog.create();
        var estimate = new CraftingCostCalculator(catalog).calculate("nexora:infinite_rocket",
                java.math.BigInteger.ONE, java.util.Map.of(), java.util.Map.of());
        assertEquals(new java.math.BigInteger("819200"), estimate.rawMaterials().get("minecraft:coal"));
        assertEquals(new java.math.BigInteger("409600"), estimate.rawMaterials().get("minecraft:iron_ingot"));
        assertEquals(new java.math.BigInteger("7680"), estimate.rawMaterials().get("minecraft:redstone"));
        assertNull(estimate.marketCostCoins());
        assertFalse(estimate.hasCompleteMarketPrices());
    }
}
