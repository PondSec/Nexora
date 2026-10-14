package network.nexora.trading;

public final class AuctionAccounting {
    public static final int LISTING_FEE_BPS = 100;
    public static final int SUCCESSFUL_SALE_TAX_BPS = 100;

    private AuctionAccounting() {}

    public static long listingFee(long startOrBinPriceMinor) {
        return MoneyMath.fee(startOrBinPriceMinor, LISTING_FEE_BPS);
    }

    public static long saleTax(long finalPriceMinor) {
        return MoneyMath.fee(finalPriceMinor, SUCCESSFUL_SALE_TAX_BPS);
    }

    public static long sellerProceeds(long finalPriceMinor) {
        return Math.subtractExact(finalPriceMinor, saleTax(finalPriceMinor));
    }
}
