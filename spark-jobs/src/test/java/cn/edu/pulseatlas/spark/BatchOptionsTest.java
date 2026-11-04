package cn.edu.pulseatlas.spark;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Map;

import org.junit.jupiter.api.Test;

class BatchOptionsTest {
    @Test
    void readsJdbcPasswordOnlyFromNamedEnvironmentVariable() {
        BatchOptions options = BatchOptions.parse(new String[] {
            "--batch-id", "fixture-1",
            "--concert-input", "concerts.csv",
            "--comment-input", "comments.csv",
            "--jdbc-url", "jdbc:mysql://mysql/concert",
            "--jdbc-user", "app",
            "--jdbc-password-env", "TEST_DB_PASSWORD"
        }, Map.of("TEST_DB_PASSWORD", "secret-from-environment"));

        assertEquals("secret-from-environment", options.jdbcPassword());
        assertEquals(20, options.riskMinSamples());
        assertEquals(0.40, options.riskNegativeRatio());
    }

    @Test
    void rejectsUnsafeBatchIds() {
        assertThrows(IllegalArgumentException.class, () -> BatchOptions.parse(new String[] {
            "--batch-id", "../escape",
            "--concert-input", "concerts.csv",
            "--comment-input", "comments.csv"
        }, Map.of()));

        assertThrows(IllegalArgumentException.class, () -> BatchOptions.parse(new String[] {
            "--batch-id", "a".repeat(81),
            "--concert-input", "concerts.csv",
            "--comment-input", "comments.csv"
        }, Map.of()));
    }
}
