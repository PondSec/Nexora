package network.nexora.core;
import network.nexora.core.api.InventoryJournal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
class InventoryJournalTest {
    @TempDir Path temp;
    @Test void crashBeforeCommitRestoresBeforeAndAfterCommitRestoresAfter() throws Exception {
        var path=temp.resolve("journal.sqlite");var id=UUID.randomUUID();
        try(var store=new ProgressStore(path.toFile(),0)){store.join(id,"Player");}
        var journal=new InventoryJournal(path);
        journal.prepare(id,InventoryJournal.Domain.CRAFT,"craft-1",new byte[]{1},new byte[]{2});
        assertFalse(new InventoryJournal(path).pending(id).committed());
        assertThrows(java.sql.SQLException.class,()->journal.prepare(id,InventoryJournal.Domain.CRAFT,"craft-2",new byte[]{2},new byte[]{3}));
        journal.commitCraft(id,"craft-1");var recovered=new InventoryJournal(path).pending(id);
        assertTrue(recovered.committed());assertArrayEquals(new byte[]{2},recovered.after());
        var mutable=recovered.after();mutable[0]=9;assertArrayEquals(new byte[]{2},recovered.after());
        journal.finish(id,"wrong-operation");assertNotNull(journal.pending(id));
        journal.finish(id,"craft-1");assertNull(journal.pending(id));
    }
    @Test void businessReceiptAndOwnerDetermineRecoveryEvenWithoutFinish() throws Exception {
        var path=temp.resolve("receipt.sqlite");var a=UUID.randomUUID();var b=UUID.randomUUID();
        try(var store=new ProgressStore(path.toFile(),0)){store.join(a,"A");store.join(b,"B");}
        try(var c=DriverManager.getConnection("jdbc:sqlite:"+path);var s=c.createStatement()){
            s.execute("CREATE TABLE bazaar_transactions(idempotency_key TEXT PRIMARY KEY,profile_uuid TEXT NOT NULL)");
        }
        var journal=new InventoryJournal(path);journal.prepare(a,InventoryJournal.Domain.BAZAAR_PLACE,"sell",new byte[]{1},new byte[]{2});
        assertFalse(journal.pending(a).committed());assertNull(journal.pending(b));
        try(var c=DriverManager.getConnection("jdbc:sqlite:"+path);var p=c.prepareStatement("INSERT INTO bazaar_transactions VALUES(?,?)")){
            p.setString(1,"sell");p.setString(2,a.toString());p.executeUpdate();
        }
        assertTrue(new InventoryJournal(path).pending(a).committed());
    }
    @Test void standardizedSellRecoveryUsesDurableOwnerScopedRequestReceipt() throws Exception {
        var path=temp.resolve("standard.sqlite");var owner=UUID.randomUUID();var other=UUID.randomUUID();
        try(var store=new ProgressStore(path.toFile(),0)){store.join(owner,"Owner");store.join(other,"Other");}
        try(var c=DriverManager.getConnection("jdbc:sqlite:"+path);var statement=c.createStatement()) {
            statement.execute("CREATE TABLE standardized_bazaar_transactions(operation_key TEXT PRIMARY KEY,owner_uuid TEXT NOT NULL)");
            statement.execute("INSERT INTO standardized_bazaar_transactions VALUES('foreign','"+other+"')");
        }
        var journal=new InventoryJournal(path);journal.prepare(owner,InventoryJournal.Domain.STANDARD_PLACE,"foreign",new byte[]{1},new byte[]{2});
        assertFalse(journal.pending(owner).committed());journal.finish(owner,"foreign");
        journal.prepare(owner,InventoryJournal.Domain.STANDARD_PLACE,"committed",new byte[]{1},new byte[]{2});
        try(var c=DriverManager.getConnection("jdbc:sqlite:"+path);var statement=c.createStatement()){statement.execute("INSERT INTO standardized_bazaar_transactions VALUES('committed','"+owner+"')");}
        assertTrue(new InventoryJournal(path).pending(owner).committed());
    }
}
