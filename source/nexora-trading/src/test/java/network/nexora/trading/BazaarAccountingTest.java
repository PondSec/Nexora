package network.nexora.trading;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BazaarAccountingTest {
    @Test void restingAskPriceImprovementReturnsToBuyerAndSellFeeIsChargedOnce() {
        assertEquals(11_200, BazaarAccounting.buyOrderReservation(140, 80));
        assertEquals(56, BazaarAccounting.buyOrderCreationFee(140, 80));
        var settlement = BazaarAccounting.settleFill(140, 125, 80, true);
        assertEquals(11_200, settlement.buyerEscrowConsumedMinor());
        assertEquals(10_000, settlement.executionCostMinor());
        assertEquals(1_200, settlement.buyerImprovementRefundMinor());
        assertEquals(100, settlement.sellerFeeMinor());
        assertEquals(9_900, settlement.sellerNetMinor());
    }

    @Test void instantBuyHasNoBuyCreationEscrowOrFee() {
        var settlement = BazaarAccounting.settleFill(125, 125, 2, false);
        assertEquals(0, settlement.buyerEscrowConsumedMinor());
        assertEquals(0, settlement.buyerImprovementRefundMinor());
        assertEquals(250, settlement.executionCostMinor());
        assertEquals(3, settlement.sellerFeeMinor());
        assertEquals(247, settlement.sellerNetMinor());
    }

    @Test void rejectsExecutionAboveLimit() {
        assertThrows(IllegalArgumentException.class,
                () -> BazaarAccounting.settleFill(124, 125, 1, true));
    }
}
