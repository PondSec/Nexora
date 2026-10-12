package network.nexora.progression;

import java.util.List;
import java.util.Map;

/** Validated item, collection and crafting source of truth for Nexora progression. */
final class DefaultContentCatalog {
    private static final List<Long> TIERS = List.of(50L, 100L, 250L, 1_000L, 2_500L, 5_000L,
            10_000L, 25_000L, 50_000L, 100_000L, 250_000L, 500_000L);
    private static final List<Resource> RESOURCES = List.of(
            r("cobblestone","cobblestone","COBBLESTONE","Cobblestone","mining"),
            r("coal","coal","COAL","Coal","mining"), r("iron","iron_ingot","IRON_INGOT","Iron","mining"),
            r("gold","gold_ingot","GOLD_INGOT","Gold","mining"), r("diamond","diamond","DIAMOND","Diamond","mining"),
            r("emerald","emerald","EMERALD","Emerald","mining"), r("redstone","redstone","REDSTONE","Redstone","mining"),
            r("lapis","lapis_lazuli","LAPIS_LAZULI","Lapis","mining"), r("quartz","quartz","QUARTZ","Quartz","mining"),
            r("obsidian","obsidian","OBSIDIAN","Obsidian","mining"), r("glowstone_dust","glowstone_dust","GLOWSTONE_DUST","Glowstone","mining"),
            r("end_stone","end_stone","END_STONE","End Stone","mining"), r("gravel","gravel","GRAVEL","Gravel","mining"),
            r("flint","flint","FLINT","Flint","mining"), r("sand","sand","SAND","Sand","mining"),
            r("ice","ice","ICE","Ice","mining"), r("snow","snowball","SNOWBALL","Snow","mining"),
            r("wheat","wheat","WHEAT","Wheat","farming"), r("carrot","carrot","CARROT","Carrot","farming"),
            r("potato","potato","POTATO","Potato","farming"), r("melon","melon_slice","MELON_SLICE","Melon","farming"),
            r("pumpkin","pumpkin","PUMPKIN","Pumpkin","farming"), r("sugar_cane","sugar_cane","SUGAR_CANE","Sugar Cane","farming"),
            r("cactus","cactus","CACTUS","Cactus","farming"), r("cocoa_beans","cocoa_beans","COCOA_BEANS","Cocoa Beans","farming"),
            r("red_mushroom","red_mushroom","RED_MUSHROOM","Red Mushroom","farming"), r("brown_mushroom","brown_mushroom","BROWN_MUSHROOM","Brown Mushroom","farming"),
            r("nether_wart","nether_wart","NETHER_WART","Nether Wart","farming"), r("seeds","wheat_seeds","WHEAT_SEEDS","Seeds","farming"),
            r("leather","leather","LEATHER","Leather","farming"), r("feather","feather","FEATHER","Feather","farming"),
            r("raw_chicken","chicken","CHICKEN","Raw Chicken","farming"), r("raw_beef","beef","BEEF","Raw Beef","farming"),
            r("raw_porkchop","porkchop","PORKCHOP","Raw Porkchop","farming"),
            r("rotten_flesh","rotten_flesh","ROTTEN_FLESH","Rotten Flesh","combat"), r("bone","bone","BONE","Bone","combat"),
            r("string","string","STRING","String","combat"), r("spider_eye","spider_eye","SPIDER_EYE","Spider Eye","combat"),
            r("gunpowder","gunpowder","GUNPOWDER","Gunpowder","combat"), r("slimeball","slime_ball","SLIME_BALL","Slimeball","combat"),
            r("ender_pearl","ender_pearl","ENDER_PEARL","Ender Pearl","combat"), r("blaze_rod","blaze_rod","BLAZE_ROD","Blaze Rod","combat"),
            r("magma_cream","magma_cream","MAGMA_CREAM","Magma Cream","combat"), r("ghast_tear","ghast_tear","GHAST_TEAR","Ghast Tear","combat"),
            r("oak_wood","oak_log","OAK_LOG","Oak Wood","foraging"), r("spruce_wood","spruce_log","SPRUCE_LOG","Spruce Wood","foraging"),
            r("birch_wood","birch_log","BIRCH_LOG","Birch Wood","foraging"), r("jungle_wood","jungle_log","JUNGLE_LOG","Jungle Wood","foraging"),
            r("acacia_wood","acacia_log","ACACIA_LOG","Acacia Wood","foraging"), r("dark_oak_wood","dark_oak_log","DARK_OAK_LOG","Dark Oak Wood","foraging")
    );

    private DefaultContentCatalog() {}

    static ContentRegistry create() {
        ContentRegistry registry = new ContentRegistry();
        registry.registerEffectHandler("vanilla_material");
        registry.registerEffectHandler("compressed_material");
        registry.registerEffectHandler("rocket_boost");
        for (Resource resource : RESOURCES) {
            String raw = "minecraft:" + resource.itemId;
            String enchanted = "nexora:enchanted_" + resource.collectionId;
            String doubleEnchanted = "nexora:double_enchanted_" + resource.collectionId;
            registry.registerItem(new ItemDefinition(raw, "minecraft:" + resource.material, "COMMON", resource.label, "vanilla_material", TradingClassification.BAZAAR_COMMODITY));
            registry.registerItem(new ItemDefinition(enchanted, "minecraft:paper", "RARE", "Enchanted " + resource.label, "compressed_material", TradingClassification.BAZAAR_COMMODITY));
            registry.registerItem(new ItemDefinition(doubleEnchanted, "minecraft:paper", "EPIC", "Double Enchanted " + resource.label, "compressed_material", TradingClassification.BAZAAR_COMMODITY));
            registry.registerCollection(new CollectionDefinition(resource.collectionId, resource.category, raw, TIERS, Map.of()));
            registry.registerRecipe(new RecipeDefinition("nexora:compress_" + resource.collectionId, enchanted, 1,
                    Map.of(raw, 160L), resource.collectionId, 4, null, 0));
            registry.registerRecipe(new RecipeDefinition("nexora:double_compress_" + resource.collectionId, doubleEnchanted, 1,
                    Map.of(enchanted, 160L), resource.collectionId, 8, null, 0));
        }
        registerBlockCompression(registry, "coal_block", "coal", "Coal Block");
        registerBlockCompression(registry, "iron_block", "iron", "Iron Block");
        registerComponent(registry, "condensed_pyrocell", "Condensed Pyrocell", Map.of(
                "nexora:enchanted_gunpowder",16L,"nexora:enchanted_blaze_rod",16L,
                "nexora:enchanted_magma_cream",16L,"nexora:enchanted_glowstone_dust",8L),
                Map.of("gunpowder",8,"blaze_rod",7,"magma_cream",7));
        registerComponent(registry, "eternal_fuel_cell", "Eternal Fuel Cell", Map.of(
                "nexora:enchanted_coal_block",16L,"nexora:enchanted_redstone",16L,"nexora:enchanted_gold",8L),
                Map.of("coal",9,"redstone",8,"gold",8));
        registerComponent(registry, "astral_navigation_core", "Astral Navigation Core", Map.of(
                "nexora:enchanted_ender_pearl",8L,"nexora:enchanted_quartz",16L,"nexora:enchanted_diamond",8L),
                Map.of("ender_pearl",8,"quartz",8,"diamond",7));
        registerComponent(registry, "aerodynamic_stabilizer", "Aerodynamic Stabilizer", Map.of(
                "nexora:enchanted_feather",16L,"nexora:enchanted_leather",16L,"nexora:enchanted_obsidian",8L),
                Map.of("feather",8,"leather",7,"obsidian",8));
        registerComponent(registry, "reinforced_rocket_chassis", "Reinforced Rocket Chassis", Map.of(
                "nexora:enchanted_iron_block",16L,"nexora:enchanted_diamond",8L,"nexora:enchanted_quartz",8L),
                Map.of("iron",10,"diamond",8,"quartz",8));

        // Collection-gated equipment recipes. Each output is a persistent Nexora item with a
        // vanilla equipment base, so crafting produces wearable/usable gear rather than lore-only entries.
        registerCraftable(registry, "farmer_helmet", "Farmer Helmet", "minecraft:iron_helmet", "RARE",
                Map.of("nexora:enchanted_wheat",32L,"nexora:enchanted_carrot",16L), Map.of("wheat",6,"carrot",5));
        registerCraftable(registry, "farmer_chestplate", "Farmer Chestplate", "minecraft:iron_chestplate", "RARE",
                Map.of("nexora:enchanted_wheat",64L,"nexora:enchanted_leather",16L), Map.of("wheat",7,"leather",5));
        registerCraftable(registry, "farmer_leggings", "Farmer Leggings", "minecraft:iron_leggings", "RARE",
                Map.of("nexora:enchanted_wheat",48L,"nexora:enchanted_potato",24L), Map.of("wheat",6,"potato",5));
        registerCraftable(registry, "farmer_boots", "Farmer Boots", "minecraft:iron_boots", "RARE",
                Map.of("nexora:enchanted_wheat",32L,"nexora:enchanted_sugar_cane",16L), Map.of("wheat",6,"sugar_cane",5));
        registerCraftable(registry, "miner_helmet", "Miner Helmet", "minecraft:diamond_helmet", "EPIC",
                Map.of("nexora:enchanted_iron",32L,"nexora:enchanted_coal",16L,"nexora:enchanted_redstone",8L),
                Map.of("iron",7,"coal",6,"redstone",5));
        registerCraftable(registry, "miner_chestplate", "Miner Chestplate", "minecraft:diamond_chestplate", "EPIC",
                Map.of("nexora:enchanted_iron",64L,"nexora:enchanted_diamond",16L,"nexora:enchanted_obsidian",8L),
                Map.of("iron",8,"diamond",6,"obsidian",5));
        registerCraftable(registry, "miner_leggings", "Miner Leggings", "minecraft:diamond_leggings", "EPIC",
                Map.of("nexora:enchanted_iron",48L,"nexora:enchanted_diamond",12L,"nexora:enchanted_lapis",8L),
                Map.of("iron",7,"diamond",6,"lapis",5));
        registerCraftable(registry, "miner_boots", "Miner Boots", "minecraft:diamond_boots", "EPIC",
                Map.of("nexora:enchanted_iron",32L,"nexora:enchanted_diamond",8L,"nexora:enchanted_redstone",8L),
                Map.of("iron",7,"diamond",6,"redstone",5));
        registerCraftable(registry, "ember_blade", "Ember Blade", "minecraft:diamond_sword", "EPIC",
                Map.of("nexora:enchanted_blaze_rod",16L,"nexora:enchanted_magma_cream",16L,"nexora:enchanted_gold",8L),
                Map.of("blaze_rod",7,"magma_cream",6,"gold",5));
        registerCraftable(registry, "ender_sword", "Ender Sword", "minecraft:netherite_sword", "LEGENDARY",
                Map.of("nexora:enchanted_ender_pearl",32L,"nexora:enchanted_diamond",24L,"nexora:enchanted_obsidian",16L),
                Map.of("ender_pearl",8,"diamond",8,"obsidian",7));

        registry.registerItem(new ItemDefinition("nexora:infinite_rocket", "minecraft:firework_rocket", "MYTHIC",
                "Eternal Thrust · Infinite Elytra Boost · 1.5 second cooldown", "rocket_boost", TradingClassification.BAZAAR_STANDARDIZED_ITEM));
        Map<String,Integer> finalUnlock = Map.ofEntries(Map.entry("gunpowder",10),Map.entry("coal",10),
                Map.entry("iron",10),Map.entry("diamond",9),Map.entry("redstone",10),Map.entry("quartz",9),
                Map.entry("ender_pearl",9),Map.entry("blaze_rod",8),Map.entry("magma_cream",7),
                Map.entry("gold",8),Map.entry("feather",8),Map.entry("leather",7),Map.entry("obsidian",8));
        registry.registerRecipe(new RecipeDefinition("nexora:infinite_rocket", "nexora:infinite_rocket", 1,
                Map.of("nexora:condensed_pyrocell",2L,"nexora:eternal_fuel_cell",2L,
                        "nexora:astral_navigation_core",1L,"nexora:aerodynamic_stabilizer",1L,
                        "nexora:reinforced_rocket_chassis",1L,"nexora:enchanted_redstone",16L),
                finalUnlock, "gunpowder", 10, null, 0));
        registerRecipeRewards(registry);
        registry.validateOrThrow();
        return registry;
    }

    private static void registerRecipeRewards(ContentRegistry registry) {
        for (RecipeDefinition recipe : registry.recipes().values()) {
            ItemDefinition output = registry.items().get(recipe.outputItemId());
            RewardType type = output.materialKey().endsWith("_sword") || recipe.outputItemId().endsWith("_blade")
                    ? RewardType.WEAPON_RECIPE_UNLOCK
                    : output.materialKey().matches("minecraft:.*_(helmet|chestplate|leggings|boots)")
                            ? RewardType.ARMOR_RECIPE_UNLOCK
                            : recipe.outputItemId().equals("nexora:infinite_rocket")
                                    ? RewardType.UTILITY_UNLOCK : RewardType.ITEM_RECIPE_UNLOCK;
            for (Map.Entry<String,Integer> requirement : recipe.collectionRequirements().entrySet()) {
                String rewardId = "collection-recipe:" + requirement.getKey() + ":" + recipe.id();
                registry.registerCollectionReward(requirement.getKey(), requirement.getValue(),
                        new CollectionReward(rewardId, type, recipe.id(), 1));
            }
        }
    }

    private static void registerBlockCompression(ContentRegistry registry, String block, String collection, String label) {
        String id="nexora:enchanted_"+block, ingredient="nexora:enchanted_"+collection;
        registry.registerItem(new ItemDefinition(id,"minecraft:paper","EPIC","Enchanted "+label,"compressed_material",TradingClassification.BAZAAR_COMMODITY));
        registry.registerRecipe(new RecipeDefinition("nexora:compress_"+block,id,1,Map.of(ingredient,160L),collection,8,null,0));
    }
    private static void registerComponent(ContentRegistry registry, String id, String label,
                                          Map<String,Long> ingredients, Map<String,Integer> requirements) {
        String item="nexora:"+id;
        registry.registerItem(new ItemDefinition(item,"minecraft:paper","EPIC",label,"compressed_material",TradingClassification.BAZAAR_STANDARDIZED_ITEM));
        String primary=requirements.keySet().stream().sorted().findFirst().orElseThrow();
        registry.registerRecipe(new RecipeDefinition("nexora:"+id,item,1,ingredients,requirements,primary,
                requirements.get(primary),null,0));
    }
    private static void registerCraftable(ContentRegistry registry, String id, String label, String material,
                                          String rarity, Map<String,Long> ingredients,
                                          Map<String,Integer> requirements) {
        String item = "nexora:" + id;
        registry.registerItem(new ItemDefinition(item, material, rarity, label, "compressed_material",
                TradingClassification.BOTH));
        String primary = requirements.keySet().stream().sorted().findFirst().orElseThrow();
        registry.registerRecipe(new RecipeDefinition("nexora:" + id, item, 1, ingredients, requirements,
                primary, requirements.get(primary), null, 0));
    }
    private static Resource r(String collection,String item,String material,String label,String category) {
        return new Resource(collection,item,material,label,category);
    }
    private record Resource(String collectionId,String itemId,String material,String label,String category) {}
}
