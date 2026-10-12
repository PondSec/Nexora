package network.nexora.trading;

import java.util.*;

/** In-memory price-time matcher. Callers must persist the returned book changes in one DB transaction. */
public final class BazaarMatchEngine {
    private final Map<UUID, BazaarOrder> orders = new HashMap<>();

    public synchronized void restore(Collection<BazaarOrder> activeOrders) {
        orders.clear();
        for (BazaarOrder order : activeOrders) {
            if (orders.putIfAbsent(order.id(), order) != null) throw new IllegalArgumentException("Duplicate order id");
        }
    }

    public synchronized List<BazaarOrder> snapshot() {
        return orders.values().stream().sorted(Comparator.comparing(BazaarOrder::createdAtMillis)
                .thenComparing(o -> o.id().toString())).toList();
    }

    public synchronized MatchResult place(BazaarOrder incoming, boolean requireFullFill) {
        if (orders.containsKey(incoming.id())) return rejected("Duplicate order ID");
        List<BazaarOrder> opposing = orders.values().stream()
                .filter(o -> o.productId().equals(incoming.productId()) && o.side() != incoming.side())
                .filter(o -> !o.profileId().equals(incoming.profileId()))
                .filter(o -> crosses(incoming, o))
                .sorted(priority(incoming.side() == OrderSide.BUY ? OrderSide.SELL : OrderSide.BUY))
                .toList();

        long stillNeeded = incoming.remainingQuantity();
        long grossTotal = 0;
        List<BazaarFill> fills = new ArrayList<>();
        Map<UUID, Long> consumed = new HashMap<>();
        for (BazaarOrder resting : opposing) {
            if (stillNeeded == 0) break;
            long available = resting.remainingQuantity();
            long quantity = Math.min(stillNeeded, available);
            long gross = Math.multiplyExact(quantity, resting.unitPriceMinor());
            grossTotal = Math.addExact(grossTotal, gross);
            BazaarOrder buy = incoming.side() == OrderSide.BUY ? incoming : resting;
            BazaarOrder sell = incoming.side() == OrderSide.SELL ? incoming : resting;
            fills.add(new BazaarFill(buy.id(), sell.id(), buy.profileId(), sell.profileId(),
                    incoming.productId(), quantity, resting.unitPriceMinor(), gross));
            consumed.merge(resting.id(), quantity, Math::addExact);
            stillNeeded -= quantity;
        }

        if (requireFullFill && stillNeeded != 0) return rejected("Insufficient opposing liquidity");

        // Apply only after all arithmetic and full-fill checks succeed.
        for (Map.Entry<UUID, Long> fill : consumed.entrySet()) {
            BazaarOrder old = orders.get(fill.getKey());
            long next = old.remainingQuantity() - fill.getValue();
            if (next == 0) orders.remove(old.id());
            else orders.put(old.id(), old.withRemaining(next));
        }
        long filled = incoming.remainingQuantity() - stillNeeded;
        BazaarOrder remainder = stillNeeded == 0 ? null : incoming.withRemaining(stillNeeded);
        if (!requireFullFill && remainder != null) orders.put(remainder.id(), remainder);
        return new MatchResult(true, "", remainder, fills, filled, grossTotal);
    }

    private static boolean crosses(BazaarOrder incoming, BazaarOrder resting) {
        return incoming.side() == OrderSide.BUY
                ? incoming.unitPriceMinor() >= resting.unitPriceMinor()
                : incoming.unitPriceMinor() <= resting.unitPriceMinor();
    }

    private static Comparator<BazaarOrder> priority(OrderSide side) {
        Comparator<BazaarOrder> price = Comparator.comparingLong(BazaarOrder::unitPriceMinor);
        if (side == OrderSide.BUY) price = price.reversed();
        return price.thenComparingLong(BazaarOrder::createdAtMillis).thenComparing(o -> o.id().toString());
    }

    private static MatchResult rejected(String reason) { return new MatchResult(false, reason, null, List.of(), 0, 0); }
}
