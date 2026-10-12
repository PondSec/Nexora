package network.nexora.trading;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MoneyMathTest {
    @Test void computesTotalsAndRoundsFeesExactly() {
        assertEquals(22_400, MoneyMath.total(140, 160));
        assertEquals(112, MoneyMath.fee(22_400, 50));
        assertEquals(1, MoneyMath.fee(1, 1));
        assertThrows(ArithmeticException.class, () -> MoneyMath.total(Long.MAX_VALUE, 2));
    }
}
