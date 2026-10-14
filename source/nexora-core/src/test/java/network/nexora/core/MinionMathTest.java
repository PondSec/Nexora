package network.nexora.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MinionMathTest {
    @Test void productionUsesWholeIntervalsAndPreservesRemainder() {
        var result = MinionMath.advance(4, 10_000, 35_500, 10_000, 86_400_000, 20);
        assertEquals(6, result.storage());
        assertEquals(30_000, result.lastAction());
    }

    @Test void offlineTimeIsCappedAndRepeatedReadsDoNotReplayTheCappedWindow() {
        long now = 200_000_000;
        var first = MinionMath.advance(0, 0, now, 10_000, 60_000, 100);
        assertEquals(6, first.storage());
        var second = MinionMath.advance(first.storage(), first.lastAction(), now, 10_000, 60_000, 100);
        assertEquals(first.storage(), second.storage());
    }

    @Test void fullStorageStopsProductionWithoutOverflow() {
        var result = MinionMath.advance(Integer.MAX_VALUE, 10, 1_000_000, 1_000, 60_000, 64);
        assertEquals(64, result.storage());
        assertEquals(1_000_000, result.lastAction());
    }
}
