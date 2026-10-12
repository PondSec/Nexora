package network.nexora.trading;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class BazaarMatchEngineTest {
    private static UUID id(long n) { return new UUID(0, n); }
    private static BazaarOrder order(long id, long profile, OrderSide side, long price, long quantity, long created) {
        return new BazaarOrder(BazaarMatchEngineTest.id(id), BazaarMatchEngineTest.id(profile), "minecraft:diamond",
                side, price, quantity, quantity, created);
    }

    @Test void matchesBestPriceThenOldestAndFillsPartialRemainder() {
        var engine = new BazaarMatchEngine();
        engine.restore(List.of(order(1, 11, OrderSide.SELL, 140, 4, 1),
                order(2, 12, OrderSide.SELL, 125, 3, 3), order(3, 13, OrderSide.SELL, 125, 4, 2)));
        MatchResult result = engine.place(order(9, 20, OrderSide.BUY, 150, 12, 4), false);
        assertTrue(result.accepted());
        assertEquals(11, result.filledQuantity());
        assertEquals(1, result.restingOrder().remainingQuantity());
        assertEquals(List.of(id(3), id(2), id(1)), result.fills().stream().map(BazaarFill::sellOrderId).toList());
        assertEquals(1_435, result.grossMinor());
    }

    @Test void immediateFullFillFailureDoesNotMutateBook() {
        var engine = new BazaarMatchEngine();
        var resting = order(1, 11, OrderSide.SELL, 125, 3, 1);
        engine.restore(List.of(resting));
        MatchResult failed = engine.place(order(2, 20, OrderSide.BUY, 125, 4, 2), true);
        assertFalse(failed.accepted());
        assertEquals(3, engine.snapshot().getFirst().remainingQuantity());
    }

    @Test void selfTradeNeverFills() {
        var engine = new BazaarMatchEngine();
        engine.restore(List.of(order(1, 11, OrderSide.SELL, 100, 2, 1)));
        MatchResult result = engine.place(order(2, 11, OrderSide.BUY, 100, 2, 2), true);
        assertFalse(result.accepted());
        assertEquals(2, engine.snapshot().getFirst().remainingQuantity());
    }
}
