package network.nexora.farm;

import org.bukkit.Material;
import java.util.EnumSet;
import java.util.Set;

final class FarmProtectionPolicy {
    private static final Set<Material> HARVESTABLE = EnumSet.of(
            Material.WHEAT, Material.CARROTS, Material.POTATOES, Material.BEETROOTS,
            Material.MELON, Material.PUMPKIN, Material.SUGAR_CANE, Material.CACTUS,
            Material.COCOA, Material.BROWN_MUSHROOM, Material.RED_MUSHROOM,
            Material.NETHER_WART, Material.SWEET_BERRY_BUSH, Material.BAMBOO,
            Material.TORCHFLOWER_CROP, Material.PITCHER_CROP);

    private FarmProtectionPolicy() {}

    static boolean isCrop(Material material) {
        return HARVESTABLE.contains(material);
    }

    static boolean mayHarvest(Material material, Integer age, Integer maximumAge) {
        if (!isCrop(material)) return false;
        return age == null || (maximumAge != null && age >= maximumAge);
    }

    static boolean shouldReplant(org.bukkit.block.Block block) {
        if (!(block.getBlockData() instanceof org.bukkit.block.data.Ageable ageable)) return false;
        return shouldReplant(block.getType(), ageable.getAge(), ageable.getMaximumAge());
    }

    static boolean shouldReplant(Material material, Integer age, Integer maximumAge) {
        if (!mayHarvest(material, age, maximumAge)) return false;
        return switch (material) {
            case WHEAT, CARROTS, POTATOES, BEETROOTS, NETHER_WART, SWEET_BERRY_BUSH,
                    TORCHFLOWER_CROP, PITCHER_CROP -> true;
            default -> false;
        };
    }
}
