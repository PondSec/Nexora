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
