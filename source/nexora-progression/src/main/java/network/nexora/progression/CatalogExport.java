package network.nexora.progression;

import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;

/** Documentation exported from the same definitions used by the running plugin. */
public final class CatalogExport {
    public static void main(String[] args) throws Exception {
        Path destination=Path.of(args[0]);Files.createDirectories(destination);
        ContentRegistry registry=DefaultContentCatalog.create();
        var items=registry.items().values().stream().sorted(Comparator.comparing(ItemDefinition::id)).toList();
        long custom=items.stream().filter(i->i.id().startsWith("nexora:")).count();
        StringBuilder catalog=new StringBuilder("# Item catalog\n\nGenerated from DefaultContentCatalog. "+custom+" custom definitions; "+items.size()+" total registered materials and items.\n\nCounts describe definitions, not complete gameplay acceptance. Compression materials are recipe ingredients, eighteen minion tokens place resource producers, equipment currently provides vanilla behavior, and Infinite Rocket has an existing boost handler. RPG equipment stats and generic abilities remain unfinished. Newly added fish/wood need reachable public sources verified in gameplay.\n\n| ID | Material | Rarity | Function | Trade classification |\n| --- | --- | --- | --- | --- |\n");
        for(var item:items)catalog.append("| ").append(item.id()).append(" | ").append(item.materialKey()).append(" | ").append(item.rarity()).append(" | ").append(item.description()).append(" | ").append(item.tradingClassification()).append(" |\n");
        Files.writeString(destination.resolve("ITEM_CATALOG.md"),catalog);
        StringBuilder recipes=new StringBuilder("# Recipe catalog\n\nGenerated from DefaultContentCatalog. "+registry.recipes().size()+" advanced recipe definitions and "+registry.collections().size()+" collections. Quantities are per craft; all listed collection tiers are required. This is the existing inventory-based advanced crafting menu; material recipes for minions also use the workbench. Full shaped/shapeless custom-grid support is not implemented. Definitions and server serialization are checked; not every late-game recipe has been acquired through a real-client progression run.\n\nCompression costs 160 raw → 1 enchanted and 160 enchanted → 1 double enchanted. No purchased or inventory-moved item earns gathering progress.\n\n| Recipe | Output | Ingredients | Collection tiers | Skill |\n| --- | --- | --- | --- | --- |\n");
        for(var recipe:registry.recipes().values().stream().sorted(Comparator.comparing(RecipeDefinition::id)).toList())recipes.append("| ").append(recipe.id()).append(" | ").append(recipe.outputAmount()).append(" × ").append(recipe.outputItemId()).append(" | ").append(format(recipe.ingredients())).append(" | ").append(format(recipe.collectionRequirements())).append(" | ").append(recipe.requiredSkill()==null?"—":recipe.requiredSkill()+" "+recipe.requiredSkillLevel()).append(" |\n");
        Files.writeString(destination.resolve("RECIPE_CATALOG.md"),recipes);
        System.out.println("Catalog exported: custom="+custom+" recipes="+registry.recipes().size()+" collections="+registry.collections().size());
    }
    private static String format(Map<String,? extends Number> values){return values.entrySet().stream().sorted(Map.Entry.comparingByKey()).map(e->e.getKey()+" × "+e.getValue()).collect(Collectors.joining(", "));}
}
