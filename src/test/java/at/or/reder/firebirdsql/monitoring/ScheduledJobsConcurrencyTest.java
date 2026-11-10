package at.or.reder.firebirdsql.monitoring;

import at.or.reder.firebirdsql.monitoring.statistics.FirebirdStatisticsMetrics;
import io.quarkus.scheduler.Scheduled;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

/**
 * Scheduled jobs call the database. With the default concurrency (PROCEED) a job that hangs
 * on a dead connection piles up one more stuck worker thread per tick; SKIP prevents that.
 */
class ScheduledJobsConcurrencyTest {

    @Test
    void allScheduledJobsSkipOverlappingExecutions() {
        List<Class<?>> beans = List.of(
            FirebirdDatabaseMetrics.class,
            FirebirdStatisticsMetrics.class,
            FirebirdConnectivityMonitor.class);

        for (Class<?> bean : beans) {
            List<Method> jobs = Arrays.stream(bean.getDeclaredMethods())
                .filter(m -> m.isAnnotationPresent(Scheduled.class))
                .toList();
            assertFalse(jobs.isEmpty(), bean.getSimpleName() + " should have a @Scheduled job");
            for (Method job : jobs) {
                assertEquals(Scheduled.ConcurrentExecution.SKIP,
                    job.getAnnotation(Scheduled.class).concurrentExecution(),
                    bean.getSimpleName() + "." + job.getName() + " must use concurrentExecution=SKIP");
            }
        }
    }
}
