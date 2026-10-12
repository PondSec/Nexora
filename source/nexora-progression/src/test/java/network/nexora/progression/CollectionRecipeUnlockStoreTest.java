package network.nexora.progression;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class CollectionRecipeUnlockStoreTest {
    @TempDir Path temp;
    private static final UUID HIGH = new UUID(0, 1);
    private static final UUID LOW = new UUID(0, 2);

    private Path database() throws Exception {
        Path file = temp.resolve("core.sqlite");
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + file);
             var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE profiles(uuid TEXT PRIMARY KEY,balance_minor INTEGER NOT NULL,skyblock_xp INTEGER NOT NULL DEFAULT 0)");
            statement.execute("CREATE TABLE collections(uuid TEXT NOT NULL,item TEXT NOT NULL,amount INTEGER NOT NULL,PRIMARY KEY(uuid,item))");
            statement.execute("INSERT INTO profiles(uuid,balance_minor,skyblock_xp) VALUES('" + HIGH + "',125000,700)");
            statement.execute("INSERT INTO profiles(uuid,balance_minor,skyblock_xp) VALUES('" + LOW + "',90000,200)");
            statement.execute("INSERT INTO collections(uuid,item,amount) VALUES('" + HIGH + "','cobblestone',1000)");
            statement.execute("INSERT INTO collections(uuid,item,amount) VALUES('" + LOW + "','cobblestone',100)");
        }
        return file;
    }

    private ContentRegistry registry() {
        ContentRegistry registry = new ContentRegistry(); registry.registerEffectHandler("material");
        registry.registerItem(new ItemDefinition("cobblestone", "minecraft:cobblestone", "COMMON", "Cobblestone", "material", true, true));
        registry.registerItem(new ItemDefinition("enchanted-cobblestone", "minecraft:paper", "UNCOMMON", "Compressed stone", "material", false, true));
        // The Core collection key remains authoritative even when the output material ID differs.
        registry.registerCollection(new CollectionDefinition("cobblestone", "mining", "minecraft:stone",
                List.of(50L, 1_000L), Map.of()));
        registry.registerRecipe(new RecipeDefinition("enchanted-cobblestone", "enchanted-cobblestone", 1,
                Map.of("cobblestone", 160L), "cobblestone", 2, null, 0));
        registry.validateOrThrow();
        return registry;
    }

    @Test void unlocksRetroactivelyByProfileAndNeverChangesCoinsOrXp() throws Exception {
        Path file = database(); var store = new CollectionRecipeUnlockStore(file); var content = registry();
        assertEquals(List.of("enchanted-cobblestone"), store.unlockEligibleRecipes(HIGH, content, 100));
        assertEquals(List.of(), store.unlockEligibleRecipes(LOW, content, 100));
        assertTrue(store.isUnlocked(HIGH, "enchanted-cobblestone"));
        assertFalse(store.isUnlocked(LOW, "enchanted-cobblestone"));

        var reopened = new CollectionRecipeUnlockStore(file);
        assertEquals(List.of(), reopened.unlockEligibleRecipes(HIGH, content, 200));
        assertEquals(125000, scalar(file, "SELECT balance_minor FROM profiles WHERE uuid='" + HIGH + "'"));
        assertEquals(700, scalar(file, "SELECT skyblock_xp FROM profiles WHERE uuid='" + HIGH + "'"));
        assertEquals(1, scalar(file, "SELECT count(*) FROM nexora_recipe_unlocks WHERE profile_uuid='" + HIGH + "'"));
    }

    @Test void concurrentBackfillsCreateExactlyOneUnlockRow() throws Exception {
        Path file = database(); var store = new CollectionRecipeUnlockStore(file); var content = registry();
        try (var executor = Executors.newFixedThreadPool(12)) {
            var calls = java.util.stream.IntStream.range(0, 48)
                    .<java.util.concurrent.Callable<Integer>>mapToObj(i -> () -> store.unlockEligibleRecipes(HIGH, content, 1000 + i).size())
                    .toList();
            int newlyUnlocked = executor.invokeAll(calls).stream().mapToInt(future -> {
                try { return future.get(); } catch (Exception exception) { throw new RuntimeException(exception); }
            }).sum();
            assertEquals(1, newlyUnlocked);
        }
        assertEquals(1, scalar(file, "SELECT count(*) FROM nexora_recipe_unlocks WHERE profile_uuid='" + HIGH + "'"));
        assertEquals(125000, scalar(file, "SELECT balance_minor FROM profiles WHERE uuid='" + HIGH + "'"));
    }

    @Test void unknownProfileCannotReceiveUnlocks() throws Exception {
        Path file = database(); var store = new CollectionRecipeUnlockStore(file);
        assertThrows(java.sql.SQLException.class, () -> store.unlockEligibleRecipes(new UUID(0, 99), registry(), 50));
        assertEquals(0, scalar(file, "SELECT count(*) FROM nexora_recipe_unlocks"));
    }

    @Test void multiCollectionPrerequisitesWaitForEveryTierThenBackfillExactlyOnce() throws Exception {
        Path file = database();
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + file);
             var statement = connection.createStatement()) {
            statement.execute("INSERT INTO collections(uuid,item,amount) VALUES('" + HIGH + "','wheat',1000)");
        }
        ContentRegistry multi = new ContentRegistry(); multi.registerEffectHandler("material");
        multi.registerItem(new ItemDefinition("minecraft:stone", "minecraft:stone", "COMMON", "Stone", "material", TradingClassification.BAZAAR_COMMODITY));
        multi.registerItem(new ItemDefinition("minecraft:wheat", "minecraft:wheat", "COMMON", "Wheat", "material", TradingClassification.BAZAAR_COMMODITY));
        multi.registerItem(new ItemDefinition("nexora:advanced", "minecraft:paper", "EPIC", "Advanced", "material", TradingClassification.BAZAAR_STANDARDIZED_ITEM));
        multi.registerCollection(new CollectionDefinition("cobblestone", "mining", "minecraft:stone", List.of(50L, 1000L), Map.of()));
        multi.registerCollection(new CollectionDefinition("wheat", "farming", "minecraft:wheat", List.of(50L, 1000L), Map.of()));
        multi.registerRecipe(new RecipeDefinition("nexora:advanced", "nexora:advanced", 1,
                Map.of("minecraft:stone", 1L, "minecraft:wheat", 1L), Map.of("cobblestone",2,"wheat",2),
                "cobblestone",2,null,0));
        var store = new CollectionRecipeUnlockStore(file);
        assertTrue(store.unlockEligibleRecipes(HIGH,multi,100).contains("nexora:advanced"));
        assertFalse(store.isUnlocked(LOW,"nexora:advanced"));
        assertTrue(store.isUnlocked(HIGH,"nexora:advanced"));
    }

    private static long scalar(Path file, String sql) throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + file);
             var statement = connection.createStatement(); var rows = statement.executeQuery(sql)) {
            assertTrue(rows.next()); return rows.getLong(1);
        }
    }
}
