package at.wolfgangreder.firebirdsql.monitoring.statistics;

import at.wolfgangreder.firebirdsql.monitoring.ParsedMetric;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.LineNumberReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses the text output of {@code FBStatisticsManager.getDatabaseStatistics()} into a list of
 * {@link ParsedMetric} entries ready for registration as Micrometer gauges.
 *
 * <p>This class is a pure function — no state, no side effects, no Micrometer dependency.
 * Metric names and tag structures mirror those produced by weatherlog's
 * {@code DatabaseMonitoringServiceImpl} for backward compatibility.
 */
public final class FbStatisticsParser {

    private static final Pattern PAT_HEADER_PAGE =
        Pattern.compile("\\A(\\t*)([^\\t]*?)(\\t*)(\\d+(\\.)?\\d*)");
    private static final Pattern PAT_DATA = Pattern.compile(
        "\\A(\\s*)(([A-Za-z ]+):\\s*)((\\d+(\\.\\d+)?)%?)" +
        "(,\\s*([A-Za-z ]+):\\s*((\\d+(\\.\\d+)?)%?))?" +
        "(,\\s*([A-Za-z ]+):\\s*((\\d+(\\.\\d+)?)%?))?");
    private static final Pattern PAT_DISTRIBUTION =
        Pattern.compile("\\A\\s*\\d{1,2}\\s*-\\s*(\\d{1,2})%\\s*=\\s*(\\d+)");

    private FbStatisticsParser() {}

    public static List<ParsedMetric> parse(String database, byte[] data) throws IOException {
        List<ParsedMetric> result = new ArrayList<>();
        if (data == null || data.length == 0) return result;

        try (LineNumberReader reader = new LineNumberReader(
                new InputStreamReader(new ByteArrayInputStream(data), StandardCharsets.UTF_8))) {

            parseHeaderPage(database, reader, result);

            if (!forwardToLine(reader, "Analyzing database pages ...")) {
                return result;
            }

            long[] totalDataPages = {0};
            boolean hasMore = true;
            while (hasMore) {
                String objectName = reader.readLine();
                if (objectName == null) break;

                int indexPos = objectName.indexOf("Index ");
                if (indexPos > 0) {
                    int spacePos = objectName.indexOf(' ', indexPos + 6);
                    String indexName = spacePos > 0
                        ? objectName.substring(indexPos + 6, spacePos)
                        : objectName.substring(indexPos + 6);
                    hasMore = parseIndexData(database, indexName, reader, result);
                } else {
                    int spacePos = objectName.indexOf(' ');
                    String tableName = spacePos > 0
                        ? objectName.substring(0, spacePos)
                        : objectName;
                    hasMore = parseTableData(database, tableName, reader, result, totalDataPages);
                }
            }

            result.add(new ParsedMetric(
                "data_total_data_pages",
                database + "_data_total_data_pages",
                Map.of("dbms", "firebirdsql", "database", database),
                totalDataPages[0]
            ));
        }
        return result;
    }

    private static void parseHeaderPage(String database, LineNumberReader reader,
                                         List<ParsedMetric> result) throws IOException {
        if (!forwardToLine(reader, "Database header page information:")) return;
        String line = reader.readLine();
        while (line != null && !line.isBlank()) {
            Matcher m = PAT_HEADER_PAGE.matcher(line);
            if (m.matches()) {
                String param = toParamName(m.group(2));
                String valueStr = m.group(4);
                Number value = valueStr.indexOf('.') >= 0
                    ? Double.valueOf(valueStr) : Long.valueOf(valueStr);
                result.add(new ParsedMetric(
                    "headerpage_" + param,
                    database + "_headerpage_" + param,
                    Map.of("dbms", "firebirdsql", "database", database),
                    value
                ));
            }
            line = reader.readLine();
        }
    }

    private static boolean parseTableData(String database, String tableName,
                                          LineNumberReader reader,
                                          List<ParsedMetric> result,
                                          long[] totalDataPages) throws IOException {
        String line = reader.readLine();
        while (line != null && !line.contains("Fill distribution:")) {
            totalDataPages[0] += processDataLine(database, "data", tableName, line, result);
            line = reader.readLine();
        }
        line = reader.readLine(); // advance past "Fill distribution:" header
        while (line != null && !line.isBlank()) {
            Matcher m = PAT_DISTRIBUTION.matcher(line);
            if (m.matches()) {
                String rangeTo = m.group(1);
                double val = Double.parseDouble(m.group(2));
                result.add(new ParsedMetric(
                    "data_distribution",
                    database + "_data_" + tableName + "_distribution_" + rangeTo,
                    Map.of("dbms", "firebirdsql", "database", database,
                           "object", tableName, "range", rangeTo),
                    val
                ));
            }
            line = reader.readLine();
        }
        return line != null;
    }

    private static boolean parseIndexData(String database, String indexName,
                                          LineNumberReader reader,
                                          List<ParsedMetric> result) throws IOException {
        String line = reader.readLine();
        while (line != null && !line.contains("Fill distribution:")) {
            processDataLine(database, "index", indexName, line, result);
            line = reader.readLine();
        }
        line = reader.readLine(); // advance past "Fill distribution:" header
        while (line != null && !line.isBlank()) {
            Matcher m = PAT_DISTRIBUTION.matcher(line);
            if (m.matches()) {
                String rangeTo = m.group(1);
                double val = Double.parseDouble(m.group(2));
                result.add(new ParsedMetric(
                    "index_distribution",
                    database + "_index_" + indexName + "_distribution_" + rangeTo,
                    Map.of("dbms", "firebirdsql", "database", database,
                           "object", indexName, "range", rangeTo),
                    val
                ));
            }
            line = reader.readLine();
        }
        return line != null;
    }

    private static long processDataLine(String database, String objectGroup,
                                         String objectName, String line,
                                         List<ParsedMetric> result) {
        Matcher m = PAT_DATA.matcher(line);
        long total = 0;
        if (m.matches()) {
            total += processDataEntry(database, objectGroup, objectName,
                toParamName(m.group(3)), m.group(5), result);
            if (m.group(8) != null) {
                total += processDataEntry(database, objectGroup, objectName,
                    toParamName(m.group(8)), m.group(10), result);
            }
            if (m.group(13) != null) {
                total += processDataEntry(database, objectGroup, objectName,
                    toParamName(m.group(13)), m.group(15), result);
            }
        }
        return total;
    }

    private static long processDataEntry(String database, String objectGroup,
                                         String objectName, String param,
                                         String valueStr, List<ParsedMetric> result) {
        double val = Double.parseDouble(valueStr);
        result.add(new ParsedMetric(
            objectGroup + "_" + param,
            database + "_" + objectGroup + "_" + objectName + "_" + param,
            Map.of("dbms", "firebirdsql", "database", database, "object", objectName),
            val
        ));
        return "data_page_slots".equals(param) ? (long) val : 0L;
    }

    private static boolean forwardToLine(LineNumberReader reader, String target) throws IOException {
        String line;
        while ((line = reader.readLine()) != null) {
            if (target.equals(line)) return true;
        }
        return false;
    }

    private static String toParamName(String s) {
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            b.append(Character.isAlphabetic(c) ? Character.toLowerCase(c) : '_');
        }
        return b.toString();
    }
}
