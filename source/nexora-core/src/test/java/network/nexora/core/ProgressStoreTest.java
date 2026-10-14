package network.nexora.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ProgressStoreTest {
    @TempDir Path dir;

    @Test void profilesHaveIndependentWalletsAndSurviveReopen() throws Exception {
        UUID a=UUID.randomUUID(),b=UUID.randomUUID();Path db=dir.resolve("profiles.sqlite");
        try(var store=new ProgressStore(db.toFile(),10_000)){
            store.join(a,"Alice");store.join(b,"Bob");
            assertTrue(store.withdraw(a,250,"test purchase"));
            assertEquals(9_750,store.balance(a));assertEquals(10_000,store.balance(b));
        }
        try(var store=new ProgressStore(db.toFile(),10_000)){
            assertEquals(9_750,store.balance(a));assertEquals(10_000,store.balance(b));
        }
    }

    @Test void withdrawalsCannotOverspendAndProgressIsUuidScoped() throws Exception {
        UUID a=UUID.randomUUID(),b=UUID.randomUUID();
        try(var store=new ProgressStore(dir.resolve("economy.sqlite").toFile(),100)){
            store.join(a,"A");store.join(b,"B");
            assertFalse(store.withdraw(a,101,"overspend"));
            assertEquals(100,store.balance(a));
            store.addProgress(a,"mining","diamond",50,3);
            assertEquals(50,store.xp(a,"mining"));assertEquals(0,store.xp(b,"mining"));
            assertEquals(3,store.collections(a).get("diamond"));assertTrue(store.collections(b).isEmpty());
        }
    }

    @Test void rejectsNegativeAndOverflowingDeposits() throws Exception {
        UUID id=UUID.randomUUID();
        try(var store=new ProgressStore(dir.resolve("limits.sqlite").toFile(),Long.MAX_VALUE-2)){
            store.join(id,"A");assertFalse(store.deposit(id,3,"overflow"));
            assertFalse(store.deposit(id,-1,"negative"));assertEquals(Long.MAX_VALUE-2,store.balance(id));
        }
    }

    @Test void deathChargeTakesHalfThePurseAndKeepsTheRemainder() throws Exception {
        UUID id=UUID.randomUUID();
        try(var store=new ProgressStore(dir.resolve("death.sqlite").toFile(),10_001)){
            store.join(id,"A");
            assertEquals(5_000,store.chargeDeathHalf(id));
            assertEquals(5_001,store.balance(id));
            assertEquals(2_500,store.chargeDeathHalf(id));
            assertEquals(2_501,store.balance(id));
        }
    }

    @Test void minionProductionAndUpgradesPersistWithoutDuplicateClaims() throws Exception {
        UUID owner=UUID.randomUUID();
        try(var store=new ProgressStore(dir.resolve("minions.sqlite").toFile(),10_000)){
            store.join(owner,"Owner");
            String id=UUID.randomUUID().toString();
            var created=store.createMinion(id,owner,"island-1","bskyblock_world",12,80,-4,"iron",1_000);
            assertEquals(1,created.tier());
            var produced=store.advanceMinion(id,4_500,1_000,86_400_000,128);
            assertEquals(3,produced.storage());
            assertEquals(4_000,produced.lastAction());
            assertEquals(2,store.takeMinionStorage(id,owner,2));
            assertEquals(1,store.minion(id).storage());
            assertTrue(store.upgradeMinion(id,owner,2_500));
            assertEquals(2,store.minion(id).tier());
            assertEquals(7_500,store.balance(owner));
            assertFalse(store.upgradeMinion(id,UUID.randomUUID(),1));
        }
        try(var store=new ProgressStore(dir.resolve("minions.sqlite").toFile(),10_000)){
            assertEquals("iron",store.minionsForOwner(owner).getFirst().type());
        }
    }

    @Test void progressionMilestonesAndDailyClaimsAreAtomicAndIdempotent() throws Exception {
        UUID owner=UUID.randomUUID();String day="2026-10-10";
        try(var store=new ProgressStore(dir.resolve("progression.sqlite").toFile(),10_000)){
            store.join(owner,"Player");
            var first=store.recordGather(owner,"mining","coal",1,1,List.of(1L,10L),10,25,
                    ProgressionMath.SKILL_DEFAULT,day,Map.of("mining",2L));
            assertEquals(1,first.collectionMilestones());assertEquals(25,first.skyXpAwarded());assertEquals(25,store.skyblockXp(owner));
            store.recordGather(owner,"mining","coal",1,1,List.of(1L,10L),10,25,
                    ProgressionMath.SKILL_DEFAULT,day,Map.of("mining",2L));
            assertEquals(2,store.dailyTasks(owner,day).getFirst().progress());
            assertTrue(store.claimDaily(owner,day,"mining",2,5_000,50));
            assertFalse(store.claimDaily(owner,day,"mining",2,5_000,50));
            assertEquals(15_000,store.balance(owner));assertEquals(75,store.skyblockXp(owner));
        }
    }

    @Test void placedProgressBlocksCanOnlyBeSuppressedOnce() throws Exception {
        try(var store=new ProgressStore(dir.resolve("placed.sqlite").toFile(),0)){
            UUID owner=UUID.randomUUID();store.join(owner,"Player");
            store.markPlacedBlock("nexora_mining",1,64,-3,owner,"DIAMOND_ORE");
            assertTrue(store.consumePlacedBlock("nexora_mining",1,64,-3));
            assertFalse(store.consumePlacedBlock("nexora_mining",1,64,-3));
        }
    }
}
