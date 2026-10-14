package network.nexora.trading;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AuctionAccountingTest {
    @Test void listingAndSaleTaxesAreExplicitAndIntegerRounded() {
        assertEquals(150, AuctionAccounting.listingFee(15_000));
        assertEquals(250, AuctionAccounting.saleTax(25_000));
        assertEquals(24_750, AuctionAccounting.sellerProceeds(25_000));
    }
}
