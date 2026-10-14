package network.nexora.trading;

import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class AuctionStateMachineTest {
    private final UUID seller = new UUID(0, 1);
    private final UUID first = new UUID(0, 2);
    private final UUID next = new UUID(0, 3);
    private AuctionState auction(long end) {
        return new AuctionState(new UUID(0, 9), seller, AuctionState.Mode.BID,
                AuctionState.Status.ACTIVE, 10_000, 0, 0, null, end, 0);
    }

    @Test void minimumBidAndAntiSnipingRefundPreviousEscrow() {
        AuctionState state = auction(200_000);
        var firstBid = AuctionStateMachine.bid(state, first, 10_250, 100_000, 100, 120_000, 120_000);
        assertTrue(firstBid.accepted());
        assertEquals(220_000, firstBid.updated().endsAtMillis());
        assertTrue(firstBid.extended());
        var secondBid = AuctionStateMachine.bid(firstBid.updated(), next, 10_507, 110_000, 100, 120_000, 120_000);
        assertTrue(secondBid.accepted());
        assertEquals(first, secondBid.refundedProfile());
        assertEquals(10_250, secondBid.refundMinor());
        assertEquals(10_507, secondBid.reserveMinor());
        var sameBidderRaise = AuctionStateMachine.bid(secondBid.updated(), next, 11_000,
                111_000, 100, 120_000, 120_000);
        assertTrue(sameBidderRaise.accepted());
        assertNull(sameBidderRaise.refundedProfile());
        assertEquals(493, sameBidderRaise.reserveMinor());
    }

    @Test void sellerCannotBidAndOldAuctionRejectsNewBid() {
        assertFalse(AuctionStateMachine.bid(auction(200_000), seller, 20_000, 1, 100, 0, 0).accepted());
        assertFalse(AuctionStateMachine.bid(auction(10), first, 20_000, 10, 100, 0, 0).accepted());
    }

    @Test void binPurchaseAndExpiryAreSingleStateTransitions() {
        AuctionState bin = new AuctionState(new UUID(0, 4), seller, AuctionState.Mode.BIN,
                AuctionState.Status.ACTIVE, 15_000, 15_000, 0, null, 1_000, 0);
        assertEquals(AuctionState.Status.SOLD, AuctionStateMachine.buyNow(bin, first, 999).status());
        assertNull(AuctionStateMachine.buyNow(bin, first, 1_000));
        assertEquals(AuctionState.Status.EXPIRED, AuctionStateMachine.expire(auction(10), 10).status());
    }
}
