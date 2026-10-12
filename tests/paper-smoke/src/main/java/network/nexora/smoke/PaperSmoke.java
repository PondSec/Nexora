package network.nexora.smoke;

import network.nexora.core.api.CustomItemCatalog;
import org.bukkit.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import java.util.*;

/** Staging-only real Paper serialization and plugin integration checks. Never ship to production. */
public final class PaperSmoke extends JavaPlugin {
    private int checks;
    @Override public void onEnable() {
        if(!Boolean.getBoolean("nexora.staging") || getServer().getPort()!=25575 || !"127.0.0.1".equals(getServer().getIp())) {
            getLogger().severe("Smoke harness refuses to run outside isolated loopback staging.");getServer().getPluginManager().disablePlugin(this);return;
        }
        getCommand("nxsmoke").setExecutor((sender,command,label,args)->{
            if (sender instanceof org.bukkit.command.ConsoleCommandSender && args.length == 2
                    && args[0].equals("legacy") && Set.of("pending", "delivered", "partial").contains(args[1])) {
                var player = getServer().getPlayerExact("NexoraLegacy");
                if (player == null) return false;
                try {
                    var core = (network.nexora.core.NexoraCore) getServer().getPluginManager().getPlugin("NexoraCore");
                    var database = new network.nexora.trading.MarketplaceDatabase(core.getDataFolder().toPath().resolve("nexora.sqlite"));
                    var market = new network.nexora.trading.BazaarOrderService(database);
                    // Reset only this hardcoded synthetic profile's earlier console fixtures.
                    for (var old : market.pendingItemClaims(player.getUniqueId()))
                        market.markClaimed(player.getUniqueId(), old.claimId(), System.currentTimeMillis());
                    long now = System.currentTimeMillis();
                    market.registerProduct("coal", "minecraft:coal", now);
                    var order = market.place(player.getUniqueId(), "coal", network.nexora.trading.OrderSide.SELL,
                            1000000000, 7, false, UUID.randomUUID().toString(), now);
                    if (order.outcome() != network.nexora.trading.BazaarOrderService.Outcome.OPENED)
                        throw new IllegalStateException("Legacy fixture order did not open");
                    market.cancel(player.getUniqueId(), order.orderId(), UUID.randomUUID().toString(), now + 1);
                    var claims = market.pendingItemClaims(player.getUniqueId());
                    var claim = claims.getLast();
                    if (args[1].equals("delivered")) market.markClaimed(player.getUniqueId(), claim.claimId(), now + 2);
                    ItemStack stack = new ItemStack(Material.COAL, args[1].equals("partial") ? 3 : 7);
                    var meta = stack.getItemMeta();
                    meta.getPersistentDataContainer().set(NamespacedKey.fromString("nexoratrading:market_claim_id"), PersistentDataType.STRING, claim.claimId());
                    stack.setItemMeta(meta);player.getInventory().clear();player.getInventory().setItem(0, stack);player.saveData();
                    player.sendMessage("NXLEGACY_SEEDED");
                } catch (Exception error) { getLogger().log(java.util.logging.Level.SEVERE, "Legacy fixture failed", error); }
                return true;
            }
            if (sender instanceof org.bukkit.command.ConsoleCommandSender && args.length == 2
                    && args[0].equals("crash") && Set.of("before", "after").contains(args[1])) {
                var player = getServer().getPlayerExact("NexoraCrash");
                if (player == null) return false;
                try {
                    player.getInventory().clear();
                    player.getInventory().setItem(0, new ItemStack(Material.EMERALD, 7));
                    player.saveData();
                    ItemStack[] after = player.getInventory().getContents();
                    after[0] = new ItemStack(Material.DIAMOND, 13);
                    String operation = UUID.randomUUID().toString();
                    var core = (network.nexora.core.NexoraCore) getServer().getPluginManager().getPlugin("NexoraCore");
                    core.applyInventoryOperation(player, network.nexora.core.api.InventoryJournal.Domain.CRAFT, operation, after);
                    if (args[1].equals("after")) {
                        new network.nexora.core.api.InventoryJournal(core.getDataFolder().toPath().resolve("nexora.sqlite"))
                                .commitCraft(player.getUniqueId(), operation);
                    }
                    getLogger().warning("NEXORA_CRASH_INJECTION phase=" + args[1] + " operation=" + operation);
                    // Deliberately bypass all shutdown handlers: isolated staging acceptance only.
                    Runtime.getRuntime().halt(73);
                } catch (Exception error) { getLogger().log(java.util.logging.Level.SEVERE, "Crash injection failed", error); }
                return true;
            }
            if(!(sender instanceof org.bukkit.command.ConsoleCommandSender)||args.length!=2||!args[0].equals("seed")||!Set.of("NexoraTestA","NexoraTestB").contains(args[1]))return false;
            var player=getServer().getPlayerExact(args[1]);if(player==null)return false;
            var key=new NamespacedKey(this,"seeded");
            if(player.getPersistentDataContainer().has(key)){sender.sendMessage("Already seeded; refusing inventory reset");return true;}
            player.getInventory().clear();player.getInventory().addItem(new ItemStack(Material.COAL,64),new ItemStack(Material.COAL,64),new ItemStack(Material.COAL,40),new ItemStack(Material.ARMOR_STAND));
            if(player.getName().equals("NexoraTestA"))((network.nexora.core.NexoraCore)getServer().getPluginManager().getPlugin("NexoraCore")).awardDirectGather(player.getUniqueId(),"mining","coal",1000,1000);
            player.getPersistentDataContainer().set(key,PersistentDataType.BYTE,(byte)1);player.saveData();
            player.sendMessage("NXSMOKE_SEEDED");sender.sendMessage("Seeded "+player.getName());return true;
        });
        getServer().getScheduler().runTaskLater(this,this::run,40L);
    }
    private void verify(boolean condition,String message) { if(!condition)throw new AssertionError(message);checks++; }
    private void run() {
        try {
            CustomItemCatalog items=getServer().getServicesManager().load(CustomItemCatalog.class);
            verify(items!=null,"shared Core item API resolves across plugin classloaders");
            int custom=0;
            for(var entry:items.entries().values()) {
                var item=items.create(entry.id(),1);var restored=ItemStack.deserializeBytes(item.serializeAsBytes());
                verify(entry.id().equals(items.identify(restored)),"roundtrip "+entry.id());
                if(entry.id().startsWith("nexora:")) {
                    custom++;
                    ItemStack wrong=item.clone();wrong.setType(Material.DIRT);
                    verify(items.identify(wrong)==null,"material tamper "+entry.id());
                    ItemStack future=item.clone();var meta=future.getItemMeta();
                    meta.getPersistentDataContainer().set(NamespacedKey.fromString("nexoraprogression:item_version"),PersistentDataType.INTEGER,999);future.setItemMeta(meta);
                    verify(items.identify(future)==null,"future schema "+entry.id());
                }
            }
            verify(custom>=80,"at least 80 functional registered custom items");
            ItemStack a=items.create("nexora:infinite_rocket",1),b=items.create("nexora:infinite_rocket",1);
            var instance=NamespacedKey.fromString("nexoraprogression:item_instance_id");
            verify(!Objects.equals(a.getItemMeta().getPersistentDataContainer().get(instance,PersistentDataType.STRING),b.getItemMeta().getPersistentDataContainer().get(instance,PersistentDataType.STRING)),"Rocket unique instances");
            ItemStack[] slots=new ItemStack[41];slots[0]=a;slots[40]=items.create("nexora:enchanted_coal",32);
            var round=ItemStack.deserializeItemsFromBytes(ItemStack.serializeItemsAsBytes(slots));
            verify(round.length==41 && (round[1]==null || round[1].getType().isAir()) && round[40].getAmount()==32,"full inventory snapshot preserves null slots and offhand");
            verify(items.matches(round[40],"nexora:enchanted_coal")&&!items.matches(round[40],"nexora:enchanted_wheat"),"custom commodities remain distinct");
            for(String plugin:List.of("NexoraCore","NexoraProgression","NexoraTrading","NexoraFarm"))verify(getServer().getPluginManager().isPluginEnabled(plugin),plugin+" enabled");
            getLogger().info("NEXORA_SMOKE_PASS checks="+checks+" custom_items="+custom);
            getDataFolder().mkdirs();java.nio.file.Files.writeString(getDataFolder().toPath().resolve("result.txt"),"PASS checks="+checks+" custom_items="+custom+"\n");
        }catch(Throwable error){
            getLogger().log(java.util.logging.Level.SEVERE,"NEXORA_SMOKE_FAIL checks="+checks,error);
            try{getDataFolder().mkdirs();java.nio.file.Files.writeString(getDataFolder().toPath().resolve("result.txt"),"FAIL "+error+"\n");}catch(Exception ignored){}
        }
    }
}
