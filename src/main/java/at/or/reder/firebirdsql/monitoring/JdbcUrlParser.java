package at.or.reder.firebirdsql.monitoring;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses Jaybird JDBC URLs into their connection components.
 *
 * <p>Three URL families are supported (both {@code jdbc:firebird} and {@code jdbc:firebirdsql} schemes):
 * <ol>
 *   <li><b>Standard</b>: {@code jdbc:firebird[sql][:<sub>]://host[:port]/<database>[?params]}
 *       — {@code <sub>} is an optional sub-protocol such as {@code native}.
 *       IPv6 addresses must be enclosed in brackets, e.g. {@code [::1]}.
 *       For Linux absolute paths use a double slash after the authority
 *       ({@code //host:3050//var/lib/db.fdb}).</li>
 *   <li><b>Legacy</b>: {@code jdbc:firebird[sql][:native]:host[/port]:<database>}
 *       — deprecated but still supported by Jaybird.</li>
 *   <li><b>No-host</b>: {@code jdbc:firebird[sql]:(embedded|local):<database>[?params]}
 *       — host is {@code null}, port is {@code 0}.</li>
 * </ol>
 * Query parameters are accepted in all formats but ignored — they configure the JDBC
 * connection, not the Firebird database path.
 */
public final class JdbcUrlParser {

    private static final int DEFAULT_PORT = 3050;

    // Common scheme prefix: jdbc:firebird or jdbc:firebirdsql
    private static final String SCHEME = "jdbc:firebird(?:sql)?";

    // Host token: IPv6 address in brackets OR regular hostname / IPv4 (no colon or slash)
    private static final String HOST = "(?:\\[[^\\]]+\\]|[^:/]+)";

    /**
     * Standard URL: jdbc:firebird[sql][:<sub>]://host[:port]/<database>[?params]
     * <p>Sub-protocol ([a-z0-9]+) covers: native, inet, inet4, inet6, and any future additions.
     * Groups: 1=host, 2=port (nullable), 3=database
     */
    private static final Pattern STANDARD = Pattern.compile(
        SCHEME + "(?::[a-z0-9]+)?://" +
        "(" + HOST + ")" +
        "(?::(\\d+))?/([^?]+)(?:\\?.+)?"
    );

    /**
     * No-host: jdbc:firebird[sql]:(embedded|local):<database>[?params]
     * Groups: 1=keyword (ignored), 2=database
     */
    private static final Pattern NO_HOST = Pattern.compile(
        SCHEME + ":(embedded|local):([^?]+)(?:\\?.+)?"
    );

    /**
     * Legacy: jdbc:firebird[sql][:native]:host[/port]:<database>
     * Groups: 1=host, 2=port (nullable), 3=database
     */
    private static final Pattern LEGACY = Pattern.compile(
        SCHEME + "(?::native)?:([^/:]+?)(?:/(\\d+))?:(.+)"
    );

    private JdbcUrlParser() {}

    /**
     * Parses a Jaybird JDBC URL into its connection components.
     *
     * @param url Jaybird JDBC URL in any supported format
     * @return parsed connection info; host is {@code null} and port is {@code 0} for
     *         embedded/local URLs
     * @throws IllegalArgumentException if the URL is null or does not match any known format
     */
    public static FirebirdConnectionInfo parse(String url) {
        if (url == null) {
            throw new IllegalArgumentException("JDBC URL must not be null");
        }

        // 1. Standard format — always contains "://" after the scheme+sub-protocol
        Matcher m = STANDARD.matcher(url);
        if (m.matches()) {
            String host     = m.group(1);
            int    port     = m.group(2) != null ? Integer.parseInt(m.group(2)) : DEFAULT_PORT;
            String database = m.group(3);
            return new FirebirdConnectionInfo(host, port, database);
        }

        // 2. No-host format — must be tried before LEGACY to prevent "embedded"/"local"
        //    from being misidentified as a host name
        m = NO_HOST.matcher(url);
        if (m.matches()) {
            return new FirebirdConnectionInfo(null, 0, m.group(2));
        }

        // 3. Legacy format — host[/port]:database, optional :native sub-protocol
        m = LEGACY.matcher(url);
        if (m.matches()) {
            String host     = m.group(1);
            int    port     = m.group(2) != null ? Integer.parseInt(m.group(2)) : DEFAULT_PORT;
            String database = m.group(3);
            return new FirebirdConnectionInfo(host, port, database);
        }

        throw new IllegalArgumentException("Unrecognised Firebird JDBC URL: " + url);
    }
}
