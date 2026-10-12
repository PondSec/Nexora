package network.nexora.progression;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Resolves a recipe graph into raw inputs and optional live market/production estimates. */
public final class CraftingCostCalculator {
    public record Estimate(Map<String, BigInteger> rawMaterials, BigDecimal marketCostCoins,
                           Map<String, BigDecimal> farmingHoursByMaterial,
                           String slowestMaterial, boolean hasCompleteMarketPrices,
                           boolean hasCompleteProductionRates) {}
    private final ContentRegistry content;
    public CraftingCostCalculator(ContentRegistry content) { this.content = content; }

    public Estimate calculate(String itemId, BigInteger amount,
                              Map<String, BigDecimal> unitPriceCoins,
                              Map<String, BigDecimal> productionPerHour) {
        if (amount == null || amount.signum() <= 0) throw new IllegalArgumentException("Amount must be positive");
        Map<String,BigInteger> raw = new TreeMap<>();
        expand(itemId, amount, raw, new HashSet<>());
        BigDecimal total = BigDecimal.ZERO;
        boolean pricesComplete = true, ratesComplete = true;
        Map<String,BigDecimal> hours = new TreeMap<>();
        for (var input : raw.entrySet()) {
            BigDecimal price = unitPriceCoins.get(input.getKey());
            if (price == null || price.signum() < 0) pricesComplete = false;
            else total = total.add(price.multiply(new BigDecimal(input.getValue())));
            BigDecimal rate = productionPerHour.get(input.getKey());
            if (rate == null || rate.signum() <= 0) ratesComplete = false;
            else hours.put(input.getKey(), new BigDecimal(input.getValue()).divide(rate, 2, RoundingMode.CEILING));
        }
        String slowest = hours.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse("");
        return new Estimate(Map.copyOf(raw), pricesComplete ? total : null, Map.copyOf(hours), slowest,
                pricesComplete, ratesComplete);
    }

    private void expand(String itemId, BigInteger requested, Map<String,BigInteger> raw, Set<String> path) {
        if (!path.add(itemId)) throw new IllegalStateException("Crafting dependency cycle at " + itemId);
        RecipeDefinition recipe = content.recipes().values().stream().filter(r -> r.outputItemId().equals(itemId))
                .sorted(java.util.Comparator.comparing(RecipeDefinition::id)).findFirst().orElse(null);
        if (recipe == null) raw.merge(itemId, requested, BigInteger::add);
        else {
            BigInteger output = BigInteger.valueOf(recipe.outputAmount());
            BigInteger crafts = requested.add(output).subtract(BigInteger.ONE).divide(output);
            for (var ingredient : recipe.ingredients().entrySet())
                expand(ingredient.getKey(), crafts.multiply(BigInteger.valueOf(ingredient.getValue())), raw, path);
        }
        path.remove(itemId);
    }
}
