package network.nexora.core;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Owns the protected hotbar navigation item and its top-level/category menus. */
final class NexoraNavigation implements Listener {
    private final NexoraCore plugin;
    private final NamespacedKey itemKey;

    NexoraNavigation(NexoraCore plugin) {
        this.plugin = plugin;
        itemKey = new NamespacedKey(plugin, "navigation_item");
    }

    void start() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        plugin.getServer().getScheduler().runTaskTimer(plugin, () -> Bukkit.getOnlinePlayers().forEach(this::ensureItem), 20, 100);
        Bukkit.getOnlinePlayers().forEach(this::ensureItem);
    }

    @EventHandler public void onJoin(org.bukkit.event.player.PlayerJoinEvent event) {
        plugin.getServer().getScheduler().runTask(plugin, () -> ensureItem(event.getPlayer()));
    }

    void ensureItem(Player player) {
        ItemStack expected = createItem();
        for (int slot = 0; slot < player.getInventory().getSize(); slot++) {
            ItemStack found = player.getInventory().getItem(slot);
            if (slot != 8 && isMenuItem(found)) player.getInventory().setItem(slot, null);
        }
        ItemStack current = player.getInventory().getItem(8);
        if (isMenuItem(current)) { current.setAmount(1); return; }
        if (current != null && !current.getType().isAir()) {
            ItemStack displaced = current.clone();
            player.getInventory().setItem(8, null);
            Map<Integer, ItemStack> overflow = player.getInventory().addItem(displaced);
            overflow.values().forEach(stack -> player.getWorld().dropItemNaturally(player.getLocation(), stack));
        }
        player.getInventory().setItem(8, expected);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (!isMenuItem(event.getItem())) return;
        if (event.getAction() == Action.PHYSICAL) return;
        event.setCancelled(true);
        openMain(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        boolean fixedSlot = event.getClickedInventory() == player.getInventory() && event.getSlot() == 8;
        boolean hotbarSwap = event.getHotbarButton() == 8 && event.getAction().name().equals("HOTBAR_SWAP");
        if (fixedSlot || hotbarSwap || isMenuItem(event.getCurrentItem()) || isMenuItem(event.getCursor())) {
            event.setCancelled(true);
            if (isMenuItem(event.getCurrentItem()) || isMenuItem(event.getCursor())) openMain(player);
            return;
        }
        if (!(event.getInventory().getHolder() instanceof NavHolder holder)) return;
        event.setCancelled(true);
        if (!holder.owner.equals(player.getUniqueId())) return;
        int slot = event.getRawSlot();
        try {
            if (holder.page.equals("ROOT")) {
                switch (slot) {
                    case 10 -> plugin.showProfile(player);
                    case 12 -> plugin.showSkills(player);
                    case 14 -> openCollectionCategories(player);
                    case 16 -> plugin.showMinions(player);
                    case 22 -> plugin.showDaily(player);
                    case 28 -> plugin.warp(player, "nexora_hub");
                    case 30 -> player.performCommand("island go");
                    case 32 -> plugin.warp(player, "nexora_mining");
                    case 34 -> plugin.warp(player, "nexora_farming");
                    case 49 -> plugin.showProgress(player);
                    default -> { }
                }
            } else if (holder.page.equals("COLLECTION_CATEGORIES")) {
                switch (slot) {
                    case 11 -> plugin.showCollectionCategory(player, "mining");
                    case 13 -> plugin.showCollectionCategory(player, "farming");
                    case 15 -> plugin.showCollectionCategory(player, "foraging");
                    case 22 -> openMain(player);
                    default -> { }
                }
            }
        } catch (SQLException e) {
            player.sendMessage("§8[§bNEXORA§8] §7This menu could not load. Please try again.");
            plugin.getLogger().warning("Navigation menu database read failed: " + e.getMessage());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (event.getView().getTopInventory().getHolder() instanceof NavHolder) { event.setCancelled(true); return; }
        int topSize = event.getView().getTopInventory().getSize();
        if (isMenuItem(event.getOldCursor()) || event.getNewItems().values().stream().anyMatch(this::isMenuItem)
                || event.getRawSlots().stream().anyMatch(raw -> raw >= topSize && raw - topSize == 8)) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) { if (isMenuItem(event.getItemDrop().getItemStack())) event.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSwap(PlayerSwapHandItemsEvent event) { if (isMenuItem(event.getMainHandItem()) || isMenuItem(event.getOffHandItem())) event.setCancelled(true); }
    @EventHandler public void onDeath(PlayerDeathEvent event) { event.getDrops().removeIf(this::isMenuItem); }
    @EventHandler public void onRespawn(PlayerRespawnEvent event) { plugin.getServer().getScheduler().runTask(plugin, () -> ensureItem(event.getPlayer())); }

    void openMain(Player player) {
        NavHolder holder = new NavHolder(player.getUniqueId(), "ROOT");
        Inventory inv = Bukkit.createInventory(holder, 54, "§4NEXORA §8» §6Navigation"); holder.inventory = inv;
        fillFrame(inv);
        inv.setItem(10, named(Material.PLAYER_HEAD, "§bProfile", "§7Coins and your personal overview."));
        inv.setItem(12, named(Material.ENCHANTED_BOOK, "§bSkills", "§7Mining, Farming, Foraging and Combat."));
        inv.setItem(14, named(Material.CHEST, "§eCollections", "§7Browse Mining, Farming and Foraging."));
        inv.setItem(16, named(Material.ARMOR_STAND, "§6Minions", "§7Manage your island workers."));
        inv.setItem(22, named(Material.CLOCK, "§eDaily Tasks", "§7Complete three daily gathering goals.", "§7Rewards are Coins and SkyBlock XP."));
        inv.setItem(28, named(Material.BEACON, "§6Warp to Hub", "§7Return to the NEXORA plaza."));
        inv.setItem(30, named(Material.GRASS_BLOCK, "§aPrivate Island", "§7Teleport to your BentoBox island.", "§eClick to travel."));
        inv.setItem(32, named(Material.IRON_PICKAXE, "§bMining Realm", "§7Travel to the shared mining world."));
        inv.setItem(34, named(Material.WHEAT, "§eFarming Realm", "§7Travel to the shared farming world."));
        inv.setItem(49, named(Material.NETHER_STAR, "§6Progression", "§7SkyBlock level, XP, skills, minions and milestones."));
        player.openInventory(inv);
    }

    void openCollectionCategories(Player player) {
        NavHolder holder = new NavHolder(player.getUniqueId(), "COLLECTION_CATEGORIES");
        Inventory inv = Bukkit.createInventory(holder, 27, "§4NEXORA §8» §eCollections"); holder.inventory = inv;
        inv.setItem(11, named(Material.IRON_ORE, "§bMining Collections", "§7Ores and stone gathered directly."));
        inv.setItem(13, named(Material.WHEAT, "§eFarming Collections", "§7Crops and farm resources gathered."));
        inv.setItem(15, named(Material.OAK_LOG, "§aForaging Collections", "§7Wood gathered directly."));
        inv.setItem(22, named(Material.ARROW, "§7Back to NEXORA menu"));
        player.openInventory(inv);
    }

    private void fillFrame(Inventory inv) {
        ItemStack pane = named(Material.BLACK_STAINED_GLASS_PANE, "§0 ");
        for (int slot = 0; slot < inv.getSize(); slot++) if (slot < 9 || slot >= 45 || slot % 9 == 0 || slot % 9 == 8) inv.setItem(slot, pane);
    }
    private ItemStack createItem() { return named(Material.COMPASS, "§6§lNEXORA Menu", "§7Your network guide and travel menu.", "§eRight-click or click in inventory to open."); }
    private ItemStack named(Material material, String name, String... lore) {
        ItemStack item = new ItemStack(material); ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(name); meta.setLore(List.of(lore));
        if (name.equals("§6§lNEXORA Menu")) meta.getPersistentDataContainer().set(itemKey, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta); return item;
    }
    private boolean isMenuItem(ItemStack item) {
        return item != null && item.hasItemMeta() && item.getItemMeta().getPersistentDataContainer().has(itemKey, PersistentDataType.BYTE);
    }

    private static final class NavHolder implements InventoryHolder {
        private final UUID owner; private final String page; private Inventory inventory;
        private NavHolder(UUID owner, String page) { this.owner=owner;this.page=page; }
        @Override public Inventory getInventory() { return inventory; }
    }
}
