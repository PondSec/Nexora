package network.nexora.trading;

import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Bazaar escrow for identical non-stackable Nexora instances such as Infinite Rocket. */
public final class StandardizedBazaarService {
    public enum Outcome { OPENED, PARTIAL, FILLED, CANCELLED, INSUFFICIENT_FUNDS, INSUFFICIENT_LIQUIDITY, INVALID, NOT_OWNER, NOT_OPEN }
    public record StandardItem(String instanceId, byte[] snapshot) {
        public StandardItem { Objects.requireNonNull(instanceId); snapshot = snapshot.clone(); if (instanceId.isBlank() || snapshot.length == 0) throw new IllegalArgumentException("Invalid item snapshot"); }
        @Override public byte[] snapshot() { return snapshot.clone(); }
    }
    public record Result(Outcome outcome, UUID orderId, long filled, long remaining) {}
    public record Claim(String claimId, UUID orderId, String instanceId, byte[] snapshot) {
        public Claim { snapshot = snapshot.clone(); }
        @Override public byte[] snapshot() { return snapshot.clone(); }
    }
    public record OrderView(UUID orderId, OrderSide side, long unitPriceMinor, long remaining, String status) {}
    private final MarketplaceDatabase database;
    public StandardizedBazaarService(MarketplaceDatabase database) { this.database = Objects.requireNonNull(database); }

    public Result sell(UUID owner, String product, long price, List<StandardItem> items, String operation, long now) throws SQLException {
        if (items == null || items.isEmpty() || price <= 0 || !valid(product, operation)) throw new IllegalArgumentException("Invalid standardized sell offer");
        if (items.stream().map(StandardItem::instanceId).distinct().count() != items.size()) throw new IllegalArgumentException("Repeated item instance");
        return place(owner, product, OrderSide.SELL, price, items.size(), items, operation, false, now);
    }

    public Result sellInstant(UUID owner, String product, long price, List<StandardItem> items, String operation, long now) throws SQLException {
        if (items == null || items.isEmpty() || price <= 0 || !valid(product, operation)) throw new IllegalArgumentException("Invalid standardized instant sell");
        return place(owner, product, OrderSide.SELL, price, items.size(), items, operation, true, now);
    }

    public Result buy(UUID owner, String product, long price, long quantity, String operation, long now) throws SQLException {
        if (price <= 0 || quantity <= 0 || quantity > 10_000 || !valid(product, operation)) throw new IllegalArgumentException("Invalid standardized buy order");
        return place(owner, product, OrderSide.BUY, price, quantity, List.of(), operation, false, now);
    }

    public Result buyInstant(UUID owner, String product, long price, long quantity, String operation, long now) throws SQLException {
        if (price <= 0 || quantity <= 0 || quantity > 10_000 || !valid(product, operation)) throw new IllegalArgumentException("Invalid standardized instant buy");
        return place(owner, product, OrderSide.BUY, price, quantity, List.of(), operation, true, now);
    }

    private Result place(UUID owner, String product, OrderSide side, long price, long quantity,
                         List<StandardItem> items, String operation, boolean requireFull, long now) throws SQLException {
        UUID id = UUID.randomUUID();
        try { return database.transact(c -> placeTx(c, owner, product, side, price, quantity, items, id, operation, requireFull, now)); }
        catch (Rejected rejected) { return new Result(rejected.outcome, null, 0, 0); }
    }

    private Result placeTx(Connection c, UUID owner, String product, OrderSide side, long price, long quantity,
                           List<StandardItem> items, UUID id, String operation, boolean requireFull, long now) throws SQLException {
        if (!database.profileExists(c, owner)) throw reject(Outcome.NOT_OWNER);
        if (requireFull && available(c, owner, product, side, price) < quantity) throw reject(Outcome.INSUFFICIENT_LIQUIDITY);
        long reservation = side == OrderSide.BUY ? Math.multiplyExact(price, quantity) : 0;
        if (reservation > 0) {
            var debit = database.updateBalance(c, owner, -reservation);
            if (debit == MarketplaceDatabase.ApplyResult.INSUFFICIENT_FUNDS) throw reject(Outcome.INSUFFICIENT_FUNDS);
            if (debit != MarketplaceDatabase.ApplyResult.APPLIED) throw new SQLException("Could not reserve standardized Bazaar coins");
            database.ledger(c, owner, -reservation, "Bazaar standardized buy escrow", now, "bazaar-standard:" + operation + ":reserve");
        }
        try (var insert = c.prepareStatement("INSERT INTO standardized_bazaar_orders(order_id,profile_uuid,product_id,side,unit_price_minor,original_quantity,remaining_quantity,filled_quantity,reserved_minor,status,created_at,updated_at) VALUES(?,?,?,?,?,?,?,0,?,'OPEN',?,?)")) {
            insert.setString(1, id.toString()); insert.setString(2, owner.toString()); insert.setString(3, product); insert.setString(4, side.name());
            insert.setLong(5, price); insert.setLong(6, quantity); insert.setLong(7, quantity); insert.setLong(8, reservation); insert.setLong(9, now); insert.setLong(10, now); insert.executeUpdate();
        }
        for (StandardItem item : items) {
            try (var reserve = c.prepareStatement("INSERT INTO market_item_reservations(instance_id,market,listing_id,owner_uuid,state,created_at,updated_at) VALUES(?,'BAZAAR',?,?,'LISTED',?,?)")) {
                reserve.setString(1, item.instanceId()); reserve.setString(2, id.toString()); reserve.setString(3, owner.toString()); reserve.setLong(4, now); reserve.setLong(5, now); reserve.executeUpdate();
            } catch (SQLException duplicate) {
                if (duplicate.getMessage() != null && duplicate.getMessage().toLowerCase(java.util.Locale.ROOT).contains("constraint")) throw reject(Outcome.INVALID);
                throw duplicate;
            }
            try (var insert = c.prepareStatement("INSERT INTO standardized_bazaar_order_items(instance_id,order_id,item_snapshot,item_sha256,state,updated_at) VALUES(?,?,?,?, 'ESCROW',?)")) {
                insert.setString(1, item.instanceId()); insert.setString(2, id.toString()); insert.setBytes(3, item.snapshot()); insert.setString(4, sha256(item.snapshot())); insert.setLong(5, now); insert.executeUpdate();
            }
        }
        match(c, id, owner, product, side, price, quantity, now, operation);
        try (var query = c.prepareStatement("SELECT remaining_quantity,filled_quantity,status FROM standardized_bazaar_orders WHERE order_id=?")) {
            query.setString(1, id.toString()); try (var row = query.executeQuery()) { row.next(); long remain = row.getLong(1), filled = row.getLong(2); String status = row.getString(3);
                return new Result(status.equals("FILLED") ? Outcome.FILLED : filled > 0 ? Outcome.PARTIAL : Outcome.OPENED, id, filled, remain); }
        }
    }

    private long available(Connection c,UUID owner,String product,OrderSide side,long price)throws SQLException{
        String opposite=side==OrderSide.BUY?"SELL":"BUY";String comparison=side==OrderSide.BUY?"unit_price_minor<=?":"unit_price_minor>=?";
        try(var q=c.prepareStatement("SELECT COALESCE(SUM(remaining_quantity),0) FROM standardized_bazaar_orders WHERE product_id=? AND side=? AND status IN ('OPEN','PARTIAL') AND profile_uuid<>? AND "+comparison)){q.setString(1,product);q.setString(2,opposite);q.setString(3,owner.toString());q.setLong(4,price);try(var r=q.executeQuery()){r.next();return r.getLong(1);}}
    }

    private void match(Connection c, UUID incomingId, UUID owner, String product, OrderSide side, long limit,
                       long originalQuantity, long now, String operation) throws SQLException {
        String opposite = side == OrderSide.BUY ? "SELL" : "BUY";
        String crossing = side == OrderSide.BUY ? "unit_price_minor<=?" : "unit_price_minor>=?";
        String sort = side == OrderSide.BUY ? "unit_price_minor ASC" : "unit_price_minor DESC";
        List<OrderRow> resting = new ArrayList<>();
        try (var q = c.prepareStatement("SELECT order_id,profile_uuid,side,unit_price_minor,remaining_quantity FROM standardized_bazaar_orders WHERE product_id=? AND side=? AND status IN ('OPEN','PARTIAL') AND profile_uuid<>? AND " + crossing + " ORDER BY " + sort + ",created_at,order_id")) {
            q.setString(1, product); q.setString(2, opposite); q.setString(3, owner.toString()); q.setLong(4, limit);
            try (var rows = q.executeQuery()) { while (rows.next()) resting.add(new OrderRow(UUID.fromString(rows.getString(1)), UUID.fromString(rows.getString(2)), OrderSide.valueOf(rows.getString(3)), rows.getLong(4), rows.getLong(5))); }
        }
        long filled = 0;
        for (OrderRow counter : resting) {
            while (filled < originalQuantity && counter.remaining > 0) {
                UUID buyId = side == OrderSide.BUY ? incomingId : counter.id;
                UUID sellId = side == OrderSide.SELL ? incomingId : counter.id;
                OrderRow buy = side == OrderSide.BUY ? new OrderRow(incomingId, owner, OrderSide.BUY, limit, originalQuantity - filled) : load(c, buyId);
                OrderRow sell = side == OrderSide.SELL ? new OrderRow(incomingId, owner, OrderSide.SELL, limit, originalQuantity - filled) : load(c, sellId);
                if (buy == null || sell == null || buy.remaining <= 0 || sell.remaining <= 0) break;
                long execution = counter.price, buyerLimit = buy.price;
                String instance = side == OrderSide.SELL ? firstEscrowItem(c, incomingId) : firstEscrowItem(c, counter.id);
                if (instance == null) break;
                String claimId = UUID.randomUUID().toString();
                long refund = buyerLimit - execution;
                fillOrder(c, buyId, 1, buyerLimit, now); fillOrder(c, sellId, 1, 0, now);
                long gross = execution;
                if (refund > 0) {
                    if (database.updateBalance(c, buy.owner, refund) != MarketplaceDatabase.ApplyResult.APPLIED) throw new SQLException("Price improvement refund failed");
                    database.ledger(c, buy.owner, refund, "Bazaar standardized price improvement", now, "bazaar-standard:" + operation + ":" + claimId + ":refund");
                }
                long fee = MoneyMath.fee(gross, 100), net = gross - fee;
                if (net > 0) {
                    if (database.updateBalance(c, sell.owner, net) != MarketplaceDatabase.ApplyResult.APPLIED) throw new SQLException("Standardized Bazaar seller credit failed");
                    database.ledger(c, sell.owner, net, "Bazaar standardized sale proceeds", now, "bazaar-standard:" + operation + ":" + claimId + ":seller");
                }
                try (var claim = c.prepareStatement("INSERT INTO standardized_bazaar_claims(claim_id,profile_uuid,order_id,instance_id,item_snapshot,source_id,created_at) SELECT ?,?,?,instance_id,item_snapshot,?,? FROM standardized_bazaar_order_items WHERE instance_id=? AND state='ESCROW'")) {
                    claim.setString(1, claimId); claim.setString(2, buy.owner.toString()); claim.setString(3, sellId.toString()); claim.setString(4, "fill:" + claimId); claim.setLong(5, now); claim.setString(6, instance.toString());
                    if (claim.executeUpdate() != 1) throw new SQLException("Escrowed standardized item disappeared");
                }
                try (var update = c.prepareStatement("UPDATE standardized_bazaar_order_items SET state='CLAIMABLE',claim_id=?,updated_at=? WHERE instance_id=? AND state='ESCROW'")) {
                    update.setString(1, claimId); update.setLong(2, now); update.setString(3, instance.toString()); if (update.executeUpdate() != 1) throw new SQLException("Item escrow changed during fill");
                }
                try (var update = c.prepareStatement("UPDATE market_item_reservations SET listing_id=?,owner_uuid=?,state='CLAIMABLE',updated_at=? WHERE instance_id=? AND market='BAZAAR'")) {
                    update.setString(1, claimId); update.setString(2, buy.owner.toString()); update.setLong(3, now); update.setString(4, instance.toString()); if (update.executeUpdate() != 1) throw new SQLException("Unique item reservation missing");
                }
                counter = new OrderRow(counter.id, counter.owner, counter.side, counter.price, counter.remaining - 1); filled++;
            }
            if (filled >= originalQuantity) break;
        }
    }

    private void fillOrder(Connection c, UUID id, long quantity, long limit, long now) throws SQLException {
        String side; long reserve;
        try (var q = c.prepareStatement("SELECT side,reserved_minor FROM standardized_bazaar_orders WHERE order_id=?")) { q.setString(1, id.toString()); try (var r=q.executeQuery()){ if(!r.next()) throw new SQLException("Missing standardized order"); side=r.getString(1); reserve=r.getLong(2); } }
        long amount = side.equals("BUY") ? limit : 0;
        try (var u = c.prepareStatement("UPDATE standardized_bazaar_orders SET remaining_quantity=remaining_quantity-?,filled_quantity=filled_quantity+?,reserved_minor=?,status=CASE WHEN remaining_quantity-?=0 THEN 'FILLED' ELSE 'PARTIAL' END,updated_at=? WHERE order_id=? AND remaining_quantity>=?")) {
            u.setLong(1,quantity);u.setLong(2,quantity);u.setLong(3,Math.subtractExact(reserve,amount));u.setLong(4,quantity);u.setLong(5,now);u.setString(6,id.toString());u.setLong(7,quantity);if(u.executeUpdate()!=1)throw new SQLException("Standardized order quantity changed");
        }
    }
    private static String firstEscrowItem(Connection c, UUID order) throws SQLException {
        try(var q=c.prepareStatement("SELECT instance_id FROM standardized_bazaar_order_items WHERE order_id=? AND state='ESCROW' ORDER BY rowid LIMIT 1")){q.setString(1,order.toString());try(var r=q.executeQuery()){return r.next()?r.getString(1):null;}}
    }
    private static OrderRow load(Connection c, UUID id) throws SQLException {
        try(var q=c.prepareStatement("SELECT profile_uuid,side,unit_price_minor,remaining_quantity FROM standardized_bazaar_orders WHERE order_id=? AND status IN ('OPEN','PARTIAL')")){q.setString(1,id.toString());try(var r=q.executeQuery()){return r.next()?new OrderRow(id,UUID.fromString(r.getString(1)),OrderSide.valueOf(r.getString(2)),r.getLong(3),r.getLong(4)):null;}}
    }

    public Result cancel(UUID owner, UUID orderId, long now) throws SQLException {
        return database.transact(c -> {
            String side,status;long reserve,remaining;String product;
            try(var q=c.prepareStatement("SELECT side,status,reserved_minor,remaining_quantity,product_id FROM standardized_bazaar_orders WHERE order_id=? AND profile_uuid=?")){q.setString(1,orderId.toString());q.setString(2,owner.toString());try(var r=q.executeQuery()){if(!r.next())throw reject(Outcome.NOT_OWNER);side=r.getString(1);status=r.getString(2);reserve=r.getLong(3);remaining=r.getLong(4);product=r.getString(5);}}
            if(!status.equals("OPEN")&&!status.equals("PARTIAL"))throw reject(Outcome.NOT_OPEN);
            if(reserve>0){if(database.updateBalance(c,owner,reserve)!=MarketplaceDatabase.ApplyResult.APPLIED)throw new SQLException("Standardized buy refund failed");database.ledger(c,owner,reserve,"Bazaar standardized cancellation refund",now,"bazaar-standard:cancel:"+orderId);}
            if(side.equals("SELL")){
                try(var q=c.prepareStatement("SELECT instance_id,item_snapshot FROM standardized_bazaar_order_items WHERE order_id=? AND state='ESCROW'")){q.setString(1,orderId.toString());try(var r=q.executeQuery()){while(r.next())createClaim(c,owner,orderId,r.getString(1),r.getBytes(2),now);}}
            }
            try(var u=c.prepareStatement("UPDATE standardized_bazaar_orders SET status='CANCELLED',reserved_minor=0,updated_at=? WHERE order_id=?")){u.setLong(1,now);u.setString(2,orderId.toString());u.executeUpdate();}
            return new Result(Outcome.CANCELLED,orderId,0,0);
        });
    }
    private void createClaim(Connection c,UUID owner,UUID order,String instance,byte[] snapshot,long now)throws SQLException{
        String claim=UUID.randomUUID().toString();
        try(var i=c.prepareStatement("INSERT INTO standardized_bazaar_claims(claim_id,profile_uuid,order_id,instance_id,item_snapshot,source_id,created_at) VALUES(?,?,?,?,?,?,?)")){i.setString(1,claim);i.setString(2,owner.toString());i.setString(3,order.toString());i.setString(4,instance);i.setBytes(5,snapshot);i.setString(6,"return:"+claim);i.setLong(7,now);i.executeUpdate();}
        try(var u=c.prepareStatement("UPDATE standardized_bazaar_order_items SET state='CLAIMABLE',claim_id=?,updated_at=? WHERE instance_id=? AND state='ESCROW'")){u.setString(1,claim);u.setLong(2,now);u.setString(3,instance);if(u.executeUpdate()!=1)throw new SQLException("Escrow item missing during cancellation");}
        try(var u=c.prepareStatement("UPDATE market_item_reservations SET listing_id=?,state='CLAIMABLE',updated_at=? WHERE instance_id=? AND market='BAZAAR'")){u.setString(1,claim);u.setLong(2,now);u.setString(3,instance);u.executeUpdate();}
    }
    public List<Claim> pendingClaims(UUID owner)throws SQLException{
        try(var c=database.openConnection();var q=c.prepareStatement("SELECT claim_id,order_id,instance_id,item_snapshot FROM standardized_bazaar_claims WHERE profile_uuid=? AND claimed_at IS NULL ORDER BY created_at LIMIT 100")){q.setString(1,owner.toString());try(var r=q.executeQuery()){List<Claim> result=new ArrayList<>();while(r.next())result.add(new Claim(r.getString(1),UUID.fromString(r.getString(2)),r.getString(3),r.getBytes(4)));return List.copyOf(result);}}
    }
    public void markClaimed(UUID owner,String claimId,long now)throws SQLException{
        database.transact(c->{String instance;try(var q=c.prepareStatement("SELECT instance_id FROM standardized_bazaar_claims WHERE claim_id=? AND profile_uuid=? AND claimed_at IS NULL")){q.setString(1,claimId);q.setString(2,owner.toString());try(var r=q.executeQuery()){if(!r.next())throw new SQLException("Standardized claim missing or already delivered");instance=r.getString(1);}}
            try(var u=c.prepareStatement("UPDATE standardized_bazaar_claims SET claimed_at=? WHERE claim_id=? AND profile_uuid=? AND claimed_at IS NULL")){u.setLong(1,now);u.setString(2,claimId);u.setString(3,owner.toString());if(u.executeUpdate()!=1)throw new SQLException("Standardized claim changed");}
            try(var d=c.prepareStatement("DELETE FROM market_item_reservations WHERE market='BAZAAR' AND instance_id=?")){d.setString(1,instance);d.executeUpdate();}return null;});
    }
    public List<Long> book(String product,OrderSide side,int limit)throws SQLException{
        if(limit<1||limit>54)throw new IllegalArgumentException("Invalid book depth");
        try(var c=database.openConnection();var q=c.prepareStatement("SELECT unit_price_minor FROM standardized_bazaar_orders WHERE product_id=? AND side=? AND status IN ('OPEN','PARTIAL') ORDER BY unit_price_minor "+(side==OrderSide.BUY?"DESC":"ASC")+",created_at LIMIT ?")){q.setString(1,product);q.setString(2,side.name());q.setInt(3,limit);try(var r=q.executeQuery()){List<Long> out=new ArrayList<>();while(r.next())out.add(r.getLong(1));return List.copyOf(out);}}
    }
    public List<OrderView> myOrders(UUID owner,int limit)throws SQLException{
        if(limit<1||limit>54)throw new IllegalArgumentException("Invalid order limit");
        try(var c=database.openConnection();var q=c.prepareStatement("SELECT order_id,side,unit_price_minor,remaining_quantity,status FROM standardized_bazaar_orders WHERE profile_uuid=? ORDER BY created_at DESC LIMIT ?")){q.setString(1,owner.toString());q.setInt(2,limit);try(var r=q.executeQuery()){List<OrderView> out=new ArrayList<>();while(r.next())out.add(new OrderView(UUID.fromString(r.getString(1)),OrderSide.valueOf(r.getString(2)),r.getLong(3),r.getLong(4),r.getString(5)));return List.copyOf(out);}}
    }
    private static String sha256(byte[] bytes){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(Exception ex){throw new IllegalStateException(ex);}}
    private static boolean valid(String product,String operation){return product!=null&&!product.isBlank()&&operation!=null&&!operation.isBlank();}
    private static Rejected reject(Outcome outcome){return new Rejected(outcome);}
    private static final class Rejected extends RuntimeException{final Outcome outcome;Rejected(Outcome o){outcome=o;}}
    private record OrderRow(UUID id,UUID owner,OrderSide side,long price,long remaining){}
}
