package network.nexora.core;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import world.bentobox.bentobox.BentoBox;
import world.bentobox.bentobox.api.flags.Flag;
import world.bentobox.bentobox.api.user.User;
import world.bentobox.bentobox.database.objects.Island;

import java.sql.SQLException;
import java.util.*;

/** Island-checked, timestamp-based Nexora minions. Production is persisted, not ticked per entity. */
final class MinionService implements Listener {
    private static final String PREFIX = "§8[§bNEXORA§8] §7";
    private static final int[] STORAGE_SLOTS = {10,11,12,13,14,15,16,19,20,21,22,23,24,25,28,29,30,31,32,33,34,37,38,39,40,41,42,43};
    private final JavaPlugin plugin;
    private final ProgressStore store;
    private final NamespacedKey itemTypeKey;
    private final NamespacedKey entityIdKey;
    private final NamespacedKey tierKey;
    private final Map<String, MinionType> types = new LinkedHashMap<>();
    private final Set<ChunkKey> pendingChunks = new LinkedHashSet<>();
    private record ChunkKey(UUID world,int x,int z) {}

    MinionService(JavaPlugin plugin, ProgressStore store) {
        this.plugin = plugin;
        this.store = store;
        itemTypeKey = new NamespacedKey(plugin, "minion_item_type");
        entityIdKey = new NamespacedKey(plugin, "minion_id");
        tierKey = new NamespacedKey(plugin, "minion_tier");
        for (MinionType type : MinionType.values()) types.put(type.key, type);
        registerRecipes();
        Bukkit.getScheduler().runTaskTimer(plugin,()->{
            var box=BentoBox.getInstance();if(box==null||!box.isLoaded())return;
            int budget=4;var iterator=pendingChunks.iterator();
            List<ChunkKey> ready=new ArrayList<>();
            while(iterator.hasNext()&&budget-->0){ready.add(iterator.next());iterator.remove();}
            for(var key:ready){World world=Bukkit.getWorld(key.world());if(world!=null&&world.isChunkLoaded(key.x(),key.z()))restoreChunk(world.getChunkAt(key.x(),key.z()));}
        },20L,20L);
    }

    boolean command(Player player, String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            player.sendMessage("§b/minion list §7| §b/minion give <player> <type> §7(admin)");
            return true;
        }
        try {
            if (args[0].equalsIgnoreCase("list")) {
                List<ProgressStore.MinionRow> rows = store.minionsForOwner(player.getUniqueId());
                if (rows.isEmpty()) { player.sendMessage(PREFIX + "You have no minions placed."); return true; }
                player.sendMessage("§3§lNEXORA MINIONS §8(" + rows.size() + ")");
                for (ProgressStore.MinionRow row : rows) {
                    ProgressStore.MinionRow fresh = advance(row);
                    player.sendMessage("§b" + type(fresh.type()).label + " Minion §7T" + fresh.tier()
                            + " §8• §7Storage " + fresh.storage() + "/" + capacity(fresh.tier())
                            + " §8• §7" + fresh.world() + " " + fresh.x() + "," + fresh.y() + "," + fresh.z());
                }
                return true;
            }
            if (args[0].equalsIgnoreCase("give") && args.length == 3) {
                if (!player.hasPermission("nexora.minion.admin")) { player.sendMessage("§cNo permission."); return true; }
                Player target = Bukkit.getPlayerExact(args[1]);
                MinionType type = types.get(args[2].toLowerCase(Locale.ROOT));
                if (target == null || type == null) { player.sendMessage(PREFIX + "Usage: /minion give <online-player> <" + String.join("|", types.keySet()) + ">"); return true; }
                ItemStack item = minionItem(type);
                Map<Integer, ItemStack> overflow = target.getInventory().addItem(item);
                overflow.values().forEach(stack -> target.getWorld().dropItemNaturally(target.getLocation(), stack));
                player.sendMessage(PREFIX + "Gave " + type.label + " Minion to " + target.getName() + ".");
                return true;
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Minion database operation failed: " + e.getMessage());
            player.sendMessage(PREFIX + "Minion data is temporarily unavailable.");
            return true;
        }
        player.sendMessage(PREFIX + "Usage: /minion list or /minion give <player> <type>.");
        return true;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null) return;
        ItemStack held = event.getItem();
        MinionType type = itemType(held);
        if (type == null) return;
        event.setCancelled(true);
        Player player = event.getPlayer();
        Block clicked = event.getClickedBlock();
        Block target = clicked.getRelative(Objects.requireNonNullElse(event.getBlockFace(), org.bukkit.block.BlockFace.UP));
        if (!target.getType().isAir() || target.getY() < target.getWorld().getMinHeight()) {
            player.sendMessage(PREFIX + "Place minions on an empty block above the island floor."); return;
        }
        Optional<Island> island = islandAt(clicked.getLocation());
        if (island.isEmpty() || !island.get().isOwned() || !isAllowed(island.get(), player)) {
            player.sendMessage(PREFIX + "Minions can only be placed where BentoBox allows island building."); return;
        }
        Island ownedIsland = island.get();
        try {
            ProgressionMath.LevelProgress sky=ProgressionMath.sky(store.skyblockXp(player.getUniqueId()),((NexoraCore)plugin).skyCurve());
            int limit = ((NexoraCore)plugin).unlockedMinionSlots(sky.level());
            if (store.countMinionsOnIsland(ownedIsland.getUniqueId()) >= limit) {
                player.sendMessage(PREFIX + "This island has reached its minion limit of " + limit + "."); return;
            }
            Location at = target.getLocation().add(0.5, 0.02, 0.5);
            String id = UUID.randomUUID().toString();
            int portableTier = held.getItemMeta().getPersistentDataContainer().getOrDefault(tierKey, PersistentDataType.INTEGER, 1);
            if (portableTier < 1 || portableTier > 11) { player.sendMessage(PREFIX + "Invalid minion tier."); return; }
            ItemStack[] after=cloneContents(player);
            int heldSlot=event.getHand()==org.bukkit.inventory.EquipmentSlot.OFF_HAND?40:player.getInventory().getHeldItemSlot();
            after[heldSlot]=held.clone();after[heldSlot].setAmount(held.getAmount()-1);if(after[heldSlot].getAmount()==0)after[heldSlot]=null;
            ((NexoraCore)plugin).applyInventoryOperation(player,network.nexora.core.api.InventoryJournal.Domain.MINION_PLACE,id,after);
            try {
                store.createMinion(id, player.getUniqueId(), ownedIsland.getUniqueId(),
                        at.getWorld().getName(), target.getX(), target.getY(), target.getZ(), type.key, portableTier, System.currentTimeMillis());
            }finally{((NexoraCore)plugin).resolveInventoryOperation(player);}
            spawn(type,at,id,portableTier,0,capacity(portableTier));
            long oldXp=store.skyblockXp(player.getUniqueId());
            if(store.awardMinionType(player.getUniqueId(),type.key)) {
                ((NexoraCore)plugin).notifySkyXp(player,oldXp,store.skyblockXp(player.getUniqueId()));
                player.sendMessage(PREFIX+"First §b"+type.label+" Minion §7unlocked: §e+25 SkyBlock XP§7.");
            }
            player.sendMessage(PREFIX + type.label + " Minion placed. Right-click it to manage its storage and tier.");
        } catch (SQLException e) {
            plugin.getLogger().severe("Could not place minion: " + e.getMessage());
            player.sendMessage(PREFIX + "The minion could not be saved. Your item was kept.");
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMinionInteract(PlayerInteractAtEntityEvent event) {
        String id = event.getRightClicked().getPersistentDataContainer().get(entityIdKey, PersistentDataType.STRING);
        if (id == null) return;
        event.setCancelled(true);
        if (!(event.getRightClicked() instanceof ArmorStand stand)) return;
        try {
            ProgressStore.MinionRow row = store.minion(id);
            if (row == null || !isCorrectIsland(row, stand.getLocation())) { stand.remove(); return; }
            if (!row.owner().equals(event.getPlayer().getUniqueId())) {
                event.getPlayer().sendMessage(PREFIX + "Only the minion's owner can collect or upgrade it."); return;
            }
            if (event.getPlayer().isSneaking()) { pickup(event.getPlayer(), stand, advance(row)); return; }
            openMenu(event.getPlayer(), advance(row));
        } catch (SQLException e) {
            plugin.getLogger().severe("Could not open minion menu: " + e.getMessage());
            event.getPlayer().sendMessage(PREFIX + "Minion data is temporarily unavailable.");
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMinionDamage(EntityDamageByEntityEvent event) {
        String id = event.getEntity().getPersistentDataContainer().get(entityIdKey, PersistentDataType.STRING);
        if (id == null) return;
        event.setCancelled(true);
    }

    private void pickup(Player player, Entity entity, ProgressStore.MinionRow row) {
        try {
            if (row.storage() > 0) { player.sendMessage(PREFIX + "Collect the stored resources before picking this minion up."); return; }
            if (player.getInventory().firstEmpty() < 0) { player.sendMessage(PREFIX + "Make one free inventory slot before picking this minion up."); return; }
            String operation=UUID.randomUUID().toString();ItemStack[] after=cloneContents(player);
            after[player.getInventory().firstEmpty()]=minionItem(type(row.type()),row.tier());
            ((NexoraCore)plugin).applyInventoryOperation(player,network.nexora.core.api.InventoryJournal.Domain.MINION_PICKUP,operation,after);
            boolean picked;
            try { picked=store.pickupMinion(row.id(),player.getUniqueId(),operation); }
            finally{((NexoraCore)plugin).resolveInventoryOperation(player);}
            if(!picked)return;
            entity.remove();
            player.sendMessage(PREFIX + "Minion picked up.");
        } catch (SQLException e) {
            plugin.getLogger().severe("Could not pick up minion: " + e.getMessage());
            player.sendMessage(PREFIX + "The minion could not be picked up.");
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMenuClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof MinionHolder holder)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;
        try {
            ProgressStore.MinionRow row = store.minion(holder.id);
            if (row == null || !row.owner().equals(player.getUniqueId())) { player.closeInventory(); return; }
            row = advance(row);
            int slot = event.getRawSlot();
            if (slot == 6) collect(player, row);
            else if (slot == 7) upgrade(player, row);
            else {
                int storageIndex = storageSlotIndex(slot);
                if (storageIndex >= 0) collectSlot(player, row, storageIndex);
            }
            player.closeInventory();
            ProgressStore.MinionRow updated = store.minion(holder.id);
            if (updated != null) openMenu(player, advance(updated));
        } catch (SQLException e) {
            plugin.getLogger().severe("Minion menu action failed: " + e.getMessage());
            player.sendMessage(PREFIX + "That minion action could not be completed.");
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMinionChunkLoad(org.bukkit.event.world.ChunkLoadEvent event) {
        if(!event.getWorld().getName().equalsIgnoreCase("bskyblock_world"))return;
        pendingChunks.add(new ChunkKey(event.getWorld().getUID(),event.getChunk().getX(),event.getChunk().getZ()));
    }
    private void restoreChunk(org.bukkit.Chunk chunk) {
        Set<String> active = new HashSet<>();
        for (Entity entity : chunk.getEntities()) {
            String id = entity.getPersistentDataContainer().get(entityIdKey, PersistentDataType.STRING);
            if (id == null) continue;
            try {
                ProgressStore.MinionRow row = store.minion(id);
                if (row == null || !isCorrectIsland(row, entity.getLocation())) entity.remove();
                else active.add(id);
            } catch (SQLException e) { plugin.getLogger().warning("Could not validate saved minion entity: " + e.getMessage()); }
        }
        try {
            for (ProgressStore.MinionRow row : store.minionsInChunk(chunk.getWorld().getName(), chunk.getX(), chunk.getZ())) {
                Location location = new Location(chunk.getWorld(), row.x() + 0.5, row.y() + 0.02, row.z() + 0.5);
                if (islandAt(location).filter(i -> i.getUniqueId().equals(row.islandId())).isEmpty()) {
                    // A missing island can be temporary during addon/database startup. Never
                    // delete production or resources based on an unavailable permission context.
                    continue;
                }
                if (!active.contains(row.id())) spawn(type(row.type()), location, row.id(), row.tier(), row.storage(), capacity(row.tier()));
            }
        } catch (SQLException e) { plugin.getLogger().warning("Could not restore minions in loaded chunk: " + e.getMessage()); }
    }

    private void openMenu(Player player, ProgressStore.MinionRow row) {
        MinionType type = type(row.type());
        MinionHolder holder = new MinionHolder(row.id());
        Inventory inventory = Bukkit.createInventory(holder, 54, "§1NEXORA §8» §b" + type.label);
        holder.inventory = inventory;
        ItemStack filler = named(Material.GRAY_STAINED_GLASS_PANE, "§0 ");
        for (int i = 0; i < inventory.getSize(); i++) inventory.setItem(i, filler);
        inventory.setItem(0, named(Material.LIME_STAINED_GLASS_PANE, "§a§lMINION ONLINE", "§7Output is saved while you are offline."));
        inventory.setItem(4, named(type.icon, "§b" + type.label + " Minion §8• §fTier " + row.tier(),
                "§7Stored: §f" + row.storage() + "/" + capacity(row.tier()) + " items",
                "§7Offline production is capped at " + plugin.getConfig().getInt("minions.offline-cap-hours", 24) + " hours."));
        inventory.setItem(6, named(Material.CHEST, "§6Collect all " + type.outputLabel,
                "§7Ready: §f" + row.storage(), "§eClick to collect all available output."));
        long cost = upgradeCost(row.tier());
        inventory.setItem(7, named(Material.GOLD_INGOT, row.tier() >= 11 ? "§cMaximum tier reached" : "§aUpgrade to tier " + (row.tier() + 1),
                row.tier() >= 11 ? "§7This minion is fully upgraded." : "§7Cost: §6" + cost + " Coins", "§eClick to upgrade."));
        renderStorageSlots(inventory, row, type);
        inventory.setItem(45, named(Material.BARRIER, "§cSneak-right-click to pick up", "§7Collect stored resources first."));
        player.openInventory(inventory);
    }

    private int storageSlotIndex(int slot) {
        for (int index = 0; index < STORAGE_SLOTS.length; index++) if (STORAGE_SLOTS[index] == slot) return index;
        return -1;
    }

    private void renderStorageSlots(Inventory inventory, ProgressStore.MinionRow row, MinionType type) {
        int available = Math.max(1, (capacity(row.tier()) + 63) / 64);
        for (int index = 0; index < STORAGE_SLOTS.length; index++) {
            int slot = STORAGE_SLOTS[index];
            if (index >= available) {
                inventory.setItem(slot, named(Material.GRAY_STAINED_GLASS_PANE, "§8Locked Storage Slot", "§7Storage capacity grows with minion tiers."));
                continue;
            }
            int amount = Math.min(64, Math.max(0, row.storage() - index * 64));
            if (amount == 0) {
                inventory.setItem(slot, named(Material.LIGHT_GRAY_STAINED_GLASS_PANE, "§fEmpty Storage Slot", "§7One stack of " + type.outputLabel));
                continue;
            }
            ItemStack stack = new ItemStack(type.output, amount);
            ItemMeta meta = stack.getItemMeta();
            meta.setDisplayName("§e" + type.outputLabel);
            meta.setLore(List.of("§7Stored: §f" + amount, "§eClick to claim this stack."));
            stack.setItemMeta(meta);
            inventory.setItem(slot, stack);
        }
    }

    private ItemStack[] cloneContents(Player player) {
        ItemStack[] next=player.getInventory().getContents();for(int i=0;i<next.length;i++)next[i]=next[i]==null?null:next[i].clone();return next;
    }
    private void collect(Player player, ProgressStore.MinionRow row) throws SQLException { collectAmount(player,row,row.storage()); }
    private void collectSlot(Player player,ProgressStore.MinionRow row,int index)throws SQLException { collectAmount(player,row,Math.min(64,Math.max(0,row.storage()-index*64))); }
    private void collectAmount(Player player,ProgressStore.MinionRow row,int requested)throws SQLException {
        if(requested<=0){player.sendMessage(PREFIX+"No resources ready.");return;}
        ItemStack[] after=cloneContents(player);Material output=type(row.type()).output;
        ItemStack prototype=new ItemStack(output);int remaining=requested;
        for(int i=0;i<36&&remaining>0;i++) {
            if(after[i]==null||!after[i].isSimilar(prototype))continue;
            int moved=Math.min(remaining,Math.max(0,output.getMaxStackSize()-after[i].getAmount()));after[i].setAmount(after[i].getAmount()+moved);remaining-=moved;
        }
        for(int i=0;i<36&&remaining>0;i++) {
            if(after[i]!=null&&!after[i].getType().isAir())continue;
            int moved=Math.min(remaining,output.getMaxStackSize());after[i]=new ItemStack(output,moved);remaining-=moved;
        }
        int count=requested-remaining;if(count<=0){player.sendMessage(PREFIX+"Free inventory space first.");return;}
        String operation=UUID.randomUUID().toString();
        ((NexoraCore)plugin).applyInventoryOperation(player,network.nexora.core.api.InventoryJournal.Domain.MINION_COLLECT,operation,after);
        int collected;
        try {collected=store.takeMinionStorage(row.id(),player.getUniqueId(),count,operation);}
        finally{((NexoraCore)plugin).resolveInventoryOperation(player);}
        if(collected>0)player.sendMessage(PREFIX+"Collected "+collected+" "+type(row.type()).outputLabel+".");
    }

    private void upgrade(Player player, ProgressStore.MinionRow row) throws SQLException {
        if (row.tier() >= 11) { player.sendMessage(PREFIX + "This minion has reached tier 11."); return; }
        long costMinor = Math.multiplyExact(upgradeCost(row.tier()), 100L);
        long oldXp=store.skyblockXp(player.getUniqueId());
        if (store.upgradeMinion(row.id(), player.getUniqueId(), costMinor)) {
            ((NexoraCore)plugin).notifySkyXp(player,oldXp,store.skyblockXp(player.getUniqueId()));
            player.sendMessage(PREFIX + "Minion upgraded to tier " + (row.tier() + 1) + ".");
        } else {
            player.sendMessage(PREFIX + "Upgrade failed. Check your Coins and the minion tier.");
        }
    }

    private ProgressStore.MinionRow advance(ProgressStore.MinionRow row) throws SQLException {
        return store.advanceMinion(row.id(), System.currentTimeMillis(),
                MinionMath.intervalMillis(row.tier(), plugin.getConfig().getInt("minions.base-action-seconds", 60)),
                plugin.getConfig().getLong("minions.offline-cap-hours", 24) * 3_600_000L,
                capacity(row.tier()));
    }

    private int capacity(int tier) {
        return MinionMath.capacity(tier, plugin.getConfig().getInt("minions.storage-per-tier", 64));
    }
    private long upgradeCost(int currentTier) {
        return Math.multiplyExact(plugin.getConfig().getLong("minions.upgrade-base-cost", 25), (long) currentTier * currentTier);
    }
    private ArmorStand spawn(MinionType type, Location location, String id, int tier, int storage, int cap) {
        return location.getWorld().spawn(location, ArmorStand.class, entity -> {
            entity.setSmall(true); entity.setBasePlate(false); entity.setArms(true); entity.setGravity(false);
            entity.setInvulnerable(true); entity.setPersistent(true); entity.setSilent(true); entity.setCollidable(false);
            entity.setCustomName("§b" + type.label + " Minion §8[T" + tier + "]"); entity.setCustomNameVisible(true);
            entity.getPersistentDataContainer().set(entityIdKey, PersistentDataType.STRING, id);
            entity.getEquipment().setHelmet(new ItemStack(type.icon));
        });
    }
    private ItemStack minionItem(MinionType type) { return minionItem(type, 1); }
    private ItemStack minionItem(MinionType type, int tier) {
        var catalog=Bukkit.getServicesManager().load(network.nexora.core.api.CustomItemCatalog.class);
        ItemStack item=catalog==null?new ItemStack(Material.ARMOR_STAND):catalog.create("nexora:minion_"+type.key,1);
        ItemMeta meta = item.getItemMeta();
        meta.getPersistentDataContainer().set(NamespacedKey.fromString("nexoraprogression:item_id"),PersistentDataType.STRING,"nexora:minion_"+type.key);
        meta.getPersistentDataContainer().set(NamespacedKey.fromString("nexoraprogression:item_version"),PersistentDataType.INTEGER,1);
        meta.setDisplayName("§b" + type.label + " Minion §8[T" + tier + "]");
        meta.setLore(List.of("§7Place on an island where you can build.", "§7Right-click to manage generated resources.", "§8NEXORA MINION:" + type.key));
        meta.getPersistentDataContainer().set(itemTypeKey, PersistentDataType.STRING, type.key);
        meta.getPersistentDataContainer().set(tierKey, PersistentDataType.INTEGER, tier);
        item.setItemMeta(meta);
        return item;
    }
    private void registerRecipes() {
        for (MinionType type : MinionType.values()) {
            NamespacedKey key = new NamespacedKey(plugin, "craft_" + type.key);
            if (Bukkit.getRecipe(key) != null) continue;
            org.bukkit.inventory.ShapedRecipe recipe = new org.bukkit.inventory.ShapedRecipe(key, minionItem(type));
            recipe.shape("XXX", "XAX", "XXX");
            recipe.setIngredient('X', type.output);
            recipe.setIngredient('A', Material.ARMOR_STAND);
            if (!Bukkit.addRecipe(recipe)) plugin.getLogger().warning("Could not register minion recipe " + type.key);
        }
    }
    private MinionType itemType(ItemStack item) {
        if (item == null || item.getType()!=Material.ARMOR_STAND || !item.hasItemMeta()) return null;
        var catalog=Bukkit.getServicesManager().load(network.nexora.core.api.CustomItemCatalog.class);
        if(catalog!=null && catalog.identify(item)==null)return null;
        String key = item.getItemMeta().getPersistentDataContainer().get(itemTypeKey, PersistentDataType.STRING);
        return key == null ? null : types.get(key);
    }
    private ItemStack named(Material material, String name, String... lore) {
        ItemStack item = new ItemStack(material); ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(name); meta.setLore(List.of(lore)); item.setItemMeta(meta); return item;
    }
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onMenuDrag(org.bukkit.event.inventory.InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof MinionHolder) event.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onManipulate(org.bukkit.event.player.PlayerArmorStandManipulateEvent event) {
        if (event.getRightClicked().getPersistentDataContainer().has(entityIdKey, PersistentDataType.STRING)) event.setCancelled(true);
    }
    private Optional<Island> islandAt(Location location) {
        BentoBox bentobox = BentoBox.getInstance();
        if (bentobox == null || !bentobox.isLoaded()) return Optional.empty();
        return bentobox.getIslands().getIslandAt(location).filter(Island::isOwned)
                .filter(island -> island.getWorld() != null && island.getWorld().getName().equalsIgnoreCase("bskyblock_world"));
    }
    private boolean isAllowed(Island island, Player player) {
        Optional<Flag> placeBlocks = BentoBox.getInstance().getFlagsManager().getFlag("PLACE_BLOCKS");
        return placeBlocks.map(flag -> island.isAllowed(User.getInstance(player), flag)).orElse(false);
    }
    private boolean isCorrectIsland(ProgressStore.MinionRow row, Location location) throws SQLException {
        Optional<Island> island = islandAt(location);
        return island.isPresent() && island.get().getUniqueId().equals(row.islandId())
                && island.get().getWorld().getName().equals(row.world())
                && location.getBlockX() == row.x() && location.getBlockY() == row.y() && location.getBlockZ() == row.z();
    }
    private MinionType type(String key) { return types.getOrDefault(key, MinionType.COBBLESTONE); }

    private final class MinionHolder implements InventoryHolder {
        private final String id;
        private Inventory inventory;
        private MinionHolder(String id) { this.id = id; }
        @Override public Inventory getInventory() { return inventory; }
    }

    private enum MinionType {
        COBBLESTONE("cobblestone", "Cobblestone", Material.COBBLESTONE, Material.COBBLESTONE, "Cobblestone"),
        COAL("coal", "Coal", Material.COAL_ORE, Material.COAL, "Coal"),
        IRON("iron", "Iron", Material.IRON_ORE, Material.RAW_IRON, "Raw Iron"),
        GOLD("gold", "Gold", Material.GOLD_ORE, Material.RAW_GOLD, "Raw Gold"),
        DIAMOND("diamond", "Diamond", Material.DIAMOND_ORE, Material.DIAMOND, "Diamond"),
        REDSTONE("redstone", "Redstone", Material.REDSTONE_ORE, Material.REDSTONE, "Redstone"),
        LAPIS("lapis", "Lapis", Material.LAPIS_ORE, Material.LAPIS_LAZULI, "Lapis Lazuli"),
        EMERALD("emerald", "Emerald", Material.EMERALD_ORE, Material.EMERALD, "Emerald"),
        WHEAT("wheat", "Wheat", Material.WHEAT, Material.WHEAT, "Wheat"),
        CARROT("carrot", "Carrot", Material.CARROT, Material.CARROT, "Carrot"),
        POTATO("potato", "Potato", Material.POTATO, Material.POTATO, "Potato"),
        MELON("melon", "Melon", Material.MELON, Material.MELON_SLICE, "Melon"),
        PUMPKIN("pumpkin", "Pumpkin", Material.PUMPKIN, Material.PUMPKIN, "Pumpkin"),
        SPRUCE_WOOD("spruce_wood", "Spruce Wood", Material.SPRUCE_LOG, Material.SPRUCE_LOG, "Spruce Log"),
        ZOMBIE("zombie", "Zombie", Material.ROTTEN_FLESH, Material.ROTTEN_FLESH, "Rotten Flesh"),
        SKELETON("skeleton", "Skeleton", Material.BONE, Material.BONE, "Bone"),
        SPIDER("spider", "Spider", Material.STRING, Material.STRING, "String"),
        OAK_WOOD("oak_wood", "Oak Wood", Material.OAK_LOG, Material.OAK_LOG, "Oak Log");
        private final String key, label, outputLabel;
        private final Material icon, output;
        MinionType(String key, String label, Material icon, Material output, String outputLabel) {
            this.key=key; this.label=label; this.icon=icon; this.output=output; this.outputLabel=outputLabel;
        }
    }
}
