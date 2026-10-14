package network.nexora.farm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FarmGrowthPolicyTest {
    @Test void doublesEachNaturalCropAgeIncrement() {
        assertEquals(4, FarmGrowthPolicy.acceleratedAge(2, 3, 7, 2));
        assertEquals(7, FarmGrowthPolicy.acceleratedAge(6, 7, 7, 2));
    }

    @Test void supportsConfiguredMultipliersAndRejectsInvalidGrowth() {
        assertEquals(6, FarmGrowthPolicy.acceleratedAge(1, 2, 7, 5));
        assertEquals(2, FarmGrowthPolicy.acceleratedAge(1, 2, 7, 1));
        assertThrows(IllegalArgumentException.class, () -> FarmGrowthPolicy.acceleratedAge(3, 2, 7, 2));
        assertThrows(IllegalArgumentException.class, () -> FarmGrowthPolicy.acceleratedAge(2, 3, 7, 0));
    }
}
