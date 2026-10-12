package network.nexora.core;
import network.nexora.core.api.JdbcMetrics;
import org.junit.jupiter.api.Test;
import java.sql.*;
import static org.junit.jupiter.api.Assertions.*;
class JdbcMetricsTest {
    @Test void proxiesPreserveSqlExceptionsTransactionsAndBoundedMeasurements() throws Exception {
        var source=JdbcMetrics.Source.INVENTORY;var before=JdbcMetrics.snapshots().get(source);
        JdbcMetrics.setServerThreadId(Thread.currentThread().threadId());
        try(var connection=JdbcMetrics.instrument(DriverManager.getConnection("jdbc:sqlite::memory:"),source)) {
            assertEquals(connection,connection);assertNotNull(connection.toString());
            try(var statement=connection.createStatement()){statement.execute("CREATE TABLE sample(n INTEGER)");assertThrows(SQLException.class,()->statement.execute("INSERT INTO absent VALUES(1)"));}
            connection.setAutoCommit(false);
            try(var statement=connection.prepareStatement("INSERT INTO sample VALUES(?)")){statement.setInt(1,42);assertEquals(1,statement.executeUpdate());}
            connection.rollback();connection.setAutoCommit(true);
            try(var statement=connection.prepareStatement("SELECT count(*) FROM sample");var row=statement.executeQuery()){assertTrue(row.next());assertEquals(0,row.getInt(1));}
        }finally{JdbcMetrics.setServerThreadId(-1);}
        var after=JdbcMetrics.snapshots().get(source);
        assertEquals(7,after.calls()-before.calls());assertEquals(1,after.failures()-before.failures());assertEquals(7,after.serverThreadCalls()-before.serverThreadCalls());
        assertTrue(after.maxMillis()>0);assertTrue(after.p95UpperMillis()>0);assertEquals(4,JdbcMetrics.snapshots().size());
    }
}
