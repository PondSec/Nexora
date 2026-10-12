package network.nexora.trading;

import java.util.UUID;

/** A settled fill at the resting order's price. */
public record BazaarFill(UUID buyOrderId, UUID sellOrderId, UUID buyerProfileId,
                         UUID sellerProfileId, String productId, long quantity,
                         long unitPriceMinor, long grossMinor) {
    public BazaarFill {
        if (quantity <= 0 || unitPriceMinor <= 0 || grossMinor != Math.multiplyExact(quantity, unitPriceMinor))
            throw new IllegalArgumentException("Invalid Bazaar fill");
    }
}
