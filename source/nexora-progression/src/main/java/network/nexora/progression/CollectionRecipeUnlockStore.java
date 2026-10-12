package network.nexora.progression;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/** Persists collection-gated recipe unlocks in the existing NexoraCore SQLite database. */
public final class CollectionRecipeUnlockStore {
    private static final int BUSY_TIMEOUT_MILLIS = 10_000;
    private final String jdbcUrl;
    private final network.nexora.core.ProgressionMath.Curve skillCurve;

    public CollectionRecipeUnlockStore(Path nexoraCoreDatabase) throws SQLException {
        this(nexoraCoreDatabase, network.nexora.core.ProgressionMath.SKILL_DEFAULT);
    }
    public CollectionRecipeUnlockStore(Path nexoraCoreDatabase, network.nexora.core.ProgressionMath.Curve skillCurve) throws SQLException {
        this.skillCurve = Objects.requireNonNull(skillCurve);
        Objects.requireNonNull(nexoraCoreDatabase, "Core database path is required");
        jdbcUrl = "jdbc:sqlite:" + nexoraCoreDatabase.toAbsolutePath();
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("PRAGMA foreign_keys=ON");
            statement.execute("BEGIN IMMEDIATE");
            try {
                if (!tableExists(connection, "profiles") || !tableExists(connection, "collections"))
                    throw new SQLException("Database is missing NexoraCore profile or collection tables");
                statement.execute("CREATE TABLE IF NOT EXISTS nexora_recipe_unlocks ("
                        + "profile_uuid TEXT NOT NULL REFERENCES profiles(uuid),"
                        + "recipe_id TEXT NOT NULL, collection_id TEXT NOT NULL, source_tier INTEGER NOT NULL CHECK(source_tier>0),"
                        + "unlocked_at INTEGER NOT NULL, PRIMARY KEY(profile_uuid,recipe_id))");
                statement.execute("CREATE INDEX IF NOT EXISTS nexora_recipe_unlock_profile_idx "
                        + "ON nexora_recipe_unlocks(profile_uuid,unlocked_at)");
                statement.execute("CREATE TABLE IF NOT EXISTS nexora_progression_placed_blocks ("
                        + "world TEXT NOT NULL,x INTEGER NOT NULL,y INTEGER NOT NULL,z INTEGER NOT NULL,"
                        + "placed_by TEXT NOT NULL,created_at INTEGER NOT NULL,PRIMARY KEY(world,x,y,z))");
                statement.execute("COMMIT");
            } catch (SQLException | RuntimeException exception) {
                rollback(connection, exception);
                throw exception;
            }
        }
    }

    /**
     * Reconciles a player's existing collection totals with the content registry. This can run on
     * first enable or after a catalog update; the unique profile/recipe key prevents repeat grants.
     * It only unlocks recipes and never pays retroactive Coins or XP.
     */
    public List<String> unlockEligibleRecipes(UUID profileId, ContentRegistry registry, long nowMillis) throws SQLException {
        Objects.requireNonNull(profileId, "Profile ID is required");
        Objects.requireNonNull(registry, "Content registry is required");
        registry.validateOrThrow();
        if (nowMillis < 0) throw new IllegalArgumentException("Unlock timestamp cannot be negative");

        try (Connection connection = connect()) {
            beginImmediate(connection);
            try {
                if (!profileExists(connection, profileId)) throw new SQLException("Unknown NexoraCore profile " + profileId);
                List<String> unlocked = new ArrayList<>();
                for (RecipeDefinition recipe : registry.recipes().values().stream()
                        .sorted(java.util.Comparator.comparing(RecipeDefinition::id)).toList()) {
                    if (recipe.requiredSkill() != null && skillLevel(connection, profileId, recipe.requiredSkill()) < recipe.requiredSkillLevel()) continue;
                    boolean eligible = true;
                    for (var requirement : recipe.collectionRequirements().entrySet()) {
                        CollectionDefinition collection = registry.collections().get(requirement.getKey());
                        long total = collectionCount(connection, profileId, collection.id());
                        if (collection.tierFor(total) < requirement.getValue()) { eligible = false; break; }
                    }
                    if (!eligible) continue;
                    String source = recipe.collectionRequirements().entrySet().stream()
                            .sorted(java.util.Map.Entry.comparingByKey())
                            .map(e -> e.getKey() + ":" + e.getValue())
                            .collect(java.util.stream.Collectors.joining(","));
                    try (var insert = connection.prepareStatement("INSERT OR IGNORE INTO nexora_recipe_unlocks "
                            + "(profile_uuid,recipe_id,collection_id,source_tier,unlocked_at) VALUES(?,?,?,?,?)")) {
                        insert.setString(1, profileId.toString());
                        insert.setString(2, recipe.id());
                        insert.setString(3, source.isEmpty() ? "starter" : source);
                        insert.setInt(4, 1);
                        insert.setLong(5, nowMillis);
                        if (insert.executeUpdate() == 1) unlocked.add(recipe.id());
                    }
                }
                commit(connection);
                return List.copyOf(unlocked);
            } catch (SQLException | RuntimeException exception) {
                rollback(connection, exception);
                throw exception;
            }
        }
    }

    public int skillLevel(UUID profileId, String skill) throws SQLException {
        try (Connection connection = connect()) { return skillLevel(connection, profileId, skill); }
    }
    private int skillLevel(Connection connection, UUID profileId, String skill) throws SQLException {
        if (!tableExists(connection,"skills")) return 1;
        try (var query = connection.prepareStatement("SELECT xp FROM skills WHERE uuid=? AND skill=?")) {
            query.setString(1,profileId.toString()); query.setString(2,skill);
            try(var row=query.executeQuery()) { return network.nexora.core.ProgressionMath.skill(row.next()?row.getLong(1):0,skillCurve).level(); }
        }
    }

    public boolean isUnlocked(UUID profileId, String recipeId) throws SQLException {
        Objects.requireNonNull(profileId, "Profile ID is required");
        if (recipeId == null || recipeId.isBlank()) throw new IllegalArgumentException("Recipe ID is required");
        try (Connection connection = connect(); var query = connection.prepareStatement(
                "SELECT 1 FROM nexora_recipe_unlocks WHERE profile_uuid=? AND recipe_id=?")) {
            query.setString(1, profileId.toString()); query.setString(2, recipeId);
            try (var row = query.executeQuery()) { return row.next(); }
        }
    }

    public long collectionCount(UUID profileId, String collectionId) throws SQLException {
        Objects.requireNonNull(profileId, "Profile ID is required");
        if (collectionId == null || collectionId.isBlank()) throw new IllegalArgumentException("Collection ID is required");
        try (Connection connection = connect()) { return collectionCount(connection, profileId, collectionId); }
    }

    /** Adds only explicitly observed direct-gather amounts; purchases and minion output are not counted. */
    public void recordCollections(UUID profileId, java.util.Map<String,Long> amounts) throws SQLException {
        Objects.requireNonNull(profileId, "Profile ID is required");
        if (amounts == null || amounts.isEmpty()) return;
        try (Connection connection = connect()) {
            beginImmediate(connection);
            try {
                if (!profileExists(connection, profileId)) throw new SQLException("Unknown NexoraCore profile " + profileId);
                for (var entry : amounts.entrySet()) {
                    if (entry.getKey() == null || entry.getKey().isBlank() || entry.getValue() == null || entry.getValue() <= 0)
                        throw new IllegalArgumentException("Invalid collection amount");
                    try (var update = connection.prepareStatement("INSERT INTO collections(uuid,item,amount) VALUES(?,?,?) "
                            + "ON CONFLICT(uuid,item) DO UPDATE SET amount=amount+excluded.amount WHERE amount<=?")) {
                        update.setString(1, profileId.toString()); update.setString(2, entry.getKey());
                        update.setLong(3, entry.getValue()); update.setLong(4, Long.MAX_VALUE-entry.getValue());
                        if (update.executeUpdate() != 1) throw new SQLException("Collection counter overflow for " + entry.getKey());
                    }
                }
                commit(connection);
            } catch (SQLException | RuntimeException exception) { rollback(connection, exception); throw exception; }
        }
    }

    public void markPlacedBlock(String world, int x, int y, int z, UUID player, long now) throws SQLException {
        try (Connection connection = connect(); var insert = connection.prepareStatement(
                "INSERT OR REPLACE INTO nexora_progression_placed_blocks(world,x,y,z,placed_by,created_at) VALUES(?,?,?,?,?,?)")) {
            insert.setString(1, world); insert.setInt(2, x); insert.setInt(3, y); insert.setInt(4, z);
            insert.setString(5, player.toString()); insert.setLong(6, now); insert.executeUpdate();
        }
    }

    /** Atomically consumes a placement marker so player-placed blocks cannot inflate Collections. */
    public boolean consumePlacedBlock(String world, int x, int y, int z) throws SQLException {
        try (Connection connection = connect()) {
            beginImmediate(connection);
            try (var delete = connection.prepareStatement("DELETE FROM nexora_progression_placed_blocks WHERE world=? AND x=? AND y=? AND z=?")) {
                delete.setString(1, world); delete.setInt(2, x); delete.setInt(3, y); delete.setInt(4, z);
                boolean found = delete.executeUpdate() == 1; commit(connection); return found;
            } catch (SQLException | RuntimeException exception) { rollback(connection, exception); throw exception; }
        }
    }

    public Set<String> unlockedRecipes(UUID profileId) throws SQLException {
        Objects.requireNonNull(profileId, "Profile ID is required");
        Set<String> recipeIds = new TreeSet<>();
        try (Connection connection = connect(); var query = connection.prepareStatement(
                "SELECT recipe_id FROM nexora_recipe_unlocks WHERE profile_uuid=? ORDER BY recipe_id")) {
            query.setString(1, profileId.toString());
            try (var rows = query.executeQuery()) { while (rows.next()) recipeIds.add(rows.getString(1)); }
        }
        return Set.copyOf(recipeIds);
    }

    private Connection connect() throws SQLException {
        Connection connection = DriverManager.getConnection(jdbcUrl);
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA busy_timeout=" + BUSY_TIMEOUT_MILLIS);
            statement.execute("PRAGMA foreign_keys=ON");
        } catch (SQLException exception) {
            connection.close(); throw exception;
        }
        return connection;
    }

    private static void beginImmediate(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) { statement.execute("BEGIN IMMEDIATE"); }
    }
    private static void commit(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) { statement.execute("COMMIT"); }
    }
    private static void rollback(Connection connection, Exception original) {
        try (Statement statement = connection.createStatement()) { statement.execute("ROLLBACK"); }
        catch (SQLException rollbackFailure) { original.addSuppressed(rollbackFailure); }
    }
    private static boolean tableExists(Connection connection, String table) throws SQLException {
        try (var query = connection.prepareStatement("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?")) {
            query.setString(1, table); try (var row = query.executeQuery()) { return row.next(); }
        }
    }
    private static boolean profileExists(Connection connection, UUID profileId) throws SQLException {
        try (var query = connection.prepareStatement("SELECT 1 FROM profiles WHERE uuid=?")) {
            query.setString(1, profileId.toString()); try (var row = query.executeQuery()) { return row.next(); }
        }
    }
    private static long collectionCount(Connection connection, UUID profileId, String collectionId) throws SQLException {
        // Core stores its stable collection key (for example "oak_wood"), not the output material ID.
        String itemId = collectionId.startsWith("minecraft:")
                ? collectionId.substring("minecraft:".length()) : collectionId;
        try (var query = connection.prepareStatement("SELECT amount FROM collections WHERE uuid=? AND item=?")) {
            query.setString(1, profileId.toString()); query.setString(2, itemId);
            try (var row = query.executeQuery()) { return row.next() ? row.getLong(1) : 0L; }
        }
    }
}
