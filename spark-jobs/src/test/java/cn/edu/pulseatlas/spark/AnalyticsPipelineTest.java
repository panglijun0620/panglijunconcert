package cn.edu.pulseatlas.spark;

import static org.apache.spark.sql.functions.col;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SparkSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AnalyticsPipelineTest {
    @TempDir
    Path output;

    private SparkSession spark;

    @AfterEach
    void stopSpark() {
        if (spark != null) {
            spark.stop();
            SparkSession.clearActiveSession();
            SparkSession.clearDefaultSession();
        }
    }

    @Test
    void recomputesLexiconSentimentEnforcesScopeAndOverwritesBatchOutput() throws Exception {
        BatchOptions options = options();
        spark = ConcertBatchJob.createSession(options);
        spark.sparkContext().setLogLevel("ERROR");

        PipelineResult result = new AnalyticsPipeline(spark, options).execute();
        assertEquals(6, result.concertInputCount());
        assertEquals(3, result.concertSuccessCount());
        assertEquals(7, result.commentInputCount());
        assertEquals(3, result.commentSuccessCount());
        assertEquals(0, result.comments()
                .filter(col("sentiment_score").lt(-1.0).or(col("sentiment_score").gt(1.0)))
                .count());

        Row damaiScore = result.comments()
                .filter(col("source_url").equalTo("https://m.damai.cn/item/c-1"))
                .filter(col("comment_text").equalTo("bad sound and terrible service"))
                .select("sentiment_score", "sentiment_method", "scope_type")
                .first();
        assertEquals(-1.0, damaiScore.getDouble(0), 0.00001);
        assertEquals("lexicon_rule", damaiScore.getString(1));
        assertEquals("platform", damaiScore.getString(2));
        Row ignoredImportedSentiment = result.qualityMetrics()
                .filter(col("source_type").equalTo("ALL_COMMENTS")
                        .and(col("metric_key").equalTo("IMPORTED_SENTIMENT_IGNORED")))
                .select("metric_value")
                .first();
        assertEquals(2.0, ignoredImportedSentiment.getDouble(0), 0.00001);
        assertEquals(0, result.comments()
                .filter(col("concert_source_record_id").isNotNull()
                        .or(col("concert_name").isNotNull()))
                .count());
        assertEquals(1, result.comments().filter(col("scope_type").equalTo("artist")).count());
        assertEquals(0, result.comments()
                .filter(col("source_type").notEqual("damai"))
                .count());
        assertEquals(0, result.comments().filter(col("comment_time").isNull()).count());

        List<String> metricTypes = result.metrics().select("metric_type").distinct().collectAsList()
                .stream().map(row -> row.getString(0)).toList();
        assertTrue(metricTypes.containsAll(List.of(
                "CITY_CONCERT_COUNT",
                "CITY_AVERAGE_PRICE",
                "MONTH_CONCERT_COUNT",
                "ARTIST_HEAT",
                "VENUE_CONCERT_COUNT")));
        assertTrue(metricTypes.stream().noneMatch(type -> type.contains(".")));
        Row shanghaiMarket = result.metrics()
                .filter(col("metric_type").equalTo("CITY_CONCERT_COUNT")
                        .and(col("dimension_label").equalTo("Shanghai")))
                .select("metric_value")
                .first();
        assertEquals(2.0, shanghaiMarket.getDouble(0), 0.00001);
        assertEquals(0, result.concerts().filter(col("category").notEqual("演唱会")).count());
        Row aliceHeat = result.metrics()
                .filter(col("metric_type").equalTo("ARTIST_HEAT")
                        .and(col("dimension_label").equalTo("Alice")))
                .select("metric_value")
                .first();
        assertEquals(3.0, aliceHeat.getDouble(0), 0.00001);
        Row damaiCoverage = result.qualityMetrics()
                .filter(col("metric_key").equalTo("SOURCE_COVERAGE")
                        .and(col("source_type").equalTo("DAMAI")))
                .select("record_count")
                .first();
        assertEquals(3L, damaiCoverage.getLong(0));
        assertFalse(result.risks().collectAsList().isEmpty());
        assertEquals(0, result.risks()
                .filter(col("triggered").and(col("risk_level").equalTo("NONE")))
                .count());
        assertEquals(0, result.risks()
                .filter(col("risk_level").equalTo("WATCH")
                        .and(col("negative_ratio").geq(0.40)))
                .count());

        HdfsOutputWriter writer = new HdfsOutputWriter();
        writer.write(result, options);
        writer.write(result, options);
        Path concertOutput = output.resolve("dwd/clean/batch_id=fixture-2026/concerts.jsonl");
        assertEquals(3, java.nio.file.Files.readAllLines(concertOutput).size());
    }

    @Test
    void reportsRetainedOptionalFieldGapsAndExcludesUnknownCitiesFromMarketMetrics() throws Exception {
        Path concerts = output.resolve("concerts-missing-optional.csv");
        String header = Files.readAllLines(Path.of(resource("fixtures/concerts.csv")), StandardCharsets.UTF_8).get(0);
        String missingOptional = String.join(",",
                "missing-optional", "", "演唱会", "Missing Optional Fields", "", "",
                "2026-10-01 19:30:00", "", "", "", "on_sale", "piaoniu",
                "https://www.piaoniu.com/activity/missing-optional", "2026-09-20 10:00:00");
        Files.write(concerts, List.of(header, missingOptional), StandardCharsets.UTF_8);

        BatchOptions options = options(concerts.toString());
        spark = ConcertBatchJob.createSession(options);
        spark.sparkContext().setLogLevel("ERROR");

        PipelineResult result = new AnalyticsPipeline(spark, options).execute();
        Map<String, Long> missingCounts = result.qualityMetrics()
                .filter(col("source_type").equalTo("ALL_CONCERTS"))
                .select("metric_key", "missing_count")
                .collectAsList().stream()
                .collect(Collectors.toMap(row -> row.getString(0), row -> row.getLong(1)));

        assertEquals(1L, missingCounts.get("MISSING_CITY").longValue());
        assertEquals(1L, missingCounts.get("MISSING_VENUE").longValue());
        assertEquals(1L, missingCounts.get("MISSING_ARTIST").longValue());
        assertEquals(1L, missingCounts.get("MISSING_PRICE").longValue());
        Row clean = result.concerts().select("city", "venue", "artist_name").first();
        assertEquals("Unknown", clean.getString(0));
        assertEquals("场馆未公布", clean.getString(1));
        assertEquals("未知艺人", clean.getString(2));
        assertEquals(0L, result.metrics()
                .filter(col("metric_type").equalTo("CITY_CONCERT_COUNT")
                        .and(col("dimension_key").equalTo("Unknown")))
                .count());
    }

    @Test
    void rejectsMismatchedAndSpoofedConcertSourcesWithoutUrlInference() throws Exception {
        Path concerts = output.resolve("concerts-source-validation.csv");
        String header = Files.readAllLines(Path.of(resource("fixtures/concerts.csv")), StandardCharsets.UTF_8).get(0);
        Files.write(concerts, List.of(
                header,
                concertRow("valid-piaoniu", "piaoniu", "https://www.piaoniu.com/activity/valid"),
                concertRow("valid-public", "public", "https://news.qq.com/rain/a/valid"),
                concertRow("damai-concert", "damai", "https://m.damai.cn/item/invalid"),
                concertRow("unknown-damai", "unknown", "https://m.damai.cn/item/not-inferred"),
                concertRow("spoofed-piaoniu", "piaoniu", "https://evil-piaoniu.com/activity/invalid")),
                StandardCharsets.UTF_8);

        BatchOptions options = options(concerts.toString());
        spark = ConcertBatchJob.createSession(options);
        spark.sparkContext().setLogLevel("ERROR");

        PipelineResult result = new AnalyticsPipeline(spark, options).execute();
        Row invalidSources = result.qualityMetrics()
                .filter(col("source_type").equalTo("ALL_CONCERTS")
                        .and(col("metric_key").equalTo("INVALID_SOURCE")))
                .select("missing_count")
                .first();

        assertEquals(5, result.concertInputCount());
        assertEquals(2, result.concertSuccessCount());
        assertEquals(3L, invalidSources.getLong(0));
        assertEquals(0L, result.concerts()
                .filter(col("source_type").isin("damai", "unknown"))
                .count());
    }

    @Test
    void reportsConflictingCommentBodiesForTheSameSourceRecord() throws Exception {
        Path comments = output.resolve("comments-conflict.csv");
        String header = Files.readAllLines(Path.of(resource("fixtures/comments.csv")), StandardCharsets.UTF_8).get(0);
        Files.write(comments, List.of(
                header,
                commentRow("same-id", "good show", "2026-10-01 10:00:00"),
                commentRow("same-id", "great show", "2026-10-02 10:00:00")),
                StandardCharsets.UTF_8);

        BatchOptions options = options(resource("fixtures/concerts.csv"), comments.toString());
        spark = ConcertBatchJob.createSession(options);
        spark.sparkContext().setLogLevel("ERROR");

        PipelineResult result = new AnalyticsPipeline(spark, options).execute();
        Row conflicts = result.qualityMetrics()
                .filter(col("source_type").equalTo("ALL_COMMENTS")
                        .and(col("metric_key").equalTo("CONFLICTING_RECORDS")))
                .select("missing_count")
                .first();

        assertEquals(2, result.commentInputCount());
        assertEquals(0, result.commentSuccessCount());
        assertEquals(0, result.comments().count());
        assertEquals(1L, conflicts.getLong(0));
    }

    @Test
    void keepsWhitespaceOnlyCommentVariantsAsOneDuplicate() throws Exception {
        Path comments = output.resolve("comments-whitespace.csv");
        String header = Files.readAllLines(Path.of(resource("fixtures/comments.csv")), StandardCharsets.UTF_8).get(0);
        Files.write(comments, List.of(
                header,
                commentRow("same-id", "good   show", "2026-10-01 10:00:00"),
                commentRow("same-id", "good show", "2026-10-02 10:00:00")),
                StandardCharsets.UTF_8);

        BatchOptions options = options(resource("fixtures/concerts.csv"), comments.toString());
        spark = ConcertBatchJob.createSession(options);
        spark.sparkContext().setLogLevel("ERROR");

        PipelineResult result = new AnalyticsPipeline(spark, options).execute();
        Row conflicts = result.qualityMetrics()
                .filter(col("source_type").equalTo("ALL_COMMENTS")
                        .and(col("metric_key").equalTo("CONFLICTING_RECORDS")))
                .select("missing_count")
                .first();

        assertEquals(2, result.commentInputCount());
        assertEquals(1, result.commentSuccessCount());
        assertEquals(1, result.comments().count());
        assertEquals(0L, conflicts.getLong(0));
    }

    @Test
    void excludesClearlyNonConcertPerformancesFromTheFinalBatch() throws Exception {
        BatchOptions options = options(String.join(",",
                resource("fixtures/concerts.csv"),
                resource("fixtures/concert-classification.csv")));
        spark = ConcertBatchJob.createSession(options);
        spark.sparkContext().setLogLevel("ERROR");

        PipelineResult result = new AnalyticsPipeline(spark, options).execute();
        Map<String, String> categories = result.concerts()
                .filter(col("input_source_record_id").startsWith("class-"))
                .select("input_source_record_id", "category")
                .collectAsList().stream()
                .collect(Collectors.toMap(row -> row.getString(0), row -> row.getString(1)));

        assertEquals(Map.ofEntries(
                Map.entry("class-singer", "演唱会"),
                Map.entry("class-livehouse", "演唱会"),
                Map.entry("class-musical-songs", "演唱会"),
                Map.entry("class-symphonic-pop", "演唱会"),
                Map.entry("class-unverified", "演唱会")), categories);
        assertEquals(8, result.concertSuccessCount());
        Row fixtureMarket = result.metrics()
                .filter(col("metric_type").equalTo("CITY_CONCERT_COUNT")
                        .and(col("dimension_label").equalTo("Fixture City")))
                .select("metric_value")
                .first();
        assertEquals(5.0, fixtureMarket.getDouble(0), 0.00001);
    }

    private BatchOptions options() throws URISyntaxException {
        return options(resource("fixtures/concerts.csv"));
    }

    private BatchOptions options(String concerts) throws URISyntaxException {
        if (concerts.contains("concert-classification.csv")) {
            try {
                String[] inputPaths = concerts.split(",", -1);
                Path base = Path.of(inputPaths[0]);
                List<String> baseLines = Files.readAllLines(base, StandardCharsets.UTF_8);
                List<String> combined = new java.util.ArrayList<>(baseLines);
                combined.addAll(classificationRowsForBaseSchema(
                        baseLines.get(0),
                        Path.of(inputPaths[inputPaths.length - 1])));
                Path merged = output.resolve("concerts-with-classification.csv");
                Files.write(merged, combined, StandardCharsets.UTF_8);
                concerts = merged.toString();
            } catch (java.io.IOException exception) {
                throw new IllegalStateException("Unable to prepare classification fixture", exception);
            }
        }
        String comments = String.join(",",
                resource("fixtures/comments.csv"),
                resource("fixtures/comments-artist.csv"));
        return options(concerts, comments);
    }

    private BatchOptions options(String concerts, String comments) {
        return BatchOptions.parse(new String[] {
            "--batch-id", "fixture-2026",
            "--concert-input", concerts,
            "--comment-input", comments,
            "--output-root", output.toUri().toString(),
            "--master", "local[2]",
            "--risk-min-samples", "1",
            "--risk-watch-ratio", "0.20",
            "--risk-alert-ratio", "0.40"
        }, Map.of());
    }

    private String concertRow(String id, String sourceType, String sourceUrl) {
        return String.join(",",
                id, "Artist", "concert", "Source Validation " + id, "Shanghai", "Arena",
                "2026-10-01 19:30:00", "280-880", "280", "880", "on_sale", sourceType,
                sourceUrl, "2026-09-20 10:00:00");
    }

    private String commentRow(String id, String text, String collectedAt) {
        return String.join(",",
                id, "", "", "", text, "2026-09-20 10:00:00", "0", "Shanghai", "damai",
                "https://m.damai.cn/item/" + id, collectedAt, "", "");
    }

    private List<String> classificationRowsForBaseSchema(String baseHeader, Path classification) throws java.io.IOException {
        List<String> lines = Files.readAllLines(classification, StandardCharsets.UTF_8);
        String[] baseColumns = baseHeader.split(",", -1);
        String[] sourceColumns = lines.get(0).split(",", -1);
        Map<String, Integer> sourceIndexes = java.util.stream.IntStream.range(0, sourceColumns.length)
                .boxed()
                .collect(Collectors.toMap(index -> sourceColumns[index], index -> index));

        return lines.subList(1, lines.size()).stream().map(line -> {
            String[] values = line.split(",", -1);
            return java.util.Arrays.stream(baseColumns)
                    .map(column -> {
                        if (column.equals("collected_at")) {
                            return "2026-09-20";
                        }
                        Integer index = sourceIndexes.get(column);
                        return index != null && index < values.length ? values[index] : "";
                    })
                    .collect(Collectors.joining(","));
        }).toList();
    }

    private String resource(String name) throws URISyntaxException {
        return Path.of(getClass().getClassLoader().getResource(name).toURI()).toString();
    }
}
