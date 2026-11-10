package at.or.reder.firebirdsql.monitoring;

import at.or.reder.firebirdsql.monitoring.support.BlockingTestResource;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Kubernetes probes must keep answering while every worker thread is stuck (e.g. on a dead
 * database connection). Otherwise the kubelet kills a pod that is merely waiting for the DB.
 */
@QuarkusTest
@TestProfile(HealthProbeResilienceTest.TinyWorkerPoolProfile.class)
class HealthProbeResilienceTest {

    private static final int WORKERS = 2;

    public static class TinyWorkerPoolProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("quarkus.thread-pool.max-threads", String.valueOf(WORKERS));
        }
    }

    @TestHTTPResource("/")
    URL baseUrl;

    private final HttpClient client = HttpClient.newHttpClient();
    private final List<CompletableFuture<?>> blockers = new ArrayList<>();

    @BeforeEach
    void saturateWorkerPool() throws Exception {
        BlockingTestResource.reset();
        URI blockUri = baseUrl.toURI().resolve("/test/block");
        for (int i = 0; i < WORKERS + 2; i++) {
            blockers.add(client.sendAsync(HttpRequest.newBuilder(blockUri).GET().build(),
                    HttpResponse.BodyHandlers.ofString()));
        }
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (BlockingTestResource.IN_FLIGHT.get() < WORKERS && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertEquals(WORKERS, BlockingTestResource.IN_FLIGHT.get(), "worker pool must be saturated");
    }

    @AfterEach
    void releaseWorkers() {
        BlockingTestResource.release.countDown();
        blockers.forEach(CompletableFuture::join);
    }

    private HttpResponse<String> probe(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(baseUrl.toURI().resolve(path))
                .timeout(Duration.ofSeconds(2)).GET().build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void livenessAnswersWhileWorkerPoolIsSaturated() throws Exception {
        HttpResponse<String> response = probe("/q/health/live");

        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("\"UP\""), response.body());
    }

    @Test
    void readinessAnswersUpWhileWorkerPoolIsSaturated() throws Exception {
        HttpResponse<String> response = probe("/q/health/ready");

        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("\"UP\""), response.body());
    }
}
