package network.nexora.farm;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.Ageable;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Animals;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockGrowEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityBreedEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Farm-only population and protection. It does not modify NexoraCore, the Hub, or Minions. */
public final class NexoraFarm extends JavaPlugin implements Listener {
    private static final Map<EntityType, String> SUPPORTED_SPECIES = Map.of(
            EntityType.COW, "cow", EntityType.PIG, "pig", EntityType.SHEEP, "sheep",
            EntityType.CHICKEN, "chicken", EntityType.RABBIT, "rabbit");
    private static final Set<Material> UNSAFE_GROUND = EnumSet.of(
            Material.MAGMA_BLOCK, Material.CAMPFIRE, Material.SOUL_CAMPFIRE,
            Material.CACTUS, Material.SWEET_BERRY_BUSH, Material.WITHER_ROSE,
            Material.POWDER_SNOW, Material.ICE, Material.PACKED_ICE, Material.BLUE_ICE, Material.FROSTED_ICE);

    private NamespacedKey managedKey;
    private final Map<EntityType, String> species = new EnumMap<>(EntityType.class);
    private final List<ChunkPoint> scanQueue = new ArrayList<>();
    private final Set<Long> forceLoadedCropChunks = new HashSet<>();
    private final Set<Long> warnedSparseChunks = new HashSet<>();
    private World farm;
    private World hub;
    private int targetPerChunkPerSpecies;
    private int minX, maxX, minZ, maxZ, cropMinX, cropMaxX, cropMinZ, cropMaxZ;
    private int scanPerTick, scanIndex, candidateIndex;

    @Override public void onEnable() {
        managedKey = new NamespacedKey(this, "managed_farm_animal");
        saveDefaultConfig();
        loadSettings();
        getServer().getPluginManager().registerEvents(this, this);
        farm = Bukkit.getWorld(getConfig().getString("farm.world", "nexora_farming"));
        hub = Bukkit.getWorld(getConfig().getString("build-access.hub-world", "nexora_hub"));
        if (farm == null) {
            getLogger().severe("Farm world is not loaded; population and protection have not started.");
            return;
        }
        prepareChunkQueue();
        Bukkit.getScheduler().runTaskTimer(this, task -> scanBatch(task), 1L, 1L);
    }

    private void loadSettings() {
        targetPerChunkPerSpecies = Math.max(1, getConfig().getInt("farm.animals.per-chunk-per-species", 1));
        for (String configured : getConfig().getStringList("farm.animals.species")) {
            try {
                EntityType type = EntityType.valueOf(configured.trim().toUpperCase(java.util.Locale.ROOT));
                if (SUPPORTED_SPECIES.containsKey(type)) species.put(type, SUPPORTED_SPECIES.get(type));
                else getLogger().warning("Unsupported configured farm species: " + configured);
            } catch (IllegalArgumentException ex) {
                getLogger().warning("Invalid configured farm species: " + configured);
            }
        }
        if (species.isEmpty()) species.putAll(SUPPORTED_SPECIES);
        minX = getConfig().getInt("farm.animal-area.min-x", -144);
        maxX = getConfig().getInt("farm.animal-area.max-x", -16);
        minZ = getConfig().getInt("farm.animal-area.min-z", -96);
        maxZ = getConfig().getInt("farm.animal-area.max-z", 128);
        cropMinX = getConfig().getInt("farm.crop-area.min-x", -80);
        cropMaxX = getConfig().getInt("farm.crop-area.max-x", 96);
        cropMinZ = getConfig().getInt("farm.crop-area.min-z", -80);
        cropMaxZ = getConfig().getInt("farm.crop-area.max-z", 80);
        scanPerTick = Math.max(1, Math.min(8, getConfig().getInt("farm.scan-chunks-per-tick", 4)));
        if (maxX <= minX || maxZ <= minZ || cropMaxX < cropMinX || cropMaxZ < cropMinZ)
            throw new IllegalArgumentException("Farm bounds must have positive dimensions");
    }

    private void prepareChunkQueue() {
        int lowX = Math.floorDiv(Math.min(minX, cropMinX), 16), highX = Math.floorDiv(Math.max(maxX, cropMaxX), 16);
        int lowZ = Math.floorDiv(Math.min(minZ, cropMinZ), 16), highZ = Math.floorDiv(Math.max(maxZ, cropMaxZ), 16);
        for (int x = lowX; x <= highX; x++) for (int z = lowZ; z <= highZ; z++) {
            ChunkPoint point = new ChunkPoint(x, z);
            if (overlaps(point, minX, maxX, minZ, maxZ) || overlaps(point, cropMinX, cropMaxX, cropMinZ, cropMaxZ))
                scanQueue.add(point);
        }
    }

    private void scanBatch(org.bukkit.scheduler.BukkitTask task) {
        int end = Math.min(scanIndex + scanPerTick, scanQueue.size());
        for (; scanIndex < end; scanIndex++) {
            ChunkPoint point = scanQueue.get(scanIndex);
            if (!farm.isChunkGenerated(point.x, point.z)) continue;
            boolean animalChunk = overlaps(point, minX, maxX, minZ, maxZ);
            boolean cropChunk = overlaps(point, cropMinX, cropMaxX, cropMinZ, cropMaxZ);
            if (!animalChunk && !cropChunk) continue;
            Chunk chunk = farm.getChunkAt(point.x, point.z, true);
            if (chunk == null) continue;
            if (cropChunk && getConfig().getBoolean("farm.crop-chunk-force-load", true)) {
                // Every generated farm chunk is force-loaded so crop growth continues without players.
                chunk.setForceLoaded(true);
                forceLoadedCropChunks.add(chunk.getChunkKey());
            }
            if (animalChunk) ensureChunkPopulation(chunk);
        }
        if (scanIndex >= scanQueue.size()) {
            task.cancel();
            getLogger().info("Farm chunk population scan completed: target " + targetPerChunkPerSpecies
                    + " each for " + String.join(", ", species.values()) + " in every generated farm-island chunk.");
            long interval = Math.max(60, getConfig().getLong("farm.replenish-seconds", 300)) * 20L;
            Bukkit.getScheduler().runTaskTimer(this, this::replenishLoadedChunks, interval, interval);
        }
    }

    private void ensureChunkPopulation(Chunk chunk) {
        Map<EntityType, Integer> counts = new EnumMap<>(EntityType.class);
        species.keySet().forEach(type -> counts.put(type, 0));
        for (Entity entity : chunk.getEntities()) {
            Integer count = counts.get(entity.getType());
            if (count != null && entity instanceof Animals) counts.put(entity.getType(), count + 1);
        }
        // Reduce only previous sidecar-spawned, unnamed animals; preserve legacy and player-named stock.
        for (EntityType type : species.keySet()) {
            int managed = 0;
            for (Entity entity : chunk.getEntities()) {
                if (entity.getType() == type && entity instanceof Animals && entity.getCustomName() == null
                        && entity.getPersistentDataContainer().has(managedKey, PersistentDataType.STRING)) managed++;
            }
            int remove = FarmPopulationMath.managedSurplus(targetPerChunkPerSpecies, counts.get(type), managed);
            if (remove <= 0) continue;
            for (Entity entity : chunk.getEntities()) {
                if (remove == 0) break;
                if (entity.getType() == type && entity instanceof Animals && entity.getCustomName() == null
                        && entity.getPersistentDataContainer().has(managedKey, PersistentDataType.STRING)) {
                    entity.remove();
                    counts.merge(type, -1, Integer::sum);
                    remove--;
                }
            }
        }
        Map<EntityType, Integer> missing = new EnumMap<>(EntityType.class);
        int totalMissing = 0;
        for (EntityType type : species.keySet()) {
            int amount = FarmPopulationMath.missingAnimals(targetPerChunkPerSpecies, counts.get(type));
            missing.put(type, amount);
            totalMissing += amount;
        }
        List<org.bukkit.Location> points = findSpreadPositions(chunk, totalMissing);
        int pointIndex = 0;
        for (Map.Entry<EntityType, String> entry : species.entrySet()) {
            for (int i = 0; i < missing.get(entry.getKey()) && pointIndex < points.size(); i++, pointIndex++) {
                org.bukkit.Location point = points.get(pointIndex);
                Entity entity = farm.spawnEntity(point, entry.getKey(), CreatureSpawnEvent.SpawnReason.CUSTOM);
                if (entity instanceof Animals animal) markManaged(animal, entry.getValue());
            }
        }
    }

    private List<org.bukkit.Location> findSpreadPositions(Chunk chunk, int required) {
        List<org.bukkit.Location> candidates = new ArrayList<>(required);
        if (required == 0) return candidates;
        int baseX = chunk.getX() << 4, baseZ = chunk.getZ() << 4;
        int fromX = Math.max(baseX, minX), toX = Math.min(baseX + 15, maxX);
        int fromZ = Math.max(baseZ, minZ), toZ = Math.min(baseZ + 15, maxZ);
        int attempts = 0;
        while (candidates.size() < required && attempts++ < required * 64) {
            int sequence = candidateIndex++;
            int x = FarmPopulationMath.spreadCoordinate(sequence, 2, fromX, toX);
            int z = FarmPopulationMath.spreadCoordinate(sequence, 3, fromZ, toZ);
            Block ground = farm.getHighestBlockAt(x, z);
            if (!ground.getType().isSolid() || UNSAFE_GROUND.contains(ground.getType())) continue;
            org.bukkit.Location spot = ground.getLocation().add(.5, 1, .5);
            if (!spot.getBlock().isPassable() || !spot.clone().add(0, 1, 0).getBlock().isPassable()) continue;
            boolean spaced = true;
            for (org.bukkit.Location previous : candidates) {
                if (previous.distanceSquared(spot) < 2.25) { spaced = false; break; }
            }
            if (spaced) candidates.add(spot);
        }
        if (candidates.size() < required && warnedSparseChunks.add(chunk.getChunkKey()))
            getLogger().warning("Could only find " + candidates.size() + " safe animal positions in chunk " + chunk.getX() + "," + chunk.getZ());
        return candidates;
    }

    private void replenishLoadedChunks() {
        if (farm == null) return;
        for (Chunk chunk : farm.getLoadedChunks())
            if (overlaps(new ChunkPoint(chunk.getX(), chunk.getZ()), minX, maxX, minZ, maxZ)) ensureChunkPopulation(chunk);
    }

    private static boolean overlaps(ChunkPoint point, int minX, int maxX, int minZ, int maxZ) {
        int x1 = point.x << 4, z1 = point.z << 4;
        return x1 <= maxX && x1 + 15 >= minX && z1 <= maxZ && z1 + 15 >= minZ;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFarmChunkLoad(ChunkLoadEvent event) {
        if (!inFarm(event.getWorld()) || !overlaps(new ChunkPoint(event.getChunk().getX(), event.getChunk().getZ()), minX, maxX, minZ, maxZ)) return;
        Bukkit.getScheduler().runTask(this, () -> {
            if (event.getChunk().isLoaded()) ensureChunkPopulation(event.getChunk());
        });
    }

    private boolean inAnimalArea(int x, int z) { return x >= minX && x <= maxX && z >= minZ && z <= maxZ; }
    private boolean inFarm(World world) { return farm != null && world.getUID().equals(farm.getUID()); }
    private boolean inHub(World world) { return hub != null && world.getUID().equals(hub.getUID()); }
    private boolean inFarmOrHub(World world) { return inFarm(world) || inHub(world); }
    private static boolean isCreativeOperator(org.bukkit.entity.Player player) {
        return player.isOp() && player.getGameMode() == GameMode.CREATIVE;
    }

    private boolean isCreativeOperatorInBuildWorld(org.bukkit.entity.Player player, World world) {
        if (!isCreativeOperator(player)) return false;
        String name = world.getName();
        return name.equalsIgnoreCase(getConfig().getString("build-access.hub-world", "nexora_hub"))
                || name.equalsIgnoreCase(getConfig().getString("build-access.mining-world", "nexora_mining"))
                || name.equalsIgnoreCase(getConfig().getString("farm.world", "nexora_farming"));
    }

    // NexoraCore protects the Hub at normal event priority. This narrow override runs afterward;
    // regular players and operators outside Creative remain subject to the existing protections.
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPrivilegedBuildBreak(BlockBreakEvent event) {
        if (isCreativeOperatorInBuildWorld(event.getPlayer(), event.getBlock().getWorld())) event.setCancelled(false);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPrivilegedBuildPlace(BlockPlaceEvent event) {
        if (isCreativeOperatorInBuildWorld(event.getPlayer(), event.getBlock().getWorld())) event.setCancelled(false);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPrivilegedBuildInteract(PlayerInteractEvent event) {
        if (event.getClickedBlock() != null && isCreativeOperatorInBuildWorld(event.getPlayer(), event.getClickedBlock().getWorld()))
            event.setCancelled(false);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPrivilegedBuildBucketEmpty(PlayerBucketEmptyEvent event) {
        if (isCreativeOperatorInBuildWorld(event.getPlayer(), event.getBlock().getWorld())) event.setCancelled(false);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPrivilegedBuildBucketFill(PlayerBucketFillEvent event) {
        if (isCreativeOperatorInBuildWorld(event.getPlayer(), event.getBlock().getWorld())) event.setCancelled(false);
    }

    private void markManaged(Animals animal, String kind) {
        animal.getPersistentDataContainer().set(managedKey, PersistentDataType.STRING, kind);
        animal.setPersistent(true);
        animal.setRemoveWhenFarAway(false);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onFarmBreak(BlockBreakEvent event) {
        boolean hubWorld = inHub(event.getBlock().getWorld());
        boolean farmWorld = inFarm(event.getBlock().getWorld());
        if ((!hubWorld && !farmWorld) || isCreativeOperator(event.getPlayer())) return;
        if (hubWorld) {
            if (!FarmProtectionPolicy.isCrop(event.getBlock().getType())) return;
            event.setCancelled(false);
        } else if (!isMatureCrop(event.getBlock())) { event.setCancelled(true); return; }
        org.bukkit.Location location = event.getBlock().getLocation().clone();
        Material crop = event.getBlock().getType();
        BlockData original = event.getBlock().getBlockData().clone();
        if (original instanceof Ageable ageable) ageable.setAge(0);
        event.getBlock().getChunk().setForceLoaded(true);
        Bukkit.getScheduler().runTask(this, () -> replantHarvestedCrop(location, crop, original));
    }

    private void replantHarvestedCrop(org.bukkit.Location location, Material crop, BlockData original) {
        Block block = location.getBlock();
        if (block.getType() != Material.AIR) return;
        Material soil = block.getRelative(0, -1, 0).getType();
        if (crop == Material.MELON || crop == Material.PUMPKIN) {
            if (soil != Material.FARMLAND) return;
            block.setType(crop == Material.MELON ? Material.MELON_STEM : Material.PUMPKIN_STEM, false);
            return;
        }
        boolean validSoil = switch (crop) {
            case NETHER_WART -> soil == Material.SOUL_SAND || soil == Material.SOUL_SOIL;
            case SWEET_BERRY_BUSH -> EnumSet.of(Material.GRASS_BLOCK, Material.DIRT, Material.COARSE_DIRT,
                    Material.PODZOL, Material.ROOTED_DIRT, Material.MOSS_BLOCK).contains(soil);
            case COCOA -> original instanceof Directional directional
                    && block.getRelative(directional.getFacing().getOppositeFace()).getType().name().endsWith("JUNGLE_LOG");
            case SUGAR_CANE -> (soil == Material.SUGAR_CANE || isCaneSoil(soil)) && hasAdjacentWater(block.getRelative(0, -1, 0));
            case CACTUS -> soil == Material.CACTUS || soil == Material.SAND || soil == Material.RED_SAND;
            case BAMBOO -> soil == Material.BAMBOO || soil == Material.BAMBOO_SAPLING || isBambooSoil(soil);
            case BROWN_MUSHROOM, RED_MUSHROOM -> EnumSet.of(Material.MYCELIUM, Material.PODZOL, Material.MUSHROOM_STEM,
                    Material.BROWN_MUSHROOM_BLOCK, Material.RED_MUSHROOM_BLOCK).contains(soil);
            default -> soil == Material.FARMLAND;
        };
        if (!validSoil) return;
        try {
            block.setBlockData(original, false);
        } catch (IllegalArgumentException invalidData) {
            block.setType(crop, false);
            if (block.getBlockData() instanceof Ageable ageable) { ageable.setAge(0); block.setBlockData(ageable, false); }
        }
    }

    private static boolean isCaneSoil(Material soil) { return soil == Material.SAND || soil == Material.RED_SAND || soil == Material.DIRT || soil == Material.GRASS_BLOCK || soil == Material.MUD; }
    private static boolean isBambooSoil(Material soil) { return soil == Material.DIRT || soil == Material.GRASS_BLOCK || soil == Material.PODZOL || soil == Material.COARSE_DIRT || soil == Material.MOSS_BLOCK || soil == Material.SAND; }
    private static boolean hasAdjacentWater(Block block) {
        for (BlockFace face : List.of(BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST))
            if (block.getRelative(face).getType() == Material.WATER) return true;
        return false;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFarmCropGrow(BlockGrowEvent event) {
        if (!inFarmOrHub(event.getBlock().getWorld()) || !FarmProtectionPolicy.isCrop(event.getBlock().getType())) return;
        int multiplier = Math.max(1, Math.min(4, getConfig().getInt("farm.crop-growth.multiplier", 2)));
        if (multiplier == 1) return;
        org.bukkit.block.BlockState proposed = event.getNewState();
        if (!(proposed.getBlockData() instanceof Ageable ageable)) return;
        if (!(event.getBlock().getBlockData() instanceof Ageable current)) return;
        int currentAge = current.getAge();
        int boostedAge = FarmGrowthPolicy.acceleratedAge(currentAge, ageable.getAge(), ageable.getMaximumAge(), multiplier);
        ageable.setAge(boostedAge);
        proposed.setBlockData(ageable);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFarmPlace(BlockPlaceEvent event) {
        if (inFarm(event.getBlock().getWorld()) && !isCreativeOperator(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFarmBuckets(PlayerBucketEmptyEvent event) {
        if (inFarm(event.getBlock().getWorld()) && !isCreativeOperator(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFarmBucketPickup(PlayerBucketFillEvent event) {
        if (inFarm(event.getBlock().getWorld()) && !isCreativeOperator(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFarmInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.PHYSICAL || event.getClickedBlock() == null || !inFarm(event.getClickedBlock().getWorld())) return;
        if (isCreativeOperator(event.getPlayer())) return;
        if (event.getClickedBlock().getType() == Material.FARMLAND) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityChangeFarmBlock(EntityChangeBlockEvent event) {
        if (inFarm(event.getBlock().getWorld()) && event.getBlock().getType() == Material.FARMLAND) event.setCancelled(true);
    }

    private boolean isMatureCrop(Block block) {
        if (block.getBlockData() instanceof Ageable ageable)
            return FarmProtectionPolicy.mayHarvest(block.getType(), ageable.getAge(), ageable.getMaximumAge());
        return FarmProtectionPolicy.mayHarvest(block.getType(), null, null);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onNaturalFarmSpawn(CreatureSpawnEvent event) {
        if (!inFarm(event.getLocation().getWorld()) || !species.containsKey(event.getEntityType())) return;
        if (event.getSpawnReason() == CreatureSpawnEvent.SpawnReason.NATURAL
                || event.getSpawnReason() == CreatureSpawnEvent.SpawnReason.CHUNK_GEN
                || event.getSpawnReason() == CreatureSpawnEvent.SpawnReason.BREEDING) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onManagedBreed(EntityBreedEvent event) {
        if (!inFarm(event.getEntity().getWorld())) return;
        if (event.getMother().getPersistentDataContainer().has(managedKey, PersistentDataType.STRING)
                || event.getFather().getPersistentDataContainer().has(managedKey, PersistentDataType.STRING)) event.setCancelled(true);
    }

    private record ChunkPoint(int x, int z) {}
}
