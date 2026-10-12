package network.nexora.core.api;

import org.bukkit.inventory.ItemStack;
import java.util.Map;

/** Shared service contract. Core owns the API; Progression owns the existing content registry. */
public interface CustomItemCatalog {
    record Entry(String id, String name, String material, String rarity, int maxStack,
                 int version, long npcValueMinor, boolean bazaar, boolean auction,
                 Map<String, Double> stats, String ability) {
        public Entry { stats = Map.copyOf(stats); }
    }
    Map<String, Entry> entries();
    /** Returns null for unknown IDs, incompatible materials/versions or malformed stacks. */
    String identify(ItemStack stack);
    ItemStack create(String id, int amount);
    boolean matches(ItemStack stack, String id);
}
