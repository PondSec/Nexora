package network.nexora.trading;

import java.math.BigInteger;
import java.util.UUID;

/** Pure lifecycle rules; callers persist state, wallet escrow and refunds in one transaction. */
public final class AuctionStateMachine {
    private AuctionStateMachine() {}

    public static AuctionBidResult bid(AuctionState auction, UUID bidder, long amountMinor,
                                       long nowMillis, long absoluteStepMinor,
                                       long antiSnipeWindowMillis, long extensionMillis) {
        if (auction.mode() != AuctionState.Mode.BID || auction.status() != AuctionState.Status.ACTIVE)
            return rejected("Auction is not accepting bids");
        if (nowMillis >= auction.endsAtMillis()) return rejected("Auction has ended");
        if (bidder == null || bidder.equals(auction.sellerId())) return rejected("Seller cannot bid on own listing");
        if (absoluteStepMinor <= 0 || antiSnipeWindowMillis < 0 || extensionMillis < 0)
            return rejected("Invalid auction settings");

        long baseline = Math.max(auction.currentBidMinor(), auction.startPriceMinor());
        long percentageStep = BigInteger.valueOf(baseline).multiply(BigInteger.valueOf(25))
                .add(BigInteger.valueOf(999)).divide(BigInteger.valueOf(1_000)).longValueExact();
        long step = Math.max(absoluteStepMinor, percentageStep);
        long minimum = Math.addExact(baseline, step);
        if (amountMinor < minimum) return rejected("Bid must be at least " + minimum + " minor units");

        boolean extended = auction.endsAtMillis() - nowMillis < antiSnipeWindowMillis;
        long nextEnd = extended ? Math.addExact(nowMillis, extensionMillis) : auction.endsAtMillis();
        AuctionState updated = auction.change(AuctionState.Status.ACTIVE, amountMinor, bidder, nextEnd);
        UUID previous = auction.highestBidderId();
        boolean sameBidder = bidder.equals(previous);
        long refund = previous == null || sameBidder ? 0 : auction.currentBidMinor();
        long reserve = sameBidder ? Math.subtractExact(amountMinor, auction.currentBidMinor()) : amountMinor;
        return new AuctionBidResult(true, "", updated, sameBidder ? null : previous, refund, reserve, extended);
    }

    public static AuctionState buyNow(AuctionState auction, UUID buyer, long nowMillis) {
        if (auction.mode() != AuctionState.Mode.BIN || auction.status() != AuctionState.Status.ACTIVE
                || nowMillis >= auction.endsAtMillis() || buyer == null || buyer.equals(auction.sellerId())) return null;
        return auction.change(AuctionState.Status.SOLD, auction.binPriceMinor(), buyer, auction.endsAtMillis());
    }

    public static AuctionState expire(AuctionState auction, long nowMillis) {
        if (auction.status() != AuctionState.Status.ACTIVE || nowMillis < auction.endsAtMillis()) return null;
        AuctionState.Status status = auction.highestBidderId() == null
                ? AuctionState.Status.EXPIRED : AuctionState.Status.ENDED;
        return auction.change(status, auction.currentBidMinor(), auction.highestBidderId(), auction.endsAtMillis());
    }

    private static AuctionBidResult rejected(String reason) {
        return new AuctionBidResult(false, reason, null, null, 0, 0, false);
    }
}
