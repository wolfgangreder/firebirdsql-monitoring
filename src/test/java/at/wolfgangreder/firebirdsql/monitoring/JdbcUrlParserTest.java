package at.wolfgangreder.firebirdsql.monitoring;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.params.provider.Arguments.arguments;

class JdbcUrlParserTest {

    // -----------------------------------------------------------------------
    // Parameterised happy-path tests — one row per URL variant
    // -----------------------------------------------------------------------

    @ParameterizedTest(name = "{0}")
    @MethodSource("validUrls")
    void parse(String url, String expectedHost, int expectedPort, String expectedDatabase) {
        FirebirdConnectionInfo info = JdbcUrlParser.parse(url);
        assertAll(
            () -> assertEquals(expectedHost,    info.host(),     "host"),
            () -> assertEquals(expectedPort,    info.port(),     "port"),
            () -> assertEquals(expectedDatabase, info.database(), "database")
        );
    }

    static Stream<Arguments> validUrls() {
        return Stream.of(
            // ---- Standard format: jdbc:firebird[sql][:<sub>]://host[:port]/<db>[?params] ----

            // Both scheme aliases, absolute path (double-slash = leading slash in db path)
            arguments("jdbc:firebird://localhost:3050//var/lib/firebird/data/test.fdb",
                      "localhost", 3050, "/var/lib/firebird/data/test.fdb"),
            arguments("jdbc:firebirdsql://localhost:3050//var/lib/firebird/data/test.fdb",
                      "localhost", 3050, "/var/lib/firebird/data/test.fdb"),

            // Relative path, non-standard port
            arguments("jdbc:firebird://db.host:3051/data/test.fdb",
                      "db.host", 3051, "data/test.fdb"),

            // No port — defaults to 3050
            arguments("jdbc:firebird://myhost//path/to/db.fdb",
                      "myhost", 3050, "/path/to/db.fdb"),
            arguments("jdbc:firebirdsql://automatix.local//var/lib/firebird/data/frodo.firebird",
                      "automatix.local", 3050, "/var/lib/firebird/data/frodo.firebird"),

            // Query parameters must be stripped from the database path
            arguments("jdbc:firebirdsql://automatix.local:3050//var/lib/firebird/data/frodo.firebird?charSet=UTF-8&defaultResultSetHoldable=true",
                      "automatix.local", 3050, "/var/lib/firebird/data/frodo.firebird"),

            // native sub-protocol, standard URL style
            arguments("jdbc:firebirdsql:native://db.host:3050//var/lib/db.fdb",
                      "db.host", 3050, "/var/lib/db.fdb"),
            arguments("jdbc:firebird:native://db.host:3050//var/lib/db.fdb",
                      "db.host", 3050, "/var/lib/db.fdb"),
            arguments("jdbc:firebirdsql:native://db.host//var/lib/db.fdb",
                      "db.host", 3050, "/var/lib/db.fdb"),

            // IPv4 literal host
            arguments("jdbc:firebirdsql://192.168.1.100:3050//var/lib/db.fdb",
                      "192.168.1.100", 3050, "/var/lib/db.fdb"),

            // IPv6 literal host — RFC 2732 bracket notation
            arguments("jdbc:firebirdsql://[::1]:3050//var/lib/db.fdb",
                      "[::1]", 3050, "/var/lib/db.fdb"),
            arguments("jdbc:firebirdsql://[::1]//var/lib/db.fdb",
                      "[::1]", 3050, "/var/lib/db.fdb"),
            arguments("jdbc:firebirdsql://[2001:db8::1]:3050//var/lib/db.fdb",
                      "[2001:db8::1]", 3050, "/var/lib/db.fdb"),

            // Windows server path (drive-letter relative to the server, not double-slash)
            arguments("jdbc:firebirdsql://win-server:3050/C:/databases/app.fdb",
                      "win-server", 3050, "C:/databases/app.fdb"),

            // ---- Legacy format: jdbc:firebird[sql][:<sub>]:host[/port]:<db> ----

            // firebird scheme, explicit port, absolute path
            arguments("jdbc:firebird:localhost/3050:/var/lib/firebird/data/test.fdb",
                      "localhost", 3050, "/var/lib/firebird/data/test.fdb"),

            // firebird scheme, no port → default 3050
            arguments("jdbc:firebird:localhost:/var/lib/firebird/data/test.fdb",
                      "localhost", 3050, "/var/lib/firebird/data/test.fdb"),

            // firebird scheme, non-standard port, relative path
            arguments("jdbc:firebird:db.host/3051:data/test.fdb",
                      "db.host", 3051, "data/test.fdb"),

            // firebirdsql scheme, explicit port, absolute path (single slash — no double-slash convention in legacy format)
            arguments("jdbc:firebirdsql:myhost/3050:/path/to/db.fdb",
                      "myhost", 3050, "/path/to/db.fdb"),

            // firebirdsql scheme, no port → default 3050
            arguments("jdbc:firebirdsql:myhost:data/relative.fdb",
                      "myhost", 3050, "data/relative.fdb"),

            // native sub-protocol, legacy URL style
            arguments("jdbc:firebirdsql:native:host.local/3050:/var/lib/db.fdb",
                      "host.local", 3050, "/var/lib/db.fdb"),
            arguments("jdbc:firebird:native:host.local/3051:/var/lib/db.fdb",
                      "host.local", 3051, "/var/lib/db.fdb"),
            arguments("jdbc:firebirdsql:native:host.local:/var/lib/db.fdb",
                      "host.local", 3050, "/var/lib/db.fdb"),

            // ---- No-host formats: embedded and local ----
            // host must be null, port must be 0

            arguments("jdbc:firebirdsql:embedded:/var/lib/firebird/data/local.fdb",
                      null, 0, "/var/lib/firebird/data/local.fdb"),
            arguments("jdbc:firebird:embedded:/var/lib/firebird/data/local.fdb",
                      null, 0, "/var/lib/firebird/data/local.fdb"),
            arguments("jdbc:firebirdsql:embedded:data/relative.fdb",
                      null, 0, "data/relative.fdb"),
            arguments("jdbc:firebirdsql:embedded:/var/lib/db.fdb?charSet=UTF-8",
                      null, 0, "/var/lib/db.fdb"),

            arguments("jdbc:firebirdsql:local:/var/lib/firebird/data/local.fdb",
                      null, 0, "/var/lib/firebird/data/local.fdb"),
            arguments("jdbc:firebird:local:/var/lib/firebird/data/local.fdb",
                      null, 0, "/var/lib/firebird/data/local.fdb"),
            arguments("jdbc:firebirdsql:local:data/relative.fdb",
                      null, 0, "data/relative.fdb")
        );
    }

    // -----------------------------------------------------------------------
    // Error cases
    // -----------------------------------------------------------------------

    @Test
    void parse_nullThrows() {
        assertThrows(IllegalArgumentException.class, () -> JdbcUrlParser.parse(null));
    }

    @Test
    void parse_wrongSchemeThrows() {
        assertThrows(IllegalArgumentException.class,
            () -> JdbcUrlParser.parse("jdbc:postgresql://localhost/test"));
    }

    @Test
    void parse_malformedUrlThrows() {
        assertThrows(IllegalArgumentException.class,
            () -> JdbcUrlParser.parse("not-a-url"));
    }

    @Test
    void parse_emptyStringThrows() {
        assertThrows(IllegalArgumentException.class,
            () -> JdbcUrlParser.parse(""));
    }
}
