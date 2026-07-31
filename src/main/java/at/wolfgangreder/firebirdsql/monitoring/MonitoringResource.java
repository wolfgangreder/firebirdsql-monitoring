package at.wolfgangreder.firebirdsql.monitoring;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * REST resource exposing Firebird database monitoring information.
 */
@Path("/monitoring")
@Produces(MediaType.APPLICATION_JSON)
public class MonitoringResource {

    @Inject
    DataSource dataSource;

    /**
     * Returns general database information from MON$DATABASE.
     */
    @GET
    @Path("/database")
    public Response getDatabaseInfo() {
        Map<String, Object> info = new HashMap<>();
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT MON$DATABASE_NAME, MON$PAGE_SIZE, MON$PAGES,"
                     + " MON$SWEEP_INTERVAL, MON$FORCED_WRITES, MON$ODS_MAJOR, MON$ODS_MINOR"
                     + " FROM MON$DATABASE")) {
            if (rs.next()) {
                info.put("databaseName", rs.getString(1).trim());
                info.put("pageSize", rs.getLong(2));
                info.put("pages", rs.getLong(3));
                info.put("sweepInterval", rs.getLong(4));
                info.put("forcedWrites", rs.getBoolean(5));
                info.put("odsVersion", rs.getInt(6) + "." + rs.getInt(7));
                info.put("status", "UP");
            } else {
                info.put("status", "DOWN");
            }
        } catch (Exception e) {
            info.put("status", "DOWN");
            info.put("error", e.getMessage());
        }
        return Response.ok(info).build();
    }

    /**
     * Returns the list of current database connections from MON$ATTACHMENTS.
     */
    @GET
    @Path("/connections")
    public Response getConnectionInfo() {
        Map<String, Object> result = new HashMap<>();
        List<Map<String, Object>> connections = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT MON$ATTACHMENT_ID, MON$SERVER_PID, MON$REMOTE_ADDRESS,"
                     + " MON$REMOTE_PROCESS, MON$TIMESTAMP, MON$STATE"
                     + " FROM MON$ATTACHMENTS"
                     + " WHERE MON$ATTACHMENT_ID <> CURRENT_CONNECTION"
                     + " ORDER BY MON$TIMESTAMP DESC ROWS 50")) {
            while (rs.next()) {
                Map<String, Object> attachment = new HashMap<>();
                attachment.put("id", rs.getLong(1));
                attachment.put("serverPid", rs.getLong(2));
                attachment.put("remoteAddress", rs.getString(3));
                attachment.put("remoteProcess", rs.getString(4));
                attachment.put("connectedSince", rs.getTimestamp(5));
                attachment.put("state", rs.getInt(6) == 1 ? "ACTIVE" : "IDLE");
                connections.add(attachment);
            }
            result.put("connections", connections);
            result.put("count", connections.size());
        } catch (Exception e) {
            result.put("error", e.getMessage());
            result.put("count", 0);
            result.put("connections", connections);
        }
        return Response.ok(result).build();
    }

    /**
     * Returns active transactions from MON$TRANSACTIONS.
     */
    @GET
    @Path("/transactions")
    public Response getTransactionInfo() {
        Map<String, Object> result = new HashMap<>();
        List<Map<String, Object>> transactions = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT MON$TRANSACTION_ID, MON$ATTACHMENT_ID,"
                     + " MON$STATE, MON$TIMESTAMP, MON$TOP_TRANSACTION,"
                     + " MON$OLDEST_TRANSACTION, MON$OLDEST_ACTIVE"
                     + " FROM MON$TRANSACTIONS"
                     + " ORDER BY MON$TIMESTAMP DESC ROWS 50")) {
            while (rs.next()) {
                Map<String, Object> tx = new HashMap<>();
                tx.put("transactionId", rs.getLong(1));
                tx.put("attachmentId", rs.getLong(2));
                tx.put("state", rs.getInt(3) == 1 ? "ACTIVE" : "IDLE");
                tx.put("startedAt", rs.getTimestamp(4));
                tx.put("topTransaction", rs.getLong(5));
                tx.put("oldestTransaction", rs.getLong(6));
                tx.put("oldestActiveTransaction", rs.getLong(7));
                transactions.add(tx);
            }
            result.put("transactions", transactions);
            result.put("count", transactions.size());
        } catch (Exception e) {
            result.put("error", e.getMessage());
            result.put("count", 0);
            result.put("transactions", transactions);
        }
        return Response.ok(result).build();
    }
}
