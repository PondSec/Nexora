package network.nexora.trading;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
class RegistryCommodityTest {
    @TempDir Path temp;
    @Test void sameVisualMaterialHasDistinctCanonicalAssetsAndLargeClaimsAreBounded()throws Exception {
        Path file=temp.resolve("market.sqlite");var seller=UUID.randomUUID();var buyer=UUID.randomUUID();
        try(var c=DriverManager.getConnection("jdbc:sqlite:"+file);var s=c.createStatement()){
            s.execute("CREATE TABLE profiles(uuid TEXT PRIMARY KEY,balance_minor INTEGER NOT NULL CHECK(balance_minor>=0))");
            s.execute("CREATE TABLE economy_ledger(id TEXT PRIMARY KEY,uuid TEXT,delta_minor INTEGER,reason TEXT,created_at INTEGER)");
            try(var p=c.prepareStatement("INSERT INTO profiles VALUES(?,1000000)")){p.setString(1,seller.toString());p.executeUpdate();p.setString(1,buyer.toString());p.executeUpdate();}
        }
        var database=new MarketplaceDatabase(file);var bazaar=new BazaarOrderService(database);
        assertTrue(bazaar.registerProduct("nexora:enchanted_coal","nexora:enchanted_coal",1));
        assertTrue(bazaar.registerProduct("nexora:enchanted_wheat","nexora:enchanted_wheat",1));
        assertEquals(BazaarOrderService.Outcome.OPENED,bazaar.place(seller,"nexora:enchanted_coal",OrderSide.SELL,10,200,false,"sell",2).outcome());
        assertEquals(BazaarOrderService.Outcome.FILLED,bazaar.place(buyer,"nexora:enchanted_coal",OrderSide.BUY,10,200,true,"buy",3).outcome());
        var claim=bazaar.pendingItemClaims(buyer).getFirst();assertEquals(200,claim.quantity());
        assertNull(bazaar.claimPortion(seller,claim.claimId(),64,4));
        var portion=bazaar.claimPortion(buyer,claim.claimId(),64,4);assertEquals(64,portion.quantity());
        assertEquals(200,bazaar.pendingItemClaims(buyer).stream().mapToLong(BazaarOrderService.ItemClaim::quantity).sum());
        bazaar.markClaimed(buyer,portion.claimId(),5);
        assertThrows(java.sql.SQLException.class,()->bazaar.markClaimed(buyer,portion.claimId(),6));
        var reopened=new BazaarOrderService(new MarketplaceDatabase(file));
        assertEquals(136,reopened.pendingItemClaims(buyer).stream().mapToLong(BazaarOrderService.ItemClaim::quantity).sum());
        assertTrue(reopened.pendingItemClaims(seller).isEmpty());
    }
}
