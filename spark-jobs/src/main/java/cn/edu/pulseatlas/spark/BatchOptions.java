package cn.edu.pulseatlas.spark;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

public record BatchOptions(
        String batchId,
        String concertInput,
        String commentInput,
        String outputRoot,
        String master,
        String jdbcUrl,
        String jdbcUser,
        String jdbcPassword,
        int riskMinSamples,
        double riskNegativeRatio) {

    private static final Pattern BATCH_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,79}");

    public static BatchOptions parse(String[] args, Map<String, String> environment) {
        Map<String, String> values = new LinkedHashMap<>();
        for (int index = 0; index < args.length; index++) {
            String argument = args[index];
            if (!argument.startsWith("--")) {
                throw new IllegalArgumentException("Unexpected argument: " + argument);
            }
            if (index + 1 >= args.length || args[index + 1].startsWith("--")) {
                throw new IllegalArgumentException("Missing value for " + argument);
            }
            values.put(argument.substring(2), args[++index]);
        }

        String batchId = required(values, "batch-id");
        if (!BATCH_ID.matcher(batchId).matches()) {
            throw new IllegalArgumentException("batch-id must match " + BATCH_ID.pattern());
        }

        String passwordEnvironment = values.getOrDefault("jdbc-password-env", "MYSQL_PASSWORD");
        String jdbcPassword = environment.get(passwordEnvironment);
        String jdbcUrl = blankToNull(values.getOrDefault("jdbc-url", environment.get("JDBC_URL")));
        String jdbcUser = blankToNull(values.getOrDefault("jdbc-user", environment.get("MYSQL_USER")));
        if (jdbcUrl != null && (jdbcUser == null || jdbcPassword == null || jdbcPassword.isBlank())) {
            throw new IllegalArgumentException(
                    "JDBC publishing requires --jdbc-user/MYSQL_USER and the password environment variable "
                            + passwordEnvironment);
        }

        int minimumSamples = parseInt(values.getOrDefault("risk-min-samples", "20"), "risk-min-samples");
        double negativeRatio = parseDouble(
                values.getOrDefault("risk-negative-ratio", "0.40"), "risk-negative-ratio");
        if (minimumSamples < 1) {
            throw new IllegalArgumentException("risk-min-samples must be at least 1");
        }
        if (negativeRatio < 0.0 || negativeRatio > 1.0) {
            throw new IllegalArgumentException("risk-negative-ratio must be between 0 and 1");
        }

        return new BatchOptions(
                batchId,
                required(values, "concert-input"),
                required(values, "comment-input"),
                values.getOrDefault("output-root", "hdfs:///concert"),
                blankToNull(values.get("master")),
                jdbcUrl,
                jdbcUser,
                jdbcPassword,
                minimumSamples,
                negativeRatio);
    }

    public boolean jdbcEnabled() {
        return jdbcUrl != null;
    }

    private static String required(Map<String, String> values, String key) {
        String value = blankToNull(values.get(key));
        if (value == null) {
            throw new IllegalArgumentException("Missing required option --" + key);
        }
        return value;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static int parseInt(String value, String name) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(name + " must be an integer", exception);
        }
    }

    private static double parseDouble(String value, String name) {
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(name + " must be numeric", exception);
        }
    }

    public BatchOptions {
        Objects.requireNonNull(batchId);
        Objects.requireNonNull(concertInput);
        Objects.requireNonNull(commentInput);
        Objects.requireNonNull(outputRoot);
    }
}
