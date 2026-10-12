package network.nexora.core;
import org.bukkit.Material;
/** One direct-gather mapping shared by Core and the existing progression adapter. */
public final class GatheringPolicy {
    private GatheringPolicy() {}
    public static String collectionId(Material material) {
        return switch (material) {
            case STONE, COBBLESTONE -> "cobblestone";
            case COAL_ORE, DEEPSLATE_COAL_ORE, COAL -> "coal";
            case IRON_ORE, DEEPSLATE_IRON_ORE, IRON_INGOT -> "iron";
            case GOLD_ORE, DEEPSLATE_GOLD_ORE, GOLD_INGOT -> "gold";
            case DIAMOND_ORE, DEEPSLATE_DIAMOND_ORE, DIAMOND -> "diamond";
            case EMERALD_ORE, DEEPSLATE_EMERALD_ORE, EMERALD -> "emerald";
            case REDSTONE_ORE, DEEPSLATE_REDSTONE_ORE, REDSTONE -> "redstone";
            case LAPIS_ORE, DEEPSLATE_LAPIS_ORE, LAPIS_LAZULI -> "lapis";
            case NETHER_QUARTZ_ORE, QUARTZ -> "quartz";
            case OBSIDIAN -> "obsidian";
            case GLOWSTONE, GLOWSTONE_DUST -> "glowstone_dust";
            case END_STONE -> "end_stone";
            case GRAVEL -> "gravel";
            case FLINT -> "flint";
            case SAND -> "sand";
            case ICE, PACKED_ICE, BLUE_ICE -> "ice";
            case SNOW, SNOW_BLOCK, SNOWBALL -> "snow";
            case WHEAT -> "wheat";
            case CARROTS, CARROT -> "carrot";
            case POTATOES, POTATO -> "potato";
            case MELON, MELON_SLICE -> "melon";
            case PUMPKIN -> "pumpkin";
            case SUGAR_CANE -> "sugar_cane";
            case CACTUS -> "cactus"; case COCOA, COCOA_BEANS -> "cocoa_beans";
            case RED_MUSHROOM_BLOCK, RED_MUSHROOM -> "red_mushroom";
            case BROWN_MUSHROOM_BLOCK, BROWN_MUSHROOM -> "brown_mushroom";
            case NETHER_WART -> "nether_wart"; case WHEAT_SEEDS -> "seeds";
            case LEATHER -> "leather"; case FEATHER -> "feather"; case CHICKEN -> "raw_chicken";
            case BEEF -> "raw_beef"; case PORKCHOP -> "raw_porkchop";
            case ROTTEN_FLESH -> "rotten_flesh"; case BONE -> "bone"; case STRING -> "string";
            case SPIDER_EYE -> "spider_eye"; case GUNPOWDER -> "gunpowder"; case SLIME_BALL -> "slimeball";
            case ENDER_PEARL -> "ender_pearl"; case BLAZE_ROD -> "blaze_rod";
            case MAGMA_CREAM -> "magma_cream"; case GHAST_TEAR -> "ghast_tear";
            case OAK_LOG, OAK_WOOD -> "oak_wood"; case SPRUCE_LOG, SPRUCE_WOOD -> "spruce_wood";
            case BIRCH_LOG, BIRCH_WOOD -> "birch_wood"; case JUNGLE_LOG, JUNGLE_WOOD -> "jungle_wood";
            case ACACIA_LOG, ACACIA_WOOD -> "acacia_wood"; case DARK_OAK_LOG, DARK_OAK_WOOD -> "dark_oak_wood";
            case COD -> "cod"; case SALMON -> "salmon"; case PUFFERFISH -> "pufferfish"; case TROPICAL_FISH -> "tropical_fish";
            case MANGROVE_LOG, MANGROVE_WOOD -> "mangrove_wood"; case CHERRY_LOG, CHERRY_WOOD -> "cherry_wood";
            case PALE_OAK_LOG, PALE_OAK_WOOD -> "pale_oak_wood"; case BAMBOO_BLOCK, STRIPPED_BAMBOO_BLOCK -> "bamboo_wood";
            default -> null;
        };
    }


    public static String skill(Material material) {
        String id=collectionId(material);if(id==null)return null;
        if(id.endsWith("_wood"))return "foraging";
        if(java.util.Set.of("wheat","carrot","potato","melon","pumpkin","sugar_cane","cactus","cocoa_beans","red_mushroom","brown_mushroom","nether_wart","seeds","leather","feather","raw_chicken","raw_beef","raw_porkchop").contains(id))return "farming";
        if(java.util.Set.of("rotten_flesh","bone","string","spider_eye","gunpowder","slimeball","ender_pearl","blaze_rod","magma_cream","ghast_tear").contains(id))return "combat";
        if(java.util.Set.of("cod","salmon","pufferfish","tropical_fish").contains(id))return "fishing";
        return "mining";
    }
}
