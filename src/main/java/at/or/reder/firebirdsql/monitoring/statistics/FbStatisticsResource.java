package at.or.reder.firebirdsql.monitoring.statistics;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;

/**
 * REST endpoint that exposes the raw {@code FBStatisticsManager} output as plain text.
 *
 * <p>Statistics are fetched live on each request. Collection is expensive (full table/index
 * scan on the Firebird server); callers should avoid polling this endpoint frequently.
 *
 * <p>Returns {@code 503 Service Unavailable} when the Firebird server is unreachable,
 * credentials are incorrect, or the JDBC URL was not parseable at startup.
 */
@Path("/statistics")
public class FbStatisticsResource {

    @Inject
    FirebirdStatisticsFetcher fetcher;

    @GET
    @Produces(MediaType.TEXT_PLAIN)
    public Response getStatisticsOutput() {
        try {
            byte[] bytes = fetcher.fetchStatistics();
            return Response.ok(new String(bytes, StandardCharsets.UTF_8)).build();
        } catch (IOException | SQLException | IllegalStateException ex) {
            return Response.status(503).entity(ex.getMessage()).build();
        }
    }
}
