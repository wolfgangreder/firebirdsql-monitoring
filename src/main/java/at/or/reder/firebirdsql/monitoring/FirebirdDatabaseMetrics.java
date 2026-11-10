package at.or.reder.firebirdsql.monitoring;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Collects Firebird database metrics from the MON$ monitoring tables
 * and exposes them as Micrometer gauges for Prometheus scraping.
 *
 * <p>Each section (connections, transactions, statements, io, memory) can be
 * independently enabled or disabled via config properties. Disabled sections
 * register no gauges and perform no scheduled DB queries. On-demand REST
 * endpoints in {@link MonitoringResource} are unaffected by these flags and
 * never touch the registered gauges.
 */
@ApplicationScoped
public class FirebirdDatabaseMetrics {

    private static final Logger LOGGER = Logger.getLogger(FirebirdDatabaseMetrics.class.getName());

    @Inject
    DataSource dataSource;

    @Inject
    MeterRegistry registry;

    // -------------------------------------------------------------------------
    // Per-section switches
    // -------------------------------------------------------------------------

    @ConfigProperty(name = "firebird.monitoring.connections.enabled", defaultValue = "true")
    boolean connectionsEnabled;

    @ConfigProperty(name = "firebird.monitoring.transactions.enabled", defaultValue = "true")
    boolean transactionsEnabled;

    @ConfigProperty(name = "firebird.monitoring.statements.enabled", defaultValue = "true")
    boolean statementsEnabled;

    @ConfigProperty(name = "firebird.monitoring.io.enabled", defaultValue = "true")
    boolean ioEnabled;

    @ConfigProperty(name = "firebird.monitoring.memory.enabled", defaultValue = "true")
    boolean memoryEnabled;

    // -------------------------------------------------------------------------
    // Gauge backing values
    // -------------------------------------------------------------------------

    private final AtomicLong activeConnections = new AtomicLong(0);
    private final AtomicLong activeTransactions = new AtomicLong(0);
    private final AtomicLong activeStatements  = new AtomicLong(0);
    private final AtomicLong pageFetches       = new AtomicLong(0);
    private final AtomicLong pageReads         = new AtomicLong(0);
    private final AtomicLong pageWrites        = new AtomicLong(0);
    private final AtomicLong pageMarks         = new AtomicLong(0);
    private final AtomicLong memoryUsed        = new AtomicLong(0);
    private final AtomicLong memoryAllocated   = new AtomicLong(0);

    // -------------------------------------------------------------------------
    // Startup – register gauges for enabled sections only
    // -------------------------------------------------------------------------

    void onStart(@Observes StartupEvent ev) {
        if (connectionsEnabled) {
            registerConnectionGauges();
        }
        if (transactionsEnabled) {
            registerTransactionGauges();
        }
        if (statementsEnabled) {
            registerStatementGauges();
        }
        if (ioEnabled) {
            registerIoGauges();
        }
        if (memoryEnabled) {
            registerMemoryGauges();
        }
    }

    private void registerConnectionGauges() {
        Gauge.builder("firebird.connections.active", activeConnections, AtomicLong::get)
                .description("Number of active database connections (excluding monitoring connection)")
                .register(registry);
    }

    private void registerTransactionGauges() {
        Gauge.builder("firebird.transactions.active", activeTransactions, AtomicLong::get)
                .description("Number of active transactions")
                .register(registry);
    }

    private void registerStatementGauges() {
        Gauge.builder("firebird.statements.active", activeStatements, AtomicLong::get)
                .description("Number of active statements in other connections")
                .register(registry);
    }

    private void registerIoGauges() {
        Gauge.builder("firebird.io.page_fetches", pageFetches, AtomicLong::get)
                .description("Total number of page fetches at database level")
                .baseUnit("pages")
                .register(registry);
        Gauge.builder("firebird.io.page_reads", pageReads, AtomicLong::get)
                .description("Total number of page reads from disk at database level")
                .baseUnit("pages")
                .register(registry);
        Gauge.builder("firebird.io.page_writes", pageWrites, AtomicLong::get)
                .description("Total number of page writes to disk at database level")
                .baseUnit("pages")
                .register(registry);
        Gauge.builder("firebird.io.page_marks", pageMarks, AtomicLong::get)
                .description("Total number of dirty (marked) pages at database level")
                .baseUnit("pages")
                .register(registry);
    }

    private void registerMemoryGauges() {
        Gauge.builder("firebird.memory.used_bytes", memoryUsed, AtomicLong::get)
                .description("Memory currently in use by the database engine")
                .baseUnit("bytes")
                .register(registry);
        Gauge.builder("firebird.memory.allocated_bytes", memoryAllocated, AtomicLong::get)
                .description("Total memory allocated by the database engine")
                .baseUnit("bytes")
                .register(registry);
    }

    // -------------------------------------------------------------------------
    // Scheduled collection – only enabled sections query the DB
    // -------------------------------------------------------------------------

    @Scheduled(every = "${firebird.monitoring.interval:30s}",
               identity = "firebird-monitoring-collect",
               concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void collectMetrics() {
        if (!connectionsEnabled && !transactionsEnabled && !statementsEnabled
                && !ioEnabled && !memoryEnabled) {
            return;
        }
        try (Connection conn = dataSource.getConnection()) {
            if (connectionsEnabled) {
                collectConnectionStats(conn);
            }
            if (transactionsEnabled) {
                collectTransactionStats(conn);
            }
            if (statementsEnabled) {
                collectStatementStats(conn);
            }
            if (ioEnabled) {
                collectIoStats(conn);
            }
            if (memoryEnabled) {
                collectMemoryStats(conn);
            }
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to collect Firebird metrics: {0}", e.getMessage());
        }
    }

    private void collectConnectionStats(Connection conn) throws Exception {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT COUNT(*) FROM MON$ATTACHMENTS WHERE MON$ATTACHMENT_ID <> CURRENT_CONNECTION")) {
            if (rs.next()) {
                activeConnections.set(rs.getLong(1));
            }
        }
    }

    private void collectTransactionStats(Connection conn) throws Exception {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM MON$TRANSACTIONS")) {
            if (rs.next()) {
                activeTransactions.set(rs.getLong(1));
            }
        }
    }

    private void collectStatementStats(Connection conn) throws Exception {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT COUNT(*) FROM MON$STATEMENTS"
                     + " WHERE MON$ATTACHMENT_ID <> CURRENT_CONNECTION"
                     + " AND MON$STATE = 1")) {
            if (rs.next()) {
                activeStatements.set(rs.getLong(1));
            }
        }
    }

    private void collectIoStats(Connection conn) throws Exception {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT MON$PAGE_FETCHES, MON$PAGE_READS, MON$PAGE_WRITES, MON$PAGE_MARKS"
                     + " FROM MON$IO_STATS WHERE MON$STAT_GROUP = 0")) {
            if (rs.next()) {
                pageFetches.set(rs.getLong(1));
                pageReads.set(rs.getLong(2));
                pageWrites.set(rs.getLong(3));
                pageMarks.set(rs.getLong(4));
            }
        }
    }

    private void collectMemoryStats(Connection conn) throws Exception {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT MON$MEMORY_USED, MON$MEMORY_ALLOCATED"
                     + " FROM MON$MEMORY_USAGE WHERE MON$STAT_GROUP = 0")) {
            if (rs.next()) {
                memoryUsed.set(rs.getLong(1));
                memoryAllocated.set(rs.getLong(2));
            }
        }
    }
}
