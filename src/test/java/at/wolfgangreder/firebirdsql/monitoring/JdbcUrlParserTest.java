package at.wolfgangreder.firebirdsql.monitoring;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class JdbcUrlParserTest {

    @Test
    void parse_absolutePathWithExplicitPort() {
        FirebirdConnectionInfo info =
            JdbcUrlParser.parse("jdbc:firebird://localhost:3050//var/lib/firebird/data/test.fdb");
        assertEquals("localhost", info.host());
        assertEquals(3050, info.port());
        assertEquals("/var/lib/firebird/data/test.fdb", info.database());
    }

    @Test
    void parse_relativePathWithNonStandardPort() {
        FirebirdConnectionInfo info =
            JdbcUrlParser.parse("jdbc:firebird://db.host:3051/data/test.fdb");
        assertEquals("db.host", info.host());
        assertEquals(3051, info.port());
        assertEquals("data/test.fdb", info.database());
    }

    @Test
    void parse_noPortDefaultsTo3050() {
        FirebirdConnectionInfo info =
            JdbcUrlParser.parse("jdbc:firebird://myhost//path/to/db.fdb");
        assertEquals("myhost", info.host());
        assertEquals(3050, info.port());
        assertEquals("/path/to/db.fdb", info.database());
    }

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
}
