package network.nexora.trading;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.block.sign.Side;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Paper entry point. Core owns the profile UUID and purse; this module adds bank/market tables. */
public final class NexoraTradingPlugin extends JavaPlugin implements Listener {
    private MarketplaceDatabase database;
    private network.nexora.core.NexoraCore core;
    private network.nexora.core.api.CustomItemCatalog itemCatalog;
    private BazaarOrderService bazaar;
    private StandardizedBazaarService standardizedBazaar;
    private AuctionHouseService auctionHouse;
    private NpcLiquidityService npcLiquidity;
    private final Map<String, MarketProduct> products = new LinkedHashMap<>();
    private NamespacedKey npcKey;
    private NamespacedKey actionKey;
    private NamespacedKey claimKey;
    private final Set<UUID> busyPlayers = new HashSet<>();
    private final Map<UUID, ListingDraft> listingDrafts = new ConcurrentHashMap<>();
    private final Map<UUID, BazaarDraft> bazaarDrafts = new ConcurrentHashMap<>();
    private final Map<UUID, StandardBazaarDraft> standardizedBazaarDrafts = new ConcurrentHashMap<>();
    private final Map<UUID, BazaarBrowseState> bazaarBrowseStates = new ConcurrentHashMap<>();
    private final Set<UUID> bazaarSearchPrompts = ConcurrentHashMap.newKeySet();
    private final Map<UUID, SignPrompt> signPrompts = new ConcurrentHashMap<>();
    private NamespacedKey temporarySignKey;

    @Override public void onEnable() {
        npcKey = new NamespacedKey(this, "nexora_npc");
        actionKey = new NamespacedKey(this, "trading_action");
        claimKey = new NamespacedKey(this, "market_claim_id");
        temporarySignKey = new NamespacedKey(this, "temporary_price_sign");
        core = (network.nexora.core.NexoraCore)Bukkit.getPluginManager().getPlugin("NexoraCore");
        itemCatalog=Bukkit.getServicesManager().load(network.nexora.core.api.CustomItemCatalog.class);
        if (core == null || !core.isEnabled() || itemCatalog == null) {
            getLogger().severe("NexoraCore is not enabled; wallet integration is disabled.");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }
        Path coreDb = core.getDataFolder().toPath().resolve("nexora.sqlite");
        try { database = new MarketplaceDatabase(coreDb); }
        catch (SQLException ex) {
            getLogger().severe("Could not connect to NexoraCore SQLite wallet: " + ex.getMessage());
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }
        bazaar = new BazaarOrderService(database);
        standardizedBazaar = new StandardizedBazaarService(database);
        auctionHouse = new AuctionHouseService(database);
        npcLiquidity = new NpcLiquidityService(database);
        if (!loadMarketProducts()) {
            getLogger().severe("Trading configuration has invalid market products; disabling plugin to prevent unusable listings.");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }
        Bukkit.getPluginManager().registerEvents(this, this);
        if (getCommand("bank") != null) getCommand("bank").setExecutor((sender, command, label, args) -> {
            if (!(sender instanceof org.bukkit.entity.Player player)) { sender.sendMessage("Players only."); return true; }
            openBank(player);
            return true;
        });
        if (getCommand("bazaar") != null) getCommand("bazaar").setExecutor((sender, command, label, args) -> {
            if (!(sender instanceof org.bukkit.entity.Player player)) { sender.sendMessage("Players only."); return true; }
            handleBazaarCommand(player, args);
            return true;
        });
        Bukkit.getScheduler().runTask(this, this::spawnConfiguredNpcs);
        Bukkit.getScheduler().runTaskTimer(this, this::accrueOnlineInterest, 1_200L, 1_200L);
        Bukkit.getScheduler().runTaskTimer(this, this::settleExpiredAuctions, 1_200L, 1_200L);
        recoverTemporarySigns();
        getLogger().info("Nexora Bank, player Bazaar, bounded NPC liquidity and Auction House services are enabled.");
    }

    @Override public void onDisable() {
        for (var entry : List.copyOf(signPrompts.entrySet())) { restoreTemporarySign(entry.getValue()); clearTemporarySign(entry.getKey()); }
        signPrompts.clear();
    }

    private void accrueOnlineInterest() {
        List<UUID> players = Bukkit.getOnlinePlayers().stream().map(org.bukkit.entity.Player::getUniqueId)
                .filter(busyPlayers::add).toList();
        if (players.isEmpty()) return;
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            for (UUID id : players) {
                long earned = 0;
                String failure = null;
                try { earned = database.accrueDailyInterest(id, System.currentTimeMillis()); }
                catch (SQLException ex) { failure = ex.getMessage(); }
                long amount = earned;
                String error = failure;
                Bukkit.getScheduler().runTask(this, () -> {
                    busyPlayers.remove(id);
                    org.bukkit.entity.Player player = Bukkit.getPlayer(id);
                    if (player == null) return;
                    if (error != null) getLogger().warning("Daily bank interest failed for " + id + ": " + error);
                    else if (amount > 0) player.sendMessage("§aYour bank earned " + coins(amount) + " Coins in 24-hour interest.");
                });
            }
        });
    }

    private boolean loadMarketProducts() {
        try {
            if (!getDataFolder().exists() && !getDataFolder().mkdirs()) throw new IllegalStateException("Could not create plugin data folder");
            java.io.File configFile = new java.io.File(getDataFolder(), "market.yml");
            if (!configFile.exists()) saveResource("market.yml", false);
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(configFile);
            var section = yaml.getConfigurationSection("products");
            if (section == null) throw new IllegalStateException("market.yml has no products section");
            long now = System.currentTimeMillis();
            for (String id : section.getKeys(false)) {
                String base = "products." + id + ".";
                Material material = Material.matchMaterial(yaml.getString(base + "material", ""));
                if (material == null || !material.isItem() || material.getMaxStackSize() <= 1) {
                    getLogger().warning("Skipping non-stackable or unknown market material: " + id); continue;
                }
                long price = yaml.getLong(base + "reference-price-minor", 0);
                if (price <= 0) { getLogger().warning("Skipping market product without positive reference price: " + id); continue; }
                if (!bazaar.registerProduct(id, material.getKey().toString(), now)) {
                    getLogger().severe("Market product id conflicts with the existing registry: " + id); return false;
                }
                long initialStock = yaml.getLong(base + "initial-stock", 0);
                long initialBudget = yaml.getLong(base + "initial-budget-minor", 0);
                long maxStock = yaml.getLong(base + "max-stock", initialStock);
                long maxBudget = yaml.getLong(base + "max-budget-minor", initialBudget);
                var policy = new NpcLiquidityService.Policy("bazaar_master", id, price,
                        yaml.getInt("market-maker.spread-bps", 1_000), initialStock, initialBudget,
                        maxStock, maxBudget, yaml.getLong(base + "restock-items-per-hour", 0),
                        yaml.getLong(base + "restock-coins-per-hour-minor", 0));
                npcLiquidity.configure(policy, now);
                products.put(id, new MarketProduct(id, material, yaml.getString(base + "category", "Resources"), policy));
            }
            for(var item:itemCatalog.entries().values().stream().sorted(java.util.Comparator.comparing(network.nexora.core.api.CustomItemCatalog.Entry::id)).toList()) {
                if(!item.id().startsWith("nexora:") || !item.bazaar() || item.maxStack()<=1 || !item.stats().isEmpty() || item.ability()!=null)continue;
                Material material=Material.matchMaterial(item.material());
                if(!bazaar.registerProduct(item.id(),item.id(),now))throw new IllegalStateException("Registry product identity conflict: "+item.id());
                var disabledNpc=new NpcLiquidityService.Policy("bazaar_master",item.id(),Math.max(1,item.npcValueMinor()),1000,0,0,0,0,0,0);
                npcLiquidity.configure(disabledNpc,now);
                products.put(item.id(),new MarketProduct(item.id(),material,"Enchanted & Components",disabledNpc));
            }
            products.put("nexora:infinite_rocket", new MarketProduct("nexora:infinite_rocket", Material.FIREWORK_ROCKET,
                    "Mythic Utilities", null, true));
            return !products.isEmpty();
        } catch (Exception ex) { getLogger().severe("Could not load Bazaar products: " + ex.getMessage()); return false; }
    }

    private void handleBazaarCommand(org.bukkit.entity.Player player, String[] args) {
        try {
            if(args.length==0 || args[0].equalsIgnoreCase("open")){openBazaar(player);return;}
            if(args[0].equalsIgnoreCase("orders")){openMyBazaarOrders(player);return;}
            if(args[0].equalsIgnoreCase("cancel") && args.length==2){
                var result=bazaar.cancel(player.getUniqueId(),UUID.fromString(args[1]),UUID.randomUUID().toString(),System.currentTimeMillis());
                player.sendMessage("§e"+result.message());deliverBazaarClaims(player);return;
            }
            if(args.length==4 && Set.of("buy","sell","instantbuy","instantsell").contains(args[0].toLowerCase(java.util.Locale.ROOT))){
                MarketProduct product=products.get(args[1].toLowerCase(java.util.Locale.ROOT));
                if(product==null){player.sendMessage("§cUnknown Bazaar product.");return;}
                if(product.standardized){openStandardizedProduct(player,product,1);return;}
                String action=args[0].toLowerCase(java.util.Locale.ROOT);
                var result=submitCommodityOrder(player,product,action.endsWith("sell")?OrderSide.SELL:OrderSide.BUY,
                        Long.parseLong(args[2]),parseCoins(args[3]),action.startsWith("instant"));
                player.sendMessage("§e"+result.message()+" · "+result.filled()+" filled, "+result.remaining()+" remaining.");
                deliverBazaarClaims(player);return;
            }
            player.sendMessage("§e/bazaar <buy|sell|instantbuy|instantsell> <product> <quantity> <coin-price> · /bazaar orders · /bazaar cancel <id>");
        }catch(Exception error){getLogger().warning("Bazaar transaction failed: "+error.getMessage());player.sendMessage("§cTransaction could not complete. Saved inventory recovery has been applied.");}
    }

    private BazaarOrderService.PlaceResult submitCommodityOrder(org.bukkit.entity.Player player, MarketProduct product, OrderSide side, long quantity, long price, boolean instant) throws Exception {
        if(quantity<1||quantity>1_000_000||price<1)throw new IllegalArgumentException("Invalid order quantity or price");
        String operation=UUID.randomUUID().toString();
        if(side==OrderSide.SELL && removeProduct(player,product,quantity,network.nexora.core.api.InventoryJournal.Domain.BAZAAR_PLACE,operation)==null)
            throw new IllegalArgumentException("Not enough matching items");
        try { return bazaar.place(player.getUniqueId(),product.id,side,price,quantity,instant,operation,System.currentTimeMillis()); }
        finally { if(side==OrderSide.SELL)core.resolveInventoryOperation(player); }
    }

    private void openBazaar(org.bukkit.entity.Player player) {
        BazaarBrowseState requested = bazaarBrowseStates.computeIfAbsent(player.getUniqueId(), ignored -> new BazaarBrowseState("ALL", "", 0));
        BazaarHolder holder = new BazaarHolder(); Inventory inv = Bukkit.createInventory(holder, 54, Component.text("NEXORA | BAZAAR")); holder.inventory = inv;
        List<MarketProduct> matches = products.values().stream().filter(product -> requested.category.equals("ALL")
                        || product.category.equalsIgnoreCase(requested.category))
                .filter(product -> requested.search.isBlank() || product.id.toLowerCase(java.util.Locale.ROOT).contains(requested.search)
                        || product.category.toLowerCase(java.util.Locale.ROOT).contains(requested.search))
                .toList();
        int pageCount = Math.max(1, (matches.size() + 44) / 45);
        int page = Math.max(0, Math.min(requested.page, pageCount - 1));
        BazaarBrowseState state = page == requested.page ? requested : new BazaarBrowseState(requested.category, requested.search, page);
        if (page != requested.page) bazaarBrowseStates.put(player.getUniqueId(), state);
        int start = page * 45, end = Math.min(matches.size(), start + 45);
        for (int index = start; index < end; index++) {
            MarketProduct product = matches.get(index);
            int slot = index - start;
            if (product.standardized) {
                inv.setItem(slot, button(Material.FIREWORK_ROCKET, "§dInfinite Rocket", List.of("§5MYTHIC · Player market only", "§7No NPC quotes · unique escrow", "§eClick to view Rocket orders"), "bazaar-product:" + product.id));
                continue;
            }
            try {
                NpcLiquidityService.Quote q = npcLiquidity.quote(product.policy, System.currentTimeMillis());
                List<String> lore = List.of("§7" + product.category, "§6System bid: " + coins(q.bidMinor()) + " §7× " + q.bidQuantity(),
                        "§aSystem ask: " + coins(q.askMinor()) + " §7× " + q.askQuantity(), "§8System offers use finite stored stock and Coins.", "§eClick to open");
                ItemStack icon = button(product.material, "§f" + pretty(product.id), lore, "bazaar-product:" + product.id);
                inv.setItem(slot, icon);
            } catch (SQLException ex) { getLogger().warning("Bazaar quote failed for " + product.id + ": " + ex.getMessage()); }
        }
        inv.setItem(45, button(Material.BOOK, "§bMy Bazaar Orders", "bazaar:orders"));
        inv.setItem(46, button(Material.BARREL, "§eCollect bought items", "bazaar:claim"));
        inv.setItem(47, button(Material.COMPASS, "§eCategories: " + state.category, "bazaar:categories"));
        inv.setItem(48, button(Material.NAME_TAG, "§bSearch", List.of("§7Current: " + (state.search.isBlank() ? "none" : state.search), "§eEnter a product name"), "bazaar:search"));
        inv.setItem(49, button(Material.MILK_BUCKET, "§cClear Search", "bazaar:clear-search"));
        inv.setItem(50, button(Material.ARROW, "§ePrevious Page", page > 0 ? "bazaar-page:" + (page - 1) : null));
        inv.setItem(51, button(Material.PAPER, "§fPage " + (page + 1) + "/" + pageCount,
                List.of("§7" + matches.size() + " products", "§7Search: " + (state.search.isBlank() ? "none" : state.search))));
        inv.setItem(52, button(Material.ARROW, "§eNext Page", page + 1 < pageCount ? "bazaar-page:" + (page + 1) : null));
        player.openInventory(inv);
    }

    private void openBazaarCategories(org.bukkit.entity.Player player) {
        BazaarHolder holder = new BazaarHolder(); Inventory inv = Bukkit.createInventory(holder, 27, Component.text("BAZAAR | CATEGORIES")); holder.inventory = inv;
        var categories = new java.util.LinkedHashSet<String>(); categories.add("ALL"); products.values().forEach(p -> categories.add(p.category));
        int slot = 0; for (String category : categories) {
            Material icon = category.equals("ALL") ? Material.BOOK : category.equals("Farming") ? Material.WHEAT
                    : category.equals("Mining") ? Material.IRON_PICKAXE : category.equals("Combat") ? Material.IRON_SWORD
                    : category.equals("Foraging") ? Material.OAK_LOG : category.equals("Mythic Utilities") ? Material.FIREWORK_ROCKET : Material.CHEST;
            inv.setItem(slot++, button(icon, "§e" + category, "bazaar-category:" + category));
        }
        inv.setItem(22, button(Material.ARROW, "§eBack", "bazaar:back")); player.openInventory(inv);
    }

    private void openProduct(org.bukkit.entity.Player player, MarketProduct product) {
        openProduct(player, product, 1);
    }

    private void openProduct(org.bukkit.entity.Player player, MarketProduct product, int selectedQuantity) {
        if (product.standardized) { openStandardizedProduct(player, product, selectedQuantity); return; }
        ProductHolder holder = new ProductHolder(); Inventory inv = Bukkit.createInventory(holder, 27, Component.text("BAZAAR | " + pretty(product.id))); holder.inventory = inv;
        try {
            var quote = npcLiquidity.quote(product.policy, System.currentTimeMillis());
            long buyGross = Math.multiplyExact(quote.askMinor(), selectedQuantity);
            long buyTotal = Math.addExact(buyGross, MoneyMath.fee(buyGross, 100));
            long sellGross = Math.multiplyExact(quote.bidMinor(), selectedQuantity);
            long sellNet = sellGross - MoneyMath.fee(sellGross, 100);
            inv.setItem(4, button(product.material, "§f" + pretty(product.id), List.of(
                    "§6System bid: " + coins(quote.bidMinor()) + " each · stock " + quote.bidQuantity(),
                    "§aSystem ask: " + coins(quote.askMinor()) + " each · stock " + quote.askQuantity(),
                    "§7Orders use the same price-time matching rules.")));
            int[] amounts = {1, 16, 64};
            for (int i = 0; i < amounts.length; i++) {
                int qty = amounts[i];
                boolean selected = qty == selectedQuantity;
                long askTotal = Math.addExact(Math.multiplyExact(quote.askMinor(), qty),
                        MoneyMath.fee(Math.multiplyExact(quote.askMinor(), qty), 100));
                long bidNet = Math.multiplyExact(quote.bidMinor(), qty)
                        - MoneyMath.fee(Math.multiplyExact(quote.bidMinor(), qty), 100);
                inv.setItem(10 + i, button(selected ? Material.LIME_DYE : Material.GRAY_DYE,
                        (selected ? "§a▶ " : "§f") + "Quantity ×" + qty,
                        List.of("§7System buy total: §6" + coins(askTotal), "§7System sell net: §6" + coins(bidNet),
                                "§7Select this quantity"), "bazaar-quantity:" + product.id.replace(":", "%3A") + ":" + qty));
            }
            int npcBuyAmount = Math.toIntExact(Math.min(selectedQuantity, quote.askQuantity()));
            int npcSellAmount = Math.toIntExact(Math.min(selectedQuantity, quote.bidQuantity()));
            inv.setItem(19, button(Material.GOLD_INGOT, "§aBuy from System ×" + selectedQuantity,
                    List.of("§7Price " + coins(quote.askMinor()) + " × " + selectedQuantity,
                            "§7Includes 1% trade fee", "§6Total: " + coins(buyTotal),
                            npcBuyAmount == selectedQuantity ? "§eClick to buy" : "§cOnly " + npcBuyAmount + " in System stock"),
                    npcBuyAmount == selectedQuantity ? "bazaar-npc-buy:" + product.id.replace(":", "%3A") + ":" + selectedQuantity : null));
            inv.setItem(20, button(Material.EMERALD, "§bPlace Buy Order ×" + selectedQuantity,
                    List.of("§7Limit: " + coins(quote.askMinor()) + " each", "§7Escrow + order fee: §6" +
                            coins(Math.addExact(Math.multiplyExact(quote.askMinor(), selectedQuantity),
                                    BazaarAccounting.buyOrderCreationFee(quote.askMinor(), selectedQuantity))),
                            "§7Filled automatically against player sell orders"),
                    "bazaar-order:" + product.id.replace(":", "%3A") + ":BUY:" + selectedQuantity + ":" + quote.askMinor()));
            inv.setItem(24, button(Material.BARREL, "§ePlace Sell Offer ×" + selectedQuantity,
                    List.of("§7Limit: " + coins(quote.bidMinor()) + " each", "§7Estimated net after 1% fee: §6" + coins(sellNet),
                            "§7Filled automatically against player buy orders"),
                    "bazaar-order:" + product.id.replace(":", "%3A") + ":SELL:" + selectedQuantity + ":" + quote.bidMinor()));
            inv.setItem(25, button(Material.RED_DYE, "§cSell to System ×" + selectedQuantity,
                    List.of("§7Bid " + coins(quote.bidMinor()) + " × " + selectedQuantity,
                            "§7Net after 1% fee: §6" + coins(sellNet),
                            npcSellAmount == selectedQuantity ? "§eClick to sell" : "§cSystem can buy " + npcSellAmount + ""),
                    npcSellAmount == selectedQuantity ? "bazaar-npc-sell:" + product.id.replace(":", "%3A") + ":" + selectedQuantity : null));
            inv.setItem(22, button(Material.ARROW, "§eBack", "bazaar:back"));
        } catch (SQLException ex) { player.sendMessage("§cCould not load market quote."); return; }
        player.openInventory(inv);
    }

    private void openStandardizedProduct(org.bukkit.entity.Player player, MarketProduct product, int selectedQuantity) {
        ProductHolder holder = new ProductHolder(); Inventory inv = Bukkit.createInventory(holder, 27, Component.text("BAZAAR | INFINITE ROCKET")); holder.inventory = inv;
        try {
            List<Long> bids = standardizedBazaar.book(product.id, OrderSide.BUY, 5);
            List<Long> asks = standardizedBazaar.book(product.id, OrderSide.SELL, 5);
            inv.setItem(4, button(Material.FIREWORK_ROCKET, "§d§lInfinite Rocket", List.of("§5MYTHIC · Eternal Thrust",
                    "§7Standardized, unique item instances", "§7Bazaar sells only real player-crafted Rockets",
                    "§7NPC market maker: disabled", "§6Best player bid: " + (bids.isEmpty() ? "none" : coins(bids.getFirst())),
                    "§aBest player ask: " + (asks.isEmpty() ? "none" : coins(asks.getFirst())))));
            int[] quantities = {1, 2, 4};
            for (int i = 0; i < quantities.length; i++) {
                int qty = quantities[i];
                inv.setItem(10 + i, button(qty == selectedQuantity ? Material.LIME_DYE : Material.GRAY_DYE,
                        (qty == selectedQuantity ? "§a▶ " : "§f") + "Quantity ×" + qty,
                        List.of("§7Unique physical Rockets required for sell offers", "§eClick to select"),
                        "bazaar-quantity:" + product.id.replace(":", "%3A") + ":" + qty));
            }
            inv.setItem(19, button(Material.EMERALD, "§bCreate Buy Order", List.of("§7Enter a per-Rocket limit price", "§7Coins escrowed; matching is price-time priority", "§7Quantity: " + selectedQuantity), "bazaar-standard-price:BUY:" + selectedQuantity));
            inv.setItem(25, button(Material.BARREL, "§eCreate Sell Offer", List.of("§7Enter a per-Rocket limit price", "§7Requires " + selectedQuantity + " real Rockets", "§7Items are saved as unique escrow instances"), "bazaar-standard-price:SELL:" + selectedQuantity));
            if (!asks.isEmpty()) {
                long total = Math.multiplyExact(asks.getFirst(), selectedQuantity);
                inv.setItem(14, button(Material.GOLD_INGOT, "§aInstant Buy ×" + selectedQuantity,
                        List.of("§7Best player ask: " + coins(asks.getFirst()), "§7Estimated total: §6" + coins(total), "§7Requires full liquidity; no resting remainder"),
                        "bazaar-standard-instant:BUY:" + selectedQuantity + ":" + asks.getFirst()));
            }
            if (!bids.isEmpty()) {
                long net = Math.multiplyExact(bids.getFirst(), selectedQuantity) - MoneyMath.fee(Math.multiplyExact(bids.getFirst(), selectedQuantity), 100);
                inv.setItem(16, button(Material.EMERALD, "§eInstant Sell ×" + selectedQuantity,
                        List.of("§7Best player bid: " + coins(bids.getFirst()), "§7Estimated net: §6" + coins(net), "§7Requires full liquidity; no resting remainder"),
                        "bazaar-standard-instant:SELL:" + selectedQuantity + ":" + bids.getFirst()));
            }
            inv.setItem(22, button(Material.ARROW, "§eBack", "bazaar:back"));
        } catch (SQLException ex) { player.sendMessage("§cCould not load Rocket order book."); return; }
        player.openInventory(inv);
    }

    private void openAuctionHouse(org.bukkit.entity.Player player) {
        AuctionHolder holder = new AuctionHolder(); Inventory inv = Bukkit.createInventory(holder, 54, Component.text("NEXORA | AUCTION HOUSE")); holder.inventory = inv;
        try {
            List<AuctionHouseService.Listing> listings = auctionHouse.browse(45, System.currentTimeMillis());
            int slot = 0;
            for (var listing : listings) {
                Material material = Material.matchMaterial(listing.itemType()); if (material == null) material = Material.PAPER;
                inv.setItem(slot++, button(material, "§b" + pretty(listing.itemType()), List.of("§7" + listing.mode(), "§6" + (listing.mode() == AuctionHouseService.Mode.BIN ? "BIN: " + coins(listing.priceMinor()) : "Start: " + coins(listing.priceMinor())),
                        "§eCurrent bid: " + coins(listing.currentBidMinor()), "§7Ends " + Math.max(0, (listing.endsAt() - System.currentTimeMillis()) / 60_000) + " minutes", "§8Seller " + listing.seller(), "§eClick for details"), "ah-view:" + listing.id()));
            }
        } catch (SQLException ex) { player.sendMessage("§cAuction listings could not be loaded."); }
        inv.setItem(45, button(Material.NAME_TAG, "§aCreate Listing", List.of("§7List the item in your hand", "§eClick to choose BIN or auction"), "ah-create"));
        inv.setItem(47, button(Material.BOOK, "§bMy Listings", "ah-mine"));
        inv.setItem(48, button(Material.GOLD_INGOT, "§6My Leading Bids", "ah-my-bids"));
        inv.setItem(49, button(Material.CHEST, "§eCollect won or expired items", "ah:claim"));
        inv.setItem(53, button(Material.CLOCK, "§eRefresh", "ah-home"));
        player.openInventory(inv);
    }

    private void openAuctionDetail(org.bukkit.entity.Player player, UUID id) throws SQLException {
        var listing = auctionHouse.getActive(id, System.currentTimeMillis());
        if (listing == null) { player.sendMessage("§cThis listing is no longer active."); openAuctionHouse(player); return; }
        AuctionHolder holder = new AuctionHolder(); Inventory inv = Bukkit.createInventory(holder, 27, Component.text("AUCTION | DETAILS")); holder.inventory = inv;
        Material material = Material.matchMaterial(listing.itemType()); if (material == null) material = Material.PAPER;
        inv.setItem(4, button(material, "§b" + pretty(listing.itemType()), List.of("§7Mode: " + listing.mode(), "§6" + (listing.mode() == AuctionHouseService.Mode.BIN ? "Price: " + coins(listing.priceMinor()) : "Starting price: " + coins(listing.priceMinor())), "§eCurrent bid: " + coins(listing.currentBidMinor()), "§7Ends in " + Math.max(0, (listing.endsAt() - System.currentTimeMillis()) / 60_000) + " minutes", "§7Seller: " + listing.seller())));
        if (listing.mode() == AuctionHouseService.Mode.BIN) {
            long fee = MoneyMath.fee(listing.priceMinor(), 100);
            inv.setItem(11, button(Material.GOLD_INGOT, "§aBuy now", List.of("§7Price: " + coins(listing.priceMinor()), "§7Buyer fee: " + coins(fee), "§7Total: " + coins(Math.addExact(listing.priceMinor(), fee)), "§eContinue to confirmation"), "ah-bin-confirm:" + id));
        } else {
            long base = listing.currentBidMinor() == 0 ? listing.priceMinor() : listing.currentBidMinor();
            long minimum = listing.currentBidMinor() == 0 ? base : Math.addExact(base, Math.max(1, MoneyMath.fee(base, 500)));
            inv.setItem(10, bidButton(id, minimum, "§aMinimum bid"));
            inv.setItem(13, bidButton(id, Math.max(minimum, Math.addExact(base, MoneyMath.fee(base, 1_000))), "§eRaise 10%"));
            inv.setItem(16, bidButton(id, Math.max(minimum, Math.addExact(base, MoneyMath.fee(base, 2_500))), "§6Raise 25%"));
        }
        inv.setItem(22, button(Material.ARROW, "§eBack to listings", "ah-home")); player.openInventory(inv);
    }

    private ItemStack bidButton(UUID id, long amount, String title) {
        return button(Material.EMERALD, title, List.of("§7Bid: " + coins(amount), "§7Coins are held in escrow until", "§7you are outbid or the auction ends.", "§eConfirm on next page"), "ah-bid-confirm:" + id + ":" + amount);
    }

    private void openCreateListing(org.bukkit.entity.Player player) {
        ItemStack held = player.getInventory().getItemInMainHand();
        if (held.getType().isAir()) { player.sendMessage("§cHold the item you want to list, then choose Create Listing."); openAuctionHouse(player); return; }
        AuctionHolder holder = new AuctionHolder(); Inventory inv = Bukkit.createInventory(holder, 27, Component.text("AUCTION | CHOOSE TYPE")); holder.inventory = inv;
        inv.setItem(4, button(held.getType(), "§bItem to list: " + pretty(held.getType().getKey().getKey()), List.of("§7Stack size: " + held.getAmount())));
        inv.setItem(11, button(Material.GOLD_INGOT, "§aBuy It Now Listing", List.of("§7One fixed price", "§7You can cancel before a bid is placed"), "ah-create-mode:BIN"));
        inv.setItem(15, button(Material.CLOCK, "§eTimed Auction", List.of("§7Bidders compete until it ends", "§7The leading bid is held in escrow"), "ah-create-mode:BID"));
        inv.setItem(22, button(Material.ARROW, "§eBack", "ah-home")); player.openInventory(inv);
    }

    private void openListingDuration(org.bukkit.entity.Player player, AuctionHouseService.Mode mode) {
        AuctionHolder holder = new AuctionHolder(); Inventory inv = Bukkit.createInventory(holder, 27, Component.text("AUCTION | DURATION")); holder.inventory = inv;
        inv.setItem(10, button(Material.CLOCK, "§a1 hour", "ah-duration:" + mode + ":60"));
        inv.setItem(12, button(Material.CLOCK, "§e6 hours", "ah-duration:" + mode + ":360"));
        inv.setItem(14, button(Material.CLOCK, "§624 hours", "ah-duration:" + mode + ":1440"));
        inv.setItem(16, button(Material.CLOCK, "§b7 days", "ah-duration:" + mode + ":10080"));
        inv.setItem(22, button(Material.ARROW, "§eBack", "ah-create")); player.openInventory(inv);
    }

    private void openListingPrice(org.bukkit.entity.Player player, ListingDraft draft) {
        listingDrafts.put(player.getUniqueId(), draft);
        AuctionHolder holder = new AuctionHolder();
        Inventory inv = Bukkit.createInventory(holder, 27, Component.text("AUCTION | SET PRICE"));
        holder.inventory = inv;
        inv.setItem(4, button(draft.item.getType(), "§b" + pretty(draft.item.getType().getKey().getKey()),
                List.of("§7" + draft.mode + " · stack ×" + draft.item.getAmount())));
        inv.setItem(13, button(Material.OAK_SIGN, "§eEnter price on sign", List.of(
                "§7A sign editor will open.", "§7Enter Coins on line 1, e.g. 125.50."), "ah-price-sign"));
        if (draft.priceMinor > 0) {
            inv.setItem(15, button(Material.GOLD_INGOT, "§6Price: " + coins(draft.priceMinor),
                    List.of("§7Listing fee: " + coins(MoneyMath.fee(draft.priceMinor, 200)))));
            inv.setItem(11, button(Material.LIME_CONCRETE, "§aReview Listing", "ah-price-review"));
        }
        inv.setItem(22, button(Material.ARROW, "§eBack", "ah-create"));
        player.openInventory(inv);
    }

    private void openAuctionPriceSign(org.bukkit.entity.Player player) {
        ListingDraft draft = listingDrafts.get(player.getUniqueId());
        if (draft == null) { openAuctionHouse(player); return; }
        Location origin = player.getLocation();
        Block target = null;
        for (int dy = 2; dy <= 5 && target == null; dy++) for (int dx = -2; dx <= 2 && target == null; dx++)
            for (int dz = -2; dz <= 2 && target == null; dz++) {
                Block candidate = origin.getBlock().getRelative(dx, dy, dz);
                if (candidate.getType().isAir()) target = candidate;
            }
        if (target == null) { player.sendMessage("§cNo safe space for a temporary price sign nearby."); openListingPrice(player, draft); return; }
        Location location = target.getLocation();
        target.setType(Material.OAK_SIGN, false);
        if (!(target.getState() instanceof Sign sign)) { target.setType(Material.AIR, false); player.sendMessage("§cCould not open the price sign."); return; }
        String token = UUID.randomUUID().toString();
        sign.getPersistentDataContainer().set(temporarySignKey, PersistentDataType.STRING, token);
        sign.update(true, false);
        SignPrompt prompt = new SignPrompt(location, token);
        signPrompts.put(player.getUniqueId(), prompt);
        persistTemporarySign(player.getUniqueId(), prompt);
        prompt.cleanup = Bukkit.getScheduler().runTaskLater(this, () -> {
            if (signPrompts.remove(player.getUniqueId(), prompt)) {
                restoreTemporarySign(prompt);
                clearTemporarySign(player.getUniqueId());
                if (player.isOnline()) { player.sendMessage("§7Price entry expired."); openListingPrice(player, draft); }
            }
        }, 20L * 60);
        sign.getSide(Side.FRONT).line(0, Component.text("Enter Coins"));
        sign.getSide(Side.FRONT).line(1, Component.text("e.g. 125.50"));
        sign.update(true, false);
        player.openSign(sign, Side.FRONT);
    }

    private void openStandardBazaarPriceSign(org.bukkit.entity.Player player, StandardBazaarDraft draft) {
        standardizedBazaarDrafts.put(player.getUniqueId(), draft);
        Location origin = player.getLocation(); Block target = null;
        for (int dy = 2; dy <= 5 && target == null; dy++) for (int dx = -2; dx <= 2 && target == null; dx++)
            for (int dz = -2; dz <= 2 && target == null; dz++) { Block candidate = origin.getBlock().getRelative(dx, dy, dz); if (candidate.getType().isAir()) target = candidate; }
        if (target == null) { player.sendMessage("§cNo safe space for a temporary price sign nearby."); openProduct(player, products.get("nexora:infinite_rocket"), draft.quantity); return; }
        Location location = target.getLocation(); target.setType(Material.OAK_SIGN, false);
        if (!(target.getState() instanceof Sign sign)) { target.setType(Material.AIR, false); player.sendMessage("§cCould not open the price sign."); return; }
        String token = UUID.randomUUID().toString(); sign.getPersistentDataContainer().set(temporarySignKey, PersistentDataType.STRING, token); sign.update(true, false);
        SignPrompt prompt = new SignPrompt(location, token); signPrompts.put(player.getUniqueId(), prompt); persistTemporarySign(player.getUniqueId(), prompt);
        prompt.cleanup = Bukkit.getScheduler().runTaskLater(this, () -> {
            if (signPrompts.remove(player.getUniqueId(), prompt)) { restoreTemporarySign(prompt); clearTemporarySign(player.getUniqueId());
                if (player.isOnline()) { standardizedBazaarDrafts.remove(player.getUniqueId()); player.sendMessage("§7Price entry expired."); openProduct(player, products.get("nexora:infinite_rocket"), draft.quantity); } }
        }, 20L * 60);
        sign.getSide(Side.FRONT).line(0, Component.text("Price in Coins")); sign.getSide(Side.FRONT).line(1, Component.text("e.g. 125000")); sign.update(true, false); player.openSign(sign, Side.FRONT);
    }

    private void openBazaarSearchSign(org.bukkit.entity.Player player) {
        Location origin = player.getLocation(); Block target = null;
        for (int dy = 2; dy <= 5 && target == null; dy++) for (int dx = -2; dx <= 2 && target == null; dx++)
            for (int dz = -2; dz <= 2 && target == null; dz++) { Block candidate = origin.getBlock().getRelative(dx, dy, dz); if (candidate.getType().isAir()) target = candidate; }
        if (target == null) { player.sendMessage("§cNo safe space for a temporary search sign nearby."); openBazaar(player); return; }
        Location location = target.getLocation(); target.setType(Material.OAK_SIGN, false);
        if (!(target.getState() instanceof Sign sign)) { target.setType(Material.AIR, false); player.sendMessage("§cCould not open search input."); return; }
        String token = UUID.randomUUID().toString(); sign.getPersistentDataContainer().set(temporarySignKey, PersistentDataType.STRING, token); sign.update(true, false);
        SignPrompt prompt = new SignPrompt(location, token); signPrompts.put(player.getUniqueId(), prompt); bazaarSearchPrompts.add(player.getUniqueId()); persistTemporarySign(player.getUniqueId(), prompt);
        prompt.cleanup = Bukkit.getScheduler().runTaskLater(this, () -> {
            if (signPrompts.remove(player.getUniqueId(), prompt)) { bazaarSearchPrompts.remove(player.getUniqueId()); restoreTemporarySign(prompt); clearTemporarySign(player.getUniqueId());
                if (player.isOnline()) { player.sendMessage("§7Search entry expired."); openBazaar(player); } }
        }, 20L * 60);
        sign.getSide(Side.FRONT).line(0, Component.text("Search products")); sign.getSide(Side.FRONT).line(1, Component.text("name or item ID")); sign.update(true, false); player.openSign(sign, Side.FRONT);
    }

    @EventHandler public void onPriceSignChange(SignChangeEvent event) {
        SignPrompt prompt = signPrompts.get(event.getPlayer().getUniqueId());
        if (prompt == null || !prompt.matches(event.getBlock().getLocation())) return;
        event.setCancelled(true);
        signPrompts.remove(event.getPlayer().getUniqueId(), prompt);
        if (prompt.cleanup != null) prompt.cleanup.cancel();
        restoreTemporarySign(prompt);
        clearTemporarySign(event.getPlayer().getUniqueId());
        if (bazaarSearchPrompts.remove(event.getPlayer().getUniqueId())) {
            String enteredSearch = event.getLine(0).trim().toLowerCase(java.util.Locale.ROOT);
            BazaarBrowseState old = bazaarBrowseStates.getOrDefault(event.getPlayer().getUniqueId(), new BazaarBrowseState("ALL", "", 0));
            bazaarBrowseStates.put(event.getPlayer().getUniqueId(), new BazaarBrowseState(old.category, enteredSearch, 0));
            Bukkit.getScheduler().runTask(this, () -> { if (event.getPlayer().isOnline()) openBazaar(event.getPlayer()); });
            return;
        }
        String entered = event.getLine(0).trim().replace(',', '.');
        try {
            long price = parseCoins(entered);
            if (price <= 0) throw new IllegalArgumentException("Price must be positive");
            StandardBazaarDraft standard = standardizedBazaarDrafts.get(event.getPlayer().getUniqueId());
            if (standard != null) {
                StandardBazaarDraft priced = new StandardBazaarDraft(standard.product, standard.side, standard.quantity, price);
                standardizedBazaarDrafts.put(event.getPlayer().getUniqueId(), priced);
                Bukkit.getScheduler().runTask(this, () -> { if (event.getPlayer().isOnline()) openStandardBazaarConfirmation(event.getPlayer(), priced); });
                return;
            }
            ListingDraft old = listingDrafts.get(event.getPlayer().getUniqueId());
            if (old == null) return;
            ListingDraft priced = new ListingDraft(old.mode, old.durationMillis, old.item, price);
            listingDrafts.put(event.getPlayer().getUniqueId(), priced);
            Bukkit.getScheduler().runTask(this, () -> { if (event.getPlayer().isOnline()) openListingPrice(event.getPlayer(), priced); });
        } catch (RuntimeException invalid) {
            event.getPlayer().sendMessage("§cEnter a positive price with at most two decimal places on the first line.");
            Bukkit.getScheduler().runTask(this, () -> { if (event.getPlayer().isOnline()) {
                StandardBazaarDraft standard = standardizedBazaarDrafts.get(event.getPlayer().getUniqueId());
                if (standard != null) openStandardizedProduct(event.getPlayer(), standard.product, standard.quantity);
                else { ListingDraft listing = listingDrafts.get(event.getPlayer().getUniqueId()); if (listing != null) openListingPrice(event.getPlayer(), listing); }
            }});
        }
    }

    private void restoreTemporarySign(SignPrompt prompt) {
        World world = prompt.location.getWorld();
        if (world == null || !world.isChunkLoaded(prompt.location.getBlockX() >> 4, prompt.location.getBlockZ() >> 4)) return;
        Block block = prompt.location.getBlock();
        if (block.getState() instanceof Sign sign && prompt.token.equals(sign.getPersistentDataContainer().get(temporarySignKey, PersistentDataType.STRING)))
            block.setType(Material.AIR, false);
    }

    private void recoverTemporarySigns() {
        java.io.File file = new java.io.File(getDataFolder(), "temporary-signs.yml");
        if (!file.isFile()) return;
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        for (String id : yaml.getKeys(false)) {
            World world = Bukkit.getWorld(yaml.getString(id + ".world", ""));
            if (world == null) continue;
            Location loc = new Location(world, yaml.getInt(id + ".x"), yaml.getInt(id + ".y"), yaml.getInt(id + ".z"));
            if (!world.isChunkLoaded(loc.getBlockX() >> 4, loc.getBlockZ() >> 4)) continue;
            restoreTemporarySign(new SignPrompt(loc, yaml.getString(id + ".token", "")));
            yaml.set(id, null);
        }
        try { yaml.save(file); } catch (java.io.IOException ex) { getLogger().warning("Could not clear temporary sign recovery file: " + ex.getMessage()); }
    }

    private void persistTemporarySign(UUID playerId, SignPrompt prompt) {
        java.io.File file = new java.io.File(getDataFolder(), "temporary-signs.yml");
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        String path = playerId.toString();
        yaml.set(path + ".world", prompt.location.getWorld().getName()); yaml.set(path + ".x", prompt.location.getBlockX());
        yaml.set(path + ".y", prompt.location.getBlockY()); yaml.set(path + ".z", prompt.location.getBlockZ()); yaml.set(path + ".token", prompt.token);
        try { yaml.save(file); } catch (java.io.IOException ex) { getLogger().warning("Could not persist temporary sign cleanup marker: " + ex.getMessage()); }
    }

    private void clearTemporarySign(UUID playerId) {
        java.io.File file = new java.io.File(getDataFolder(), "temporary-signs.yml");
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file); yaml.set(playerId.toString(), null);
        try { yaml.save(file); } catch (java.io.IOException ex) { getLogger().warning("Could not clear temporary sign marker: " + ex.getMessage()); }
    }

    private void openMyAuctions(org.bukkit.entity.Player player, boolean bids) throws SQLException {
        AuctionHolder holder = new AuctionHolder(); Inventory inv = Bukkit.createInventory(holder, 54, Component.text(bids ? "AUCTION | MY BIDS" : "AUCTION | MY LISTINGS")); holder.inventory = inv;
        var rows = bids ? auctionHouse.myBids(player.getUniqueId(), 45, System.currentTimeMillis()) : auctionHouse.myListings(player.getUniqueId(), 45, System.currentTimeMillis());
        int slot = 0;
        for (var listing : rows) {
            Material material = Material.matchMaterial(listing.itemType()); if (material == null) material = Material.PAPER;
            String action = bids ? "ah-view:" + listing.id() : "ah-cancel-confirm:" + listing.id();
            inv.setItem(slot++, button(material, "§b" + pretty(listing.itemType()), List.of("§7" + listing.mode(), "§6" + (listing.mode() == AuctionHouseService.Mode.BIN ? "BIN: " + coins(listing.priceMinor()) : "Start: " + coins(listing.priceMinor())), "§eCurrent bid: " + coins(listing.currentBidMinor()), "§7Ends in " + Math.max(0, (listing.endsAt() - System.currentTimeMillis()) / 60_000) + " minutes", bids ? "§7You are currently leading" : listing.currentBidMinor() > 0 ? "§cHas bids; cannot be cancelled" : "§eClick to cancel and reclaim item"), bids ? action : listing.currentBidMinor() > 0 ? null : action));
        }
        inv.setItem(49, button(Material.ARROW, "§eBack", "ah-home")); player.openInventory(inv);
        if (rows.isEmpty()) player.sendMessage(bids ? "§7You have no leading bids." : "§7You have no active listings.");
    }

    private void openAuctionConfirmation(org.bukkit.entity.Player player, String title, String description, String confirmAction) {
        AuctionHolder holder = new AuctionHolder(); Inventory inv = Bukkit.createInventory(holder, 27, Component.text("AUCTION | CONFIRM")); holder.inventory = inv;
        inv.setItem(13, button(Material.PAPER, "§e" + title, List.of("§7" + description, "§cReview before confirming.")));
        inv.setItem(11, button(Material.LIME_CONCRETE, "§aConfirm", confirmAction));
        inv.setItem(15, button(Material.RED_CONCRETE, "§cCancel", "ah-home")); player.openInventory(inv);
    }

    private void startListingPriceGui(org.bukkit.entity.Player player, AuctionHouseService.Mode mode, long durationMillis) {
        ItemStack held = player.getInventory().getItemInMainHand();
        if (held.getType().isAir()) { player.sendMessage("§cHold an item before creating a listing."); openAuctionHouse(player); return; }
        openListingPrice(player, new ListingDraft(mode, durationMillis, held.clone(), 0));
    }

    private void openMyBazaarOrders(org.bukkit.entity.Player player) throws SQLException {
        BazaarHolder holder = new BazaarHolder(); Inventory inv = Bukkit.createInventory(holder, 54, Component.text("BAZAAR | MY ORDERS")); holder.inventory = inv;
        int slot = 0;
        for (var order : bazaar.myOrders(player.getUniqueId(), 45)) {
            MarketProduct p = products.get(order.productId()); Material material = p == null ? Material.PAPER : p.material;
            boolean active = order.status().equals("OPEN") || order.status().equals("PARTIAL");
            String action = active ? "bazaar-cancel-confirm:" + order.orderId() : null;
            inv.setItem(slot++, button(material, "§f" + pretty(order.productId()), List.of("§7" + order.side() + " · " + coins(order.unitPriceMinor()) + " each",
                    "§7Filled " + order.filled() + " · remaining " + order.remaining(), "§7Status: " + order.status(), active ? "§eClick to cancel" : "§8Closed"), action));
        }
        for (var order : (slot < 45 ? standardizedBazaar.myOrders(player.getUniqueId(), 45 - slot) : List.<StandardizedBazaarService.OrderView>of())) {
            if (slot >= 45) break;
            boolean active = order.status().equals("OPEN") || order.status().equals("PARTIAL");
            inv.setItem(slot++, button(Material.FIREWORK_ROCKET, "§dInfinite Rocket · " + order.side(),
                    List.of("§7Limit: " + coins(order.unitPriceMinor()), "§7Remaining: " + order.remaining(), "§8" + order.status(), active ? "§eClick to cancel" : ""),
                    active ? "bazaar-standard-cancel-confirm:" + order.orderId() : null));
        }
        inv.setItem(49, button(Material.ARROW, "§eBack to Bazaar", "bazaar:back"));
        player.openInventory(inv);
    }

    private void openBazaarConfirmation(org.bukkit.entity.Player player, String title, String description, String action) {
        BazaarHolder holder = new BazaarHolder(); Inventory inv = Bukkit.createInventory(holder, 27, Component.text("BAZAAR | CONFIRM")); holder.inventory = inv;
        inv.setItem(13, button(Material.PAPER, "§e" + title, List.of("§7" + description)));
        inv.setItem(11, button(Material.LIME_CONCRETE, "§aConfirm", action));
        inv.setItem(15, button(Material.RED_CONCRETE, "§cCancel", "bazaar:back")); player.openInventory(inv);
    }

    private void openStandardBazaarConfirmation(org.bukkit.entity.Player player, StandardBazaarDraft draft) {
        long gross = Math.multiplyExact(draft.price, draft.quantity);
        AuctionHolder holder = new AuctionHolder(); Inventory inv = Bukkit.createInventory(holder, 27, Component.text("BAZAAR | REVIEW ROCKET ORDER")); holder.inventory = inv;
        inv.setItem(11, button(Material.PAPER, "§dInfinite Rocket · " + (draft.instant ? "Instant " : "Limit ") + draft.side, List.of("§7Quantity: " + draft.quantity,
                "§7Limit per Rocket: §6" + coins(draft.price),
                draft.side == OrderSide.BUY ? "§7Escrow total: §6" + coins(gross) : "§7Estimated seller fee: §6" + coins(MoneyMath.fee(gross, 100)),
                "§7Matches resting player orders immediately")));
        inv.setItem(15, button(Material.LIME_CONCRETE, "§aConfirm Order", "bazaar-standard-confirm"));
        inv.setItem(22, button(Material.RED_CONCRETE, "§cCancel", "bazaar:back")); player.openInventory(inv);
    }

    private void executeStandardBazaarOrder(org.bukkit.entity.Player player) throws Exception {
        StandardBazaarDraft draft = standardizedBazaarDrafts.remove(player.getUniqueId());
        if (draft == null || draft.price <= 0) { openProduct(player, products.get("nexora:infinite_rocket")); return; }
        if (draft.side == OrderSide.BUY) {
            var result = draft.instant
                    ? standardizedBazaar.buyInstant(player.getUniqueId(), draft.product.id, draft.price, draft.quantity, UUID.randomUUID().toString(), System.currentTimeMillis())
                    : standardizedBazaar.buy(player.getUniqueId(), draft.product.id, draft.price, draft.quantity, UUID.randomUUID().toString(), System.currentTimeMillis());
            player.sendMessage("§eRocket buy order: " + result.outcome() + " · " + result.filled() + " filled, " + result.remaining() + " open.");
        } else {
            List<ItemStack> selected = new java.util.ArrayList<>(); List<StandardizedBazaarService.StandardItem> escrow = new java.util.ArrayList<>();
            for (ItemStack stack : player.getInventory().getStorageContents()) {
                if (selected.size() >= draft.quantity) break;
                if (stack == null || stack.getType().isAir() || !isInfiniteRocket(stack)) continue;
                String instance = rocketInstanceId(stack);
                if (instance == null || instance.isBlank()) continue;
                selected.add(stack.clone()); escrow.add(new StandardizedBazaarService.StandardItem(instance, stack.clone().serializeAsBytes()));
            }
            if (selected.size() != draft.quantity) { player.sendMessage("§cYou need " + draft.quantity + " unique Infinite Rockets in your inventory."); openStandardizedProduct(player, draft.product, draft.quantity); return; }
            String operation=UUID.randomUUID().toString();
            ItemStack[] after=player.getInventory().getContents();
            for(int i=0;i<after.length;i++)after[i]=after[i]==null?null:after[i].clone();
            Set<String> instances=selected.stream().map(this::rocketInstanceId).collect(java.util.stream.Collectors.toSet());
            for(int slot=0;slot<player.getInventory().getStorageContents().length;slot++) {
                if(after[slot]!=null && instances.contains(rocketInstanceId(after[slot])))after[slot]=null;
            }
            core.applyInventoryOperation(player,network.nexora.core.api.InventoryJournal.Domain.STANDARD_PLACE,operation,after);
            try {
                var result = draft.instant
                        ? standardizedBazaar.sellInstant(player.getUniqueId(), draft.product.id, draft.price, escrow, operation, System.currentTimeMillis())
                        : standardizedBazaar.sell(player.getUniqueId(), draft.product.id, draft.price, escrow, operation, System.currentTimeMillis());
                player.sendMessage("§eRocket sell offer: " + result.outcome() + " · " + result.filled() + " filled, " + result.remaining() + " open.");
            } finally {core.resolveInventoryOperation(player);}
        }
        deliverStandardizedBazaarClaims(player); openStandardizedProduct(player, draft.product, draft.quantity);
    }

    private boolean isInfiniteRocket(ItemStack stack) { return "nexora:infinite_rocket".equals(itemCatalog.identify(stack)); }
    private String rocketInstanceId(ItemStack stack) {
        if (!stack.hasItemMeta()) return null;
        NamespacedKey key = NamespacedKey.fromString("nexoraprogression:item_instance_id");
        return key == null ? null : stack.getItemMeta().getPersistentDataContainer().get(key, PersistentDataType.STRING);
    }
    private void removeExactInstance(org.bukkit.entity.Player player, String id) {
        for (int slot = 0; slot < player.getInventory().getStorageContents().length; slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (stack != null && id != null && id.equals(rocketInstanceId(stack))) { player.getInventory().setItem(slot, null); return; }
        }
    }

    private void confirmBazaarOrder(org.bukkit.entity.Player player, MarketProduct product, OrderSide side, int quantity, long price) {
        if (product == null || quantity < 1 || price < 1) return;
        long gross = Math.multiplyExact(price, quantity);
        long costOrNet = side == OrderSide.BUY ? Math.addExact(gross, BazaarAccounting.buyOrderCreationFee(price, quantity))
                : gross - MoneyMath.fee(gross, 100);
        bazaarDrafts.put(player.getUniqueId(), new BazaarDraft(product, side, quantity, price));
        openBazaarConfirmation(player, side == OrderSide.BUY ? "Place buy order" : "Place sell offer",
                side + " " + quantity + " × " + pretty(product.id) + " at " + coins(price) + " each · " +
                        (side == OrderSide.BUY ? "escrow + fee " : "estimated net ") + coins(costOrNet), "bazaar-order-confirm");
    }

    private void executeBazaarOrder(org.bukkit.entity.Player player) throws Exception {
        BazaarDraft draft=bazaarDrafts.remove(player.getUniqueId());if(draft==null){openBazaar(player);return;}
        var result=submitCommodityOrder(player,draft.product,draft.side,draft.quantity,draft.price,false);
        player.sendMessage("§e"+result.message()+" · "+result.filled()+" filled, "+result.remaining()+" remaining.");
        deliverBazaarClaims(player);openMyBazaarOrders(player);
    }

    private void tradeNpcBuy(org.bukkit.entity.Player player, MarketProduct product, int quantity) throws Exception {
        if (product == null) return;
        var quote = npcLiquidity.quote(product.policy, System.currentTimeMillis());
        long total = Math.addExact(Math.multiplyExact(quote.askMinor(), quantity), MoneyMath.fee(Math.multiplyExact(quote.askMinor(), quantity), 100));
        if (quote.askQuantity() < quantity) { player.sendMessage("§cSystem stock changed; available: " + quote.askQuantity()); openProduct(player, product, quantity); return; }
        var outcome = npcLiquidity.buyFromNpc(product.policy, player.getUniqueId(), quantity, quote.askMinor(), UUID.randomUUID().toString(), System.currentTimeMillis());
        player.sendMessage(outcome == NpcLiquidityService.Outcome.TRADED ? "§aBought " + quantity + " × " + pretty(product.id) + " for " + coins(total) + " Coins." : "§cSystem buy failed: " + outcome);
        if (outcome == NpcLiquidityService.Outcome.TRADED) deliverBazaarClaims(player);
        openProduct(player, product, quantity);
    }

    private void tradeNpcSell(org.bukkit.entity.Player player, MarketProduct product, int quantity) throws Exception {
        if(product==null)return;
        var quote=npcLiquidity.quote(product.policy,System.currentTimeMillis());
        if(quote.bidQuantity()<quantity){player.sendMessage("§cNPC liquidity is insufficient.");return;}
        String operation=UUID.randomUUID().toString();
        if(removeProduct(player,product,quantity,network.nexora.core.api.InventoryJournal.Domain.NPC_TRADE,operation)==null){player.sendMessage("§cNot enough matching items.");return;}
        try {
            var outcome=npcLiquidity.sellToNpc(product.policy,player.getUniqueId(),quantity,quote.bidMinor(),operation,System.currentTimeMillis());
            player.sendMessage("§eNPC sale: "+outcome);
        }finally{core.resolveInventoryOperation(player);}
        openProduct(player,product,quantity);
    }

    private void settleExpiredAuctions() {
        try { int count = auctionHouse.settleExpired(System.currentTimeMillis()); if (count > 0) getLogger().info("Settled " + count + " expired auction(s)."); }
        catch (SQLException ex) { getLogger().warning("Could not settle expired auctions: " + ex.getMessage()); }
    }

    private void handleMarketClick(org.bukkit.entity.Player player, String action) {
        try {
            if (action.startsWith("bazaar-product:")) { MarketProduct product = products.get(action.substring("bazaar-product:".length())); if (product != null) openProduct(player, product); return; }
            if (action.startsWith("bazaar-quantity:")) { String[] bits = action.split(":"); MarketProduct product = products.get(bits[1].replace("%3A", ":")); if (product != null) openProduct(player, product, Integer.parseInt(bits[2])); return; }
            if (action.startsWith("bazaar-standard-price:")) { String[] bits = action.split(":"); MarketProduct product = products.get("nexora:infinite_rocket");
                StandardBazaarDraft draft = new StandardBazaarDraft(product, OrderSide.valueOf(bits[1]), Integer.parseInt(bits[2]), 0, false);
                openStandardBazaarPriceSign(player, draft); return; }
            if (action.startsWith("bazaar-standard-instant:")) { String[] bits = action.split(":");
                StandardBazaarDraft draft = new StandardBazaarDraft(products.get("nexora:infinite_rocket"), OrderSide.valueOf(bits[1]), Integer.parseInt(bits[2]), Long.parseLong(bits[3]), true);
                standardizedBazaarDrafts.put(player.getUniqueId(), draft); openStandardBazaarConfirmation(player, draft); return; }
            if (action.equals("bazaar-standard-confirm")) { executeStandardBazaarOrder(player); return; }
            if (action.equals("bazaar:orders")) { openMyBazaarOrders(player); return; }
            if (action.equals("bazaar:categories")) { openBazaarCategories(player); return; }
            if (action.equals("bazaar:search")) { openBazaarSearchSign(player); return; }
            if (action.equals("bazaar:clear-search")) { BazaarBrowseState old = bazaarBrowseStates.getOrDefault(player.getUniqueId(), new BazaarBrowseState("ALL", "", 0)); bazaarBrowseStates.put(player.getUniqueId(), new BazaarBrowseState(old.category, "", 0)); openBazaar(player); return; }
            if (action.startsWith("bazaar-category:")) { String category = action.substring("bazaar-category:".length()); BazaarBrowseState old = bazaarBrowseStates.getOrDefault(player.getUniqueId(), new BazaarBrowseState("ALL", "", 0)); bazaarBrowseStates.put(player.getUniqueId(), new BazaarBrowseState(category, old.search, 0)); openBazaar(player); return; }
            if (action.startsWith("bazaar-page:")) { int page = Integer.parseInt(action.substring("bazaar-page:".length())); BazaarBrowseState old = bazaarBrowseStates.getOrDefault(player.getUniqueId(), new BazaarBrowseState("ALL", "", 0)); bazaarBrowseStates.put(player.getUniqueId(), new BazaarBrowseState(old.category, old.search, page)); openBazaar(player); return; }
            if (action.startsWith("bazaar-cancel-confirm:")) { UUID id = UUID.fromString(action.substring("bazaar-cancel-confirm:".length())); openBazaarConfirmation(player, "Cancel Bazaar order?", "Remaining escrow will be returned.", "bazaar-cancel-execute:" + id); return; }
            if (action.startsWith("bazaar-cancel-execute:")) { UUID id = UUID.fromString(action.substring("bazaar-cancel-execute:".length())); var result = bazaar.cancel(player.getUniqueId(), id, UUID.randomUUID().toString(), System.currentTimeMillis()); player.sendMessage("§e" + result.message()); if (result.outcome() == BazaarOrderService.Outcome.CANCELLED) deliverBazaarClaims(player); openMyBazaarOrders(player); return; }
            if (action.startsWith("bazaar-standard-cancel-confirm:")) { UUID id = UUID.fromString(action.substring("bazaar-standard-cancel-confirm:".length())); openBazaarConfirmation(player, "Cancel Rocket order?", "Unused Coins or Rocket instances return as claims.", "bazaar-standard-cancel:" + id); return; }
            if (action.startsWith("bazaar-standard-cancel:")) { UUID id = UUID.fromString(action.substring("bazaar-standard-cancel:".length())); var result = standardizedBazaar.cancel(player.getUniqueId(), id, System.currentTimeMillis()); player.sendMessage("§eRocket order: " + result.outcome()); deliverStandardizedBazaarClaims(player); openMyBazaarOrders(player); return; }
            if (action.startsWith("bazaar-npc-buy:")) { String[] bits = action.split(":"); MarketProduct product = products.get(bits[1].replace("%3A", ":")); tradeNpcBuy(player, product, Integer.parseInt(bits[2])); return; }
            if (action.startsWith("bazaar-npc-sell:")) { String[] bits = action.split(":"); MarketProduct product = products.get(bits[1].replace("%3A", ":")); tradeNpcSell(player, product, Integer.parseInt(bits[2])); return; }
            if (action.startsWith("bazaar-order:")) { String[] bits = action.split(":"); MarketProduct product = products.get(bits[1].replace("%3A", ":")); confirmBazaarOrder(player, product, OrderSide.valueOf(bits[2]), Integer.parseInt(bits[3]), Long.parseLong(bits[4])); return; }
            if (action.equals("bazaar-order-confirm")) { executeBazaarOrder(player); return; }
            if (action.equals("bazaar:back")) { openBazaar(player); return; }
            if (action.equals("bazaar:claim")) { deliverBazaarClaims(player); return; }
            if (action.equals("ah:claim")) { deliverAuctionClaims(player); return; }
            if (action.equals("ah-home")) { listingDrafts.remove(player.getUniqueId()); openAuctionHouse(player); return; }
            if (action.equals("ah-create")) { openCreateListing(player); return; }
            if (action.equals("ah-mine")) { openMyAuctions(player, false); return; }
            if (action.equals("ah-my-bids")) { openMyAuctions(player, true); return; }
            if (action.startsWith("ah-view:")) { openAuctionDetail(player, UUID.fromString(action.substring(8))); return; }
            if (action.startsWith("ah-create-mode:")) { openListingDuration(player, AuctionHouseService.Mode.valueOf(action.substring(15))); return; }
            if (action.startsWith("ah-duration:")) {
                String[] bits = action.split(":"); startListingPriceGui(player, AuctionHouseService.Mode.valueOf(bits[1]), Math.multiplyExact(Long.parseLong(bits[2]), 60_000)); return;
            }
            if (action.equals("ah-price-sign")) { openAuctionPriceSign(player); return; }
            if (action.equals("ah-price-review")) {
                ListingDraft draft = listingDrafts.get(player.getUniqueId());
                if (draft == null || draft.priceMinor <= 0) { player.sendMessage("§cChoose a valid price first."); openAuctionHouse(player); return; }
                long fee = MoneyMath.fee(draft.priceMinor, 200);
                openAuctionConfirmation(player, "Review listing", draft.mode + " · " + pretty(draft.item.getType().getKey().getKey()) + " ×" + draft.item.getAmount() + " · price " + coins(draft.priceMinor) + " · listing fee " + coins(fee), "ah-list-confirm"); return;
            }
            if (action.startsWith("ah-bin-confirm:")) {
                UUID id = UUID.fromString(action.substring("ah-bin-confirm:".length()));
                var listing = auctionHouse.getActive(id, System.currentTimeMillis()); if (listing == null) { player.sendMessage("§cThis listing is no longer active."); openAuctionHouse(player); return; }
                long total = Math.addExact(listing.priceMinor(), MoneyMath.fee(listing.priceMinor(), 100));
                openAuctionConfirmation(player, "Buy " + pretty(listing.itemType()), "Price " + coins(listing.priceMinor()) + " + 1% fee · total " + coins(total), "ah-buy-execute:" + id); return;
            }
            if (action.startsWith("ah-bid-confirm:")) {
                String[] bits = action.split(":"); UUID id = UUID.fromString(bits[1]); long amount = Long.parseLong(bits[2]);
                openAuctionConfirmation(player, "Place bid", "Bid " + coins(amount) + " Coins. This amount is escrowed; an outbid refunds it.", "ah-bid-execute:" + id + ":" + amount); return;
            }
            if (action.equals("ah-list-confirm")) {
                ListingDraft draft = listingDrafts.remove(player.getUniqueId());
                ItemStack hand = player.getInventory().getItemInMainHand();
                if (draft == null || hand.getType().isAir() || !hand.isSimilar(draft.item) || hand.getAmount() != draft.item.getAmount()) {
                    player.sendMessage("§cThe held item changed; no listing was created. Start again with the intended item."); openAuctionHouse(player); return;
                }
                String itemId = draft.item.hasItemMeta() ? draft.item.getItemMeta().getPersistentDataContainer().get(
                        NamespacedKey.fromString("nexoraprogression:item_id"), PersistentDataType.STRING) : null;
                var definition=itemId==null?null:itemCatalog.entries().get(itemId);
                if(itemId!=null && (definition==null || !definition.auction() || !itemId.equals(itemCatalog.identify(draft.item)))) {
                    player.sendMessage("§cThat custom item is not approved for auctions.");return;
                }
                if(draft.item.hasItemMeta() && draft.item.getItemMeta().getPersistentDataContainer().has(NamespacedKey.fromString("nexoracore:navigation_item"))) {
                    player.sendMessage("§cNavigation items cannot be traded.");return;
                }
                String instanceId=draft.item.hasItemMeta()?draft.item.getItemMeta().getPersistentDataContainer().get(NamespacedKey.fromString("nexoraprogression:item_instance_id"),PersistentDataType.STRING):null;
                String operation=UUID.randomUUID().toString();
                ItemStack[] after=player.getInventory().getContents();for(int i=0;i<after.length;i++)after[i]=after[i]==null?null:after[i].clone();
                after[player.getInventory().getHeldItemSlot()]=null;
                core.applyInventoryOperation(player,network.nexora.core.api.InventoryJournal.Domain.AUCTION_LIST,operation,after);
                try {
                    var result=auctionHouse.create(player.getUniqueId(),draft.mode,draft.priceMinor,draft.durationMillis,
                            itemId==null?draft.item.getType().getKey().toString():itemId,draft.item.serializeAsBytes(),instanceId,operation,System.currentTimeMillis());
                    player.sendMessage("§e"+result.message());
                }finally{core.resolveInventoryOperation(player);}
                openAuctionHouse(player); return;
            }
            if (action.startsWith("ah-buy-execute:")) {
                UUID id = UUID.fromString(action.substring("ah-buy-execute:".length()));
                var result = auctionHouse.buyBin(player.getUniqueId(), id, UUID.randomUUID().toString(), System.currentTimeMillis());
                player.sendMessage("§e" + result.message()); if (result.outcome() == AuctionHouseService.Outcome.PURCHASED) deliverAuctionClaims(player);
                openAuctionHouse(player); return;
            }
            if (action.startsWith("ah-bid-execute:")) {
                String[] bits = action.split(":");
                var result = auctionHouse.bid(player.getUniqueId(), UUID.fromString(bits[1]), Long.parseLong(bits[2]), UUID.randomUUID().toString(), System.currentTimeMillis());
                player.sendMessage("§e" + result.message()); openAuctionHouse(player); return;
            }
            if (action.startsWith("ah-cancel-confirm:")) {
                UUID id = UUID.fromString(action.substring("ah-cancel-confirm:".length()));
                openAuctionConfirmation(player, "Cancel listing?", "The item returns to Auction House claims. Listing fee is not refunded.", "ah-cancel-execute:" + id); return;
            }
            if (action.startsWith("ah-cancel-execute:")) {
                UUID id = UUID.fromString(action.substring("ah-cancel-execute:".length()));
                var result = auctionHouse.cancel(player.getUniqueId(), id, UUID.randomUUID().toString(), System.currentTimeMillis());
                player.sendMessage("§e" + result.message()); if (result.outcome() == AuctionHouseService.Outcome.CANCELLED) deliverAuctionClaims(player);
                openMyAuctions(player, false); return;
            }
            if (action.startsWith("npc-buy:") || action.startsWith("npc-sell:")) {
                String[] p = action.split(":"); MarketProduct product = products.get(p[1].replace("%3A", ":")); int quantity = Integer.parseInt(p[2]);
                var q = npcLiquidity.quote(product.policy, System.currentTimeMillis());
                if (action.startsWith("npc-buy:")) {
                    long available = Math.min(quantity, q.askQuantity()); if (available <= 0) { player.sendMessage("§cNo System stock available."); return; }
                    var result = npcLiquidity.buyFromNpc(product.policy, player.getUniqueId(), available, q.askMinor(), UUID.randomUUID().toString(), System.currentTimeMillis());
                    if (result == NpcLiquidityService.Outcome.TRADED) deliverBazaarClaims(player); else player.sendMessage("§cNPC offer unavailable: " + result);
                } else {
                    long available = Math.min(quantity, q.bidQuantity());
                    if(available<=0){player.sendMessage("§cNo funded NPC bid.");return;}
                    tradeNpcSell(player,product,Math.toIntExact(available));
                }
                openProduct(player, product); return;
            }
        } catch (Exception ex) { getLogger().warning("Market GUI action failed: " + ex.getMessage()); player.sendMessage("§cTrade failed safely."); }
    }

    private void deliverBazaarClaims(org.bukkit.entity.Player player) {
        try {
            deliverStandardizedBazaarClaims(player);
            for (var claim : bazaar.pendingItemClaims(player.getUniqueId())) {
                MarketProduct product = products.get(claim.productId()); if (product == null) continue;
                if(!canFit(player,marketStack(product,Math.toIntExact(Math.min(64,claim.quantity()))))){player.sendMessage("§eFree inventory space, then claim again.");return;}
                var portion=bazaar.claimPortion(player.getUniqueId(),claim.claimId(),Math.min(64,claim.quantity()),System.currentTimeMillis());
                if(portion==null)continue;
                ItemStack item = marketStack(product,Math.toIntExact(portion.quantity()));
                deliverClaim(player, item, portion.claimId(), network.nexora.core.api.InventoryJournal.Domain.BAZAAR_CLAIM, () -> bazaar.markClaimed(player.getUniqueId(), portion.claimId(), System.currentTimeMillis()));
            }
        } catch (Exception ex) { getLogger().warning("Bazaar claim delivery failed: " + ex.getMessage()); player.sendMessage("§cItems remain in your Bazaar claims."); }
    }

    private void deliverStandardizedBazaarClaims(org.bukkit.entity.Player player) throws Exception {
        for (var claim : standardizedBazaar.pendingClaims(player.getUniqueId())) {
            ItemStack item = ItemStack.deserializeBytes(claim.snapshot());
            deliverClaim(player, item, claim.claimId(), network.nexora.core.api.InventoryJournal.Domain.STANDARD_CLAIM, () -> standardizedBazaar.markClaimed(player.getUniqueId(), claim.claimId(), System.currentTimeMillis()));
        }
    }

    private void deliverAuctionClaims(org.bukkit.entity.Player player) {
        try {
            for (var claim : auctionHouse.pendingClaims(player.getUniqueId())) {
                ItemStack item = ItemStack.deserializeBytes(claim.snapshot());
                deliverClaim(player, item, claim.claimId(), network.nexora.core.api.InventoryJournal.Domain.AUCTION_CLAIM, () -> auctionHouse.markClaimed(player.getUniqueId(), claim.claimId(), System.currentTimeMillis()));
            }
        } catch (Exception ex) { getLogger().warning("Auction claim delivery failed: " + ex.getMessage()); player.sendMessage("§cItems remain in your Auction House claims."); }
    }

    private void deliverClaim(org.bukkit.entity.Player player, ItemStack item, String claimId, network.nexora.core.api.InventoryJournal.Domain domain, SqlAction acknowledge) throws Exception {
        if(!canFit(player,item)){player.sendMessage("§eFree inventory space, then claim again.");return;}
        ItemStack[] after=player.getInventory().getContents();
        for(int i=0;i<after.length;i++)after[i]=after[i]==null?null:after[i].clone();
        int remaining=item.getAmount();int storageSize=player.getInventory().getStorageContents().length;
        for(int i=0;i<storageSize && remaining>0;i++){
            if(after[i]==null||!after[i].isSimilar(item))continue;
            int moved=Math.min(remaining,Math.max(0,item.getMaxStackSize()-after[i].getAmount()));after[i].setAmount(after[i].getAmount()+moved);remaining-=moved;
        }
        for(int i=0;i<storageSize && remaining>0;i++){
            if(after[i]!=null&&!after[i].getType().isAir())continue;
            int moved=Math.min(remaining,item.getMaxStackSize());after[i]=item.clone();after[i].setAmount(moved);remaining-=moved;
        }
        if(remaining!=0)throw new IllegalStateException("Claim inventory plan is inconsistent");
        core.applyInventoryOperation(player,domain,claimId,after);
        try{acknowledge.run();}finally{core.resolveInventoryOperation(player);}
        String identity=itemCatalog.identify(item);
        String label=identity==null?pretty(item.getType().getKey().getKey()):itemCatalog.entries().get(identity).name();
        player.sendMessage("§aClaimed "+item.getAmount()+" × "+label+".");
    }

    private boolean containsClaim(org.bukkit.entity.Player player, String id) {
        for (ItemStack stack : player.getInventory().getContents()) if (stack != null && stack.hasItemMeta()
                && id.equals(stack.getItemMeta().getPersistentDataContainer().get(claimKey, PersistentDataType.STRING))) return true;
        return false;
    }

    private boolean canFit(org.bukkit.entity.Player player, ItemStack item) {
        long room = 0; for (ItemStack stack : player.getInventory().getStorageContents()) {
            if (stack == null || stack.getType().isAir()) room += item.getMaxStackSize();
            else if (stack.isSimilar(item)) room += Math.max(0, item.getMaxStackSize() - stack.getAmount());
        }
        return room >= item.getAmount();
    }

    private ItemStack marketStack(MarketProduct product,int amount) {
        ItemStack item=product.id.startsWith("nexora:")?itemCatalog.create(product.id,1):new ItemStack(product.material);
        item.setAmount(amount);return item;
    }
    private boolean matchesProduct(ItemStack stack,MarketProduct product) {
        if(stack==null)return false;
        return product.id.startsWith("nexora:")?itemCatalog.matches(stack,product.id):stack.getType()==product.material&&!stack.hasItemMeta();
    }
    private ItemStack removeProduct(org.bukkit.entity.Player player,MarketProduct product,long requested,network.nexora.core.api.InventoryJournal.Domain domain,String operation) throws SQLException {
        if(requested<=0||requested>Integer.MAX_VALUE)return null;
        ItemStack[] after=player.getInventory().getContents();
        for(int i=0;i<after.length;i++)after[i]=after[i]==null?null:after[i].clone();
        long remaining=requested;
        for(int i=0;i<player.getInventory().getStorageContents().length && remaining>0;i++) {
            ItemStack stack=after[i];if(!matchesProduct(stack,product))continue;
            int take=(int)Math.min(remaining,stack.getAmount());remaining-=take;
            if(take==stack.getAmount())after[i]=null;else stack.setAmount(stack.getAmount()-take);
        }
        if(remaining!=0)return null;
        core.applyInventoryOperation(player,domain,operation,after);
        return marketStack(product,(int)requested);
    }

    private void restoreItem(org.bukkit.entity.Player player, ItemStack item) {
        var overflow = player.getInventory().addItem(item);
        for (ItemStack extra : overflow.values()) player.getWorld().dropItemNaturally(player.getLocation(), extra);
        player.saveData();
    }

    private boolean accepted(BazaarOrderService.PlaceResult result) {
        return result.outcome() == BazaarOrderService.Outcome.OPENED || result.outcome() == BazaarOrderService.Outcome.PARTIAL
                || result.outcome() == BazaarOrderService.Outcome.FILLED;
    }

    private static long parseCoins(String text) {
        return new BigDecimal(text).setScale(2, RoundingMode.UNNECESSARY).movePointRight(2).longValueExact();
    }

    private static String pretty(String key) {
        String[] parts = key.replace("minecraft:", "").replace('_', ' ').split(" ");
        StringBuilder out = new StringBuilder(); for (String part : parts) { if (!out.isEmpty()) out.append(' '); out.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1)); }
        return out.toString();
    }

    @EventHandler public void onPlayerJoin(PlayerJoinEvent event) {
        try { reconcileReservedRockets(event.getPlayer()); }
        catch (Exception ex) { getLogger().severe("Could not reconcile unique market items for " + event.getPlayer().getUniqueId() + ": " + ex.getMessage()); }
        Bukkit.getScheduler().runTaskLater(this, this::accrueOnlineInterest, 1L);
    }

    /** Recovers the crash window after durable marketplace escrow but before playerdata was saved. */
    private void reconcileReservedRockets(org.bukkit.entity.Player player) throws SQLException {
        Set<String> reserved = database.reservedMarketItemInstances();
        if (reserved.isEmpty()) return;
        int removed = 0;
        for (int slot = 0; slot < player.getInventory().getStorageContents().length; slot++) {
            ItemStack item = player.getInventory().getItem(slot);
            if (isReservedRocket(item, reserved)) { player.getInventory().setItem(slot, null); removed++; }
        }
        ItemStack[] armor = player.getInventory().getArmorContents();
        for (int slot = 0; slot < armor.length; slot++) if (isReservedRocket(armor[slot], reserved)) { armor[slot] = null; removed++; }
        if (removed > 0) player.getInventory().setArmorContents(armor);
        if (isReservedRocket(player.getInventory().getItemInOffHand(), reserved)) { player.getInventory().setItemInOffHand(null); removed++; }
        var ender = player.getEnderChest();
        for (int slot = 0; slot < ender.getSize(); slot++) if (isReservedRocket(ender.getItem(slot), reserved)) { ender.setItem(slot, null); removed++; }
        if (removed > 0) { player.saveData(); player.sendMessage("§eA Rocket already held by a Bazaar or Auction House listing was recovered into its market escrow."); }
    }

    private boolean isReservedRocket(ItemStack item, Set<String> reserved) {
        String id = rocketInstanceId(item);
        return isInfiniteRocket(item) && id != null && reserved.contains(id);
    }

    @EventHandler public void onPlayerQuit(PlayerQuitEvent event) {
        bazaarSearchPrompts.remove(event.getPlayer().getUniqueId());
        SignPrompt prompt = signPrompts.remove(event.getPlayer().getUniqueId());
        if (prompt != null) { if (prompt.cleanup != null) prompt.cleanup.cancel(); restoreTemporarySign(prompt); clearTemporarySign(event.getPlayer().getUniqueId()); }
    }

    private void spawnConfiguredNpcs() {
        var resource = getResource("npcs.yml");
        try (var input = resource == null ? null : new InputStreamReader(resource, StandardCharsets.UTF_8)) {
            if (input == null) throw new IllegalStateException("Missing npcs.yml resource");
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(input);
            for (String id : List.of("bazaar", "auction-house", "bank")) {
                String base = "npcs." + id + ".";
                World world = Bukkit.getWorld(yaml.getString(base + "world", "nexora_hub"));
                if (world == null) { getLogger().severe("Skipping " + id + " NPC: configured world is not loaded."); continue; }
                Location location = new Location(world, yaml.getDouble(base + "x"), yaml.getDouble(base + "y"),
                        yaml.getDouble(base + "z"), (float) yaml.getDouble(base + "yaw"), (float) yaml.getDouble(base + "pitch"));
                if (!safeNpcLocation(location)) {
                    getLogger().severe("Skipping " + id + " NPC: exact requested position is not a safe free-standing spot; no Hub blocks were changed.");
                    continue;
                }
                Entity existing = world.getEntities().stream().filter(entity -> id.equals(
                        entity.getPersistentDataContainer().get(npcKey, PersistentDataType.STRING))).findFirst().orElse(null);
                if (existing instanceof Villager villager) { villager.teleport(location); configureNpc(villager, id, yaml.getString(base + "name", id)); }
                else {
                    Villager villager = (Villager) world.spawnEntity(location, EntityType.VILLAGER);
                    configureNpc(villager, id, yaml.getString(base + "name", id));
                }
                getLogger().info("Configured " + id + " NPC at " + world.getName() + " " + location.getX() + "," + location.getY() + "," + location.getZ());
            }
        } catch (Exception ex) { getLogger().severe("Could not load requested Hub NPC locations: " + ex.getMessage()); }
    }

    private boolean safeNpcLocation(Location location) {
        if (!location.getBlock().isPassable() || !location.clone().add(0, 1, 0).getBlock().isPassable()) return false;
        return location.clone().subtract(0, 1, 0).getBlock().getType().isSolid();
    }

    private void configureNpc(Villager villager, String id, String name) {
        villager.getPersistentDataContainer().set(npcKey, PersistentDataType.STRING, id);
        villager.customName(legacy(name));
        villager.setCustomNameVisible(true);
        villager.setAI(false);
        villager.setInvulnerable(true);
        villager.setSilent(true);
        villager.setCollidable(false);
        villager.setPersistent(true);
        villager.setRemoveWhenFarAway(false);
        villager.setProfession(Villager.Profession.LIBRARIAN);
    }

    @EventHandler public void onNpcClick(PlayerInteractEntityEvent event) {
        String npc = event.getRightClicked().getPersistentDataContainer().get(npcKey, PersistentDataType.STRING);
        if (npc == null) return;
        event.setCancelled(true);
        if (npc.equals("bank")) openBank(event.getPlayer());
        else if (npc.equals("bazaar")) openBazaar(event.getPlayer());
        else if (npc.equals("auction-house")) openAuctionHouse(event.getPlayer());
    }

    @EventHandler public void onNpcDamage(EntityDamageEvent event) {
        if (event.getEntity().getPersistentDataContainer().has(npcKey, PersistentDataType.STRING)) event.setCancelled(true);
    }

    private void openBank(org.bukkit.entity.Player player) {
        UUID id = player.getUniqueId();
        if (!busyPlayers.add(id)) return;
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            long interest = 0;
            String failure = null;
            try { interest = database.accrueDailyInterest(id, System.currentTimeMillis()); }
            catch (SQLException ex) { failure = ex.getMessage(); }
            long earned = interest, purse = -1, bank = -1;
            if (failure == null) {
                try { purse = database.balanceMinor(id); bank = database.bankBalanceMinor(id); }
                catch (SQLException ex) { failure = ex.getMessage(); }
            }
            long currentPurse = purse, currentBank = bank;
            String error = failure;
            Bukkit.getScheduler().runTask(this, () -> {
                busyPlayers.remove(id);
                if (!player.isOnline()) return;
                if (error != null) player.sendMessage("§cBank could not be loaded: " + error);
                else if (earned > 0) player.sendMessage("§aYour bank earned " + coins(earned) + " Coins in 24-hour interest.");
                if (error == null) showBank(player, currentPurse, currentBank);
            });
        });
    }

    private void showBank(org.bukkit.entity.Player player, long purse, long bank) {
        BankHolder holder = new BankHolder();
        Inventory inventory = Bukkit.createInventory(holder, 27, Component.text("NEXORA | BANK"));
        holder.inventory = inventory;
        inventory.setItem(4, button(Material.GOLD_INGOT, "§6Purse: " + coins(purse), (String) null));
        inventory.setItem(13, button(Material.ENDER_CHEST, "§eBank: " + coins(bank), List.of("§72% interest after each full 24 hours")));
        inventory.setItem(10, button(Material.LIME_DYE, "§aDeposit 10%", "deposit:10"));
        inventory.setItem(12, button(Material.LIME_DYE, "§aDeposit 50%", "deposit:50"));
        inventory.setItem(14, button(Material.LIME_DYE, "§aDeposit all", "deposit:100"));
        inventory.setItem(16, button(Material.RED_DYE, "§cWithdraw 10%", "withdraw:10"));
        inventory.setItem(22, button(Material.RED_DYE, "§cWithdraw 50%", "withdraw:50"));
        inventory.setItem(25, button(Material.RED_DYE, "§cWithdraw all", "withdraw:100"));
        player.openInventory(inventory);
    }

    @EventHandler public void onBankClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof BankHolder)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof org.bukkit.entity.Player player) || event.getRawSlot() >= 27) return;
        ItemStack item = event.getCurrentItem();
        if (item == null || !item.hasItemMeta()) return;
        String action = item.getItemMeta().getPersistentDataContainer().get(actionKey, PersistentDataType.STRING);
        if (action == null) return;
        String[] parts = action.split(":", 2);
        boolean deposit = parts[0].equals("deposit");
        if (!deposit && !parts[0].equals("withdraw")) return;
        int percent = Integer.parseInt(parts[1]);
        UUID id = player.getUniqueId();
        if (!busyPlayers.add(id)) return;
        player.closeInventory();
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            String error = null;
            MarketplaceDatabase.BankResult result = MarketplaceDatabase.BankResult.UNKNOWN_PROFILE;
            try {
                long available = deposit ? database.balanceMinor(id) : database.bankBalanceMinor(id);
                long amount = percent == 100 ? available
                        : (available / 100) * percent + (available % 100) * percent / 100;
                if (amount > 0) {
                    String operationId = UUID.randomUUID().toString();
                    result = deposit ? database.deposit(id, amount, operationId, System.currentTimeMillis())
                            : database.withdraw(id, amount, operationId, System.currentTimeMillis());
                }
            } catch (SQLException | ArithmeticException ex) { error = ex.getMessage(); }
            MarketplaceDatabase.BankResult finalResult = result;
            String finalError = error;
            Bukkit.getScheduler().runTask(this, () -> {
                busyPlayers.remove(id);
                if (!player.isOnline()) return;
                if (finalError != null) player.sendMessage("§cBank operation failed: " + finalError);
                else if (finalResult == MarketplaceDatabase.BankResult.APPLIED)
                    player.sendMessage("§a" + (deposit ? "Deposited Coins." : "Withdrew Coins."));
                else if (finalResult == MarketplaceDatabase.BankResult.ALREADY_APPLIED)
                    player.sendMessage("§eThat bank transaction was already processed.");
                else if (finalResult == MarketplaceDatabase.BankResult.INSUFFICIENT_PURSE)
                    player.sendMessage("§cYour purse changed; reopen the bank and try again.");
                else if (finalResult == MarketplaceDatabase.BankResult.INSUFFICIENT_BANK)
                    player.sendMessage("§cYour bank balance changed; reopen the bank and try again.");
                else if (finalResult == MarketplaceDatabase.BankResult.BALANCE_LIMIT)
                    player.sendMessage("§cYour purse is at its maximum balance.");
                else player.sendMessage("§cNo Coins were moved. Amount must be at least 0.01 Coin.");
                openBank(player);
            });
        });
    }

    @EventHandler public void onBankDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof BankHolder) event.setCancelled(true);
    }

    @EventHandler public void onMarketClick(InventoryClickEvent event) {
        Object holder = event.getView().getTopInventory().getHolder();
        if (!(holder instanceof BazaarHolder) && !(holder instanceof ProductHolder) && !(holder instanceof AuctionHolder)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof org.bukkit.entity.Player player) || event.getRawSlot() < 0
                || event.getRawSlot() >= event.getView().getTopInventory().getSize()) return;
        ItemStack item = event.getCurrentItem(); if (item == null || !item.hasItemMeta()) return;
        String action = item.getItemMeta().getPersistentDataContainer().get(actionKey, PersistentDataType.STRING);
        if (action != null) handleMarketClick(player, action);
    }

    @EventHandler public void onMarketDrag(InventoryDragEvent event) {
        Object holder = event.getView().getTopInventory().getHolder();
        if (holder instanceof BazaarHolder || holder instanceof ProductHolder || holder instanceof AuctionHolder) event.setCancelled(true);
    }

    private ItemStack button(Material material, String title, String action) { return button(material, title, action == null ? null : List.of("§7Click to transfer"), action); }
    private ItemStack button(Material material, String title, List<String> lore) { return button(material, title, lore, null); }
    private ItemStack button(Material material, String title, List<String> lore, String action) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(legacy(title));
        if (lore != null) meta.lore(lore.stream().map(NexoraTradingPlugin::legacy).toList());
        if (action != null) meta.getPersistentDataContainer().set(actionKey, PersistentDataType.STRING, action);
        item.setItemMeta(meta);
        return item;
    }

    private static String coins(long minor) {
        return BigDecimal.valueOf(minor, 2).setScale(2, RoundingMode.DOWN).toPlainString();
    }

    private static Component legacy(String text) { return LegacyComponentSerializer.legacySection().deserialize(text); }

    private static final class BankHolder implements InventoryHolder {
        private Inventory inventory;
        @Override public Inventory getInventory() { return inventory; }
    }

    private static final class BazaarHolder implements InventoryHolder {
        private Inventory inventory;
        @Override public Inventory getInventory() { return inventory; }
    }
    private static final class ProductHolder implements InventoryHolder {
        private Inventory inventory;
        @Override public Inventory getInventory() { return inventory; }
    }
    private static final class AuctionHolder implements InventoryHolder {
        private Inventory inventory;
        @Override public Inventory getInventory() { return inventory; }
    }
    private static final class SignPrompt {
        private final Location location; private final String token; private org.bukkit.scheduler.BukkitTask cleanup;
        private SignPrompt(Location location, String token) { this.location = location.clone(); this.token = token; }
        private boolean matches(Location other) { return location.getWorld().equals(other.getWorld()) && location.getBlockX() == other.getBlockX() && location.getBlockY() == other.getBlockY() && location.getBlockZ() == other.getBlockZ(); }
    }
    private record BazaarDraft(MarketProduct product, OrderSide side, int quantity, long price) {}
    private record StandardBazaarDraft(MarketProduct product, OrderSide side, int quantity, long price, boolean instant) {
        private StandardBazaarDraft(MarketProduct product, OrderSide side, int quantity, long price) { this(product, side, quantity, price, false); }
    }
    private record BazaarBrowseState(String category, String search, int page) {}
    private record ListingDraft(AuctionHouseService.Mode mode, long durationMillis, ItemStack item, long priceMinor) {}
    private record MarketProduct(String id, Material material, String category, NpcLiquidityService.Policy policy, boolean standardized) {
        private MarketProduct(String id, Material material, String category, NpcLiquidityService.Policy policy) { this(id, material, category, policy, false); }
    }
    @FunctionalInterface private interface SqlAction { void run() throws SQLException; }
}
