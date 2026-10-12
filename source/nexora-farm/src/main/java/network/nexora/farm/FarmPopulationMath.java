package network.nexora.farm;

final class FarmPopulationMath {
    private FarmPopulationMath() {}

    static int spreadCoordinate(int index, int base, int minimum, int maximum) {
        if (index < 0 || base < 2 || maximum < minimum) throw new IllegalArgumentException("Invalid spread parameters");
        if (maximum == minimum) return minimum;
        double fraction = halton(index + 1, base);
        return minimum + (int) Math.floor(fraction * (maximum - minimum + 1));
    }

    static int missingAnimals(int target, int current) {
        if (target < 0 || current < 0) throw new IllegalArgumentException("Animal counts cannot be negative");
        return Math.max(0, target - current);
    }

    static int managedSurplus(int target, int current, int managed) {
        if (target < 0 || current < 0 || managed < 0) throw new IllegalArgumentException("Animal counts cannot be negative");
        return Math.min(Math.max(0, current - target), managed);
    }

    private static double halton(int index, int base) {
        double result = 0, factor = 1.0 / base;
        while (index > 0) {
            result += factor * (index % base);
            index /= base;
            factor /= base;
        }
        return result;
    }
}
