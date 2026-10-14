package network.nexora.trading;

import java.util.Objects;
import java.util.List;

/** Bounded system liquidity policy. Quotes are offers, never trades or synthetic price history. */
public final class NpcMarketMaker {
    public enum Direction { BUY_FROM_PLAYER, SELL_TO_PLAYER }

    public record ProductPolicy(String productId, long referencePriceMinor, int spreadBps,
                                long maxBidInventory, long maxOfferInventory,
                                long maxCoinBudgetMinor, int replenishPerHour,
                                long replenishCoinBudgetMinor) {
        public ProductPolicy {
            Objects.requireNonNull(productId);
            if (productId.isBlank() || referencePriceMinor <= 0 || spreadBps < 100 || spreadBps > 5_000
                    || maxBidInventory < 0 || maxOfferInventory < 0 || maxCoinBudgetMinor < 0
                    || replenishPerHour < 0 || replenishCoinBudgetMinor < 0)
                throw new IllegalArgumentException("Invalid NPC market maker policy");
        }
    }

    public record Account(long inventory, long coinsMinor, long replenishedItemsThisHour,
                          long replenishedCoinsThisHour) {
        public Account {
            if (inventory < 0 || coinsMinor < 0 || replenishedItemsThisHour < 0 || replenishedCoinsThisHour < 0)
                throw new IllegalArgumentException("Negative market maker balance");
        }
    }

    public record Quote(String productId, Direction direction, long unitPriceMinor,
                        long quantity, boolean systemOffer) {}

    /** Returns a bid and ask derived from configured seed values and real, current account limits. */
    public List<Quote> quotes(ProductPolicy policy, Account account, long playerMarketVolumeLastHour,
                              long totalMarketVolumeLastHour, long maxNpcShareBps) {
        Objects.requireNonNull(policy); Objects.requireNonNull(account);
        if (maxNpcShareBps < 0 || maxNpcShareBps > 10_000 || playerMarketVolumeLastHour < 0
                || totalMarketVolumeLastHour < 0 || playerMarketVolumeLastHour > totalMarketVolumeLastHour)
            throw new IllegalArgumentException("Invalid liquidity inputs");
        long npcShare = totalMarketVolumeLastHour == 0 ? 0
                : java.math.BigInteger.valueOf(totalMarketVolumeLastHour - playerMarketVolumeLastHour)
                .multiply(java.math.BigInteger.valueOf(10_000))
                .divide(java.math.BigInteger.valueOf(totalMarketVolumeLastHour)).longValueExact();
        if (npcShare >= maxNpcShareBps) return List.of();
        long halfSpread = Math.max(1, policy.spreadBps() / 2L);
        long bidPrice = Math.max(1, Math.multiplyExact(policy.referencePriceMinor(), 10_000 - halfSpread) / 10_000);
        long askPrice = Math.max(bidPrice + 1,
                Math.multiplyExact(policy.referencePriceMinor(), 10_000 + halfSpread) / 10_000);
        long bidByBudget = Math.min(account.coinsMinor(), policy.maxCoinBudgetMinor()) / bidPrice;
        long participationBps = maxNpcShareBps == 0 ? 0
                : Math.max(0, maxNpcShareBps - npcShare) * 10_000 / maxNpcShareBps;
        long bidQuantity = Math.min(policy.maxBidInventory(), bidByBudget);
        long askQuantity = Math.min(policy.maxOfferInventory(), account.inventory());
        bidQuantity = scaled(bidQuantity, participationBps);
        askQuantity = scaled(askQuantity, participationBps);
        java.util.ArrayList<Quote> result = new java.util.ArrayList<>(2);
        if (askQuantity > 0) result.add(new Quote(policy.productId(), Direction.SELL_TO_PLAYER, askPrice, askQuantity, true));
        if (bidQuantity > 0) result.add(new Quote(policy.productId(), Direction.BUY_FROM_PLAYER, bidPrice, bidQuantity, true));
        return List.copyOf(result);
    }

    /** Explicit replenishment cap; it creates inventory only from a configured, auditable source. */
    public Account replenish(ProductPolicy policy, Account account, long requestedItems, long requestedCoins) {
        Objects.requireNonNull(policy); Objects.requireNonNull(account);
        if (requestedItems < 0 || requestedCoins < 0) throw new IllegalArgumentException("Negative replenishment");
        long remainingHourly = Math.max(0, policy.replenishPerHour() - account.replenishedItemsThisHour());
        long addItems = Math.min(requestedItems, Math.min(remainingHourly,
                policy.maxOfferInventory() - Math.min(account.inventory(), policy.maxOfferInventory())));
        long remainingBudget = Math.max(0, policy.replenishCoinBudgetMinor() - account.replenishedCoinsThisHour());
        long addCoins = Math.min(requestedCoins, Math.min(remainingBudget,
                policy.maxCoinBudgetMinor() - Math.min(account.coinsMinor(), policy.maxCoinBudgetMinor())));
        return new Account(Math.addExact(account.inventory(), addItems), Math.addExact(account.coinsMinor(), addCoins),
                Math.addExact(account.replenishedItemsThisHour(), addItems),
                Math.addExact(account.replenishedCoinsThisHour(), addCoins));
    }

    private static long scaled(long amount, long basisPoints) {
        return Math.addExact((amount / 10_000) * basisPoints, (amount % 10_000) * basisPoints / 10_000);
    }
}
