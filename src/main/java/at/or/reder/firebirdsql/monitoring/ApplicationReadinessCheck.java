package at.or.reder.firebirdsql.monitoring;

import io.smallrye.health.api.AsyncHealthCheck;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Readiness;

/**
 * Readiness health check for Kubernetes.
 *
 * <p>Deliberately independent of the database: this is an exporter, and a database outage
 * must not take the pod out of service. Database reachability is reported through the
 * {@code firebird_up} metric instead and shown here as informational data only.
 *
 * <p>Implemented as {@link AsyncHealthCheck} (I/O thread) and reads cached state only, so it
 * cannot be starved by worker threads stuck on a database call.
 */
@Readiness
@ApplicationScoped
public class ApplicationReadinessCheck implements AsyncHealthCheck {

    @Inject
    FirebirdConnectivityMonitor connectivity;

    @Override
    public Uni<HealthCheckResponse> call() {
        return Uni.createFrom().item(() -> HealthCheckResponse.builder()
                .name("application")
                .up()
                .withData("firebird", connectivity.isUp() ? "UP" : "DOWN")
                .build());
    }
}
