package network.nexora.core.api;

import java.nio.file.Path;
import java.sql.*;
import java.util.*;

/** Write-ahead bridge between playerdata snapshots and durable business transaction receipts.
 * The caller must block player interactions until recover/finish has saved the resolved snapshot.
 * No Bukkit objects are accessed here. Store opaque Paper serialized inventory bytes. */
public final class InventoryJournal {
    public enum Domain {
        LEGACY_CLAIM("SELECT 1 FROM legacy_claim_reconciliations WHERE operation_key=? AND owner_uuid=?"),
        CRAFT(null), STANDARD_PLACE("SELECT 1 FROM standardized_bazaar_transactions WHERE operation_key=? AND owner_uuid=?"), BAZAAR_PLACE("SELECT 1 FROM bazaar_transactions WHERE idempotency_key=? AND profile_uuid=?"),
        NPC_TRADE("SELECT 1 FROM bazaar_market_maker_transactions WHERE idempotency_key=? AND player_uuid=?"),
        AUCTION_LIST("SELECT 1 FROM auction_transactions WHERE idempotency_key=? AND actor_uuid=?"),
        BAZAAR_CLAIM("SELECT 1 FROM bazaar_claimables WHERE claim_id=? AND profile_uuid=? AND claimed_at IS NOT NULL"),
        AUCTION_CLAIM("SELECT 1 FROM auction_claimables WHERE claim_id=? AND profile_uuid=? AND claimed_at IS NOT NULL"),
        STANDARD_CLAIM("SELECT 1 FROM standardized_bazaar_claims WHERE claim_id=? AND profile_uuid=? AND claimed_at IS NOT NULL"),
        MINION_PLACE("SELECT 1 FROM minions WHERE id=? AND owner_uuid=?"),
        MINION_PICKUP("SELECT 1 FROM minion_inventory_receipts WHERE operation_key=? AND owner_uuid=?"),
        MINION_COLLECT("SELECT 1 FROM minion_inventory_receipts WHERE operation_key=? AND owner_uuid=?");
        final String receiptSql; Domain(String sql){receiptSql=sql;}
    }
    public record Pending(UUID owner, Domain domain, String operation, byte[] before, byte[] after, boolean committed) {
        public Pending { before=before.clone();after=after.clone(); }
        @Override public byte[] before(){return before.clone();}
        @Override public byte[] after(){return after.clone();}
    }
    private final String url;
    public InventoryJournal(Path path)throws SQLException {
        url="jdbc:sqlite:"+path.toAbsolutePath();
        try(var c=connect();var s=c.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS inventory_operation_journal(owner_uuid TEXT PRIMARY KEY REFERENCES profiles(uuid), domain TEXT NOT NULL, operation_key TEXT NOT NULL UNIQUE, before_snapshot BLOB NOT NULL, after_snapshot BLOB NOT NULL, committed INTEGER NOT NULL DEFAULT 0 CHECK(committed IN (0,1)), created_at INTEGER NOT NULL)");
        }
    }
    public void prepare(UUID owner,Domain domain,String operation,byte[] before,byte[] after)throws SQLException {
        Objects.requireNonNull(domain);if(operation==null||operation.isBlank()||before.length==0||after.length==0)throw new IllegalArgumentException("Invalid inventory operation");
        try(var c=connect();var p=c.prepareStatement("INSERT INTO inventory_operation_journal(owner_uuid,domain,operation_key,before_snapshot,after_snapshot,created_at) VALUES(?,?,?,?,?,?)")) {
            p.setString(1,owner.toString());p.setString(2,domain.name());p.setString(3,operation);p.setBytes(4,before);p.setBytes(5,after);p.setLong(6,System.currentTimeMillis());p.executeUpdate();
        }
    }
    public Pending pending(UUID owner)throws SQLException {
        try(var c=connect();var q=c.prepareStatement("SELECT domain,operation_key,before_snapshot,after_snapshot,committed FROM inventory_operation_journal WHERE owner_uuid=?")) {
            q.setString(1,owner.toString());try(var r=q.executeQuery()) {
                if(!r.next())return null;
                Domain domain=Domain.valueOf(r.getString(1));String operation=r.getString(2);boolean committed=r.getInt(5)!=0;
                if(domain.receiptSql!=null)try(var receipt=c.prepareStatement(domain.receiptSql)) {
                    receipt.setString(1,operation);receipt.setString(2,owner.toString());try(var row=receipt.executeQuery()){committed=row.next();}
                }
                return new Pending(owner,domain,operation,r.getBytes(3),r.getBytes(4),committed);
            }
        }
    }
    public void commitCraft(UUID owner,String operation)throws SQLException {
        try(var c=connect();var p=c.prepareStatement("UPDATE inventory_operation_journal SET committed=1 WHERE owner_uuid=? AND operation_key=? AND domain='CRAFT'")) {
            p.setString(1,owner.toString());p.setString(2,operation);if(p.executeUpdate()!=1)throw new SQLException("Missing craft journal");
        }
    }
    /** Call ONLY after the resolved playerdata snapshot has been saved successfully. */
    public void finish(UUID owner,String operation)throws SQLException {
        try(var c=connect();var p=c.prepareStatement("DELETE FROM inventory_operation_journal WHERE owner_uuid=? AND operation_key=?")) {
            p.setString(1,owner.toString());p.setString(2,operation);p.executeUpdate();
        }
    }
    private Connection connect()throws SQLException {
        var c=JdbcMetrics.instrument(DriverManager.getConnection(url),JdbcMetrics.Source.INVENTORY);
        try(var s=c.createStatement()){s.execute("PRAGMA busy_timeout=10000");s.execute("PRAGMA foreign_keys=ON");s.execute("PRAGMA synchronous=FULL");}
        catch(SQLException e){c.close();throw e;}return c;
    }
}
