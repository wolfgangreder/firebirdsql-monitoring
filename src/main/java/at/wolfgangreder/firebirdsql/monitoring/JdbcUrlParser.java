package at.wolfgangreder.firebirdsql.monitoring;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class JdbcUrlParser {

    // Matches: jdbc:firebird://host[:port]/database-path
    // The database path may start with / for absolute paths (double-slash in URL = single leading slash in path).
    private static final Pattern PATTERN =
        Pattern.compile("jdbc:firebird://([^:/]+)(?::(\\d+))?/(.+)");

    private JdbcUrlParser() {}

    /**
     * Parses a Jaybird JDBC URL into its connection components.
     *
     * @param url JDBC URL of the form {@code jdbc:firebird://host[:port]/database}
     * @return parsed connection info
     * @throws IllegalArgumentException if the URL is null or does not match the expected format
     */
    public static FirebirdConnectionInfo parse(String url) {
        if (url == null) {
            throw new IllegalArgumentException("JDBC URL must not be null");
        }
        Matcher m = PATTERN.matcher(url);
        if (!m.matches()) {
            throw new IllegalArgumentException("Unrecognised Firebird JDBC URL: " + url);
        }
        String host = m.group(1);
        int port = m.group(2) != null ? Integer.parseInt(m.group(2)) : 3050;
        String database = m.group(3);
        return new FirebirdConnectionInfo(host, port, database);
    }
}
