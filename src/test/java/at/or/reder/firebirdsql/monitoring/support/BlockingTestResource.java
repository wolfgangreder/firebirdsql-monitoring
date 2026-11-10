package at.or.reder.firebirdsql.monitoring.support;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Test-only endpoint that occupies a worker thread until released. Used to simulate a
 * saturated worker pool (e.g. threads stuck on a dead database connection).
 */
@Path("/test/block")
public class BlockingTestResource {

    public static final AtomicInteger IN_FLIGHT = new AtomicInteger();
    public static volatile CountDownLatch release = new CountDownLatch(1);

    public static void reset() {
        IN_FLIGHT.set(0);
        release = new CountDownLatch(1);
    }

    @GET
    @Produces(MediaType.TEXT_PLAIN)
    public String block() throws InterruptedException {
        IN_FLIGHT.incrementAndGet();
        try {
            release.await(60, TimeUnit.SECONDS);
            return "released";
        } finally {
            IN_FLIGHT.decrementAndGet();
        }
    }
}
