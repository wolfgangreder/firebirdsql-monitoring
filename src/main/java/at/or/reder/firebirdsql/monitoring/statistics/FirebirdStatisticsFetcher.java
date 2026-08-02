package at.or.reder.firebirdsql.monitoring.statistics;

import at.or.reder.firebirdsql.monitoring.FirebirdConnectionInfo;
import at.or.reder.firebirdsql.monitoring.JdbcUrlParser;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.sql.SQLException;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.firebirdsql.gds.impl.GDSType;
import org.firebirdsql.gds.impl.wire.WireGDSFactoryPlugin;
import org.firebirdsql.management.FBStatisticsManager;
import org.firebirdsql.management.StatisticsManager;

/**
 * Encapsulates FBStatisticsManager setup and raw statistics fetching.
 *
 * <p>Shared by {@link FirebirdStatisticsMetrics} (scheduled collection) and
 * {@link FbStatisticsResource} (on-demand HTTP endpoint) to avoid duplicating
 * connection configuration.
 */
@ApplicationScoped
public class FirebirdStatisticsFetcher {

    private static final Logger LOGGER =
        Logger.getLogger(FirebirdStatisticsFetcher.class.getName());

    @ConfigProperty(name = "quarkus.datasource.jdbc.url")
    String jdbcUrl;

    @ConfigProperty(name = "quarkus.datasource.username")
    String username;

    @ConfigProperty(name = "quarkus.datasource.password")
    String password;

    // Package-private to allow direct inspection in tests.
    volatile FirebirdConnectionInfo connectionInfo;

    @PostConstruct
    void init() {
        try {
            connectionInfo = JdbcUrlParser.parse(jdbcUrl);
        } catch (IllegalArgumentException ex) {
            LOGGER.log(Level.SEVERE,
                "Cannot parse Firebird JDBC URL — statistics collection disabled", ex);
        }
    }

    /** Returns {@code true} if the JDBC URL was parsed successfully at startup. */
    public boolean isConfigured() {
        return connectionInfo != null;
    }

    /**
     * Returns the database path from the parsed JDBC URL, or {@code null} if not configured.
     * Used as the {@code database} tag value in Micrometer gauges.
     */
    public String database() {
        return connectionInfo != null ? connectionInfo.database() : null;
    }

    /**
     * Fetches raw {@code FBStatisticsManager} output (table + index + record-version statistics).
     *
     * @return raw bytes written by FBStatisticsManager to its output stream
     * @throws IllegalStateException if the JDBC URL was not parseable at startup
     * @throws IOException           if an I/O error occurs communicating with the Firebird server
     * @throws SQLException          if the Firebird service manager reports an error
     */
    public byte[] fetchStatistics() throws IOException, SQLException {
        if (connectionInfo == null) {
            throw new IllegalStateException(
                "Firebird JDBC URL not configured or not parseable");
        }
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        FBStatisticsManager mgr = new FBStatisticsManager(
            GDSType.getType(WireGDSFactoryPlugin.PURE_JAVA_TYPE_NAME));
        mgr.setCharSet("UTF-8");
        mgr.setUser(username);
        mgr.setPassword(password);
        mgr.setServerName(connectionInfo.host());
        mgr.setPortNumber(connectionInfo.port());
        mgr.setDatabase(connectionInfo.database());
        mgr.setLogger(bos);
        mgr.getDatabaseStatistics(
            StatisticsManager.DATA_TABLE_STATISTICS
            | StatisticsManager.INDEX_STATISTICS
            | StatisticsManager.RECORD_VERSION_STATISTICS);
        return bos.toByteArray();
    }
}
