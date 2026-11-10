package at.or.reder.firebirdsql.monitoring;

import at.or.reder.firebirdsql.monitoring.support.TarpitServer;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

/**
 * A database that accepts the TCP connection but never answers must not hang a request thread
 * forever. The JDBC socket timeout from {@code application.properties} must be applied to the
 * datasource connections.
 */
@QuarkusTest
@TestProfile(JdbcTimeoutTest.HungDatabaseProfile.class)
class JdbcTimeoutTest {

    private static final TarpitServer TARPIT = new TarpitServer();

    public static class HungDatabaseProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            // The pool must not give up before the driver's socket timeout fires, otherwise
            // this test would pass without any socket timeout configured.
            return Map.of(
                "quarkus.datasource.jdbc.url",
                "jdbc:firebird://127.0.0.1:" + TARPIT.port() + "//tmp/tarpit.fdb",
                "quarkus.datasource.jdbc.acquisition-timeout", "PT60S");
        }
    }

    @TestHTTPResource("/monitoring/database")
    URL databaseUrl;

    @Test
    void requestAgainstHungDatabaseFailsInsteadOfHanging() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(databaseUrl.toURI())
                .timeout(Duration.ofSeconds(30)).GET().build();

        HttpResponse<String> response = HttpClient.newHttpClient()
                .send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("\"DOWN\""), response.body());
    }
}
