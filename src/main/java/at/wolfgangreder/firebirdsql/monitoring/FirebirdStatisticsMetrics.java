package at.wolfgangreder.firebirdsql.monitoring;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.scheduler.Scheduled;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.firebirdsql.gds.impl.GDSType;
import org.firebirdsql.gds.impl.wire.WireGDSFactoryPlugin;
import org.firebirdsql.management.FBStatisticsManager;
import org.firebirdsql.management.StatisticsManager;

import javax.sql.DataSource;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Collects Firebird database statistics via {@code FBStatisticsManager} and exposes them as
 * Micrometer gauges.
 *
 * <p>Metric names are identical to those produced by weatherlog's
 * {@code DatabaseMonitoringServiceImpl} for backward compatibility with existing deployments.
 *
 * <p>Scheduling interval is controlled by {@code firebird.statistics.interval} (default 1h).
 * Statistics collection is expensive; do not reduce the interval significantly.
 */
@ApplicationScoped
public class FirebirdStatisticsMetrics {

    private static final Logger LOGGER =
        Logger.getLogger(FirebirdStatisticsMetrics.class.getName());

    @ConfigProperty(name = "quarkus.datasource.jdbc.url")
    String jdbcUrl;

    @ConfigProperty(name = "quarkus.datasource.username")
    String username;

    @ConfigProperty(name = "quarkus.datasource.password")
    String password;

    @Inject
    DataSource dataSource;

    @Inject
    MeterRegistry meterRegistry;

    // Package-private to allow direct inspection in tests.
    volatile FirebirdConnectionInfo connectionInfo;

    private final Map<String, Number> values = new ConcurrentHashMap<>();
    /** Keys of gauges already registered; prevents duplicate Micrometer registrations. */
    private final Set<String> registeredKeys = ConcurrentHashMap.newKeySet();

    @PostConstruct
    void init() {
        try {
            connectionInfo = JdbcUrlParser.parse(jdbcUrl);
        } catch (IllegalArgumentException ex) {
            // Preserve stacktrace for easier debugging
            LOGGER.log(Level.SEVERE,
                "Cannot parse Firebird JDBC URL — statistics collection disabled", ex);
        }
    }

    /**
     * Hourly collection trigger. Fetches statistics via {@code FBStatisticsManager} and
     * updates all gauges. Errors are logged and the cycle is skipped.
     */
    @Scheduled(every = "${firebird.statistics.interval:1h}")
    void collectStatistics() {
        if (connectionInfo == null) return;
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            FBStatisticsManager mgr = createStatisticsManager(connectionInfo);
            mgr.setLogger(bos);
            mgr.getDatabaseStatistics(
                StatisticsManager.DATA_TABLE_STATISTICS
                | StatisticsManager.INDEX_STATISTICS
                | StatisticsManager.RECORD_VERSION_STATISTICS);
            // FBServiceManager opens and closes its service connection per operation;
            // no explicit close() call is needed or available on this API.
            processStatisticsOutput(bos.toByteArray());
        } catch (IOException | SQLException ex) {
            LOGGER.log(Level.SEVERE, "Failed to collect Firebird statistics", ex);
        }
        fetchAndUpdateDbFilePages();
    }

    /**
     * Parses raw {@code FBStatisticsManager} output and registers/updates all gauges.
     * Package-private so tests can call it directly with canned data, bypassing the
     * network connection to Firebird.
     */
    void processStatisticsOutput(byte[] data) throws IOException {
        if (connectionInfo == null) return;
        List<ParsedMetric> metrics = FbStatisticsParser.parse(connectionInfo.database(), data);
        for (ParsedMetric m : metrics) {
            values.put(m.valueKey(), m.value());
            registerGauge(m);
        }
    }

    /**
     * Updates the {@code data_dbfile_pages} gauge with the given page count.
     * Package-private so tests can call it directly without a DataSource connection.
     */
    void updateDbFilePages(long pages) {
        if (connectionInfo == null) return;
        String key = connectionInfo.database() + "_data_dbfile_pages";
        String db  = connectionInfo.database();
        values.put(key, pages);
        if (registeredKeys.add(key)) {
            Gauge.builder("data_dbfile_pages",
                    () -> {
                        Number n = values.get(key);
                        return n != null ? n.doubleValue() : Double.NaN;
                    })
                .tag("dbms", "firebirdsql")
                .tag("database", db)
                .register(meterRegistry);
        }
    }

    private void registerGauge(ParsedMetric metric) {
        String key = metric.valueKey();
        if (!registeredKeys.add(key)) {
            return; // gauge already registered; values map update is sufficient
        }
        Gauge.Builder<?> builder = Gauge.builder(metric.metricName(),
            () -> {
                Number n = values.get(key);
                return n != null ? n.doubleValue() : Double.NaN;
            });
        metric.tags().forEach((k, v) -> builder.tag(k, v));
        builder.register(meterRegistry);
    }

    private void fetchAndUpdateDbFilePages() {
        if (connectionInfo == null) return;
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT MON$PAGES FROM MON$DATABASE")) {
            if (rs.next()) {
                updateDbFilePages(rs.getLong(1));
            }
        } catch (SQLException ex) {
            LOGGER.log(Level.SEVERE,
                "Failed to collect data_dbfile_pages from MON$DATABASE", ex);
        }
    }

    private FBStatisticsManager createStatisticsManager(FirebirdConnectionInfo info) {
        FBStatisticsManager mgr = new FBStatisticsManager(
            GDSType.getType(WireGDSFactoryPlugin.PURE_JAVA_TYPE_NAME));
        mgr.setCharSet("UTF-8");
        mgr.setUser(username);
        mgr.setPassword(password);
        mgr.setServerName(info.host());
        mgr.setPortNumber(info.port());
        mgr.setDatabase(info.database());
        return mgr;
    }
}
