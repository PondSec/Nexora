package network.nexora.farm;

import org.junit.jupiter.api.Test;
import java.util.HashSet;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class FarmPopulationMathTest {
    @Test void spreadCoordinatesStayInsideConfiguredFarmBounds() {
        Set<String> points = new HashSet<>();
        for (int i = 0; i < 160; i++) {
            int x = FarmPopulationMath.spreadCoordinate(i, 2, -144, -16);
            int z = FarmPopulationMath.spreadCoordinate(i, 3, -96, 128);
            assertTrue(x >= -144 && x <= -16);
            assertTrue(z >= -96 && z <= 128);
            points.add(x + ":" + z);
        }
        assertTrue(points.size() >= 150, "sequence should distribute animals across the farm");
    }

    @Test void replenishmentNeverExceedsTarget() {
        assertEquals(32, FarmPopulationMath.missingAnimals(32, 0));
        assertEquals(1, FarmPopulationMath.missingAnimals(32, 31));
        assertEquals(0, FarmPopulationMath.missingAnimals(32, 32));
        assertEquals(0, FarmPopulationMath.missingAnimals(32, 33));
    }

    @Test void reducingTargetRemovesOnlyManagedSurplus() {
        assertEquals(1, FarmPopulationMath.managedSurplus(1, 2, 1));
        assertEquals(0, FarmPopulationMath.managedSurplus(1, 2, 0));
        assertEquals(0, FarmPopulationMath.managedSurplus(1, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> FarmPopulationMath.managedSurplus(1, 2, -1));
    }

    @Test void spreadCoordinatesSupportSingleBlockEdgeChunks() {
        for (int i = 0; i < 10; i++) {
            assertEquals(-16, FarmPopulationMath.spreadCoordinate(i, 2, -16, -16));
            assertEquals(128, FarmPopulationMath.spreadCoordinate(i, 3, 128, 128));
        }
        assertThrows(IllegalArgumentException.class, () -> FarmPopulationMath.spreadCoordinate(0, 2, 9, 8));
    }
}
