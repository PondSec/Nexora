package network.nexora.trading;

/** Fee and escrow rules shared by order creation and fill settlement. */
public final class BazaarAccounting {
    public static final int BUY_ORDER_CREATION_FEE_BPS = 50;
    public static final int SELL_SETTLEMENT_FEE_BPS = 100;

    public record FillSettlement(long buyerEscrowConsumedMinor, long executionCostMinor,
                                 long buyerImprovementRefundMinor, long sellerFeeMinor,
                                 long sellerNetMinor) {}

    private BazaarAccounting() {}

    public static long buyOrderReservation(long limitPriceMinor, long quantity) {
        return MoneyMath.total(limitPriceMinor, quantity);
    }

    public static long buyOrderCreationFee(long limitPriceMinor, long quantity) {
        return MoneyMath.fee(buyOrderReservation(limitPriceMinor, quantity), BUY_ORDER_CREATION_FEE_BPS);
    }

    public static FillSettlement settleFill(long buyerLimitMinor, long executionPriceMinor,
                                             long quantity, boolean limitBuyOrder) {
        if (buyerLimitMinor <= 0 || executionPriceMinor <= 0 || executionPriceMinor > buyerLimitMinor || quantity <= 0)
            throw new IllegalArgumentException("Fill price or quantity violates the buy limit");
        long escrow = limitBuyOrder ? MoneyMath.total(buyerLimitMinor, quantity) : 0;
        long gross = MoneyMath.total(executionPriceMinor, quantity);
        long refund = limitBuyOrder ? Math.subtractExact(escrow, gross) : 0;
        long fee = MoneyMath.fee(gross, SELL_SETTLEMENT_FEE_BPS);
        return new FillSettlement(escrow, gross, refund, fee, Math.subtractExact(gross, fee));
    }
}
