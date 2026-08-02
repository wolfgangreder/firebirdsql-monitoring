package at.or.reder.firebirdsql.monitoring;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Liveness;

/**
 * Liveness health check for Kubernetes.
 * Reports UP as long as the application JVM is running.
 * Used by Kubernetes liveness probes to detect deadlocked or hung applications.
 */
@Liveness
@ApplicationScoped
public class ApplicationLivenessCheck implements HealthCheck {

    @Override
    public HealthCheckResponse call() {
        return HealthCheckResponse.up("application");
    }
}
