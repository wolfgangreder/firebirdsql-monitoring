package at.or.reder.firebirdsql.monitoring;

import java.util.Map;

/**
 * A single metric entry parsed from FBStatisticsManager output.
 *
 * @param metricName  Micrometer gauge name (e.g. {@code "headerpage_flags"})
 * @param valueKey    Key used to look up the current value in the VALUES map inside
 *                    {@link FirebirdStatisticsMetrics}
 * @param tags        Tag key-value pairs for the gauge (e.g. dbms, database, object)
 * @param value       The parsed numeric value
 */
public record ParsedMetric(
    String metricName,
    String valueKey,
    Map<String, String> tags,
    Number value
) {}
