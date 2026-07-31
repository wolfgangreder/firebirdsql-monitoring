package at.wolfgangreder.firebirdsql.monitoring;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.is;

/**
 * Integration tests for the monitoring application.
 *
 * These tests use the {@code %test} application profile (src/test/resources/application.properties)
 * which disables the Firebird datasource health check and the scheduler so that a live
 * Firebird database is not required to run the test suite.
 *
 * Endpoints that touch the database (e.g. /monitoring/database) still return HTTP 200
 * but will carry an {@code error} field in the JSON response when no database is available.
 */
@QuarkusTest
class MonitoringResourceTest {

    // ------------------------------------------------------------------
    // Prometheus metrics endpoint (no database required)
    // ------------------------------------------------------------------

    @Test
    void metricsEndpointReturns200() {
        given()
                .when().get("/q/metrics")
                .then()
                .statusCode(200)
                .contentType(anyOf(containsString("text/plain"), containsString("openmetrics")));
    }

    @Test
    void metricsResponseContainsFirebirdGauges() {
        given()
                .when().get("/q/metrics")
                .then()
                .statusCode(200)
                .body(containsString("firebird_connections_active"));
    }

    // ------------------------------------------------------------------
    // Health probes (Kubernetes liveness / readiness)
    // ------------------------------------------------------------------

    @Test
    void livenessProbeReturnsUp() {
        given()
                .when().get("/q/health/live")
                .then()
                .statusCode(200)
                .body("status", equalTo("UP"));
    }

    @Test
    void readinessProbeResponds() {
        // Readiness includes the DB check; with no real Firebird it returns 503.
        given()
                .when().get("/q/health/ready")
                .then()
                .statusCode(anyOf(is(200), is(503)));
    }

    @Test
    void healthRootResponds() {
        given()
                .when().get("/q/health")
                .then()
                .statusCode(anyOf(is(200), is(503)));
    }

    // ------------------------------------------------------------------
    // Monitoring REST endpoints (gracefully handle missing database)
    // ------------------------------------------------------------------

    @Test
    void databaseEndpointAlwaysReturnsJson() {
        given()
                .when().get("/monitoring/database")
                .then()
                .statusCode(200)
                .body("status", notNullValue());
    }

    @Test
    void connectionsEndpointAlwaysReturnsJson() {
        given()
                .when().get("/monitoring/connections")
                .then()
                .statusCode(200)
                .body("count", notNullValue());
    }

    @Test
    void transactionsEndpointAlwaysReturnsJson() {
        given()
                .when().get("/monitoring/transactions")
                .then()
                .statusCode(200)
                .body("count", notNullValue());
    }
}
