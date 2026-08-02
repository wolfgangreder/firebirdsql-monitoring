package at.or.reder.firebirdsql.monitoring.statistics;

import at.or.reder.firebirdsql.monitoring.statistics.FirebirdStatisticsFetcher;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.containsString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

/**
 * Tests {@code GET /statistics} using a mocked {@link FirebirdStatisticsFetcher}
 * to cover both the success and error paths without requiring a live Firebird server.
 */
@QuarkusTest
class FbStatisticsResourceMockTest {

    private static final String SAMPLE_STATS =
        "Database header page information:\n"
        + "\tFlags\t\t\t0\n"
        + "\tGeneration\t\t\t42\n"
        + "\n"
        + "Analyzing database pages ...\n"
        + "\n";

    @InjectMock
    FirebirdStatisticsFetcher fetcher;

    @Test
    void statisticsEndpointReturns200WithRawOutput() throws Exception {
        when(fetcher.fetchStatistics())
            .thenReturn(SAMPLE_STATS.getBytes(StandardCharsets.UTF_8));

        given()
            .when().get("/statistics")
            .then()
            .statusCode(200)
            .contentType(containsString("text/plain"))
            .body(containsString("Database header page information:"))
            .body(containsString("Generation"));
    }

    @Test
    void statisticsEndpointReturns503OnIOException() throws Exception {
        doThrow(new IOException("Firebird unreachable"))
            .when(fetcher).fetchStatistics();

        given()
            .when().get("/statistics")
            .then()
            .statusCode(503)
            .body(containsString("Firebird unreachable"));
    }
}
