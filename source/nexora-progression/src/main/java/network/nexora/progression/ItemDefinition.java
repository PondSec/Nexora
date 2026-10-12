package network.nexora.progression;

public record ItemDefinition(String id, String materialKey, String rarity, String description,
                             String effectHandler, TradingClassification tradingClassification, ItemProperties properties) {
    public ItemDefinition(String id, String materialKey, String rarity, String description,
                          String effectHandler, TradingClassification tradingClassification) {
        this(id, materialKey, rarity, description, effectHandler, tradingClassification, ItemProperties.DEFAULT);
    }
    public ItemDefinition(String id, String materialKey, String rarity, String description,
                          String effectHandler, boolean bazaarAllowed, boolean auctionAllowed) {
        this(id, materialKey, rarity, description, effectHandler,
                !bazaarAllowed && !auctionAllowed ? TradingClassification.NOT_TRADABLE
                        : bazaarAllowed && auctionAllowed ? TradingClassification.BOTH
                        : bazaarAllowed ? TradingClassification.BAZAAR_COMMODITY : TradingClassification.AUCTION_ONLY);
    }
    public ItemDefinition {
        if (properties == null) throw new IllegalArgumentException("Item properties are required");
        if (blank(id) || blank(materialKey) || blank(rarity) || blank(description))
            throw new IllegalArgumentException("Item identity, material, rarity and description are required");
        if (effectHandler == null || effectHandler.isBlank()) throw new IllegalArgumentException("Every custom item needs an implemented effect handler");
        if (tradingClassification == null) throw new IllegalArgumentException("Every item needs an explicit trading classification");
    }
    public boolean bazaarAllowed() {
        return tradingClassification == TradingClassification.BAZAAR_COMMODITY
                || tradingClassification == TradingClassification.BAZAAR_STANDARDIZED_ITEM
                || tradingClassification == TradingClassification.BOTH;
    }
    public boolean auctionAllowed() {
        return tradingClassification == TradingClassification.AUCTION_ONLY
                || tradingClassification == TradingClassification.BOTH
                || tradingClassification == TradingClassification.BAZAAR_STANDARDIZED_ITEM;
    }
    private static boolean blank(String s) { return s == null || s.isBlank(); }
}
