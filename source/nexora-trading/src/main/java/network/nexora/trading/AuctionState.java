package network.nexora.trading;

import java.util.UUID;

public record AuctionState(UUID id, UUID sellerId, Mode mode, Status status,
                           long startPriceMinor, long binPriceMinor, long currentBidMinor,
                           UUID highestBidderId, long endsAtMillis, long version) {
    public enum Mode { BIN, BID }
    public enum Status { DRAFT, ACTIVE, SOLD, ENDED, EXPIRED, CANCELLED, CLAIMED }

    public AuctionState {
        if (id == null || sellerId == null || mode == null || status == null || startPriceMinor <= 0
                || binPriceMinor < 0 || currentBidMinor < 0 || endsAtMillis < 0 || version < 0)
            throw new IllegalArgumentException("Invalid auction state");
        if (mode == Mode.BIN && binPriceMinor <= 0) throw new IllegalArgumentException("BIN price is required");
        if (mode == Mode.BID && binPriceMinor != 0) throw new IllegalArgumentException("Bid auctions cannot have a BIN price");
    }

    AuctionState change(Status next, long bid, UUID bidder, long end) {
        return new AuctionState(id, sellerId, mode, next, startPriceMinor, binPriceMinor,
                bid, bidder, end, Math.addExact(version, 1));
    }
}
