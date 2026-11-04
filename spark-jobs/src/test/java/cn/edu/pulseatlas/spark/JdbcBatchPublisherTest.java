package cn.edu.pulseatlas.spark;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.junit.jupiter.api.Test;

class JdbcBatchPublisherTest {
    @Test
    void createsBoundedUniqueDatasetVersionsForCacheRotation() {
        String batchId = "a".repeat(80);
        String first = JdbcBatchPublisher.datasetVersion(
                batchId, UUID.fromString("00000000-0000-0000-0000-000000000001"));
        String second = JdbcBatchPublisher.datasetVersion(
                batchId, UUID.fromString("00000000-0000-0000-0000-000000000002"));

        assertEquals(80, first.length());
        assertTrue(first.startsWith("a".repeat(43) + "-"));
        assertNotEquals(first, second);
    }
}
