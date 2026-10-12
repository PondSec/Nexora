package network.nexora.progression;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class ContentRegistryTest {
    @Test void acceptsRegisteredOutputsIngredientsHandlersAndUnlocks() {
        ContentRegistry registry = new ContentRegistry();
        registry.registerEffectHandler("material");
        registry.registerItem(new ItemDefinition("cobblestone", "minecraft:cobblestone", "COMMON", "Raw stone", "material", true, true));
        registry.registerItem(new ItemDefinition("enchanted-cobblestone", "minecraft:paper", "UNCOMMON", "Compressed stone", "material", false, true));
        registry.registerRecipe(new RecipeDefinition("enchanted-cobblestone-recipe", "enchanted-cobblestone", 1,
                Map.of("cobblestone", 160L), "cobblestone", 4, null, 0));
        registry.registerCollection(new CollectionDefinition("cobblestone", "mining", "minecraft:cobblestone",
                List.of(50L, 100L, 250L, 1_000L), Map.of(4, List.of(new CollectionReward("enchanted-unlock",
                RewardType.ITEM_RECIPE_UNLOCK, "enchanted-cobblestone-recipe", 0)))));
        assertDoesNotThrow(registry::validateOrThrow);
    }

    @Test void reportsMissingEffectHandlersRecipeReferencesAndIngredientItems() {
        ContentRegistry registry = new ContentRegistry();
        registry.registerItem(new ItemDefinition("mystery", "minecraft:paper", "RARE", "Unknown effect", "missing-handler", false, true));
        registry.registerRecipe(new RecipeDefinition("bad-recipe", "mystery", 1,
                Map.of("not-registered", 1L), null, 0, null, 0));
        registry.registerCollection(new CollectionDefinition("stone", "mining", "minecraft:stone", List.of(10L),
                Map.of(1, List.of(new CollectionReward("bad-reward", RewardType.RECIPE_UNLOCK, "missing-recipe", 0)))));
        List<String> errors = registry.validate();
        assertTrue(errors.stream().anyMatch(s -> s.contains("Missing effect handler")));
        assertTrue(errors.stream().anyMatch(s -> s.contains("Unknown recipe")));
        assertTrue(errors.stream().anyMatch(s -> s.contains("Unknown ingredient")));
    }

    @Test void rejectsDependencyCyclesAndDuplicateIdentifiers() {
        ContentRegistry registry = new ContentRegistry();
        registry.registerEffectHandler("basic");
        registry.registerItem(new ItemDefinition("a", "minecraft:paper", "COMMON", "A", "basic", false, true));
        registry.registerItem(new ItemDefinition("b", "minecraft:paper", "COMMON", "B", "basic", false, true));
        registry.registerRecipe(new RecipeDefinition("a-from-b", "a", 1, Map.of("b", 1L), null, 0, null, 0));
        registry.registerRecipe(new RecipeDefinition("b-from-a", "b", 1, Map.of("a", 1L), null, 0, null, 0));
        assertTrue(registry.validate().stream().anyMatch(s -> s.contains("dependency cycle")));
        assertThrows(IllegalArgumentException.class, () -> registry.registerRecipe(
                new RecipeDefinition("a-from-b", "a", 1, Map.of("b", 1L), null, 0, null, 0)));
    }
}
