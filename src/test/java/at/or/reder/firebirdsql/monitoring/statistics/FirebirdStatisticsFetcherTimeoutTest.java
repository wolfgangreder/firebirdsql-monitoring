package at.or.reder.firebirdsql.monitoring.statistics;

import at.or.reder.firebirdsql.monitoring.support.TarpitServer;
import java.io.IOException;
import java.sql.SQLException;
import java.time.Duration;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

/**
 * The statistics fetch uses the Firebird service manager, which does not go through the
 * connection pool. It needs its own network timeouts so a hung server cannot block a
 * thread (scheduler or HTTP worker) forever.
 */
class FirebirdStatisticsFetcherTimeoutTest {

    @Test
    void fetchAgainstHungServerFailsWithinSocketTimeout() {
        try (TarpitServer tarpit = new TarpitServer()) {
            FirebirdStatisticsFetcher fetcher = new FirebirdStatisticsFetcher();
            fetcher.jdbcUrl = "jdbc:firebird://127.0.0.1:" + tarpit.port() + "//tmp/tarpit.fdb";
            fetcher.username = "sysdba";
            fetcher.password = "masterkey";
            fetcher.soTimeout = Duration.ofSeconds(1);
            fetcher.connectTimeout = Duration.ofSeconds(2);
            fetcher.init();

            assertTimeoutPreemptively(Duration.ofSeconds(15), () ->
                assertThrows(Exception.class, fetcher::fetchStatistics));
        }
    }
}
