package at.wolfgangreder.firebirdsql.monitoring;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Readiness;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

/**
 * Readiness health check for Kubernetes.
 * Verifies the application can reach the Firebird database.
 * Used by Kubernetes readiness probes to control traffic routing.
 */
@Readiness
@ApplicationScoped
public class FirebirdReadinessCheck implements HealthCheck {

    @Inject
    DataSource dataSource;

    @Override
    public HealthCheckResponse call() {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT 1 FROM RDB$DATABASE")) {
            if (rs.next()) {
                return HealthCheckResponse.up("firebird-database");
            }
            return HealthCheckResponse.down("firebird-database");
        } catch (Exception e) {
            return HealthCheckResponse.builder()
                    .name("firebird-database")
                    .down()
                    .withData("error", e.getMessage())
                    .build();
        }
    }
}
