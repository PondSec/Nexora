package network.nexora.progression;

import network.nexora.core.api.CustomItemCatalog;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import java.util.*;

/** The sole custom item factory and identity validator. Legacy unversioned items remain valid. */
public final class RegistryItemCatalog implements CustomItemCatalog {
    public static final NamespacedKey ID = Objects.requireNonNull(NamespacedKey.fromString("nexoraprogression:item_id"));
    public static final NamespacedKey VERSION = Objects.requireNonNull(NamespacedKey.fromString("nexoraprogression:item_version"));
    public static final NamespacedKey INSTANCE = Objects.requireNonNull(NamespacedKey.fromString("nexoraprogression:item_instance_id"));
    private final Map<String,ItemDefinition> definitions;
    private final Map<String,Entry> catalogEntries;
    public RegistryItemCatalog(ContentRegistry registry) { this.definitions = registry.items(); this.catalogEntries=buildEntries(); }
    @Override public Map<String,Entry> entries() { return catalogEntries; }
    private Map<String,Entry> buildEntries() {
        Map<String,Entry> entries = new LinkedHashMap<>();
        for (var d:definitions.values()) {
            var p=d.properties();
            entries.put(d.id(),new Entry(d.id(),name(d),d.materialKey(),d.rarity(),maxStack(d),p.version(),p.npcValueMinor(),d.bazaarAllowed(),d.auctionAllowed(),p.stats(),p.ability()));
        }
        return Map.copyOf(entries);
    }
    @Override public String identify(ItemStack stack) {
        if(stack==null || stack.getType().isAir() || stack.getAmount()<1) return null;
        ItemMeta meta=stack.getItemMeta();
        if(meta!=null && meta.getPersistentDataContainer().has(ID) && !meta.getPersistentDataContainer().has(ID,PersistentDataType.STRING))return null;
        String id=meta==null?null:meta.getPersistentDataContainer().get(ID,PersistentDataType.STRING);
        var minionType=NamespacedKey.fromString("nexoracore:minion_item_type");
        if(id==null && meta!=null && meta.getPersistentDataContainer().has(minionType,PersistentDataType.STRING))id="nexora:minion_"+meta.getPersistentDataContainer().get(minionType,PersistentDataType.STRING);
        if(id==null) return !stack.hasItemMeta() && definitions.containsKey(stack.getType().getKey().toString()) ? stack.getType().getKey().toString() : null;
        var d=definitions.get(id);
        if(d==null || !id.startsWith("nexora:") || stack.getType()!=Material.matchMaterial(d.materialKey()) || stack.getAmount()>maxStack(d)) return null;
        if(meta.getPersistentDataContainer().has(VERSION) && !meta.getPersistentDataContainer().has(VERSION,PersistentDataType.INTEGER))return null;
        var version=meta.getPersistentDataContainer().get(VERSION,PersistentDataType.INTEGER);
        if(version!=null && (version<1 || version>d.properties().version())) return null;
        if(id.startsWith("nexora:minion_")) {
            String type=meta.getPersistentDataContainer().get(minionType,PersistentDataType.STRING);
            int tier=meta.getPersistentDataContainer().getOrDefault(NamespacedKey.fromString("nexoracore:minion_tier"),PersistentDataType.INTEGER,1);
            if(!id.equals("nexora:minion_"+type)||tier<1||tier>11)return null;
        }
        if(id.equals("nexora:infinite_rocket")) {
            String instance=meta.getPersistentDataContainer().get(INSTANCE,PersistentDataType.STRING);
            try { if(!UUID.fromString(instance).toString().equals(instance))return null; } catch (IllegalArgumentException|NullPointerException e) { return null; }
        }
        return id;
    }
    @Override public boolean matches(ItemStack stack,String id) { return id.equals(identify(stack)); }
    @Override public ItemStack create(String id,int amount) {
        var d=definitions.get(id);
        if(d==null || amount<1 || amount>maxStack(d)) throw new IllegalArgumentException("Unknown item or invalid stack amount: "+id);
        ItemStack item=new ItemStack(Objects.requireNonNull(Material.matchMaterial(d.materialKey())),amount);
        if(!id.startsWith("nexora:"))return item;
        var meta=item.getItemMeta();
        meta.displayName(Component.text(color(d.rarity())+name(d)));
        List<Component> lore=new ArrayList<>();
        lore.add(Component.text("§7"+d.description()));
        d.properties().stats().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e->lore.add(Component.text("§a+"+e.getValue()+" "+e.getKey().replace('_',' '))));
        if(d.properties().ability()!=null)lore.add(Component.text("§6Ability: "+d.properties().ability().replace('_',' ')));
        lore.add(Component.text(color(d.rarity())+d.rarity()));
        lore.add(Component.text("§8Item ID: "+id));
        meta.lore(lore); meta.setMaxStackSize(maxStack(d));
        meta.getPersistentDataContainer().set(ID,PersistentDataType.STRING,id);
        meta.getPersistentDataContainer().set(VERSION,PersistentDataType.INTEGER,d.properties().version());
        if(maxStack(d)==1)meta.getPersistentDataContainer().set(INSTANCE,PersistentDataType.STRING,UUID.randomUUID().toString());
        if(id.startsWith("nexora:minion_")) {
            meta.getPersistentDataContainer().set(NamespacedKey.fromString("nexoracore:minion_item_type"),PersistentDataType.STRING,id.substring("nexora:minion_".length()));
            meta.getPersistentDataContainer().set(NamespacedKey.fromString("nexoracore:minion_tier"),PersistentDataType.INTEGER,1);
        }
        item.setItemMeta(meta);return item;
    }
    private int maxStack(ItemDefinition d) {
        var material=Objects.requireNonNull(Material.matchMaterial(d.materialKey()));
        return d.id().equals("nexora:infinite_rocket")?1:Math.min(d.properties().stackLimit(),material.getMaxStackSize());
    }
    static String name(ItemDefinition d) {
        String raw=d.id().replace("nexora:","").replace("minecraft:","").replace('_',' ');
        StringBuilder s=new StringBuilder();for(String w:raw.split(" +")){if(!s.isEmpty())s.append(' ');s.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));}return s.toString();
    }
    static String color(String rarity) {return switch(rarity){case "UNCOMMON"->"§a";case "RARE"->"§9";case "EPIC"->"§5";case "LEGENDARY"->"§6";case "MYTHIC"->"§d";default->"§f";};}
}
