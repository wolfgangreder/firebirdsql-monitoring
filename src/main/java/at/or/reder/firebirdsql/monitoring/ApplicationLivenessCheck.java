package at.or.reder.firebirdsql.monitoring;

import io.smallrye.health.api.AsyncHealthCheck;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Liveness;

/**
 * Liveness health check for Kubernetes.
 * Reports UP as long as the application JVM is running.
 * Used by Kubernetes liveness probes to detect deadlocked or hung applications.
 *
 * <p>Implemented as {@link AsyncHealthCheck} so it runs on the I/O thread instead of the
 * worker pool and stays answerable when all worker threads are busy or stuck. It must never
 * depend on the database: a database outage must not cause the kubelet to restart the pod.
 */
@Liveness
@ApplicationScoped
public class ApplicationLivenessCheck implements AsyncHealthCheck {

    @Override
    public Uni<HealthCheckResponse> call() {
        return Uni.createFrom().item(HealthCheckResponse.up("application"));
    }
}
