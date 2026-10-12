package network.nexora.progression;

import java.util.*;

/** One validated source of truth for item, collection, reward and recipe identifiers. */
public final class ContentRegistry {
    private final Map<String, ItemDefinition> items = new LinkedHashMap<>();
    private final Map<String, CollectionDefinition> collections = new LinkedHashMap<>();
    private final Map<String, RecipeDefinition> recipes = new LinkedHashMap<>();
    private final Set<String> effectHandlers = new HashSet<>();

    public void registerEffectHandler(String handlerId) {
        if (handlerId == null || handlerId.isBlank() || !effectHandlers.add(handlerId))
            throw new IllegalArgumentException("Effect handler is blank or duplicated: " + handlerId);
    }
    public void registerItem(ItemDefinition item) { unique(items, item.id(), item); }
    public void registerCollection(CollectionDefinition collection) { unique(collections, collection.id(), collection); }
    public void registerCollectionReward(String collectionId, int tier, CollectionReward reward) {
        CollectionDefinition collection = collections.get(collectionId);
        if (collection == null) throw new IllegalArgumentException("Unknown collection: " + collectionId);
        if (tier < 1 || tier > collection.tierThresholds().size()) throw new IllegalArgumentException("Invalid reward tier: " + tier);
        Map<Integer, List<CollectionReward>> updated = new LinkedHashMap<>(collection.rewards());
        List<CollectionReward> tierRewards = new ArrayList<>(updated.getOrDefault(tier, List.of()));
        if (tierRewards.stream().anyMatch(existing -> existing.id().equals(reward.id())))
            throw new IllegalArgumentException("Duplicate collection reward: " + reward.id());
        tierRewards.add(reward);
        updated.put(tier, List.copyOf(tierRewards));
        collections.put(collectionId, new CollectionDefinition(collection.id(), collection.category(), collection.resourceId(),
                collection.tierThresholds(), updated));
    }
    public void registerRecipe(RecipeDefinition recipe) { unique(recipes, recipe.id(), recipe); }

    public List<String> validate() {
        List<String> errors = new ArrayList<>();
        for (ItemDefinition item : items.values()) if (!effectHandlers.contains(item.effectHandler()))
            errors.add("Missing effect handler " + item.effectHandler() + " for item " + item.id());
        for (CollectionDefinition collection : collections.values()) {
            for (var reward : collection.rewardsThrough(collection.tierThresholds().size())) {
                if (requiresRecipe(reward.type()) && !recipes.containsKey(reward.targetId()))
                    errors.add("Unknown recipe " + reward.targetId() + " in reward " + reward.id());
                if (reward.type() == RewardType.COINS || reward.type() == RewardType.SKILL_XP || reward.type() == RewardType.SKYBLOCK_XP) {
                    if (reward.amount() <= 0) errors.add("Nonpositive numeric reward " + reward.id());
                } else if (!items.containsKey(reward.targetId()) && !recipes.containsKey(reward.targetId())) {
                    errors.add("Unknown reward target " + reward.targetId() + " in reward " + reward.id());
                }
            }
        }
        for (RecipeDefinition recipe : recipes.values()) {
            if (!items.containsKey(recipe.outputItemId())) errors.add("Unknown output item " + recipe.outputItemId() + " in recipe " + recipe.id());
            for (String ingredient : recipe.ingredients().keySet())
                if (!items.containsKey(ingredient)) errors.add("Unknown ingredient " + ingredient + " in recipe " + recipe.id());
            for (var requirement : recipe.collectionRequirements().entrySet()) {
                CollectionDefinition collection = collections.get(requirement.getKey());
                if (collection == null || requirement.getValue() > collection.tierThresholds().size())
                    errors.add("Invalid collection requirement " + requirement.getKey() + " on recipe " + recipe.id());
            }
        }
        detectRecipeCycles(errors);
        return List.copyOf(errors);
    }

    public void validateOrThrow() {
        List<String> errors = validate();
        if (!errors.isEmpty()) throw new IllegalStateException(String.join("\n", errors));
    }

    private void detectRecipeCycles(List<String> errors) {
        Map<String, Integer> state = new HashMap<>();
        for (String itemId : items.keySet()) visit(itemId, state, new ArrayDeque<>(), errors);
    }

    private void visit(String itemId, Map<String, Integer> state, Deque<String> path, List<String> errors) {
        int current = state.getOrDefault(itemId, 0);
        if (current == 2) return;
        if (current == 1) { errors.add("Recipe dependency cycle: " + String.join(" -> ", path) + " -> " + itemId); return; }
        state.put(itemId, 1); path.addLast(itemId);
        for (RecipeDefinition recipe : recipes.values()) if (recipe.outputItemId().equals(itemId))
            for (String ingredient : recipe.ingredients().keySet()) visit(ingredient, state, path, errors);
        path.removeLast(); state.put(itemId, 2);
    }

    private static boolean requiresRecipe(RewardType type) {
        return switch (type) {
            case RECIPE_UNLOCK, ITEM_RECIPE_UNLOCK, ARMOR_RECIPE_UNLOCK, WEAPON_RECIPE_UNLOCK,
                    TOOL_RECIPE_UNLOCK, ACCESSORY_RECIPE_UNLOCK, MINION_RECIPE_UNLOCK,
                    MINION_UPGRADE_RECIPE_UNLOCK -> true;
            default -> false;
        };
    }

    private static <T> void unique(Map<String, T> values, String id, T value) {
        if (values.putIfAbsent(id, value) != null) throw new IllegalArgumentException("Duplicate content ID: " + id);
    }
    public Map<String, ItemDefinition> items() { return Map.copyOf(items); }
    public Map<String, CollectionDefinition> collections() { return Map.copyOf(collections); }
    public Map<String, RecipeDefinition> recipes() { return Map.copyOf(recipes); }
}
