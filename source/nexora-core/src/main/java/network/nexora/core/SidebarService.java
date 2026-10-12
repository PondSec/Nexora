package network.nexora.core;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.ScoreboardManager;
import org.bukkit.scoreboard.Team;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** Per-player sidebar with stable entries and in-place team updates to prevent flicker. */
final class SidebarService implements Listener {
    private final NexoraCore plugin;
    private final ProgressStore store;
    private final Map<UUID, Sidebar> sidebars = new HashMap<>();
    private int taskId = -1;
    private final Set<UUID> pending = new HashSet<>();
    private final Map<UUID, Snapshot> snapshots = new HashMap<>();
    private record Snapshot(long skyXp, long purse, long bank, int minions) {}

    SidebarService(NexoraCore plugin, ProgressStore store) { this.plugin = plugin; this.store = store; }

    void start() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        long period = Math.max(20, plugin.getConfig().getLong("scoreboard.refresh-ticks", 40));
        taskId = plugin.getServer().getScheduler().scheduleSyncRepeatingTask(plugin, this::refreshAll, 1, period);
        for (Player player : Bukkit.getOnlinePlayers()) plugin.getServer().getScheduler().runTask(plugin, () -> update(player));
    }

    void stop() {
        if (taskId >= 0) plugin.getServer().getScheduler().cancelTask(taskId);
        for (Player player : Bukkit.getOnlinePlayers()) {
            Sidebar sidebar = sidebars.get(player.getUniqueId());
            if (sidebar != null && player.getScoreboard() == sidebar.board) player.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
        }
        sidebars.clear(); pending.clear(); snapshots.clear();
    }

    @EventHandler public void onJoin(PlayerJoinEvent event) {
        plugin.getServer().getScheduler().runTask(plugin, () -> update(event.getPlayer()));
    }
    @EventHandler public void onWorldChange(PlayerChangedWorldEvent event) { update(event.getPlayer()); }
    @EventHandler public void onQuit(PlayerQuitEvent event) { sidebars.remove(event.getPlayer().getUniqueId()); snapshots.remove(event.getPlayer().getUniqueId()); }

    private void refreshAll() { for (Player player : Bukkit.getOnlinePlayers()) update(player); }
    void refresh(Player player) { update(player); }

    private void update(Player player) {
        if (!player.isOnline()) return;
        Sidebar state = sidebars.get(player.getUniqueId());
        if (state == null) {
            ScoreboardManager manager = Bukkit.getScoreboardManager();
            Scoreboard current = player.getScoreboard();
            if (hasOtherSidebar(current)) {
                plugin.getLogger().warning("A different sidebar objective is already active for " + player.getName() + "; Nexora sidebar left disabled for this player.");
                sidebars.put(player.getUniqueId(), Sidebar.skipped());
                return;
            }
            Scoreboard board = manager.getNewScoreboard();
            Objective objective = board.registerNewObjective("nexora", "dummy", "§6§lNEXORA SKYBLOCK");
            objective.setDisplaySlot(DisplaySlot.SIDEBAR);
            List<String> entries = new ArrayList<>(); List<Team> teams = new ArrayList<>();
            for (int i = 0; i < 12; i++) {
                String entry = "§" + Integer.toHexString(i) + "§r";
                Team team = board.registerNewTeam("nx" + i);
                team.addEntry(entry); entries.add(entry); teams.add(team);
                objective.getScore(entry).setScore(12 - i);
            }
            state = new Sidebar(board, teams, entries, false); sidebars.put(player.getUniqueId(), state);
            player.setScoreboard(board);
        } else if (state.skipped) return;
        else if (player.getScoreboard() != state.board) {
            sidebars.remove(player.getUniqueId());
            plugin.getLogger().warning("A different plugin replaced " + player.getName() + "'s Nexora sidebar; respecting that scoreboard.");
            return;
        }
        UUID profileId = player.getUniqueId();
        if (pending.add(profileId)) {
            plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
                Snapshot loaded = null; Exception error = null;
                try { loaded = new Snapshot(store.skyblockXp(profileId), store.balance(profileId), store.bankBalance(profileId), store.countMinionsForOwner(profileId)); }
                catch (Exception ex) { error = ex; }
                Snapshot result = loaded; Exception failure = error;
                if (!plugin.isEnabled()) return;
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    pending.remove(profileId);
                    if (failure != null) { plugin.getLogger().warning("Sidebar snapshot failed: " + failure.getMessage()); return; }
                    if (player.isOnline() && Bukkit.getPlayer(profileId) == player) { snapshots.put(profileId, result); render(player); }
                });
            });
        }
        render(player);
    }

    private void render(Player player) {
        Sidebar state = sidebars.get(player.getUniqueId());
        Snapshot snapshot = snapshots.get(player.getUniqueId());
        if (state == null || state.skipped || snapshot == null) return;
        try {
            long skyXp = snapshot.skyXp();
            ProgressionMath.LevelProgress level = ProgressionMath.sky(skyXp, plugin.skyCurve());
            long purse = snapshot.purse();
            int minions = snapshot.minions();
            int slots = plugin.unlockedMinionSlots(level.level());
            List<String> lines = List.of(
                    "§6§lNEXORA SKYBLOCK",
                    "§8" + LocalDate.now().format(DateTimeFormatter.ofPattern("dd/MM/yyyy")),
                    "§4Profile: §f" + shortText(plugin.getConfig().getString("scoreboard.profile-name", "Main"), 10),
                    "§6SkyBlock Level: §f" + level.level(),
                    level.requiredXp() == 0 ? "§eProgress: §fMAX LEVEL" : "§eProgress: §f" + level.currentXp() + "/" + level.requiredXp() + " XP",
                    "§r",
                    "§4Location: §f" + shortText(location(player.getWorld()), 15),
                    "§6Purse: §f" + String.format(Locale.US, "%,d.%02d", purse / 100, Math.abs(purse % 100)) + " Coins",
                    "§6Bank: §f" + String.format(Locale.US, "%,d.%02d", snapshot.bank() / 100, snapshot.bank() % 100) + " Coins",
                    "§4Minions: §f" + minions + "/" + slots,
                    "§fOnline: §e" + Bukkit.getOnlinePlayers().size(),
                    "§8nexora.pondsec.com"
            );
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                if (!line.equals(state.lastLines.get(i))) { state.teams.get(i).setPrefix(line); state.lastLines.set(i, line); }
            }
        } catch (Exception e) { plugin.getLogger().warning("Sidebar update failed for " + player.getName() + ": " + e.getMessage()); }
    }

    private boolean hasOtherSidebar(Scoreboard scoreboard) {
        return scoreboard.getObjectives().stream().anyMatch(o -> o.getDisplaySlot() == DisplaySlot.SIDEBAR);
    }
    private String location(World world) {
        String name = world.getName();
        if (name.equalsIgnoreCase(plugin.getConfig().getString("hub-world", "nexora_hub"))) return "Hub";
        if (name.equalsIgnoreCase("nexora_mining")) return "Mining Realm";
        if (name.equalsIgnoreCase("nexora_farming")) return "Farming Realm";
        if (name.equalsIgnoreCase("bskyblock_world")) return "Private Island";
        return name;
    }
    private String shortText(String value, int max) {
        if (value == null || value.isBlank()) return "Main";
        String safe = ChatColor.stripColor(value).replaceAll("[^A-Za-z0-9 _-]", "");
        return safe.length() <= max ? safe : safe.substring(0, max);
    }

    private static final class Sidebar {
        private final Scoreboard board; private final List<Team> teams; private final List<String> entries;
        private final List<String> lastLines = new ArrayList<>(Collections.nCopies(12, "")); private final boolean skipped;
        private Sidebar(Scoreboard board, List<Team> teams, List<String> entries, boolean skipped) { this.board=board;this.teams=teams;this.entries=entries;this.skipped=skipped; }
        private static Sidebar skipped() { return new Sidebar(null,List.of(),List.of(),true); }
    }
}
