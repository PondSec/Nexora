package network.nexora.trading;

import java.util.List;

public record MatchResult(boolean accepted, String reason, BazaarOrder restingOrder,
                          List<BazaarFill> fills, long filledQuantity, long grossMinor) {
    public MatchResult { fills = List.copyOf(fills); }
}
