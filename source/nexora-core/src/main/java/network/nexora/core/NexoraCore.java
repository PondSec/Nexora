package network.nexora.core;

import net.milkbowl.vault.economy.AbstractEconomy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerItemDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.entity.Item;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Ageable;
import net.kyori.adventure.text.Component;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

import java.sql.SQLException;
import java.util.*;
import java.time.LocalDate;
import java.time.ZoneId;

public final class NexoraCore extends JavaPlugin implements Listener {
    private ProgressStore store;
    private MinionService minions;
    private SidebarService sidebar;
    private NexoraNavigation navigation;
    private final Economy economy = new Economy();
    private final Map<UUID,Long> lastProgressFeedback=new HashMap<>();
    private final Map<UUID,Long> lastHubIslandPortal=new HashMap<>();
    private static final String PREFIX="§8[§bNEXORA§8] §7";

    @Override public void onEnable() {
        saveDefaultConfig();
        getConfig().options().copyDefaults(true);saveConfig();
        try { store=new ProgressStore(new java.io.File(getDataFolder(),"nexora.sqlite"),getConfig().getLong("starting-balance-coins",100)*100); }
        catch(SQLException e){getLogger().severe("Could not open Nexora database: "+e.getMessage());getServer().getPluginManager().disablePlugin(this);return;}
        getServer().getServicesManager().register(net.milkbowl.vault.economy.Economy.class,economy,this,ServicePriority.Highest);
        setupWorlds();
        startBlockRegeneration();
        minions=new MinionService(this,store);
        sidebar=new SidebarService(this,store);
        sidebar.start();
        navigation=new NexoraNavigation(this);
        navigation.start();
        getServer().getPluginManager().registerEvents(this,this);
        getServer().getPluginManager().registerEvents(minions,this);
        for(String c:List.of("menu","profile","progress","daily","coins","skills","collections","hub","nexora","minion")){PluginCommand cmd=getCommand(c);if(cmd!=null)cmd.setExecutor(this::command);}
        getLogger().info("NexoraCore enabled; SQLite UUID profiles and Vault economy provider registered.");
    }
    @Override public void onDisable(){if(sidebar!=null)sidebar.stop();if(store!=null)try{store.close();}catch(SQLException e){getLogger().severe("Database close failed: "+e.getMessage());}}
    @EventHandler public void onJoin(PlayerJoinEvent e){try{store.join(e.getPlayer().getUniqueId(),e.getPlayer().getName());e.getPlayer().setFoodLevel(20);e.getPlayer().setSaturation(20);e.getPlayer().setExhaustion(0);makeToolsUnbreakable(e.getPlayer());if(!e.getPlayer().hasPlayedBefore())welcome(e.getPlayer());World hub=getServer().getWorld(getConfig().getString("hub-world","nexora_hub"));if(hub!=null)getServer().getScheduler().runTask(this,()->{if(e.getPlayer().isOnline()){Location spawn=safeHubSpawn(hub);if(spawn!=null)e.getPlayer().teleportAsync(spawn);else getLogger().severe("Refused unsafe Hub join teleport: no configured safe spawn.");}});}catch(SQLException ex){getLogger().severe("Profile join failed: "+ex.getMessage());}}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void onHunger(FoodLevelChangeEvent event){if(event.getEntity() instanceof Player player){event.setCancelled(true);player.setFoodLevel(20);player.setSaturation(20);player.setExhaustion(0);}}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void onToolDamage(PlayerItemDamageEvent event){if(isNexoraTool(event.getItem().getType()))event.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST)
    public void onDeath(PlayerDeathEvent event){
        Player player=event.getEntity();event.setKeepInventory(true);event.getDrops().clear();event.setKeepLevel(true);event.setDroppedExp(0);
        if(isOnOwnIsland(player)){
            player.sendMessage(PREFIX+"You died on your island. Your Coins are safe.");
            return;
        }
        try{long lost=store.chargeDeathHalf(player.getUniqueId());if(lost>0)player.sendMessage(PREFIX+"You lost §6"+format(lost)+" Coins §7(half your purse) on death.");else player.sendMessage(PREFIX+"Your inventory and Coins were kept on death.");if(sidebar!=null)sidebar.refresh(player);}
        catch(SQLException ex){getLogger().severe("Could not apply death purse loss: "+ex.getMessage());player.sendMessage(PREFIX+"Your inventory was kept; Coins could not be updated.");}
    }
    private boolean isOnOwnIsland(Player player){
        try{return world.bentobox.bentobox.BentoBox.getInstance().getIslands().getIslandAt(player.getLocation())
                .filter(world.bentobox.bentobox.database.objects.Island::isOwned)
                .filter(island->island.getOwner().equals(player.getUniqueId())||island.getMemberSet().contains(player.getUniqueId())).isPresent();}
        catch(RuntimeException ex){return false;}
    }
    private boolean isNexoraTool(Material material){
        String name=material.name();
        return name.endsWith("_PICKAXE")||name.endsWith("_AXE")||name.endsWith("_HOE")||name.endsWith("_SHOVEL")||name.endsWith("_SWORD")
                ||Set.of("BOW","CROSSBOW","TRIDENT","MACE","FISHING_ROD","CARROT_ON_A_STICK","WARPED_FUNGUS_ON_A_STICK","SHEARS","FLINT_AND_STEEL").contains(name);
    }
    private void makeToolsUnbreakable(Player player){
        ItemStack[] contents=player.getInventory().getContents();boolean changed=false;
        for(int i=0;i<contents.length;i++){ItemStack item=contents[i];if(item==null||!isNexoraTool(item.getType()))continue;ItemMeta meta=item.getItemMeta();if(meta.isUnbreakable())continue;meta.setUnbreakable(true);item.setItemMeta(meta);contents[i]=item;changed=true;}
        if(changed)player.getInventory().setContents(contents);
    }
    @EventHandler public void onDiscoverWorld(PlayerChangedWorldEvent event){
        Player player=event.getPlayer();String world=player.getWorld().getName();
        if(!Set.of("nexora_hub","bskyblock_world","nexora_mining","nexora_farming").contains(world))return;
        try{long before=store.skyblockXp(player.getUniqueId());if(store.claimAchievement(player.getUniqueId(),"area:"+world,getConfig().getLong("progression.rewards.area-discovery-xp",50))){
            long after=store.skyblockXp(player.getUniqueId());
            player.sendMessage(PREFIX+"New area discovered: §b"+world.replace("nexora_",""));
            notifySkyXp(player,before,after);
        }}catch(SQLException ex){getLogger().warning("Area discovery could not be saved: "+ex.getMessage());}
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=false)
    public void onHubIslandPortal(PlayerPortalEvent event){
        Player player=event.getPlayer();
        if(!isHub(event.getFrom().getWorld()))return;
        event.setCancelled(true);
        warpFromHubPortal(player);
    }
    @EventHandler(ignoreCancelled=true)
    public void onHubIslandPortalRegion(PlayerMoveEvent event){
        Location to=event.getTo();
        if(to==null||event.getFrom().getBlockX()==to.getBlockX()&&event.getFrom().getBlockY()==to.getBlockY()&&event.getFrom().getBlockZ()==to.getBlockZ())return;
        if(!isHub(to.getWorld())||!getConfig().getBoolean("hub-island-portal.enabled",true))return;
        double x=getConfig().getDouble("hub-island-portal.x",1.3D),y=getConfig().getDouble("hub-island-portal.y",56D),z=getConfig().getDouble("hub-island-portal.z",28.9D);
        if(Math.abs(to.getX()-x)>getConfig().getDouble("hub-island-portal.radius-x",4D)
                ||Math.abs(to.getY()-y)>getConfig().getDouble("hub-island-portal.radius-y",3D)
                ||Math.abs(to.getZ()-z)>getConfig().getDouble("hub-island-portal.radius-z",4D))return;
        warpFromHubPortal(event.getPlayer());
    }
    private void warpFromHubPortal(Player player){
        long now=System.currentTimeMillis();
        if(now-lastHubIslandPortal.getOrDefault(player.getUniqueId(),0L)<3000L)return;
        lastHubIslandPortal.put(player.getUniqueId(),now);
        getServer().getScheduler().runTask(this,()->{
            if(player.isOnline()&&isHub(player.getWorld()))player.performCommand("island go");
        });
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=false) public void onBreak(BlockBreakEvent e){Player p=e.getPlayer();Material m=e.getBlock().getType();String skill=null;
        boolean hub=isHub(e.getBlock().getWorld());
        if(!hub&&e.isCancelled())return;
        if(hub&&!isCreativeOperator(p)){
            if(isHubOre(m)){
                if(!scheduleHubOreRestore(e.getBlock())){e.setCancelled(true);return;}
                e.setCancelled(false);
                skill="mining";
            }else if(isUsefulCrop(m)){
                e.setCancelled(false);
                skill="farming";
            }else{e.setCancelled(true);return;}
        }
        if(Set.of(Material.STONE,Material.COBBLESTONE,Material.COAL_ORE,Material.IRON_ORE,Material.GOLD_ORE,Material.DIAMOND_ORE,Material.EMERALD_ORE,Material.REDSTONE_ORE,Material.LAPIS_ORE,Material.DEEPSLATE,Material.DEEPSLATE_COAL_ORE,Material.DEEPSLATE_IRON_ORE,Material.DEEPSLATE_GOLD_ORE,Material.DEEPSLATE_DIAMOND_ORE,Material.DEEPSLATE_EMERALD_ORE,Material.DEEPSLATE_REDSTONE_ORE,Material.DEEPSLATE_LAPIS_ORE).contains(m))skill="mining";
        else if(Set.of(Material.WHEAT,Material.CARROTS,Material.POTATOES,Material.MELON,Material.PUMPKIN,Material.SUGAR_CANE).contains(m)){
            if(e.getBlock().getBlockData() instanceof org.bukkit.block.data.Ageable ageable && ageable.getAge()<ageable.getMaximumAge())return;
            skill="farming";
        }
        else if(m.name().endsWith("_LOG")||m==Material.OAK_WOOD||m==Material.SPRUCE_WOOD||m==Material.BIRCH_WOOD)skill="foraging";
        if(skill!=null){String collection=collectionKey(m);try{
            if(store.consumePlacedBlock(e.getBlock().getWorld().getName(),e.getBlock().getX(),e.getBlock().getY(),e.getBlock().getZ()))return;
            long oldSkyXp=store.skyblockXp(p.getUniqueId());
            ProgressStore.GatherResult result=store.recordGather(p.getUniqueId(),skill,collection,1,collection==null?0:1,
                    getConfig().getLongList("collections.milestones"),
                    getConfig().getLong("progression.rewards.skill-level-xp",10),
                    getConfig().getLong("progression.rewards.collection-milestone-xp",25),skillCurve(),dailyDay(),dailyTargets());
            long newSkyXp=store.skyblockXp(p.getUniqueId());
            ProgressionMath.LevelProgress after=ProgressionMath.sky(newSkyXp,skyCurve());
            notifySkyXp(p,oldSkyXp,newSkyXp);
            if(result.skillLevelAfter()>result.skillLevelBefore()){
                p.playSound(p.getLocation(),org.bukkit.Sound.ENTITY_PLAYER_LEVELUP,0.55f,1.4f);
                p.sendMessage(PREFIX+title(skill)+" skill reached level §b"+result.skillLevelAfter()+"§7.");
            }
            if(result.collectionMilestones()>0){
                p.playSound(p.getLocation(),org.bukkit.Sound.BLOCK_NOTE_BLOCK_CHIME,0.55f,1.6f);
                p.sendMessage(PREFIX+"Collection milestone reached: §b"+title(collection.replace('_',' '))+" §7("+result.collectionCount()+").");
            }
            long now=System.currentTimeMillis();
            if(now-lastProgressFeedback.getOrDefault(p.getUniqueId(),0L)>=getConfig().getLong("progression.feedback-cooldown-ms",1000)){
                p.sendActionBar(Component.text("§b+1 "+title(skill)+" XP §8• §6SkyBlock Level "+after.level()));lastProgressFeedback.put(p.getUniqueId(),now);
            }
        }catch(SQLException ex){getLogger().warning("Progress save failed: "+ex.getMessage());}}
    }
    private static boolean isCreativeOperator(Player player){return player.isOp()&&player.getGameMode()==org.bukkit.GameMode.CREATIVE;}
    private static boolean isHubOre(Material material){return switch(material){case COAL_ORE,DEEPSLATE_COAL_ORE,IRON_ORE,DEEPSLATE_IRON_ORE->true;default->false;};}
    private static boolean isUsefulCrop(Material material){return switch(material){
        case WHEAT,CARROTS,POTATOES,BEETROOTS,MELON,PUMPKIN,SUGAR_CANE,CACTUS,COCOA,BROWN_MUSHROOM,RED_MUSHROOM,NETHER_WART,SWEET_BERRY_BUSH,BAMBOO,TORCHFLOWER_CROP,PITCHER_CROP->true;
        default->false;
    };}
    private boolean scheduleHubOreRestore(Block block){
        BlockData original=block.getBlockData().clone();
        long delay=Math.max(1000L,getConfig().getLong("hub.ore-regeneration-delay-ms",12000L));
        try{store.scheduleBlockRestore(block.getWorld().getName(),block.getX(),block.getY(),block.getZ(),original.getAsString(),System.currentTimeMillis()+delay);}
        catch(SQLException ex){getLogger().severe("Could not persist Hub ore regeneration: "+ex.getMessage());return false;}
        block.getChunk().setForceLoaded(true);
        getServer().getScheduler().runTask(this,()->{if(block.getType().isAir())block.setType(Material.BEDROCK,false);});
        return true;
    }
    private void startBlockRegeneration(){
        try{for(ProgressStore.BlockRestore row:store.pendingBlockRestores()){World world=Bukkit.getWorld(row.world());if(world!=null)world.getChunkAt(row.x()>>4,row.z()>>4).setForceLoaded(true);}}
        catch(SQLException ex){getLogger().severe("Could not load pending Hub ore regenerations: "+ex.getMessage());}
        getServer().getScheduler().runTaskTimer(this,()->{
            try{for(ProgressStore.BlockRestore row:store.dueBlockRestores(System.currentTimeMillis(),128)){
                World world=Bukkit.getWorld(row.world());
                if(world==null){getLogger().warning("Ore regeneration world is not loaded: "+row.world());continue;}
                Block block=world.getBlockAt(row.x(),row.y(),row.z());
                if(block.getType()==Material.BEDROCK||block.getType().isAir())try{block.setBlockData(Bukkit.createBlockData(row.blockData()),false);}
                catch(IllegalArgumentException ex){getLogger().severe("Invalid saved ore block data at "+row.world()+" "+row.x()+","+row.y()+","+row.z());}
                store.finishBlockRestore(row);
            }}catch(SQLException ex){getLogger().severe("Could not process Hub ore regeneration: "+ex.getMessage());}
        },20L,20L);
    }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true)
    public void onBlockDrop(BlockDropItemEvent event){
        Player player=event.getPlayer();
        if(!autoPickupEnabled(player))return;
        Location location=event.getBlock().getLocation();
        for(Item dropped:new ArrayList<>(event.getItems())){
            ItemStack stack=dropped.getItemStack().clone();
            dropped.remove();
            Map<Integer,ItemStack> overflow=player.getInventory().addItem(stack);
            overflow.values().forEach(leftover->location.getWorld().dropItemNaturally(location,leftover));
        }
    }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true)
    public void onMobDrops(EntityDeathEvent event){
        Player player=event.getEntity().getKiller();
        if(player==null)return;
        Material trackedDrop=switch(event.getEntityType()){
            case ZOMBIE->Material.ROTTEN_FLESH;case CREEPER->Material.GUNPOWDER;case SKELETON->Material.BONE;
            case SPIDER,CAVE_SPIDER->Material.STRING;case COW->Material.LEATHER;case CHICKEN->Material.FEATHER;
            case SHEEP->null;case PIG->Material.PORKCHOP;case ENDERMAN->Material.ENDER_PEARL;
            case BLAZE->Material.BLAZE_ROD;case MAGMA_CUBE->Material.MAGMA_CREAM;case GHAST->Material.GHAST_TEAR;
            default->null;
        };
        String collection=trackedDrop==null?null:collectionKey(trackedDrop);
        long amount=trackedDrop==null?0:event.getDrops().stream().filter(stack->stack.getType()==trackedDrop).mapToLong(ItemStack::getAmount).sum();
        try{
            long oldXp=store.skyblockXp(player.getUniqueId());
            ProgressStore.GatherResult result=store.recordGather(player.getUniqueId(),"combat",amount>0?collection:null,1,
                    amount>0?amount:0,getConfig().getLongList("collections.milestones"),
                    getConfig().getLong("progression.rewards.skill-level-xp",10),
                    getConfig().getLong("progression.rewards.collection-milestone-xp",25),skillCurve(),dailyDay(),dailyTargets());
            notifySkyXp(player,oldXp,store.skyblockXp(player.getUniqueId()));
            if(result.skillLevelAfter()>result.skillLevelBefore())player.sendMessage(PREFIX+"Combat skill reached level §b"+result.skillLevelAfter()+"§7.");
            if(result.collectionMilestones()>0)player.sendMessage(PREFIX+"Collection milestone reached: §b"+title(collection)+" §7("+result.collectionCount()+").");
        }catch(SQLException ex){getLogger().warning("Combat progress save failed: "+ex.getMessage());}
        if(!autoPickupEnabled(player))return;
        Location location=event.getEntity().getLocation();
        for(ItemStack stack:new ArrayList<>(event.getDrops())){
            Map<Integer,ItemStack> overflow=player.getInventory().addItem(stack.clone());
            overflow.values().forEach(leftover->location.getWorld().dropItemNaturally(location,leftover));
        }
        event.getDrops().clear();
    }
    private boolean autoPickupEnabled(Player player){
        try{return ProgressionMath.sky(store.skyblockXp(player.getUniqueId()),skyCurve()).level()>=5;}
        catch(SQLException ex){getLogger().warning("Could not check auto-pickup level: "+ex.getMessage());return false;}
    }
    @EventHandler(ignoreCancelled=true) public void onPlace(BlockPlaceEvent e){
        if(isHub(e.getBlock().getWorld())){e.setCancelled(true);return;}
        if(collectionKey(e.getBlockPlaced().getType())!=null)try{var b=e.getBlockPlaced();store.markPlacedBlock(b.getWorld().getName(),b.getX(),b.getY(),b.getZ(),e.getPlayer().getUniqueId(),b.getType().name());}
        catch(SQLException ex){getLogger().warning("Could not mark placed progress block: "+ex.getMessage());}
    }
    @EventHandler(ignoreCancelled=true) public void onHubInteract(PlayerInteractEvent e){if(e.getClickedBlock()!=null&&isHub(e.getClickedBlock().getWorld()))e.setCancelled(true);}
    private boolean isHub(World world){return world.getName().equalsIgnoreCase(getConfig().getString("hub-world","nexora_hub"));}
    private void setupWorlds(){
        String hubName=getConfig().getString("hub-world","nexora_hub");World hub=Bukkit.getWorld(hubName);
        if(hub==null)hub=Bukkit.createWorld(new WorldCreator(hubName).environment(World.Environment.NORMAL));
        World mining=Bukkit.getWorld("nexora_mining");if(mining==null)mining=Bukkit.createWorld(new WorldCreator("nexora_mining"));
        World farming=Bukkit.getWorld("nexora_farming");if(farming==null)farming=Bukkit.createWorld(new WorldCreator("nexora_farming"));
        if(hub!=null){hub.setGameRule(org.bukkit.GameRule.DO_MOB_SPAWNING,false);hub.setGameRule(org.bukkit.GameRule.DO_WEATHER_CYCLE,false);hub.setTime(6000);
            Location spawn=safeHubSpawn(hub);
            if(spawn!=null){hub.setSpawnLocation(spawn);getLogger().info("Using verified Hub spawn at "+spawn.getBlockX()+","+spawn.getBlockY()+","+spawn.getBlockZ()+".");}
            else if(getConfig().getBoolean("hub-build.enabled",true))buildHub(hub);
            else getLogger().severe("Hub map is configured as external but its configured spawn is unsafe; Hub teleports are disabled.");
        }
        if(mining!=null)mining.getWorldBorder().setSize(4000);if(farming!=null)farming.getWorldBorder().setSize(2000);
    }
    private void buildHub(World world){
        if(world.getPersistentDataContainer().has(new org.bukkit.NamespacedKey(this,"hub_built"),org.bukkit.persistence.PersistentDataType.BYTE))return;
        int y=Math.max(world.getMinHeight()+80,80);Location spawn=new Location(world,0.5,y+1,0.5);world.setSpawnLocation(spawn);
        for(int x=-30;x<=30;x++)for(int z=-30;z<=30;z++){
            Material floor=(Math.abs(x)<=3||Math.abs(z)<=3)?Material.POLISHED_DEEPSLATE:Material.DEEPSLATE_TILES;
            if(Math.abs(x)==4||Math.abs(z)==4)floor=Material.CYAN_TERRACOTTA;
            if((Math.abs(x)==30||Math.abs(z)==30))floor=Material.DARK_PRISMARINE;
            world.getBlockAt(x,y,z).setType(floor,false);
        }
        for(int x=-8;x<=8;x++)for(int z=-8;z<=8;z++)if(Math.max(Math.abs(x),Math.abs(z))==8)world.getBlockAt(x,y+1,z).setType(Material.SEA_LANTERN,false);
        for(int x:new int[]{-18,18})for(int z:new int[]{-18,18}){
            for(int h=1;h<=5;h++)world.getBlockAt(x,y+h,z).setType(Material.CUT_COPPER,false);
            world.getBlockAt(x,y+6,z).setType(Material.SEA_LANTERN,false);
            for(int dx=-2;dx<=2;dx++)for(int dz=-2;dz<=2;dz++)if(Math.abs(dx)+Math.abs(dz)<4)world.getBlockAt(x+dx,y+7,z+dz).setType(Material.CYAN_STAINED_GLASS,false);
        }
        world.getBlockAt(0,y,0).setType(Material.GOLD_BLOCK,false);world.getBlockAt(0,y+1,0).setType(Material.BEACON,false);
        world.getPersistentDataContainer().set(new org.bukkit.NamespacedKey(this,"hub_built"),org.bukkit.persistence.PersistentDataType.BYTE,(byte)1);
    }
    private Location safeHubSpawn(World world){
        if(!world.getName().equalsIgnoreCase(getConfig().getString("hub-spawn.world","nexora_hub")))return null;
        double x=getConfig().getDouble("hub-spawn.x",-2.5D),y=getConfig().getDouble("hub-spawn.y",64D),z=getConfig().getDouble("hub-spawn.z",0.5D);
        Location target=new Location(world,x,y,z,(float)getConfig().getDouble("hub-spawn.yaw",0D),(float)getConfig().getDouble("hub-spawn.pitch",0D));
        Material ground=world.getBlockAt(target.getBlockX(),target.getBlockY()-1,target.getBlockZ()).getType();
        if(!ground.isSolid()||ground==Material.LAVA||ground==Material.MAGMA_BLOCK||!target.getBlock().isPassable()||!target.clone().add(0,1,0).getBlock().isPassable())return null;
        return target;
    }
    private String collectionKey(Material m){return switch(m){case STONE,COBBLESTONE->"cobblestone";case COAL_ORE,DEEPSLATE_COAL_ORE,COAL->"coal";case IRON_ORE,DEEPSLATE_IRON_ORE,IRON_INGOT->"iron";case GOLD_ORE,DEEPSLATE_GOLD_ORE,GOLD_INGOT->"gold";case DIAMOND_ORE,DEEPSLATE_DIAMOND_ORE,DIAMOND->"diamond";case EMERALD_ORE,DEEPSLATE_EMERALD_ORE,EMERALD->"emerald";case REDSTONE_ORE,DEEPSLATE_REDSTONE_ORE,REDSTONE->"redstone";case LAPIS_ORE,DEEPSLATE_LAPIS_ORE,LAPIS_LAZULI->"lapis";case WHEAT->"wheat";case CARROTS->"carrot";case POTATOES->"potato";case MELON->"melon";case PUMPKIN->"pumpkin";case SUGAR_CANE->"sugar_cane";case OAK_LOG, OAK_WOOD->"oak_wood";case SPRUCE_LOG,SPRUCE_WOOD->"spruce_wood";case BIRCH_LOG,BIRCH_WOOD->"birch_wood";case LEATHER->"leather";case FEATHER->"feather";case CHICKEN->"raw_chicken";case BEEF->"raw_beef";case PORKCHOP->"raw_porkchop";case ROTTEN_FLESH->"rotten_flesh";case BONE->"bone";case STRING->"string";case SPIDER_EYE->"spider_eye";case GUNPOWDER->"gunpowder";case SLIME_BALL->"slimeball";case ENDER_PEARL->"ender_pearl";case BLAZE_ROD->"blaze_rod";case MAGMA_CREAM->"magma_cream";case GHAST_TEAR->"ghast_tear";default->null;};}
    private boolean command(CommandSender sender,Command cmd,String label,String[] args){if(!(sender instanceof Player p)){sender.sendMessage("Player only.");return true;}try{UUID id=p.getUniqueId();switch(cmd.getName().toLowerCase(Locale.ROOT)){
        case "coins"->p.sendMessage(PREFIX+"Balance: §6"+format(store.balance(id))+" Coins");
        case "profile"->openProfile(p);
        case "menu"->navigation.openMain(p);
        case "progress"->openProgress(p);
        case "daily"->openDaily(p);
        case "skills"->openSkills(p);
        case "collections"->openCollections(p);
        case "hub"->{var w=getServer().getWorld(getConfig().getString("hub-world","nexora_hub"));Location spawn=w==null?null:safeHubSpawn(w);if(spawn==null)p.sendMessage(PREFIX+"Hub spawn is unavailable; an administrator must check it.");else p.teleportAsync(spawn);}
        case "minion"->{return minions.command(p,args);}
        case "nexora"->{if(!p.hasPermission("nexora.admin")){p.sendMessage("§cNo permission.");break;}p.sendMessage(PREFIX+"Core online; database="+new java.io.File(getDataFolder(),"nexora.sqlite").length()+" bytes; Vault provider active.");}
        default->{return false;}}
      }catch(SQLException ex){p.sendMessage(PREFIX+"Your data could not be loaded. Please retry shortly.");getLogger().severe("Profile command failed: "+ex.getMessage());}return true;}
    private String title(String s){return s.substring(0,1).toUpperCase(Locale.ROOT)+s.substring(1);}
    void notifySkyXp(Player player,long beforeXp,long afterXp){
        ProgressionMath.LevelProgress before=ProgressionMath.sky(beforeXp,skyCurve()),after=ProgressionMath.sky(afterXp,skyCurve());
        if(after.level()>before.level()){
            player.sendTitle("§6§lSKYBLOCK LEVEL UP!","§fYou reached level §e"+after.level(),5,50,15);
            player.playSound(player.getLocation(),org.bukkit.Sound.UI_TOAST_CHALLENGE_COMPLETE,0.8f,1.1f);
            int extraSlots=unlockedMinionSlots(after.level())-unlockedMinionSlots(before.level());
            if(extraSlots>0)player.sendMessage(PREFIX+"SkyBlock level unlocked §b"+extraSlots+" additional minion slot"+(extraSlots==1?".":"s."));
        }
        if(sidebar!=null)sidebar.refresh(player);
    }
    private String format(long minor){return String.format(Locale.US,"%,d.%02d",minor/100,Math.abs(minor%100));}
    private String progressBar(long current,long required){int filled=required<=0?10:(int)Math.min(10,Math.max(0,(current*10)/required));return "§6"+"▰".repeat(filled)+"§8"+"▱".repeat(10-filled);}
    private void welcome(Player p){for(String line:getConfig().getStringList("welcome"))p.sendMessage(ChatColor.translateAlternateColorCodes('&',line));}
    ProgressionMath.Curve skyCurve(){return new ProgressionMath.Curve(getConfig().getLong("progression.skyblock.xp-base",100),getConfig().getLong("progression.skyblock.xp-linear",35),getConfig().getLong("progression.skyblock.xp-quadratic",3),getConfig().getInt("progression.skyblock.max-level",100));}
    ProgressionMath.Curve skillCurve(){return new ProgressionMath.Curve(getConfig().getLong("progression.skills.xp-base",50),getConfig().getLong("progression.skills.xp-linear",20),getConfig().getLong("progression.skills.xp-quadratic",2),getConfig().getInt("progression.skills.max-level",50));}
    int unlockedMinionSlots(int level){int base=getConfig().getInt("minions.base-slots",2),every=Math.max(1,getConfig().getInt("progression.minion-slot-every-levels",10)),max=getConfig().getInt("minions.max-per-island",10);return Math.min(max,base+Math.max(0,(level-1)/every));}
    private String dailyDay(){return LocalDate.now(ZoneId.of(getConfig().getString("daily.timezone","Europe/Berlin"))).toString();}
    private Map<String,Long> dailyTargets(){Map<String,Long> out=new LinkedHashMap<>();for(String q:List.of("mining","farming","foraging"))out.put(q,getConfig().getLong("daily.quests."+q+".target",0));return out;}

    @EventHandler(priority=EventPriority.HIGH,ignoreCancelled=true)
    public void onMenuClick(InventoryClickEvent event){
        if(!(event.getInventory().getHolder() instanceof NexoraMenu menu))return;
        event.setCancelled(true);
        if(!(event.getWhoClicked() instanceof Player player)||!menu.owner.equals(player.getUniqueId()))return;
        try{int slot=event.getRawSlot();switch(menu.section){
            case "PROFILE"->{if(slot==18)openSkills(player);else if(slot==20)navigation.openCollectionCategories(player);else if(slot==22)showMinions(player);else if(slot==8)openProgress(player);}
            case "SKILLS"->{if(slot==18)openProfile(player);else if(slot==20)navigation.openCollectionCategories(player);}
            case "COLLECTIONS"->{if(slot==45)openProfile(player);else if(slot==46)openSkills(player);}
            case "PROGRESSION"->{if(slot==45)openProfile(player);else if(slot==47)openSkills(player);else if(slot==49)navigation.openCollectionCategories(player);}
            case "DAILY"->{if(slot==10)claimDaily(player,"mining");else if(slot==13)claimDaily(player,"farming");else if(slot==16)claimDaily(player,"foraging");else if(slot==22)navigation.openMain(player);}
            default->{if(menu.section.startsWith("COLLECTIONS ")){if(slot==45)openProfile(player);else if(slot==46)navigation.openCollectionCategories(player);}}
        }}catch(SQLException e){player.sendMessage(PREFIX+"Your menu could not load. Please try again.");getLogger().warning("GUI database read failed: "+e.getMessage());}
    }
    @EventHandler(priority=EventPriority.HIGH,ignoreCancelled=true)
    public void onMenuDrag(InventoryDragEvent event){if(event.getView().getTopInventory().getHolder() instanceof NexoraMenu)event.setCancelled(true);}
    void openProfile(Player player)throws SQLException{
        Inventory inv=menu(player,"PROFILE");
        ProgressionMath.LevelProgress level=ProgressionMath.sky(store.skyblockXp(player.getUniqueId()),skyCurve());
        inv.setItem(4,menuItem(Material.NETHER_STAR,"§6§lSkyBlock Level "+level.level(),"§7Progress: §f"+level.currentXp()+"§7/§f"+level.requiredXp()+" XP",progressBar(level.currentXp(),level.requiredXp()),"§7Lifetime XP: §f"+level.totalXp(),"§eOpen /progress for your full overview."));
        inv.setItem(8,menuItem(Material.EXPERIENCE_BOTTLE,"§bProgression overview","§7See levels, skills, collections, minions and milestones.","§eClick to open."));
        inv.setItem(11,menuItem(Material.PLAYER_HEAD,"§b§l"+player.getName(),"§7Your persistent Nexora profile","§8UUID: "+player.getUniqueId()));
        inv.setItem(13,menuItem(Material.GOLD_INGOT,"§6Coins","§7Balance: §6"+format(store.balance(player.getUniqueId())),"§7Use /coins for a quick check."));
        inv.setItem(15,menuItem(Material.GRASS_BLOCK,"§aSkyBlock Island","§7Create or return with §f/island","§7Island membership stays separate from your wallet."));
        inv.setItem(18,menuItem(Material.BOOK,"§bSkills","§7View Mining, Farming, Foraging and Combat."));
        inv.setItem(20,menuItem(Material.CHEST,"§eCollections","§7Track gathered resources and milestone progress."));
        inv.setItem(22,menuItem(Material.ARMOR_STAND,"§bMinions","§7Manage island workers with §f/minion list."));
        player.openInventory(inv);
    }
    void showProfile(Player player)throws SQLException{openProfile(player);}
    void showSkills(Player player)throws SQLException{openSkills(player);}
    void showProgress(Player player)throws SQLException{openProgress(player);}
    void showMinions(Player player)throws SQLException{minions.command(player,new String[]{"list"});}
    void showDaily(Player player)throws SQLException{openDaily(player);}
    void warp(Player player,String worldName){
        World world=Bukkit.getWorld(worldName);
        if(world==null){player.sendMessage(PREFIX+"That destination is unavailable.");return;}
        if(worldName.equalsIgnoreCase(getConfig().getString("hub-world","nexora_hub"))){Location spawn=safeHubSpawn(world);if(spawn==null){player.sendMessage(PREFIX+"Hub spawn is unavailable; an administrator must check it.");return;}player.teleportAsync(spawn);player.sendMessage(PREFIX+"Warped to §bHub§7.");return;}
        org.bukkit.block.Block top=world.getHighestBlockAt(0,0);
        Location target=top.getLocation().add(0.5,1,0.5);
        if(!target.getBlock().isPassable())target=top.getLocation().add(0.5,2,0.5);
        player.teleport(target);player.sendMessage(PREFIX+"Warped to §b"+title(worldName.replace("nexora_",""))+"§7.");
    }
    void showCollectionCategory(Player player,String category)throws SQLException{
        List<String> keys=switch(category){case "mining"->List.of("cobblestone","coal","iron","gold","diamond","emerald","redstone","lapis");case "farming"->List.of("wheat","carrot","potato","melon","pumpkin","sugar_cane");case "foraging"->List.of("oak_wood","spruce_wood","birch_wood");default->List.of();};
        if(keys.isEmpty())return;
        Inventory inv=menu(player,"COLLECTIONS §8• §e"+title(category),54);Map<String,Long> progress=store.collections(player.getUniqueId());
        List<Long> milestones=getConfig().getLongList("collections.milestones").stream().filter(n->n>0).sorted().toList();int slot=10;
        for(String key:keys){long count=progress.getOrDefault(key,0L);long next=milestones.stream().filter(n->n>count).findFirst().orElse(0L);Material icon=collectionIcon(key);
            inv.setItem(slot++,menuItem(icon,"§e"+title(key.replace('_',' ')),"§7Collected: §f"+count,next==0?"§aAll configured milestones reached.":"§7Next: §f"+count+"/"+next,"§7Gathering only; purchased or minion output is excluded."));}
        inv.setItem(45,menuItem(Material.ARROW,"§7Back to profile"));inv.setItem(46,menuItem(Material.CHEST,"§eCollections","§7All tracked categories."));player.openInventory(inv);
    }
    private Material collectionIcon(String key){return switch(key){case "cobblestone"->Material.COBBLESTONE;case "coal"->Material.COAL;case "iron"->Material.RAW_IRON;case "gold"->Material.RAW_GOLD;case "diamond"->Material.DIAMOND;case "emerald"->Material.EMERALD;case "redstone"->Material.REDSTONE;case "lapis"->Material.LAPIS_LAZULI;case "wheat"->Material.WHEAT;case "carrot"->Material.CARROT;case "potato"->Material.POTATO;case "melon"->Material.MELON;case "pumpkin"->Material.PUMPKIN;case "sugar_cane"->Material.SUGAR_CANE;case "oak_wood"->Material.OAK_LOG;case "spruce_wood"->Material.SPRUCE_LOG;default->Material.BIRCH_LOG;};}
    void openSkills(Player player)throws SQLException{
        Inventory inv=menu(player,"SKILLS");
        Map<String,Long> values=store.skills(player.getUniqueId());
        int slot=10;
        for(String skill:List.of("mining","farming","foraging","combat")){
            long xp=values.getOrDefault(skill,0L);ProgressionMath.LevelProgress progress=ProgressionMath.skill(xp,skillCurve());
            inv.setItem(slot,menuItem(switch(skill){case "mining"->Material.IRON_PICKAXE;case "farming"->Material.WHEAT;case "foraging"->Material.OAK_LOG;default->Material.IRON_SWORD;},
                    "§b"+title(skill)+" §8• §fLevel "+progress.level(),"§7Total XP: §f"+xp,"§7Progress: §b"+progress.currentXp()+"§7/§b"+progress.requiredXp()+" XP",progressBar(progress.currentXp(),progress.requiredXp()),"§7Reward per skill level: §e+"+getConfig().getLong("progression.rewards.skill-level-xp",10)+" SkyBlock XP","§7Level cap: §f"+progress.maxLevel()));slot+=2;
        }
        inv.setItem(18,menuItem(Material.ARROW,"§7Back to profile"));inv.setItem(20,menuItem(Material.CHEST,"§eCollections","§7Open collection progress."));
        player.openInventory(inv);
    }
    void openProgress(Player player)throws SQLException{
        Inventory inv=menu(player,"PROGRESSION",54);UUID id=player.getUniqueId();
        ProgressionMath.LevelProgress level=ProgressionMath.sky(store.skyblockXp(id),skyCurve());
        int interval=Math.max(1,getConfig().getInt("progression.minion-slot-every-levels",10));
        int nextSlotLevel=unlockedMinionSlots(level.level())>=getConfig().getInt("minions.max-per-island",10)?0:((level.level()/interval)+1)*interval;
        inv.setItem(4,menuItem(Material.NETHER_STAR,"§6§lSkyBlock Level "+level.level(),"§7Progress: §f"+level.currentXp()+"§7/§f"+level.requiredXp()+" XP",progressBar(level.currentXp(),level.requiredXp()),"§7Lifetime XP: §f"+level.totalXp(),"§7Level cap: §f"+level.maxLevel(),nextSlotLevel==0?"§aAll minion slots unlocked.":"§eNext reward: +1 Minion Slot at level "+nextSlotLevel));
        Map<String,Long> skills=store.skills(id);int slot=10;
        for(String skill:List.of("mining","farming","foraging","combat")){
            long total=skills.getOrDefault(skill,0L);ProgressionMath.LevelProgress progress=ProgressionMath.skill(total,skillCurve());
            Material icon=switch(skill){case "mining"->Material.IRON_PICKAXE;case "farming"->Material.WHEAT;case "foraging"->Material.OAK_LOG;default->Material.IRON_SWORD;};
            inv.setItem(slot,menuItem(icon,"§b"+title(skill)+" §8• §fLevel "+progress.level(),"§7XP: §f"+total,"§7To next: §f"+progress.currentXp()+"/"+progress.requiredXp(),progressBar(progress.currentXp(),progress.requiredXp()),"§7Reward per level: §e+"+getConfig().getLong("progression.rewards.skill-level-xp",10)+" SkyBlock XP","§7Cap: §f"+progress.maxLevel()));slot+=2;
        }
        long collected=store.collections(id).values().stream().mapToLong(Long::longValue).sum();int minionsPlaced=store.countMinionsForOwner(id);
        inv.setItem(19,menuItem(Material.CHEST,"§eCollections","§7Resources gathered: §f"+collected,"§7Unique collections: §f"+store.collections(id).size(),"§eOpen /collections for milestones."));
        inv.setItem(22,menuItem(Material.ARMOR_STAND,"§bMinion Progress","§7Placed: §f"+minionsPlaced+"/"+unlockedMinionSlots(level.level()),"§7First type and each new tier grant one-time SkyBlock XP.","§eUse /minion list to manage workers."));
        inv.setItem(25,menuItem(Material.GOLD_INGOT,"§6Coins","§7Purse: §6"+format(store.balance(id)),"§7Bank: §8Not configured."));
        inv.setItem(31,menuItem(Material.AMETHYST_SHARD,"§dMilestones & Achievements","§7One-time progression claims: §f"+store.countProgressionClaims(id),"§7Includes skill, collection and minion unlocks."));
        inv.setItem(45,menuItem(Material.ARROW,"§7Back to profile"));inv.setItem(47,menuItem(Material.BOOK,"§bSkills"));inv.setItem(49,menuItem(Material.CHEST,"§eCollections"));
        player.openInventory(inv);
    }
    void openDaily(Player player)throws SQLException{
        Inventory inv=menu(player,"DAILY");Map<String,ProgressStore.DailyTask> states=new HashMap<>();
        for(ProgressStore.DailyTask task:store.dailyTasks(player.getUniqueId(),dailyDay()))states.put(task.quest(),task);
        int slot=10;
        for(String quest:List.of("mining","farming","foraging")){
            long target=getConfig().getLong("daily.quests."+quest+".target",0);if(target<=0)continue;
            ProgressStore.DailyTask task=states.get(quest);long progress=task==null?0:task.progress();boolean claimed=task!=null&&task.claimed();
            long coins=getConfig().getLong("daily.quests."+quest+".reward-coins",0),xp=getConfig().getLong("daily.quests."+quest+".reward-xp",0);
            Material icon=switch(quest){case "mining"->Material.IRON_PICKAXE;case "farming"->Material.WHEAT;default->Material.OAK_LOG;};
            inv.setItem(slot,menuItem(icon,"§eDaily "+title(quest),claimed?"§aCompleted for today.":"§7Progress: §f"+progress+"/"+target,progressBar(progress,target),"§7Reward: §6"+coins+" Coins §8• §b"+xp+" SkyBlock XP",claimed?"§7Resets at midnight.":progress>=target?"§eClick to claim.":"§7Gather resources to complete."));slot+=3;
        }
        inv.setItem(22,menuItem(Material.ARROW,"§7Back to NEXORA menu"));player.openInventory(inv);
    }
    private void claimDaily(Player player,String quest)throws SQLException{
        long target=getConfig().getLong("daily.quests."+quest+".target",0),coins=getConfig().getLong("daily.quests."+quest+".reward-coins",0),xp=getConfig().getLong("daily.quests."+quest+".reward-xp",0);
        long oldXp=store.skyblockXp(player.getUniqueId());
        if(store.claimDaily(player.getUniqueId(),dailyDay(),quest,target,Math.multiplyExact(coins,100),xp)){
            notifySkyXp(player,oldXp,store.skyblockXp(player.getUniqueId()));
            player.playSound(player.getLocation(),org.bukkit.Sound.UI_TOAST_CHALLENGE_COMPLETE,0.7f,1.2f);
            player.sendMessage(PREFIX+"Daily "+quest+" task claimed: §6"+coins+" Coins §7and §b"+xp+" SkyBlock XP§7.");
        }else player.sendMessage(PREFIX+"That daily task is not complete yet, or was already claimed.");
        openDaily(player);
    }
    void openCollections(Player player)throws SQLException{
        Inventory inv=menu(player,"COLLECTIONS",54);Map<String,Long> values=store.collections(player.getUniqueId());
        List<String> keys=List.of("cobblestone","coal","iron","gold","diamond","emerald","redstone","lapis","wheat","carrot","potato","melon","pumpkin","sugar_cane","oak_wood","spruce_wood","birch_wood");
        Material[] icons={Material.COBBLESTONE,Material.COAL,Material.RAW_IRON,Material.RAW_GOLD,Material.DIAMOND,Material.EMERALD,Material.REDSTONE,Material.LAPIS_LAZULI,Material.WHEAT,Material.CARROT,Material.POTATO,Material.MELON,Material.PUMPKIN,Material.SUGAR_CANE,Material.OAK_LOG,Material.SPRUCE_LOG,Material.BIRCH_LOG};
        long[] milestones=getConfig().getLongList("collections.milestones").stream().mapToLong(Long::longValue).filter(n->n>0).sorted().toArray();
        for(int i=0;i<keys.size();i++){
            long count=values.getOrDefault(keys.get(i),0L),next=Arrays.stream(milestones).filter(n->n>count).findFirst().orElse(0);
            String progress=next==0?(milestones.length==0?"§7Milestones are not configured.":"§aAll configured milestones reached."):
                    "§7Next milestone: §f"+count+"§7/§f"+next;
            inv.setItem(10+i,menuItem(icons[i],"§e"+title(keys.get(i).replace('_',' ')),"§7Collected: §f"+count,progress,"§7Only direct gathering and harvesting count."));
        }
        inv.setItem(45,menuItem(Material.ARROW,"§7Back to profile"));inv.setItem(46,menuItem(Material.BOOK,"§bSkills","§7Open skill progression."));
        player.openInventory(inv);
    }
    private Inventory menu(Player player,String section){return menu(player,section,27);}
    private Inventory menu(Player player,String section,int size){NexoraMenu holder=new NexoraMenu(player.getUniqueId(),section);Inventory inv=Bukkit.createInventory(holder,size,"§1NEXORA §8» §b"+section);holder.inventory=inv;return inv;}
    private ItemStack menuItem(Material type,String name,String... lore){ItemStack stack=new ItemStack(type);ItemMeta meta=stack.getItemMeta();meta.setDisplayName(name);meta.setLore(List.of(lore));stack.setItemMeta(meta);return stack;}
    private static final class NexoraMenu implements InventoryHolder{
        private final UUID owner;private final String section;private Inventory inventory;
        private NexoraMenu(UUID owner,String section){this.owner=owner;this.section=section;}
        @Override public Inventory getInventory(){return inventory;}
    }

    private final class Economy extends AbstractEconomy {
        @Override public boolean isEnabled(){return NexoraCore.this.isEnabled();}
        @Override public String getName(){return "NexoraCoins";}
        @Override public boolean hasBankSupport(){return false;}
        @Override public int fractionalDigits(){return 2;}
        @Override public String format(double amount){return String.format(Locale.US,"%,.2f Coins",amount);}
        @Override public String currencyNamePlural(){return "Coins";}
        @Override public String currencyNameSingular(){return "Coin";}
        @Override public boolean hasAccount(String playerName){return player(playerName)!=null;}
        @Override public boolean hasAccount(OfflinePlayer player){return exists(player.getUniqueId());}
        @Override public boolean hasAccount(String playerName,String worldName){return hasAccount(playerName);}
        @Override public boolean hasAccount(OfflinePlayer player,String worldName){return hasAccount(player);}
        @Override public double getBalance(String playerName){var p=player(playerName);return p==null?0:money(p.getUniqueId());}
        @Override public double getBalance(OfflinePlayer player){return money(player.getUniqueId());}
        @Override public double getBalance(String playerName,String worldName){return getBalance(playerName);}
        @Override public double getBalance(OfflinePlayer player,String worldName){return getBalance(player);}
        @Override public boolean has(String playerName,double amount){return amount>=0&&getBalance(playerName)>=amount;}
        @Override public boolean has(String playerName,String worldName,double amount){return has(playerName,amount);}
        @Override public boolean has(OfflinePlayer player,double amount){return amount>=0&&getBalance(player)>=amount;}
        @Override public boolean has(OfflinePlayer player,String worldName,double amount){return has(player,amount);}
        @Override public EconomyResponse withdrawPlayer(String playerName,double amount){var p=player(playerName);return p==null?fail(amount,"Unknown player"):withdrawPlayer(p,amount);}
        @Override public EconomyResponse withdrawPlayer(String playerName,String worldName,double amount){return withdrawPlayer(playerName,amount);}
        @Override public EconomyResponse withdrawPlayer(OfflinePlayer player,double amount){long m=minor(amount);if(m<0)return fail(amount,"Invalid amount");try{boolean ok=store.withdraw(player.getUniqueId(),m,"Vault withdrawal");return ok?ok(amount):fail(amount,"Insufficient funds");}catch(SQLException e){return fail(amount,"Database error");}}
        @Override public EconomyResponse withdrawPlayer(OfflinePlayer player,String worldName,double amount){return withdrawPlayer(player,amount);}
        @Override public EconomyResponse depositPlayer(String playerName,double amount){var p=player(playerName);return p==null?fail(amount,"Unknown player"):depositPlayer(p,amount);}
        @Override public EconomyResponse depositPlayer(String playerName,String worldName,double amount){return depositPlayer(playerName,amount);}
        @Override public EconomyResponse depositPlayer(OfflinePlayer player,double amount){long m=minor(amount);if(m<0)return fail(amount,"Invalid amount");try{return store.deposit(player.getUniqueId(),m,"Vault deposit")?ok(amount):fail(amount,"Balance limit or unknown profile");}catch(SQLException e){return fail(amount,"Database error");}}
        @Override public EconomyResponse depositPlayer(OfflinePlayer player,String worldName,double amount){return depositPlayer(player,amount);}
        private Player player(String n){return getServer().getPlayerExact(n);}private boolean exists(UUID id){try{return store.balance(id)>=0&&getServer().getOfflinePlayer(id).hasPlayedBefore();}catch(Exception e){return false;}}
        private double money(UUID id){try{return store.balance(id)/100.0;}catch(SQLException e){return 0;}}
        private long minor(double n){if(!Double.isFinite(n)||n<0||n>Long.MAX_VALUE/100.0)return -1;return Math.round(n*100);}
        private EconomyResponse ok(double n){return new EconomyResponse(n,0,EconomyResponse.ResponseType.SUCCESS,null);}private EconomyResponse fail(double n,String e){return new EconomyResponse(n,0,EconomyResponse.ResponseType.FAILURE,e);}
        @Override public EconomyResponse createBank(String name,String player){return fail(0,"Banks unavailable");}
        @Override public EconomyResponse createBank(String name,OfflinePlayer player){return fail(0,"Banks unavailable");}
        @Override public EconomyResponse deleteBank(String name){return fail(0,"Banks unavailable");}
        @Override public EconomyResponse bankBalance(String name){return fail(0,"Banks unavailable");}
        @Override public EconomyResponse bankHas(String name,double amount){return fail(amount,"Banks unavailable");}
        @Override public EconomyResponse bankWithdraw(String name,double amount){return fail(amount,"Banks unavailable");}
        @Override public EconomyResponse bankDeposit(String name,double amount){return fail(amount,"Banks unavailable");}
        @Override public EconomyResponse isBankOwner(String name,String player){return fail(0,"Banks unavailable");}
        @Override public EconomyResponse isBankOwner(String name,OfflinePlayer player){return fail(0,"Banks unavailable");}
        @Override public EconomyResponse isBankMember(String name,String player){return fail(0,"Banks unavailable");}
        @Override public EconomyResponse isBankMember(String name,OfflinePlayer player){return fail(0,"Banks unavailable");}
        @Override public List<String> getBanks(){return Collections.emptyList();}
        @Override public boolean createPlayerAccount(String n){return false;}
        @Override public boolean createPlayerAccount(OfflinePlayer p){return false;}
        @Override public boolean createPlayerAccount(String n,String w){return createPlayerAccount(n);}
        @Override public boolean createPlayerAccount(OfflinePlayer p,String w){return createPlayerAccount(p);}
    }
}
