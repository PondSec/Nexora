package network.nexora.progression;

import java.util.Map;

public record RecipeDefinition(String id, String outputItemId, long outputAmount,
                               Map<String, Long> ingredients, Map<String, Integer> collectionRequirements,
                               String requiredCollectionId,
                               int requiredTier, String requiredSkill, int requiredSkillLevel) {
    public RecipeDefinition(String id, String outputItemId, long outputAmount,
                            Map<String, Long> ingredients, String requiredCollectionId,
                            int requiredTier, String requiredSkill, int requiredSkillLevel) {
        this(id, outputItemId, outputAmount, ingredients,
                requiredCollectionId == null ? Map.of() : Map.of(requiredCollectionId, requiredTier),
                requiredCollectionId, requiredTier, requiredSkill, requiredSkillLevel);
    }
    public RecipeDefinition {
        if (id == null || id.isBlank() || outputItemId == null || outputItemId.isBlank() || outputAmount <= 0)
            throw new IllegalArgumentException("Recipe ID, output and positive output amount are required");
        if (ingredients == null || ingredients.isEmpty()) throw new IllegalArgumentException("Recipe needs ingredients");
        ingredients = Map.copyOf(ingredients);
        collectionRequirements = Map.copyOf(collectionRequirements == null ? Map.of() : collectionRequirements);
        if (ingredients.entrySet().stream().anyMatch(e -> e.getKey() == null || e.getKey().isBlank() || e.getValue() == null || e.getValue() <= 0))
            throw new IllegalArgumentException("Recipe ingredient IDs and amounts must be valid");
        if (requiredTier < 0 || requiredSkillLevel < 0 || (requiredCollectionId == null) != (requiredTier == 0))
            throw new IllegalArgumentException("Recipe requirement is inconsistent");
        if (collectionRequirements.entrySet().stream().anyMatch(e -> e.getKey() == null || e.getKey().isBlank()
                || e.getValue() == null || e.getValue() < 1)) throw new IllegalArgumentException("Invalid collection requirement");
        if (requiredCollectionId != null && !collectionRequirements.containsKey(requiredCollectionId))
            throw new IllegalArgumentException("Legacy primary collection must be included in requirements");
    }
}
