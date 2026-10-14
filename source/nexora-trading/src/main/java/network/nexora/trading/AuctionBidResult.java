package network.nexora.trading;

import java.util.UUID;

public record AuctionBidResult(boolean accepted, String reason, AuctionState updated,
                               UUID refundedProfile, long refundMinor, long reserveMinor,
                               boolean extended) {}
