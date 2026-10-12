package network.nexora.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class ConcurrentProgressStoreTest {
    @TempDir Path temp;
    @Test void independentConnectionsSerializeReadThenWriteWithoutBusySnapshot() throws Exception {
        var path = temp.resolve("shared.sqlite"); UUID id = UUID.randomUUID();
        try (var a = new ProgressStore(path.toFile(), 100_000); var b = new ProgressStore(path.toFile(), 100_000);
             var executor = Executors.newFixedThreadPool(8)) {
            a.join(id,"Player"); List<Future<?>> tasks = new ArrayList<>();
            for (int i=0;i<100;i++) { final int n=i; tasks.add(executor.submit(() -> {
                var store = n%2==0 ? a : b;
                try { store.recordGather(id,"mining","coal",1,1,List.of(1L,50L),10,25,
                        ProgressionMath.SKILL_DEFAULT,"2026-10-12",Map.of()); }
                catch (Exception e) { throw new RuntimeException(e); }
            })); }
            for (var task:tasks) task.get(20,TimeUnit.SECONDS);
            assertEquals(100,a.collections(id).get("coal")); assertEquals(100,b.xp(id,"mining"));
            assertEquals(60,a.skyblockXp(id)); // two collection milestones + skill level 2
        }
    }
    @Test void pickupAndReplacementPreserveTierAndOwner() throws Exception {
        var path=temp.resolve("minion.sqlite"); UUID owner=UUID.randomUUID();
        try(var store=new ProgressStore(path.toFile(),10000)) {
            store.join(owner,"Owner"); var old=store.createMinion("old",owner,"island","world",0,64,0,"coal",1000);
            assertTrue(store.upgradeMinion(old.id(),owner,100)); int tier=store.minion(old.id()).tier();
            assertFalse(store.deleteMinion(old.id(),UUID.randomUUID())); assertTrue(store.deleteMinion(old.id(),owner));
            var next=store.createMinion("next",owner,"island","world",1,64,0,"coal",tier,2000);
            assertEquals(2,next.tier()); assertEquals(0,next.storage());
            assertThrows(IllegalArgumentException.class,()->store.createMinion("bad",owner,"island","world",2,64,0,"coal",12,2000));
        }
        try(var reopened=new ProgressStore(path.toFile(),0)) { assertEquals(2,reopened.minion("next").tier()); }
    }
    @Test void inventoryReceiptsSurviveRestartAndForeignClaimsCannotConsumeStorage() throws Exception {
        var path=temp.resolve("receipt-minion.sqlite");var owner=UUID.randomUUID();var other=UUID.randomUUID();
        try(var store=new ProgressStore(path.toFile(),0)) {
            store.join(owner,"Owner");store.join(other,"Other");store.createMinion("minion",owner,"island","world",0,64,0,"coal",1000);
            store.advanceMinion("minion",5000,1000,100000,128);
            assertEquals(0,store.takeMinionStorage("minion",other,2,"foreign"));
            assertEquals(0,store.takeMinionStorage("minion",owner,5,"oversized"));
            assertEquals(4,store.minion("minion").storage());
            assertEquals(2,store.takeMinionStorage("minion",owner,2,"collect"));
            assertThrows(java.sql.SQLException.class,()->store.takeMinionStorage("minion",owner,2,"collect"));
            assertEquals(2,store.minion("minion").storage());
        }
        try(var reopened=new ProgressStore(path.toFile(),0)){assertEquals(2,reopened.minion("minion").storage());}
    }

}
