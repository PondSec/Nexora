package network.nexora.trading;

import java.math.BigInteger;

/** Exact fixed-point wallet arithmetic: 100 minor units per Coin. */
public final class MoneyMath {
    private MoneyMath() {}

    public static long total(long unitPriceMinor, long quantity) {
        if (unitPriceMinor < 0 || quantity < 0) throw new IllegalArgumentException("Money and quantity cannot be negative");
        return Math.multiplyExact(unitPriceMinor, quantity);
    }

    /** Rounds fees up so small transactions cannot bypass a nonzero fee. */
    public static long fee(long grossMinor, int basisPoints) {
        if (grossMinor < 0 || basisPoints < 0 || basisPoints > 10_000) throw new IllegalArgumentException("Invalid fee");
        BigInteger numerator = BigInteger.valueOf(grossMinor).multiply(BigInteger.valueOf(basisPoints));
        BigInteger rounded = numerator.add(BigInteger.valueOf(9_999)).divide(BigInteger.valueOf(10_000));
        return rounded.longValueExact();
    }
}
