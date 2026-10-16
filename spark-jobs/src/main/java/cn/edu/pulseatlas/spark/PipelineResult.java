package cn.edu.pulseatlas.spark;

import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;

public record PipelineResult(
        Dataset<Row> concerts,
        Dataset<Row> comments,
        Dataset<Row> metrics,
        Dataset<Row> qualityMetrics,
        Dataset<Row> risks,
        long concertInputCount,
        long commentInputCount,
        long concertSuccessCount,
        long commentSuccessCount,
        long concertDuplicateCount,
        long commentDuplicateCount,
        Dataset<Row> rawConcerts,
        Dataset<Row> rawComments) {

    public long inputCount() {
        return concertInputCount + commentInputCount;
    }

    public long successCount() {
        return concertSuccessCount + commentSuccessCount;
    }

    public long failedCount() {
        return inputCount() - successCount() - duplicateCount();
    }

    public long duplicateCount() {
        return concertDuplicateCount + commentDuplicateCount;
    }
}
