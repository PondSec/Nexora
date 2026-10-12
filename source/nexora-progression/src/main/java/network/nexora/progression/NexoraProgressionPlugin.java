package network.nexora.progression;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.block.Action;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Runtime adapter for persistent collection unlocks and the advanced compression recipe book. */
public final class NexoraProgressionPlugin extends JavaPlugin implements Listener {
    private ContentRegistry content;
    private CollectionRecipeUnlockStore unlocks;
    private NamespacedKey itemIdKey;
    private NamespacedKey itemInstanceKey;
    private NamespacedKey actionKey;
    private final Map<UUID, org.bukkit.scheduler.BukkitTask> unlockTasks = new ConcurrentHashMap<>();
    private final Map<UUID, Long> rocketCooldownUntil = new ConcurrentHashMap<>();
    private final Set<UUID> approvedMiningWarps = ConcurrentHashMap.newKeySet();

    @Override public void onEnable() {
        Plugin core = Bukkit.getPluginManager().getPlugin("NexoraCore");
        if (core == null || !core.isEnabled()) {
            getLogger().severe("NexoraCore is not enabled; progression cannot access player profiles.");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }
        itemIdKey = new NamespacedKey(this, "item_id");
        itemInstanceKey = new NamespacedKey(this, "item_instance_id");
        actionKey = new NamespacedKey(this, "recipe_action");
        try {
            content = DefaultContentCatalog.create();
            unlocks = new CollectionRecipeUnlockStore(core.getDataFolder().toPath().resolve("nexora.sqlite"));
        } catch (SQLException | RuntimeException exception) {
            getLogger().severe("Could not initialize persistent progression: " + exception.getMessage());
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }
        Bukkit.getPluginManager().registerEvents(this, this);
        // Register after BentoBox and the world plugins finish their delayed startup. Some
        // island listeners cancel cross-world teleports even when the destination is the
        // explicitly configured public Mining Realm. Only the player's own GUI warp is approved.
        Bukkit.getScheduler().runTaskLater(this, () -> Bukkit.getPluginManager().registerEvents(new Listener() {
            @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
            public void allowRequestedMiningWarp(PlayerTeleportEvent event) {
                if (!approvedMiningWarps.contains(event.getPlayer().getUniqueId())) return;
                Location to = event.getTo();
                if (to == null || to.getWorld() == null || !to.getWorld().getName().equalsIgnoreCase("nexora_mining")) return;
                if (!event.isCancelled()) return;
                event.setCancelled(false);
                getLogger().warning("Allowed the requested teleport into the public Mining Realm after another listener cancelled it: "
                        + event.getPlayer().getName());
            }
        }, this), 100L);
        getConfig().addDefault("infinite-rocket.cooldown-millis", 1500L);
        getConfig().addDefault("infinite-rocket.maximum-horizontal-speed", 2.4D);
        getConfig().addDefault("infinite-rocket.maximum-vertical-speed", 1.8D);
        getConfig().addDefault("hub-spawn.world", "nexora_hub");
        getConfig().addDefault("hub-spawn.x", -2.5D);
        getConfig().addDefault("hub-spawn.y", 64.0D);
        getConfig().addDefault("hub-spawn.z", 0.5D);
        getConfig().addDefault("hub-spawn.yaw", 0.0D);
        getConfig().addDefault("hub-spawn.pitch", 0.0D);
        getConfig().options().copyDefaults(true);
        saveConfig();
        World hub = Bukkit.getWorld(getConfig().getString("hub-spawn.world", "nexora_hub"));
        if (hub != null) {
            Location spawn = configuredHubSpawn(hub);
            if (spawn != null) hub.setSpawnLocation(spawn.getBlockX(), spawn.getBlockY(), spawn.getBlockZ());
            else getLogger().severe("Configured Hub spawn is not safe; leaving the world's saved spawn unchanged.");
        }
        if (getCommand("recipebook") != null) getCommand("recipebook").setExecutor((sender, command, label, args) -> {
            if (!(sender instanceof org.bukkit.entity.Player player)) { sender.sendMessage("Players only."); return true; }
            openRecipeBook(player);
            return true;
        });
        getLogger().info("Persistent Collection unlocks, advanced crafting and Infinite Rocket are enabled ("
                + content.collections().size() + " existing Core collections, " + content.recipes().size() + " recipes).");
    }

    @EventHandler public void onJoin(PlayerJoinEvent event) {
        var player = event.getPlayer();
        scheduleUnlockReconciliation(player.getUniqueId());
        // A Hub map replacement changes what saved Hub coordinates point at. Return players
        // who last logged out in the Hub to its known safe spawn, without moving island players.
        if (!player.getWorld().getName().equalsIgnoreCase(getConfig().getString("hub-spawn.world", "nexora_hub"))) return;
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (!player.isOnline()) return;
            World hub = Bukkit.getWorld(getConfig().getString("hub-spawn.world", "nexora_hub"));
            Location spawn = hub == null ? null : configuredHubSpawn(hub);
            if (spawn == null) {
                getLogger().warning("Skipped safe Hub return for " + player.getName() + ": configured spawn is obstructed.");
                return;
            }
            player.teleportAsync(spawn, PlayerTeleportEvent.TeleportCause.COMMAND).whenComplete((success, error) ->
                    Bukkit.getScheduler().runTask(this, () -> {
                        if (error != null || !Boolean.TRUE.equals(success)) {
                            getLogger().warning("Safe Hub return failed for " + player.getName()
                                    + (error == null ? " (teleport was cancelled)." : ": " + error.getMessage()));
                        }
                    }));
        }, 1L);
    }

    private Location configuredHubSpawn(World world) {
        double x = getConfig().getDouble("hub-spawn.x", -2.5D);
        double y = getConfig().getDouble("hub-spawn.y", 64.0D);
        double z = getConfig().getDouble("hub-spawn.z", 0.5D);
        Location spawn = new Location(world, x, y,
                z, (float) getConfig().getDouble("hub-spawn.yaw", 0.0D),
                (float) getConfig().getDouble("hub-spawn.pitch", 0.0D));
        Block ground = world.getBlockAt(spawn.getBlockX(), spawn.getBlockY() - 1, spawn.getBlockZ());
        Block feet = spawn.getBlock();
        Block head = spawn.clone().add(0, 1, 0).getBlock();
        if (!ground.getType().isSolid() || ground.isLiquid() || !feet.isPassable() || !head.isPassable()) return null;
        return spawn;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCollectionBlockBreak(BlockBreakEvent event) {
        String collection = collectionFor(event.getBlock().getType());
        if (collection == null || CORE_COLLECTIONS.contains(collection)) return;
        try {
            if (unlocks.consumePlacedBlock(event.getBlock().getWorld().getName(), event.getBlock().getX(),
                    event.getBlock().getY(), event.getBlock().getZ())) return;
        } catch (SQLException exception) {
            getLogger().warning("Could not verify collection block origin: " + exception.getMessage()); return;
        }
        recordExtendedGather(event.getPlayer().getUniqueId(), Map.of(collection, 1L));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCollectionBlockPlace(BlockPlaceEvent event) {
        String collection = collectionFor(event.getBlockPlaced().getType());
        if (collection == null || CORE_COLLECTIONS.contains(collection)) return;
        var block = event.getBlockPlaced();
        try { unlocks.markPlacedBlock(block.getWorld().getName(), block.getX(), block.getY(), block.getZ(),
                event.getPlayer().getUniqueId(), System.currentTimeMillis()); }
        catch (SQLException exception) { getLogger().warning("Could not mark placed collection block: " + exception.getMessage()); }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onCombatCollectionDrop(EntityDeathEvent event) {
        var killer = event.getEntity().getKiller();
        if (killer == null) return;
        Map<String,Long> gathered = new java.util.HashMap<>();
        for (ItemStack drop : event.getDrops()) {
            String collection = collectionFor(drop.getType());
            if (collection != null && !CORE_COLLECTIONS.contains(collection)) gathered.merge(collection, (long) drop.getAmount(), Long::sum);
        }
        if (!gathered.isEmpty()) recordExtendedGather(killer.getUniqueId(), gathered);
    }

    private static final Set<String> CORE_COLLECTIONS = Set.of("cobblestone","coal","iron","gold","diamond","emerald",
            "redstone","lapis","wheat","carrot","potato","melon","pumpkin","sugar_cane","oak_wood","spruce_wood","birch_wood");

    private void recordExtendedGather(UUID profileId, Map<String,Long> gathered) {
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            try {
                unlocks.recordCollections(profileId, gathered);
                List<String> newlyUnlocked = unlocks.unlockEligibleRecipes(profileId, content, System.currentTimeMillis());
                if (!newlyUnlocked.isEmpty()) Bukkit.getScheduler().runTask(this, () -> notifyRecipeUnlocks(profileId, newlyUnlocked));
            } catch (SQLException | RuntimeException exception) {
                getLogger().warning("New Collection progress could not be saved for " + profileId + ": " + exception.getMessage());
            }
        });
    }

    private void notifyRecipeUnlocks(UUID profileId, List<String> newlyUnlocked) {
        var player = Bukkit.getPlayer(profileId);
        if (player == null) return;
        player.sendMessage("§b§lNEXORA RECIPE UNLOCKS");
        for (String recipeId : newlyUnlocked) {
            RecipeDefinition recipe = content.recipes().get(recipeId);
            ItemDefinition output = content.items().get(recipe.outputItemId());
            player.sendMessage("§aUnlocked: §f" + displayName(output) + " Recipe");
        }
        player.sendMessage("§7Open §f/recipebook §7to craft your unlocked recipes.");
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCollectionsCommand(PlayerCommandPreprocessEvent event) {
        String command = event.getMessage().trim().split("\\s+", 2)[0].toLowerCase(java.util.Locale.ROOT);
        if (!command.equals("/collections") && !command.equals("/collection")) return;
        event.setCancelled(true);
        openCollectionBook(event.getPlayer(), 0);
    }

    /** Add reward details to Core's existing collection-category menus without replacing NexoraCore. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onCoreCollectionEntryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof org.bukkit.entity.Player player)
                || event.getRawSlot() < 0 || event.getRawSlot() >= event.getView().getTopInventory().getSize()
                || event.getView().getTopInventory().getHolder() instanceof CollectionHolder) return;
        String title = org.bukkit.ChatColor.stripColor(event.getView().getTitle());
        if (title == null || !title.toUpperCase(java.util.Locale.ROOT).contains("COLLECTIONS")) return;
        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || !clicked.hasItemMeta() || clicked.getItemMeta().displayName() == null) return;
        String label = org.bukkit.ChatColor.stripColor(clicked.getItemMeta().getDisplayName());
        if (label == null) return;
        String id = label.toLowerCase(java.util.Locale.ROOT).replace(' ', '_');
        if (!content.collections().containsKey(id)) return;
        event.setCancelled(true);
        openCollectionRewards(player, id, 0);
    }

    /** Fallback for the existing Navigation button if the legacy Core warp target is obstructed. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onMiningWarpFallback(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof org.bukkit.entity.Player player) || event.getRawSlot() != 32) return;
        String title = org.bukkit.ChatColor.stripColor(event.getView().getTitle());
        if (title == null || !title.toLowerCase(java.util.Locale.ROOT).contains("navigation")) return;
        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || !clicked.hasItemMeta()) return;
        String label = org.bukkit.ChatColor.stripColor(clicked.getItemMeta().getDisplayName());
        if (label == null || !label.equalsIgnoreCase("Mining Realm")) return;
        if (player.getWorld().getName().equalsIgnoreCase("nexora_mining")) return;
        World mining = Bukkit.getWorld("nexora_mining");
        if (mining == null) {
            getLogger().warning("Mining navigation was clicked but the nexora_mining world is not loaded.");
            player.sendMessage("§cThe Mining Realm is temporarily unavailable. Please try again shortly.");
            return;
        }
        Location target = safeMiningSpawn(mining);
        if (target == null) {
            player.sendMessage("§cCould not find a safe Mining Realm spawn. Please tell an administrator.");
            getLogger().warning("Could not find a safe Mining Realm teleport location for " + player.getName());
            return;
        }
        event.setCancelled(true);
        player.closeInventory();
        // The saved Cave spawn can be in an unloaded chunk. Paper's async teleport loads
        // the target chunk first; the synchronous Bukkit teleport can otherwise return false.
        approvedMiningWarps.add(player.getUniqueId());
        player.teleportAsync(target, PlayerTeleportEvent.TeleportCause.COMMAND)
                .whenComplete((success, error) -> Bukkit.getScheduler().runTask(this, () -> {
            approvedMiningWarps.remove(player.getUniqueId());
            if (error != null || !Boolean.TRUE.equals(success)) {
                getLogger().warning("Mining Realm teleport failed for " + player.getName()
                        + (error == null ? " (teleport was cancelled)." : ": " + error.getMessage()));
                if (player.isOnline()) player.sendMessage("§cMining Realm teleport failed. Please try again.");
                return;
            }
            if (player.isOnline()) player.sendMessage("§8[§bNEXORA§8] §7Warped to §bMining Realm§7.");
        }));
    }

    private Location safeMiningSpawn(World world) {
        Location spawn = world.getSpawnLocation();
        int originX = spawn.getBlockX(), originZ = spawn.getBlockZ();
        // Custom adventure maps often place their intentional spawn pad below a roof, arch,
        // or decorative overhang. getHighestBlockAt() would select that structure instead
        // of the configured landing pad, so validate the configured Y coordinate first.
        Block configuredGround = world.getBlockAt(originX, spawn.getBlockY() - 1, originZ);
        Block configuredFeet = world.getBlockAt(originX, spawn.getBlockY(), originZ);
        Block configuredHead = world.getBlockAt(originX, spawn.getBlockY() + 1, originZ);
        if (configuredGround.getType().isSolid() && !configuredGround.isLiquid()
                && configuredFeet.isPassable() && configuredHead.isPassable()) {
            Location configured = new Location(world, originX + 0.5, spawn.getBlockY(), originZ + 0.5,
                    spawn.getYaw(), spawn.getPitch());
            getLogger().info("Using configured Mining Realm spawn at " + originX + ","
                    + spawn.getBlockY() + "," + originZ + " (ground="
                    + configuredGround.getType() + ").");
            return configured;
        }
        getLogger().warning("Configured Mining spawn is obstructed or unsupported at " + originX + ","
                + spawn.getBlockY() + "," + originZ + " (ground=" + configuredGround.getType()
                + ", feet=" + configuredFeet.getType() + ", head=" + configuredHead.getType() + ").");
        // First look closely around the configured world spawn. This also handles small custom spawn pads.
        for (int radius = 0; radius <= 24; radius++) {
            for (int dx = -radius; dx <= radius; dx++) for (int dz = -radius; dz <= radius; dz++) {
                if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) continue;
                Location safe = safeMiningColumn(world, originX + dx, originZ + dz, spawn);
                if (safe != null) return safe;
            }
        }

        // Some existing Mining maps put the playable island farther from the saved world spawn. Search
        // already-generated chunks only (never create terrain), in expanding rings up to 512 blocks.
        int originChunkX = originX >> 4, originChunkZ = originZ >> 4;
        int[] offsets = {8, 3, 12};
        for (int radius = 2; radius <= 32; radius++) {
            for (int dx = -radius; dx <= radius; dx++) for (int dz = -radius; dz <= radius; dz++) {
                if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) continue;
                int chunkX = originChunkX + dx, chunkZ = originChunkZ + dz;
                if (!world.isChunkGenerated(chunkX, chunkZ)) continue;
                for (int offsetX : offsets) for (int offsetZ : offsets) {
                    Location safe = safeMiningColumn(world, (chunkX << 4) + offsetX,
                            (chunkZ << 4) + offsetZ, spawn);
                    if (safe != null) return safe;
                }
            }
        }
        getLogger().warning("No safe surface found in generated Mining chunks near world spawn "
                + originX + "," + spawn.getBlockY() + "," + originZ + ".");
        return null;
    }

    private Location safeMiningColumn(World world, int x, int z, Location spawn) {
        Block ground = world.getHighestBlockAt(x, z);
        if (!ground.getType().isSolid() || ground.isLiquid()) return null;
        Location feet = ground.getLocation().add(0.5, 1, 0.5);
        if (!feet.getBlock().isPassable() || !feet.clone().add(0, 1, 0).getBlock().isPassable()) return null;
        feet.setYaw(spawn.getYaw());
        feet.setPitch(spawn.getPitch());
        return feet;
    }

    private void openCollectionBook(org.bukkit.entity.Player player, int requestedPage) {
        List<CollectionDefinition> collections = content.collections().values().stream()
                .sorted(Comparator.comparing(CollectionDefinition::category).thenComparing(CollectionDefinition::id)).toList();
        int pages = Math.max(1, (collections.size() + 44) / 45);
        int page = Math.max(0, Math.min(requestedPage, pages - 1));
        CollectionHolder holder = new CollectionHolder(page, null);
        Inventory inventory = Bukkit.createInventory(holder, 54, Component.text("NEXORA | COLLECTIONS " + (page + 1) + "/" + pages));
        holder.inventory = inventory;
        int start = page * 45;
        for (int slot = 0; slot < 45 && start + slot < collections.size(); slot++) {
            CollectionDefinition collection = collections.get(start + slot);
            try {
                long count = unlocks.collectionCount(player.getUniqueId(), collection.id());
                int tier = collection.tierFor(count);
                List<Component> lore = new ArrayList<>();
                lore.add(Component.text("§7Category: §f" + collection.category()));
                lore.add(Component.text("§7Collection tier: §e" + tier + "/" + collection.tierThresholds().size()));
                lore.add(Component.text("§7Collected: §f" + count));
                List<CollectionReward> upcoming = collection.rewardsThrough(collection.tierThresholds().size()).stream()
                        .filter(reward -> !isRewardUnlocked(player, reward)).limit(2).toList();
                if (upcoming.isEmpty()) lore.add(Component.text("§aAll listed recipe rewards unlocked"));
                else for (CollectionReward reward : upcoming) {
                    RecipeDefinition recipe = content.recipes().get(reward.targetId());
                    lore.add(Component.text("§6Reward: §f" + displayName(content.items().get(recipe.outputItemId()))));
                }
                ItemStack icon = new ItemStack(material(content.items().get(collection.resourceId())));
                ItemMeta meta = icon.getItemMeta();
                meta.displayName(Component.text("§e" + titleCase(collection.id())));
                meta.lore(lore);
                meta.getPersistentDataContainer().set(actionKey, PersistentDataType.STRING, "collection:" + collection.id());
                icon.setItemMeta(meta);
                inventory.setItem(slot, icon);
            } catch (SQLException exception) {
                getLogger().warning("Could not read collection summary: " + exception.getMessage());
            }
        }
        inventory.setItem(45, action(Material.ARROW, "§ePrevious collections", page > 0 ? "collections-page:" + (page - 1) : "noop"));
        inventory.setItem(49, action(Material.BOOK, "§bCollection rewards · " + collections.size() + " collections", "noop"));
        inventory.setItem(53, action(Material.ARROW, "§eMore collections", page + 1 < pages ? "collections-page:" + (page + 1) : "noop"));
        player.openInventory(inventory);
    }

    private void openCollectionRewards(org.bukkit.entity.Player player, String collectionId, int page) {
        CollectionDefinition collection = content.collections().get(collectionId);
        if (collection == null) return;
        try {
            long count = unlocks.collectionCount(player.getUniqueId(), collectionId);
            int tier = collection.tierFor(count);
            CollectionHolder holder = new CollectionHolder(page, collectionId);
            Inventory inventory = Bukkit.createInventory(holder, 54, Component.text("COLLECTION REWARDS | " + titleCase(collectionId)));
            holder.inventory = inventory;
            ItemStack overview = new ItemStack(material(content.items().get(collection.resourceId())));
            ItemMeta overviewMeta = overview.getItemMeta();
            overviewMeta.displayName(Component.text("§e" + titleCase(collectionId) + " · Tier " + tier));
            overviewMeta.lore(List.of(Component.text("§7Collected: " + count),
                    Component.text("§7Next tier threshold: " + (tier >= collection.tierThresholds().size() ? "MAX" : collection.tierThresholds().get(tier)))));
            overview.setItemMeta(overviewMeta);
            inventory.setItem(4, overview);

            for (int i = 0; i < collection.tierThresholds().size(); i++) {
                int milestoneTier = i + 1;
                List<CollectionReward> rewards = collection.rewards().getOrDefault(milestoneTier, List.of());
                List<String> rewardNames = rewards.stream().map(CollectionReward::targetId).map(content.recipes()::get)
                        .filter(java.util.Objects::nonNull).map(RecipeDefinition::outputItemId).map(content.items()::get)
                        .filter(java.util.Objects::nonNull).map(this::displayName).toList();
                String status = tier >= milestoneTier ? "§aREACHED" : "§cLOCKED";
                String reward = rewardNames.isEmpty() ? "§7No recipe reward" : "§6Rewards: §f" + String.join(", ", rewardNames);
                inventory.setItem(10 + i, action(Material.PAPER, "§eTier " + milestoneTier + " · " + collection.tierThresholds().get(i),
                        "noop", status, reward));
            }

            List<CollectionReward> rewards = collection.rewardsThrough(collection.tierThresholds().size()).stream()
                    .sorted(Comparator.comparingInt((CollectionReward reward) -> collection.rewards().entrySet().stream()
                                    .filter(entry -> entry.getValue().contains(reward)).map(Map.Entry::getKey).findFirst().orElse(99))
                            .thenComparing(CollectionReward::id)).toList();
            int rewardStart = Math.max(0, page * 18);
            int rewardEnd = Math.min(rewards.size(), rewardStart + 18);
            for (int i = rewardStart; i < rewardEnd; i++) {
                CollectionReward reward = rewards.get(i);
                RecipeDefinition recipe = content.recipes().get(reward.targetId());
                if (recipe == null) continue;
                ItemDefinition output = content.items().get(recipe.outputItemId());
                boolean unlocked = unlocks.isUnlocked(player.getUniqueId(), recipe.id());
                ItemStack icon = itemStack(output, 1);
                ItemMeta meta = icon.getItemMeta();
                int recipeTier = recipe.collectionRequirements().get(collectionId);
                List<Component> lore = new ArrayList<>();
                lore.add(Component.text(unlocked ? "§aRECIPE UNLOCKED" : "§cRECIPE LOCKED"));
                lore.add(Component.text("§7Reward at " + titleCase(collectionId) + " tier " + recipeTier));
                for (var requirement : recipe.collectionRequirements().entrySet().stream().sorted(Map.Entry.comparingByKey()).toList()) {
                    CollectionDefinition requiredCollection = content.collections().get(requirement.getKey());
                    long requiredCount = unlocks.collectionCount(player.getUniqueId(), requirement.getKey());
                    int currentTier = requiredCollection.tierFor(requiredCount);
                    lore.add(Component.text((currentTier >= requirement.getValue() ? "§a" : "§c") + titleCase(requirement.getKey())
                            + " tier " + currentTier + "/" + requirement.getValue()));
                }
                lore.add(Component.text("§eClick to inspect this recipe and its ingredients"));
                meta.displayName(Component.text((unlocked ? "§a" : "§c") + displayName(output)));
                meta.lore(lore);
                meta.getPersistentDataContainer().set(actionKey, PersistentDataType.STRING, "collection-recipe:" + recipe.id());
                icon.setItemMeta(meta);
                inventory.setItem(27 + i - rewardStart, icon);
            }
            inventory.setItem(45, action(Material.ARROW, "§eBack to collections", "collections-page:0"));
            if (rewardStart > 0) inventory.setItem(48, action(Material.ARROW, "§ePrevious rewards", "collection:" + collectionId + ":" + (page - 1)));
            inventory.setItem(49, action(Material.BOOK, "§bCollection recipe rewards", "noop"));
            if (rewardEnd < rewards.size()) inventory.setItem(50, action(Material.ARROW, "§eMore rewards", "collection:" + collectionId + ":" + (page + 1)));
            player.openInventory(inventory);
        } catch (SQLException exception) {
            getLogger().warning("Collection rewards could not load: " + exception.getMessage());
            player.sendMessage("§cCollection rewards could not be loaded. Try again shortly.");
        }
    }

    private boolean isRewardUnlocked(org.bukkit.entity.Player player, CollectionReward reward) {
        try { return unlocks.isUnlocked(player.getUniqueId(), reward.targetId()); }
        catch (SQLException exception) { return false; }
    }

    private String titleCase(String value) {
        StringBuilder result = new StringBuilder();
        for (String word : value.replace('_', ' ').split("\\s+")) {
            if (word.isEmpty()) continue;
            if (!result.isEmpty()) result.append(' ');
            result.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return result.toString();
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInfiniteRocketUse(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        ItemStack used = event.getItem();
        if (!isInfiniteRocket(used)) return;
        event.setCancelled(true);
        var player = event.getPlayer();
        if (!InfiniteRocketRules.eligible(player.isGliding(), player.getInventory().getChestplate() != null
                && player.getInventory().getChestplate().getType() == Material.ELYTRA)) return;
        long now = System.currentTimeMillis();
        long availableAt = rocketCooldownUntil.getOrDefault(player.getUniqueId(), 0L);
        if (!InfiniteRocketRules.cooldownReady(now, availableAt)) {
            player.sendActionBar(Component.text("§7Rocket recharging..."));
            return;
        }
        long cooldown = Math.max(250L, getConfig().getLong("infinite-rocket.cooldown-millis", 1500L));
        rocketCooldownUntil.put(player.getUniqueId(), InfiniteRocketRules.nextReadyAt(now, cooldown));
        // Paper's native Elytra boost keeps vanilla rocket motion and sound without spawning a damaging firework.
        player.boostElytra(new ItemStack(Material.FIREWORK_ROCKET));
        var velocity = player.getVelocity();
        double horizontalCap = Math.max(0.5, getConfig().getDouble("infinite-rocket.maximum-horizontal-speed", 2.4));
        double verticalCap = Math.max(0.5, getConfig().getDouble("infinite-rocket.maximum-vertical-speed", 1.8));
        InfiniteRocketRules.Velocity capped = InfiniteRocketRules.cap(
                new InfiniteRocketRules.Velocity(velocity.getX(), velocity.getY(), velocity.getZ()), horizontalCap, verticalCap);
        velocity.setX(capped.x()); velocity.setY(capped.y()); velocity.setZ(capped.z());
        player.setVelocity(velocity);
        player.getWorld().spawnParticle(org.bukkit.Particle.FIREWORK, player.getLocation().add(0, 0.4, 0), 8, 0.12, 0.12, 0.12, 0.02);
        player.playSound(player.getLocation(), org.bukkit.Sound.ENTITY_FIREWORK_ROCKET_LAUNCH, 0.35f, 1.35f);
        player.sendActionBar(Component.text("§d§lETERNAL THRUST ACTIVATED"));
    }

    private boolean isInfiniteRocket(ItemStack item) {
        return item != null && item.getType() == Material.FIREWORK_ROCKET && item.hasItemMeta()
                && "nexora:infinite_rocket".equals(item.getItemMeta().getPersistentDataContainer()
                .get(itemIdKey, PersistentDataType.STRING));
    }

    @EventHandler public void onQuit(PlayerQuitEvent event) {
        org.bukkit.scheduler.BukkitTask pending = unlockTasks.remove(event.getPlayer().getUniqueId());
        if (pending != null) pending.cancel();
    }

    private void scheduleUnlockReconciliation(UUID profileId) {
        org.bukkit.scheduler.BukkitTask previous = unlockTasks.remove(profileId);
        if (previous != null) previous.cancel();
        unlockTasks.put(profileId, Bukkit.getScheduler().runTaskLater(this, () -> {
            unlockTasks.remove(profileId);
            Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            List<String> newlyUnlocked;
            try { newlyUnlocked = unlocks.unlockEligibleRecipes(profileId, content, System.currentTimeMillis()); }
            catch (SQLException | RuntimeException exception) {
                getLogger().warning("Recipe unlock reconciliation failed for " + profileId + ": " + exception.getMessage());
                return;
            }
            if (newlyUnlocked.isEmpty()) return;
            Bukkit.getScheduler().runTask(this, () -> {
                var player = Bukkit.getPlayer(profileId);
                if (player == null) return;
                player.sendMessage("§b§lNEXORA RECIPE UNLOCKS");
                for (String recipeId : newlyUnlocked) {
                    RecipeDefinition recipe = content.recipes().get(recipeId);
                    ItemDefinition output = content.items().get(recipe.outputItemId());
                    player.sendMessage("§aUnlocked: §f" + displayName(output) + " Recipe");
                }
                player.sendMessage("§7Open §f/recipebook §7to craft your unlocked recipes.");
            });
            });
        }, 5L));
    }

    private String collectionFor(Material material) {
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
            default -> null;
        };
    }

    private void openRecipeBook(org.bukkit.entity.Player player) { openRecipeBook(player, 0); }

    private void openRecipeBook(org.bukkit.entity.Player player, int requestedPage) {
        try {
            Set<String> unlocked = unlocks.unlockedRecipes(player.getUniqueId());
            List<RecipeDefinition> recipes = content.recipes().values().stream()
                    .sorted(Comparator.comparingInt((RecipeDefinition recipe) -> recipe.id().equals("nexora:infinite_rocket") ? 0
                                    : recipe.id().matches("nexora:(condensed_pyrocell|eternal_fuel_cell|astral_navigation_core|aerodynamic_stabilizer|reinforced_rocket_chassis)") ? 1 : 2)
                            .thenComparing(RecipeDefinition::id)).toList();
            int pages = Math.max(1, (recipes.size() + 44) / 45);
            int page = Math.max(0, Math.min(requestedPage, pages - 1));
            RecipeHolder holder = new RecipeHolder(page);
            Inventory inventory = Bukkit.createInventory(holder, 54, Component.text("NEXORA | RECIPE BOOK " + (page + 1) + "/" + pages));
            holder.inventory = inventory;
            int start = page * 45;
            for (int slot = 0; slot < 45 && start + slot < recipes.size(); slot++) {
                RecipeDefinition recipe = recipes.get(start + slot);
                boolean known = unlocked.contains(recipe.id());
                ItemDefinition output = content.items().get(recipe.outputItemId());
                ItemStack icon = itemStack(output, 1);
                ItemMeta meta = icon.getItemMeta();
                meta.displayName(Component.text((known ? "§a" : "§c") + displayName(output)));
                List<Component> lore = new ArrayList<>();
                if (recipe.id().equals("nexora:infinite_rocket")) lore.add(Component.text("§dLEGENDARY & MYTHIC UTILITIES"));
                lore.add(Component.text(known ? "§aRecipe Status: UNLOCKED" : "§cRecipe Status: LOCKED"));
                for (var requirement : recipe.collectionRequirements().entrySet().stream().sorted(Map.Entry.comparingByKey()).toList()) {
                    CollectionDefinition collection = content.collections().get(requirement.getKey());
                    long count = unlocks.collectionCount(player.getUniqueId(), requirement.getKey());
                    boolean met = collection.tierFor(count) >= requirement.getValue();
                    lore.add(Component.text((met ? "§a" : "§c") + requirement.getKey() + " " + (met ? "✓" : "tier " + requirement.getValue())));
                }
                if (recipe.outputItemId().equals("nexora:infinite_rocket")) {
                    lore.add(Component.text("§6Ability: Eternal Thrust · 1.5 Seconds"));
                    lore.add(Component.text("§7Infinite Elytra Boost"));
                }
                lore.add(Component.text("§7Inputs: " + describeIngredients(recipe)));
                lore.add(Component.text(known ? "§eClick for advanced crafting" : "§7Collect resources to unlock it"));
                meta.lore(lore);
                meta.getPersistentDataContainer().set(actionKey, PersistentDataType.STRING,
                        known ? "recipe:" + recipe.id() : "locked:" + recipe.id());
                icon.setItemMeta(meta);
                inventory.setItem(slot, icon);
            }
            inventory.setItem(45, action(Material.ARROW, "§ePrevious recipes", page > 0 ? "page:" + (page - 1) : "noop"));
            inventory.setItem(49, action(Material.BOOK, "§b" + (page + 1) + "/" + pages + " · " + unlocked.size() + "/" + recipes.size() + " unlocked", "noop"));
            inventory.setItem(53, action(Material.ARROW, "§eMore recipes", page + 1 < pages ? "page:" + (page + 1) : "noop"));
            player.openInventory(inventory);
        } catch (SQLException exception) {
            getLogger().warning("Recipe Book could not load: " + exception.getMessage());
            player.sendMessage("§cYour Recipe Book could not be loaded. Try again shortly.");
        }
    }

    private void openRecipe(org.bukkit.entity.Player player, String recipeId) throws SQLException {
        openRecipe(player, recipeId, 0);
    }

    private void openRecipe(org.bukkit.entity.Player player, String recipeId, int backPage) throws SQLException {
        RecipeDefinition recipe = content.recipes().get(recipeId);
        if (recipe == null) {
            player.sendMessage("§cThat recipe is unavailable.");
            openRecipeBook(player);
            return;
        }
        boolean known = unlocks.isUnlocked(player.getUniqueId(), recipeId);
        RecipeHolder holder = new RecipeHolder(backPage);
        Inventory inventory = Bukkit.createInventory(holder, 54, Component.text("RECIPE | " + displayName(content.items().get(recipe.outputItemId()))));
        holder.inventory = inventory;
        ItemStack output = itemStack(content.items().get(recipe.outputItemId()), Math.toIntExact(recipe.outputAmount()));
        ItemMeta outputMeta = output.getItemMeta();
        outputMeta.displayName(Component.text("§aOutput: " + displayName(content.items().get(recipe.outputItemId()))));
        List<Component> outputLore = new ArrayList<>();
        outputLore.add(Component.text("§7Each craft creates " + recipe.outputAmount()));
        if (recipe.outputItemId().equals("nexora:infinite_rocket")) {
            var cost = new CraftingCostCalculator(content).calculate(recipe.outputItemId(), java.math.BigInteger.ONE,
                    Map.of(), Map.of());
            outputLore.add(Component.text("§6Full chain raw-material cost:"));
            for (var raw : cost.rawMaterials().entrySet())
                outputLore.add(Component.text("§7" + raw.getValue() + " × " + displayName(content.items().get(raw.getKey()))));
            outputLore.add(Component.text("§8Live market prices and production rates are not configured yet."));
            outputLore.add(Component.text("§dMYTHIC · LEGENDARY & MYTHIC UTILITIES"));
            outputLore.add(Component.text("§6Ability: Eternal Thrust"));
            outputLore.add(Component.text("§7Infinite Elytra Boost · Cooldown: 1.5 Seconds"));
            outputLore.add(Component.text("§7A legendary rocket powered by an eternal core."));
            outputLore.add(Component.text("§7Unlimited Elytra propulsion without consuming fireworks."));
        }
        outputLore.add(Component.text(known ? "§aRecipe Status: UNLOCKED" : "§cRecipe Status: LOCKED"));
        for (var requirement : recipe.collectionRequirements().entrySet().stream().sorted(Map.Entry.comparingByKey()).toList()) {
            CollectionDefinition collection = content.collections().get(requirement.getKey());
            long count = unlocks.collectionCount(player.getUniqueId(), requirement.getKey());
            int tier = collection.tierFor(count);
            outputLore.add(Component.text((tier >= requirement.getValue() ? "§a" : "§c") + collection.id()
                    + " tier " + tier + "/" + requirement.getValue() + " · " + count + "/" + collection.tierThresholds().get(requirement.getValue()-1)));
        }
        outputMeta.lore(outputLore);
        output.setItemMeta(outputMeta);
        inventory.setItem(4, output);
        int[] ingredientSlots = {10,11,12,13,14,15,16,19,20,21,22,23,24,25};
        int ingredientIndex = 0;
        for (var entry : recipe.ingredients().entrySet().stream().sorted(Map.Entry.comparingByKey()).toList()) {
            ItemDefinition ingredient = content.items().get(entry.getKey());
            ItemStack icon = itemStack(ingredient, 1);
            ItemMeta meta = icon.getItemMeta();
            meta.displayName(Component.text("§f" + displayName(ingredient) + " × " + entry.getValue()));
            String childRecipe = content.recipes().values().stream().filter(candidate -> candidate.outputItemId().equals(entry.getKey()))
                    .map(RecipeDefinition::id).sorted().findFirst().orElse(null);
            List<Component> ingredientLore = new ArrayList<>();
            ingredientLore.add(Component.text("§7Required: " + entry.getValue()));
            long have = itemCount(player, entry.getKey());
            ingredientLore.add(Component.text((have >= entry.getValue() ? "§a" : "§c") + "Have: " + have + "/" + entry.getValue()));
            ingredientLore.add(Component.text(childRecipe == null ? "§7Gather: " + displayName(ingredient) : "§eClick to inspect its recipe"));
            if (childRecipe != null) meta.getPersistentDataContainer().set(actionKey, PersistentDataType.STRING, "recipe:" + childRecipe);
            else meta.getPersistentDataContainer().set(actionKey, PersistentDataType.STRING, "material:" + entry.getKey());
            meta.lore(ingredientLore);
            icon.setItemMeta(meta);
            if (ingredientIndex < ingredientSlots.length) inventory.setItem(ingredientSlots[ingredientIndex++], icon);
        }
        if (known) {
            inventory.setItem(46, action(Material.LIME_DYE, "§aCraft ×1", "craft:" + recipeId + ":1:" + backPage));
            inventory.setItem(49, action(Material.LIME_DYE, "§aCraft ×4", "craft:" + recipeId + ":4:" + backPage));
            inventory.setItem(52, action(Material.LIME_DYE, "§aCraft ×16", "craft:" + recipeId + ":16:" + backPage));
        } else inventory.setItem(49, action(Material.BARRIER, "§cLocked · see missing collections above", "noop"));
        inventory.setItem(53, action(Material.ARROW, "§eBack to Recipe Book", "book:" + backPage));
        player.openInventory(inventory);
    }

    private void craft(org.bukkit.entity.Player player, String recipeId, int times, int backPage) throws SQLException {
        RecipeDefinition recipe = content.recipes().get(recipeId);
        if (recipe == null || times < 1 || times > 16 || !unlocks.isUnlocked(player.getUniqueId(), recipeId)) {
            player.sendMessage("§cThis recipe is unavailable on your profile.");
            return;
        }
        ItemDefinition outputDefinition = content.items().get(recipe.outputItemId());
        long outputAmount = Math.multiplyExact(recipe.outputAmount(), times);
        ItemStack[] next = cloneItems(player.getInventory().getStorageContents());
        for (var ingredient : recipe.ingredients().entrySet()) {
            long required = Math.multiplyExact(ingredient.getValue(), times);
            if (!remove(next, ingredient.getKey(), required)) {
                player.sendMessage("§cYou do not have enough ingredients for that quantity.");
                return;
            }
        }
        if (!insert(next, outputDefinition, outputAmount)) {
            player.sendMessage("§cMake room in your inventory before crafting.");
            return;
        }
        player.getInventory().setStorageContents(next);
        player.saveData();
        player.sendMessage("§aCrafted " + outputAmount + " × " + displayName(outputDefinition) + ".");
        openRecipe(player, recipeId, backPage);
    }

    private boolean remove(ItemStack[] inventory, String itemId, long amount) {
        long remaining = amount;
        for (int slot = 0; slot < inventory.length && remaining > 0; slot++) {
            ItemStack stack = inventory[slot];
            if (stack == null || stack.getType().isAir() || !matches(stack, itemId)) continue;
            int taken = (int) Math.min(remaining, stack.getAmount());
            remaining -= taken;
            if (taken == stack.getAmount()) inventory[slot] = null;
            else stack.setAmount(stack.getAmount() - taken);
        }
        return remaining == 0;
    }

    private boolean insert(ItemStack[] inventory, ItemDefinition definition, long amount) {
        Material material = material(definition);
        int maxStack = definition.id().equals("nexora:infinite_rocket") ? 1 : material.getMaxStackSize();
        ItemStack prototype = itemStack(definition, 1);
        long remaining = amount;
        for (ItemStack stack : inventory) {
            if (stack == null || stack.getType().isAir() || !stack.isSimilar(prototype)) continue;
            int space = maxStack - stack.getAmount();
            int moved = (int) Math.min(remaining, Math.max(0, space));
            stack.setAmount(stack.getAmount() + moved);
            remaining -= moved;
            if (remaining == 0) return true;
        }
        for (int slot = 0; slot < inventory.length && remaining > 0; slot++) {
            if (inventory[slot] != null && !inventory[slot].getType().isAir()) continue;
            int moved = (int) Math.min(remaining, maxStack);
            inventory[slot] = itemStack(definition, moved);
            remaining -= moved;
        }
        return remaining == 0;
    }

    private boolean matches(ItemStack stack, String itemId) {
        String actual = stack.getItemMeta() == null ? null
                : stack.getItemMeta().getPersistentDataContainer().get(itemIdKey, PersistentDataType.STRING);
        if (itemId.startsWith("nexora:")) return itemId.equals(actual);
        if (actual != null || stack.hasItemMeta()) return false;
        return stack.getType() == material(content.items().get(itemId));
    }

    private long itemCount(org.bukkit.entity.Player player, String itemId) {
        long count = 0;
        for (ItemStack stack : player.getInventory().getStorageContents())
            if (stack != null && !stack.getType().isAir() && matches(stack, itemId)) count += stack.getAmount();
        return count;
    }

    private void explainMaterial(org.bukkit.entity.Player player, String itemId) throws SQLException {
        String collectionId = content.collections().values().stream()
                .filter(collection -> collection.resourceId().equals(itemId)).map(CollectionDefinition::id).findFirst().orElse(null);
        if (collectionId == null) { player.sendMessage("§eGather or craft " + itemId + "."); return; }
        CollectionDefinition collection = content.collections().get(collectionId);
        long count = unlocks.collectionCount(player.getUniqueId(), collectionId);
        int tier = collection.tierFor(count);
        long next = tier >= collection.tierThresholds().size() ? 0 : collection.tierThresholds().get(tier);
        player.sendMessage("§b" + displayName(content.items().get(itemId)) + " · Collection: §f" + collectionId
                + " §7Tier " + tier + "/" + collection.tierThresholds().size()
                + (next == 0 ? " · §aMax tier" : " · §7Next tier " + count + "/" + next));
    }

    private ItemStack itemStack(ItemDefinition definition, int amount) {
        int safeAmount = definition.id().equals("nexora:infinite_rocket") ? 1 : Math.max(1, amount);
        ItemStack stack = new ItemStack(material(definition), safeAmount);
        if (!definition.id().startsWith("nexora:")) return stack;
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(Component.text((definition.id().equals("nexora:infinite_rocket") ? "§d§l" : "§d") + displayName(definition)));
        List<Component> lore = new ArrayList<>();
        lore.add(Component.text("§7" + definition.rarity() + " Nexora item"));
        if (definition.id().equals("nexora:infinite_rocket")) {
            lore.add(Component.text("§6Ability: Eternal Thrust"));
            lore.add(Component.text("§7A legendary rocket powered by an eternal core."));
            lore.add(Component.text("§7Provides unlimited Elytra propulsion without consuming fireworks."));
            lore.add(Component.text("§7Cooldown: 1.5 seconds"));
            lore.add(Component.text("§8Item ID: " + definition.id()));
        } else lore.add(Component.text("§8Item ID: " + definition.id()));
        meta.lore(lore);
        meta.getPersistentDataContainer().set(itemIdKey, PersistentDataType.STRING, definition.id());
        if (definition.id().equals("nexora:infinite_rocket"))
            meta.getPersistentDataContainer().set(itemInstanceKey, PersistentDataType.STRING, UUID.randomUUID().toString());
        stack.setItemMeta(meta);
        return stack;
    }

    private ItemStack action(Material material, String title, String action) {
        return action(material, title, action, new String[0]);
    }

    private ItemStack action(Material material, String title, String action, String... loreLines) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(Component.text(title));
        if (loreLines.length > 0) meta.lore(java.util.Arrays.stream(loreLines).map(Component::text).toList());
        meta.getPersistentDataContainer().set(actionKey, PersistentDataType.STRING, action);
        stack.setItemMeta(meta);
        return stack;
    }

    private Material material(ItemDefinition definition) {
        Material material = Material.matchMaterial(definition.materialKey());
        if (material == null || !material.isItem()) throw new IllegalStateException("Unknown item material " + definition.materialKey());
        return material;
    }

    private String displayName(ItemDefinition definition) {
        String raw = definition.id().replace("nexora:double_enchanted_", "Double Enchanted ")
                .replace("nexora:enchanted_", "Enchanted ").replace("minecraft:", "").replace('_', ' ');
        StringBuilder title = new StringBuilder();
        for (String word : raw.split("\\s+")) {
            if (word.isEmpty()) continue;
            if (!title.isEmpty()) title.append(' ');
            title.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return title.toString();
    }

    private String describeIngredients(RecipeDefinition recipe) {
        return recipe.ingredients().entrySet().stream().sorted(Map.Entry.comparingByKey())
                .map(entry -> entry.getValue() + " × " + displayName(content.items().get(entry.getKey())))
                .collect(java.util.stream.Collectors.joining(", "));
    }

    private ItemStack[] cloneItems(ItemStack[] items) {
        ItemStack[] copy = new ItemStack[items.length];
        for (int i = 0; i < items.length; i++) copy[i] = items[i] == null ? null : items[i].clone();
        return copy;
    }

    @EventHandler public void onRecipeClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof RecipeHolder)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof org.bukkit.entity.Player player) || event.getRawSlot() < 0
                || event.getRawSlot() >= event.getView().getTopInventory().getSize()) return;
        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || !clicked.hasItemMeta()) return;
        String action = clicked.getItemMeta().getPersistentDataContainer().get(actionKey, PersistentDataType.STRING);
        if (action == null || action.equals("noop")) return;
        try {
            if (action.equals("book")) openRecipeBook(player);
            else if (action.startsWith("book:")) openRecipeBook(player, Integer.parseInt(action.substring("book:".length())));
            else if (action.startsWith("page:")) openRecipeBook(player, Integer.parseInt(action.substring("page:".length())));
            else if (action.startsWith("recipe:")) openRecipe(player, action.substring("recipe:".length()), ((RecipeHolder)event.getView().getTopInventory().getHolder()).page);
            else if (action.startsWith("locked:")) openRecipe(player, action.substring("locked:".length()), ((RecipeHolder)event.getView().getTopInventory().getHolder()).page);
            else if (action.startsWith("material:")) explainMaterial(player, action.substring("material:".length()));
            else if (action.startsWith("craft:")) {
                String[] pieces = action.split(":");
                String recipeId = pieces[1] + ":" + pieces[2];
                craft(player, recipeId, Integer.parseInt(pieces[3]), pieces.length > 4 ? Integer.parseInt(pieces[4]) : 0);
            }
        } catch (SQLException | RuntimeException exception) {
            getLogger().warning("Recipe GUI action failed: " + exception.getMessage());
            player.sendMessage("§cCrafting failed safely; no ingredients were consumed.");
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onCollectionClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof CollectionHolder holder)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof org.bukkit.entity.Player player) || event.getRawSlot() < 0
                || event.getRawSlot() >= event.getView().getTopInventory().getSize()) return;
        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || !clicked.hasItemMeta()) return;
        String action = clicked.getItemMeta().getPersistentDataContainer().get(actionKey, PersistentDataType.STRING);
        if (action == null || action.equals("noop")) return;
        try {
            if (action.startsWith("collections-page:")) openCollectionBook(player, Integer.parseInt(action.substring("collections-page:".length())));
            else if (action.startsWith("collection-recipe:")) openRecipe(player, action.substring("collection-recipe:".length()), 0);
            else if (action.startsWith("collection:")) {
                String[] parts = action.substring("collection:".length()).split(":", 2);
                openCollectionRewards(player, parts[0], parts.length > 1 ? Integer.parseInt(parts[1]) : 0);
            }
        } catch (SQLException | RuntimeException exception) {
            getLogger().warning("Collection menu action failed: " + exception.getMessage());
            player.sendMessage("§cThat collection reward could not be loaded.");
        }
    }

    @EventHandler public void onCollectionDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof CollectionHolder) event.setCancelled(true);
    }

    @EventHandler public void onRecipeDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof RecipeHolder) event.setCancelled(true);
    }

    private static final class RecipeHolder implements InventoryHolder {
        private final int page;
        private Inventory inventory;
        private RecipeHolder(int page) { this.page = page; }
        @Override public Inventory getInventory() { return inventory; }
    }

    private static final class CollectionHolder implements InventoryHolder {
        private final int page;
        private final String collectionId;
        private Inventory inventory;
        private CollectionHolder(int page, String collectionId) { this.page = page; this.collectionId = collectionId; }
        @Override public Inventory getInventory() { return inventory; }
    }
}
