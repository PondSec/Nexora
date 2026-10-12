package network.nexora.trading;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class NpcMarketMakerTest {
    private final NpcMarketMaker maker = new NpcMarketMaker();
    private final NpcMarketMaker.ProductPolicy policy = new NpcMarketMaker.ProductPolicy(
            "ENCHANTED_IRON", 10_000, 1_000, 20, 10, 150_000, 5, 20_000);

    @Test void quotesAreFiniteBackedAndHaveNonCrossingSpread() {
        var quotes = maker.quotes(policy, new NpcMarketMaker.Account(4, 50_000, 0, 0), 0, 0, 2_500);
        assertEquals(2, quotes.size());
        var bid = quotes.stream().filter(q -> q.direction() == NpcMarketMaker.Direction.BUY_FROM_PLAYER).findFirst().orElseThrow();
        var ask = quotes.stream().filter(q -> q.direction() == NpcMarketMaker.Direction.SELL_TO_PLAYER).findFirst().orElseThrow();
        assertEquals(9_500, bid.unitPriceMinor());
        assertEquals(10_500, ask.unitPriceMinor());
        assertEquals(5, bid.quantity());
        assertEquals(4, ask.quantity());
        assertTrue(quotes.stream().allMatch(NpcMarketMaker.Quote::systemOffer));
    }

    @Test void npcQuotesShrinkAsRealPlayerLiquidityGrows() {
        var account = new NpcMarketMaker.Account(10, 150_000, 0, 0);
        var solo = maker.quotes(policy, account, 0, 0, 2_500);
        var active = maker.quotes(policy, account, 80, 100, 2_500);
        assertFalse(active.isEmpty());
        assertTrue(active.stream().mapToLong(NpcMarketMaker.Quote::quantity).sum()
                < solo.stream().mapToLong(NpcMarketMaker.Quote::quantity).sum());
        assertTrue(maker.quotes(policy, account, 75, 100, 2_500).isEmpty());
    }

    @Test void restockHonorsExplicitHourlyInventoryAndCoinCaps() {
        var replenished = maker.replenish(policy, new NpcMarketMaker.Account(0, 0, 0, 0), 100, 100_000);
        assertEquals(5, replenished.inventory());
        assertEquals(20_000, replenished.coinsMinor());
        assertEquals(5, replenished.replenishedItemsThisHour());
        assertEquals(20_000, replenished.replenishedCoinsThisHour());
        var again = maker.replenish(policy, replenished, 100, 100_000);
        assertEquals(replenished, again);
    }

    @Test void alphaLiquidityScenariosUseFiniteInventoryAndBudgets() {
        for (int players : List.of(1, 5, 20, 100)) {
            var account = new NpcMarketMaker.Account(10, 150_000, 0, 0);
            var quotes = maker.quotes(policy, account, players * 100L, players * 100L, 2_500);
            assertTrue(quotes.size() <= 2);
            assertTrue(quotes.stream().allMatch(q -> q.quantity() <= 20));
            assertTrue(quotes.stream().filter(q -> q.direction() == NpcMarketMaker.Direction.BUY_FROM_PLAYER)
                    .allMatch(q -> Math.multiplyExact(q.unitPriceMinor(), q.quantity()) <= account.coinsMinor()));
            assertTrue(quotes.stream().filter(q -> q.direction() == NpcMarketMaker.Direction.SELL_TO_PLAYER)
                    .allMatch(q -> q.quantity() <= account.inventory()));
        }
    }
}
