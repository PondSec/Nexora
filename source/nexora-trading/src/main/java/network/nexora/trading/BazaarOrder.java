package network.nexora.trading;

import java.util.Objects;
import java.util.UUID;

/** Immutable order-book entry. Prices are integer minor units per item. */
public record BazaarOrder(UUID id, UUID profileId, String productId, OrderSide side,
                          long unitPriceMinor, long originalQuantity, long remainingQuantity,
                          long createdAtMillis) {
    public BazaarOrder {
        Objects.requireNonNull(id);
        Objects.requireNonNull(profileId);
        Objects.requireNonNull(productId);
        Objects.requireNonNull(side);
        if (productId.isBlank() || unitPriceMinor <= 0 || originalQuantity <= 0
                || remainingQuantity <= 0 || remainingQuantity > originalQuantity || createdAtMillis < 0)
            throw new IllegalArgumentException("Invalid Bazaar order");
    }

    public BazaarOrder withRemaining(long remaining) {
        if (remaining < 0 || remaining > remainingQuantity) throw new IllegalArgumentException("Invalid remaining quantity");
        if (remaining == 0) return null;
        return new BazaarOrder(id, profileId, productId, side, unitPriceMinor,
                originalQuantity, remaining, createdAtMillis);
    }
}
