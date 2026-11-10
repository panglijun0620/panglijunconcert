package cn.edu.pulseatlas.spark;

import static org.apache.spark.sql.functions.col;

import java.math.BigDecimal;
import java.net.URI;
import java.sql.Connection;
import java.sql.Date;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.UUID;

import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;

public final class JdbcBatchPublisher {
    private static final int JDBC_BATCH_SIZE = 500;

    private final BatchOptions options;
    private final Instant startedAt;
    private final String datasetVersion;
    private long databaseBatchId;
    private long jobRunId;

    public JdbcBatchPublisher(BatchOptions options, Instant startedAt) {
        this.options = options;
        this.startedAt = startedAt;
        this.datasetVersion = datasetVersion(options.batchId(), UUID.randomUUID());
    }

    public void start(String applicationId, String workerSummary) throws SQLException {
        try (Connection connection = connection()) {
            connection.setAutoCommit(false);
            try {
                databaseBatchId = upsertBatch(connection);
                jobRunId = insertJobRun(connection, applicationId, workerSummary);
                connection.commit();
            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    public void publish(PipelineResult result, String applicationId, String workerSummary) throws SQLException {
        try (Connection connection = connection()) {
            connection.setAutoCommit(false);
            connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            try {
                lockBatch(connection);
                deletePreviousResults(connection);
                insertConcerts(connection, result.concerts());
                insertComments(connection, result.comments());
                insertMetrics(connection, result.metrics());
                QualitySummary quality = insertQualityMetrics(connection, result.qualityMetrics());
                insertRisks(connection, result.risks());
                finishBatch(connection, result, quality);
                finishJob(connection, result, applicationId, workerSummary);
                connection.commit();
            } catch (Exception exception) {
                connection.rollback();
                if (exception instanceof SQLException sqlException) {
                    throw sqlException;
                }
                throw new SQLException("Failed to publish Spark results", exception);
            }
        }
    }

    public void fail(Throwable failure) {
        if (databaseBatchId == 0) {
            return;
        }
        String summary = truncate(rootMessage(failure), 1000);
        try (Connection connection = connection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement batch = connection.prepareStatement(
                            "UPDATE analytics_batch SET status='FAILED', finished_at=?, notes=? "
                                    + "WHERE id=? AND published_at IS NULL");
                    PreparedStatement job = connection.prepareStatement(
                            "UPDATE spark_job_run SET status='FAILED', duration_ms=?, message=?, failure_reason=?, finished_at=? WHERE id=?")) {
                Timestamp finished = Timestamp.from(Instant.now());
                batch.setTimestamp(1, finished);
                batch.setString(2, summary);
                batch.setLong(3, databaseBatchId);
                batch.executeUpdate();

                if (jobRunId != 0) {
                    job.setLong(1, Duration.between(startedAt, Instant.now()).toMillis());
                    job.setString(2, "Spark batch failed");
                    job.setString(3, summary);
                    job.setTimestamp(4, finished);
                    job.setLong(5, jobRunId);
                    job.executeUpdate();
                }
                connection.commit();
            } catch (SQLException exception) {
                connection.rollback();
                System.err.println("Unable to persist failed job status: " + exception.getMessage());
            }
        } catch (SQLException exception) {
            System.err.println("Unable to connect while persisting failed job status: " + exception.getMessage());
        }
    }

    private Connection connection() throws SQLException {
        return DriverManager.getConnection(options.jdbcUrl(), options.jdbcUser(), options.jdbcPassword());
    }

    private long upsertBatch(Connection connection) throws SQLException {
        String sql = """
                INSERT INTO analytics_batch
                    (batch_id, dataset_version, source_type, raw_path, status, started_at, notes)
                VALUES (?, ?, 'MULTI_SOURCE', ?, 'RUNNING', ?, ?)
                ON DUPLICATE KEY UPDATE
                    id=LAST_INSERT_ID(id),
                    dataset_version=IF(published_at IS NULL, VALUES(dataset_version), dataset_version),
                    source_type=IF(published_at IS NULL, VALUES(source_type), source_type),
                    raw_path=IF(published_at IS NULL, VALUES(raw_path), raw_path),
                    status=IF(published_at IS NULL, 'RUNNING', status),
                    started_at=IF(published_at IS NULL, VALUES(started_at), started_at),
                    finished_at=IF(published_at IS NULL, NULL, finished_at),
                    notes=IF(published_at IS NULL, VALUES(notes), notes)
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, options.batchId());
            statement.setString(2, datasetVersion);
            statement.setString(3, rawPath());
            statement.setTimestamp(4, Timestamp.from(startedAt));
            statement.setString(5, "Spark batch started; sentiment range is [-1,1]");
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (keys.next()) {
                    return keys.getLong(1);
                }
            }
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id FROM analytics_batch WHERE batch_id=?")) {
            statement.setString(1, options.batchId());
            try (ResultSet result = statement.executeQuery()) {
                if (result.next()) {
                    return result.getLong(1);
                }
            }
        }
        throw new SQLException("Unable to resolve analytics_batch id for " + options.batchId());
    }

    private long insertJobRun(Connection connection, String applicationId, String workerSummary) throws SQLException {
        String sql = """
                INSERT INTO spark_job_run
                    (data_batch_id, job_type, execution_engine, status, application_id,
                     worker_summary, started_at, message)
                VALUES (?, 'CONCERT_COMMENT_AGGREGATION', 'SPARK_ON_YARN', 'RUNNING', ?, ?, ?, ?)
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            statement.setLong(1, databaseBatchId);
            statement.setString(2, truncate(applicationId, 120));
            statement.setString(3, truncate(workerSummary, 500));
            statement.setTimestamp(4, Timestamp.from(startedAt));
            statement.setString(5, "Cleaning and aggregating concert and comment CSV inputs");
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (keys.next()) {
                    return keys.getLong(1);
                }
            }
        }
        throw new SQLException("Unable to resolve spark_job_run id");
    }

    private void lockBatch(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id FROM analytics_batch WHERE id=? FOR UPDATE")) {
            statement.setLong(1, databaseBatchId);
            try (ResultSet ignored = statement.executeQuery()) {
                if (!ignored.next()) {
                    throw new SQLException("Batch disappeared before publish: " + databaseBatchId);
                }
            }
        }
    }

    private void deletePreviousResults(Connection connection) throws SQLException {
        String[] tables = {
            "sentiment_risk", "data_quality_metric", "analysis_metric", "clean_comment", "clean_concert"
        };
        for (String table : tables) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM " + table)) {
                statement.executeUpdate();
            }
        }
        // 只清理历史计算作业，保留采集阶段（CRAWL_ 前缀）的运行记录。
        // 采集记录不属于本批次产出，删掉会让“任务记录”页看不到采集环节。
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM spark_job_run WHERE id <> ? AND job_type NOT LIKE 'CRAWL_%'")) {
            statement.setLong(1, jobRunId);
            statement.executeUpdate();
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM analytics_batch WHERE id <> ?")) {
            statement.setLong(1, databaseBatchId);
            statement.executeUpdate();
        }
    }

    private void insertConcerts(Connection connection, Dataset<Row> concerts) throws SQLException {
        String sql = """
                INSERT INTO clean_concert
                    (data_batch_id, artist_name, category, concert_name, city, venue, show_time,
                     show_time_precision, price_text, min_price, max_price, sale_status, source_type,
                     source_record_id, source_url, collected_at, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            Iterator<Row> rows = concerts.toLocalIterator();
            while (rows.hasNext()) {
                Row row = rows.next();
                validateConcertSource(row);
                statement.setLong(1, databaseBatchId);
                statement.setString(2, truncate(string(row, "artist_name"), 120));
                statement.setString(3, truncate(string(row, "category"), 40));
                statement.setString(4, truncate(string(row, "concert_name"), 240));
                statement.setString(5, truncate(string(row, "city"), 80));
                statement.setString(6, truncate(string(row, "venue"), 180));
                statement.setTimestamp(7, timestamp(row, "show_time"));
                 statement.setString(8, truncate(string(row, "show_time_precision"), 16));
                 statement.setString(9, truncate(string(row, "price_text"), 240));
                 decimal(statement, 10, row, "min_price");
                 decimal(statement, 11, row, "max_price");
                 statement.setString(12, truncate(string(row, "sale_status"), 40));
                 statement.setString(13, truncate(string(row, "source_type"), 40));
                 statement.setString(14, truncate(string(row, "source_record_id"), 160));
                 statement.setString(15, truncate(string(row, "source_url"), 800));
                 statement.setTimestamp(16, timestamp(row, "collected_at"));
                 statement.setTimestamp(17, timestamp(row, "created_at"));
                 statement.executeUpdate();
            }
        }
    }

    private void insertComments(Connection connection, Dataset<Row> comments) throws SQLException {
        String sql = """
                INSERT INTO clean_comment
                    (concert_id, data_batch_id, artist_name, comment_text, comment_time, like_count,
                     user_region, source_type, source_record_id, source_url, collected_at,
                     sentiment_score, sentiment_method, primary_topic)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            int pending = 0;
            Iterator<Row> rows = comments.toLocalIterator();
            while (rows.hasNext()) {
                Row row = rows.next();
                validateCommentSource(row);
                String concertReference = string(row, "concert_source_record_id");
                String concertNameReference = string(row, "concert_name");
                if (hasText(concertReference) || hasText(concertNameReference)) {
                    throw new SQLException("Comment rows must not contain unverified event associations");
                }
                String sentimentMethod = string(row, "sentiment_method");
                if (!"lexicon_rule".equals(sentimentMethod)) {
                    throw new SQLException("Comment sentiment must be produced by the lexicon rule pipeline");
                }
                nullableLong(statement, 1, null);
                statement.setLong(2, databaseBatchId);
                nullableString(statement, 3, truncate(string(row, "artist_name"), 120));
                statement.setString(4, string(row, "comment_text"));
                nullableTimestamp(statement, 5, timestamp(row, "comment_time"));
                statement.setLong(6, longValue(row, "like_count"));
                nullableString(statement, 7, truncate(string(row, "user_region"), 80));
                statement.setString(8, truncate(string(row, "source_type"), 40));
                statement.setString(9, truncate(string(row, "source_record_id"), 160));
                statement.setString(10, truncate(string(row, "source_url"), 800));
                statement.setTimestamp(11, timestamp(row, "collected_at"));
                decimal(statement, 12, row, "sentiment_score");
                statement.setString(13, sentimentMethod);
                nullableString(statement, 14, truncate(string(row, "primary_topic"), 100));
                statement.addBatch();
                if (++pending % JDBC_BATCH_SIZE == 0) {
                    statement.executeBatch();
                }
            }
            if (pending % JDBC_BATCH_SIZE != 0) {
                statement.executeBatch();
            }
        }
    }

    private void insertMetrics(Connection connection, Dataset<Row> metrics) throws SQLException {
        String sql = """
                INSERT INTO analysis_metric
                    (data_batch_id, metric_type, dimension_key, dimension_label, metric_value,
                     unit, period_start, period_end, sample_count, generated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE
                    dimension_label = VALUES(dimension_label),
                    metric_value = VALUES(metric_value),
                    unit = VALUES(unit),
                    period_start = VALUES(period_start),
                    period_end = VALUES(period_end),
                    sample_count = VALUES(sample_count),
                    generated_at = VALUES(generated_at)
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            int pending = 0;
            int skipped = 0;
            // MySQL 使用 utf8mb4_0900_ai_ci 排序规则，会把全角/半角等价字符（如 ～ 与 ~）
            // 判定为相同；而 Spark 按二进制比较视为两个不同字符串。这里在写入前按 NFKC
            // 归一化做一次去重，避免同一维度键被插入两次而触发唯一键冲突。
            java.util.Set<String> seenKeys = new java.util.HashSet<>();
            Iterator<Row> rows = metrics.toLocalIterator();
            while (rows.hasNext()) {
                Row row = rows.next();
                String metricType = truncate(string(row, "metric_type"), 64);
                String dimensionKey = truncate(string(row, "dimension_key"), 160);
                String dedupeKey = java.text.Normalizer.normalize(
                        metricType + String.valueOf((char) 0) + dimensionKey, java.text.Normalizer.Form.NFKC);
                if (!seenKeys.add(dedupeKey)) {
                    skipped++;
                    continue;
                }
                statement.setLong(1, databaseBatchId);
                statement.setString(2, metricType);
                statement.setString(3, dimensionKey);
                statement.setString(4, truncate(string(row, "dimension_label"), 200));
                decimal(statement, 5, row, "metric_value");
                statement.setString(6, truncate(string(row, "unit"), 32));
                nullableDate(statement, 7, date(row, "period_start"));
                nullableDate(statement, 8, date(row, "period_end"));
                statement.setLong(9, longValue(row, "sample_count"));
                statement.setTimestamp(10, timestamp(row, "generated_at"));
                statement.addBatch();
                if (++pending % JDBC_BATCH_SIZE == 0) {
                    statement.executeBatch();
                }
            }
            if (pending % JDBC_BATCH_SIZE != 0) {
                statement.executeBatch();
            }
            if (skipped > 0) {
                System.out.printf(
                        "Metrics: skipped %d fullwidth/halfwidth equivalent duplicate row(s)%n", skipped);
            }
        }
    }

    private QualitySummary insertQualityMetrics(Connection connection, Dataset<Row> quality) throws SQLException {
        String sql = """
                INSERT INTO data_quality_metric
                    (data_batch_id, source_type, metric_key, metric_value, record_count,
                     missing_count, duplicate_count, generated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """;
        long missing = 0;
        long duplicates = 0;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            int pending = 0;
            Iterator<Row> rows = quality.toLocalIterator();
            while (rows.hasNext()) {
                Row row = rows.next();
                statement.setLong(1, databaseBatchId);
                statement.setString(2, truncate(string(row, "source_type"), 40));
                statement.setString(3, truncate(string(row, "metric_key"), 64));
                decimal(statement, 4, row, "metric_value");
                statement.setLong(5, longValue(row, "record_count"));
                statement.setLong(6, longValue(row, "missing_count"));
                statement.setLong(7, longValue(row, "duplicate_count"));
                statement.setTimestamp(8, timestamp(row, "generated_at"));
                if ("MISSING_REQUIRED".equals(string(row, "metric_key"))) {
                    missing += longValue(row, "missing_count");
                }
                if ("DUPLICATE_RECORDS".equals(string(row, "metric_key"))) {
                    duplicates += longValue(row, "duplicate_count");
                }
                statement.addBatch();
                if (++pending % JDBC_BATCH_SIZE == 0) {
                    statement.executeBatch();
                }
            }
            if (pending % JDBC_BATCH_SIZE != 0) {
                statement.executeBatch();
            }
        }
        return new QualitySummary(missing, duplicates);
    }

    private void insertRisks(Connection connection, Dataset<Row> risks) throws SQLException {
        String sql = """
                INSERT INTO sentiment_risk
                    (data_batch_id, scope_type, scope_id, object_name, negative_ratio,
                     risk_level, primary_negative_topic, sample_size, threshold_value, minimum_sample_size,
                     trigger_rule, detected_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            int pending = 0;
            Iterator<Row> rows = risks.filter(col("triggered")).toLocalIterator();
            while (rows.hasNext()) {
                Row row = rows.next();
                statement.setLong(1, databaseBatchId);
                statement.setString(2, truncate(string(row, "scope_type"), 32));
                nullableString(statement, 3, truncate(string(row, "scope_id"), 160));
                statement.setString(4, truncate(string(row, "object_name"), 240));
                decimal(statement, 5, row, "negative_ratio");
                statement.setString(6, truncate(string(row, "risk_level"), 16));
                statement.setString(7, truncate(string(row, "primary_negative_topic"), 120));
                statement.setLong(8, longValue(row, "sample_size"));
                decimal(statement, 9, row, "threshold_value");
                statement.setLong(10, longValue(row, "minimum_sample_size"));
                statement.setString(11, truncate(string(row, "trigger_rule"), 500));
                statement.setTimestamp(12, timestamp(row, "detected_at"));
                statement.addBatch();
                if (++pending % JDBC_BATCH_SIZE == 0) {
                    statement.executeBatch();
                }
            }
            if (pending % JDBC_BATCH_SIZE != 0) {
                statement.executeBatch();
            }
        }
    }

    private void finishBatch(
            Connection connection,
            PipelineResult result,
            QualitySummary quality) throws SQLException {
        String sql = """
                UPDATE analytics_batch
                SET dataset_version=?, source_type='MULTI_SOURCE', raw_path=?, status='PUBLISHED',
                    input_count=?, success_count=?, failed_count=?, missing_count=?, duplicate_count=?,
                    started_at=?, finished_at=?, published_at=?, notes=?
                WHERE id=?
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            Timestamp finished = Timestamp.from(Instant.now());
            statement.setString(1, datasetVersion);
            statement.setString(2, rawPath());
            statement.setLong(3, result.inputCount());
            statement.setLong(4, result.successCount());
            statement.setLong(5, result.failedCount());
            statement.setLong(6, quality.missing());
            statement.setLong(7, quality.duplicates());
            statement.setTimestamp(8, Timestamp.from(startedAt));
            statement.setTimestamp(9, finished);
            statement.setTimestamp(10, finished);
            statement.setString(11, "Atomic Spark JDBC publish completed; sentiment range is [-1,1]");
            statement.setLong(12, databaseBatchId);
            statement.executeUpdate();
        }
    }

    private void finishJob(
            Connection connection,
            PipelineResult result,
            String applicationId,
            String workerSummary) throws SQLException {
        String sql = """
                UPDATE spark_job_run
                SET status='SUCCEEDED', application_id=?, worker_summary=?, input_count=?,
                    success_count=?, failed_count=?, duration_ms=?, message=?, finished_at=?
                WHERE id=?
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, truncate(applicationId, 120));
            statement.setString(2, truncate(workerSummary, 500));
            statement.setLong(3, result.inputCount());
            statement.setLong(4, result.successCount());
            statement.setLong(5, result.failedCount());
            statement.setLong(6, Duration.between(startedAt, Instant.now()).toMillis());
            statement.setString(7, "HDFS outputs and MySQL projections published");
            statement.setTimestamp(8, Timestamp.from(Instant.now()));
            statement.setLong(9, jobRunId);
            statement.executeUpdate();
        }
    }

    private static String string(Row row, String field) {
        Object value = row.getAs(field);
        return value == null ? null : value.toString();
    }

    private static void validateConcertSource(Row row) throws SQLException {
        String sourceType = string(row, "source_type");
        String sourceUrl = string(row, "source_url");
        if (!approvedConcertUrl(sourceType, sourceUrl)) {
            throw new SQLException("Concert row has an unapproved source: " + sourceType + " " + sourceUrl);
        }
    }

    private static void validateCommentSource(Row row) throws SQLException {
        String sourceType = string(row, "source_type");
        String sourceUrl = string(row, "source_url");
        if (!approvedHost(sourceUrl, "damai.cn") || !"damai".equalsIgnoreCase(sourceType)) {
            throw new SQLException("Comment row must be an approved Damai source: " + sourceType + " " + sourceUrl);
        }
    }

    private static boolean approvedConcertUrl(String sourceType, String sourceUrl) {
        if ("piaoniu".equalsIgnoreCase(sourceType)) {
            return approvedHost(sourceUrl, "piaoniu.com");
        }
        if ("showstart".equalsIgnoreCase(sourceType)) {
            return approvedHost(sourceUrl, "showstart.com");
        }
        if ("public".equalsIgnoreCase(sourceType)) {
            return approvedHost(sourceUrl, "news.qq.com")
                    || approvedHost(sourceUrl, "bjd.com.cn")
                    || isGovCn(sourceUrl);
        }
        return false;
    }

    private static boolean approvedHost(String sourceUrl, String domain) {
        if (!hasText(sourceUrl)) {
            return false;
        }
        try {
            URI uri = URI.create(sourceUrl.trim());
            String host = uri.getHost();
            return "https".equalsIgnoreCase(uri.getScheme())
                    && uri.getUserInfo() == null
                    && hostMatches(host, domain);
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private static boolean isGovCn(String sourceUrl) {
        if (!hasText(sourceUrl)) {
            return false;
        }
        try {
            URI uri = URI.create(sourceUrl.trim());
            String host = uri.getHost();
            return "https".equalsIgnoreCase(uri.getScheme())
                    && uri.getUserInfo() == null
                    && host != null
                    && (host.equalsIgnoreCase("gov.cn") || host.toLowerCase().endsWith(".gov.cn"));
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private static boolean hostMatches(String host, String domain) {
        return host != null && (host.equalsIgnoreCase(domain)
                || host.toLowerCase().endsWith("." + domain.toLowerCase()));
    }

    private static long longValue(Row row, String field) {
        Number value = row.getAs(field);
        return value == null ? 0L : value.longValue();
    }

    private static Timestamp timestamp(Row row, String field) {
        return row.getAs(field);
    }

    private static Date date(Row row, String field) {
        return row.getAs(field);
    }

    private static void decimal(PreparedStatement statement, int index, Row row, String field)
            throws SQLException {
        Number value = row.getAs(field);
        if (value == null) {
            statement.setNull(index, Types.DECIMAL);
        } else {
            statement.setBigDecimal(index, BigDecimal.valueOf(value.doubleValue()));
        }
    }

    private static void nullableString(PreparedStatement statement, int index, String value)
            throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.VARCHAR);
        } else {
            statement.setString(index, value);
        }
    }

    private static void nullableLong(PreparedStatement statement, int index, Long value)
            throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.BIGINT);
        } else {
            statement.setLong(index, value);
        }
    }

    private static void nullableTimestamp(PreparedStatement statement, int index, Timestamp value)
            throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.TIMESTAMP);
        } else {
            statement.setTimestamp(index, value);
        }
    }

    private static void nullableDate(PreparedStatement statement, int index, Date value)
            throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.DATE);
        } else {
            statement.setDate(index, value);
        }
    }

    private static String truncate(String value, int limit) {
        if (value == null || value.length() <= limit) {
            return value;
        }
        return value.substring(0, limit);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String rawPath() {
        return truncate(options.concertInput() + ";" + options.commentInput(), 500);
    }

    static String datasetVersion(String batchId, UUID publicationId) {
        String suffix = "-" + publicationId;
        int prefixLength = 80 - suffix.length();
        return batchId.substring(0, Math.min(batchId.length(), prefixLength)) + suffix;
    }

    private static String rootMessage(Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return current.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }

    private record QualitySummary(long missing, long duplicates) {
    }
}
