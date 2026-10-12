package network.nexora.farm;

/** Deterministically scales each natural age increment while clamping to crop maturity. */
final class FarmGrowthPolicy {
    private FarmGrowthPolicy() {}

    static int acceleratedAge(int currentAge, int naturalAge, int maximumAge, int multiplier) {
        if (currentAge < 0 || naturalAge < currentAge || maximumAge < naturalAge || multiplier < 1)
            throw new IllegalArgumentException("Invalid crop age or growth multiplier");
        long naturalGrowth = naturalAge - currentAge;
        long scaledAge = currentAge + naturalGrowth * multiplier;
        return (int) Math.min(maximumAge, scaledAge);
    }
}
