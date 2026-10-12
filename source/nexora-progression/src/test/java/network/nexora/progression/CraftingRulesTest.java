package network.nexora.progression;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
class CraftingRulesTest {
    private final RecipeDefinition recipe=new RecipeDefinition("nexora:test","nexora:result",1,
            Map.of("nexora:enchanted_coal",3L,"minecraft:iron_ingot",2L),"coal",2,"mining",3);
    private CraftingRules.Result check(int n,boolean unlocked,Map<String,Long> items) {
        return CraftingRules.check(recipe,n,unlocked,items,Map.of("mining",3),Map.of("coal",2));
    }
    @Test void exactIngredientsAndRequirementsAreAccepted(){assertEquals(CraftingRules.Result.READY,check(1,true,Map.of("nexora:enchanted_coal",3L,"minecraft:iron_ingot",2L)));}
    @Test void lockedRecipeIsRejectedEvenWithMaterials(){assertEquals(CraftingRules.Result.LOCKED,check(1,false,Map.of("nexora:enchanted_coal",3L,"minecraft:iron_ingot",2L)));}
    @Test void vanillaCoalCannotImpersonateCustomIngredient(){assertEquals(CraftingRules.Result.MISSING_MATERIALS,check(1,true,Map.of("minecraft:coal",300L,"minecraft:iron_ingot",2L)));}
    @Test void batchUsesExactMultipliedAmounts(){assertEquals(CraftingRules.Result.MISSING_MATERIALS,check(4,true,Map.of("nexora:enchanted_coal",11L,"minecraft:iron_ingot",8L))); assertEquals(CraftingRules.Result.READY,check(4,true,Map.of("nexora:enchanted_coal",12L,"minecraft:iron_ingot",8L)));}
    @Test void skillGateIsCheckedOnEveryExecution(){assertEquals(CraftingRules.Result.SKILL_REQUIRED,CraftingRules.check(recipe,1,true,Map.of(),Map.of("mining",2),Map.of("coal",2)));}
    @Test void collectionGateIsCheckedOnEveryExecution(){assertEquals(CraftingRules.Result.COLLECTION_REQUIRED,CraftingRules.check(recipe,1,true,Map.of(),Map.of("mining",3),Map.of("coal",1)));}
    @Test void spamQuantityAndOverflowAreRejected(){assertEquals(CraftingRules.Result.INVALID_QUANTITY,check(17,true,Map.of())); var huge=new RecipeDefinition("a","b",1,Map.of("c",Long.MAX_VALUE),null,0,null,0); assertEquals(CraftingRules.Result.INVALID_QUANTITY,CraftingRules.check(huge,2,true,Map.of("c",Long.MAX_VALUE),Map.of(),Map.of()));}
}
