package network.nexora.progression;

import java.util.Map;

/** Shared server-side predicate for recipe preview and execution. */
public final class CraftingRules {
    public enum Result { READY, LOCKED, SKILL_REQUIRED, COLLECTION_REQUIRED, MISSING_MATERIALS, INVALID_QUANTITY, NO_SPACE }
    private CraftingRules() {}
    public static Result check(RecipeDefinition recipe, int crafts, boolean unlocked, Map<String,Long> available,
                               Map<String,Integer> skills, Map<String,Integer> collections) {
        if(crafts<1||crafts>16)return Result.INVALID_QUANTITY;
        if(!unlocked)return Result.LOCKED;
        if(recipe.requiredSkill()!=null && skills.getOrDefault(recipe.requiredSkill(),1)<recipe.requiredSkillLevel())return Result.SKILL_REQUIRED;
        for(var requirement:recipe.collectionRequirements().entrySet())
            if(collections.getOrDefault(requirement.getKey(),0)<requirement.getValue())return Result.COLLECTION_REQUIRED;
        try {
            Math.multiplyExact(recipe.outputAmount(),crafts);
            for(var input:recipe.ingredients().entrySet())
                if(available.getOrDefault(input.getKey(),0L)<Math.multiplyExact(input.getValue(),crafts))return Result.MISSING_MATERIALS;
        }catch(ArithmeticException e){return Result.INVALID_QUANTITY;}
        return Result.READY;
    }
}
