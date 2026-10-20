package cn.edu.pulseatlas.spark;

import java.io.BufferedWriter;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SaveMode;

public final class HdfsOutputWriter {
    public void write(PipelineResult result, BatchOptions options) {
        String root = stripTrailingSlash(options.outputRoot());
        String partition = "batch_id=" + options.batchId();

        // 【答辩讲解】数仓分层输出（ODS/DWD/DWS/ADS）：同一份数据按层级落盘，
        // 下游每层只依赖上一层，符合数据仓库"原始层→明细层→汇总层→应用层"的标准建模。
        // 输出双路径：传 hdfs:// 根路径 → 写 Parquet 到 HDFS；本地路径（含 Windows）→ 写 JSONL 到本地。
        if (isWindowsLocalPath(root)) {
            writeWindowsFixture(result, localPath(root), partition);
            return;
        }

        // ODS 贴源层：原始读入、尚未做任何清洗，保留采集原貌，出问题可回溯源数据。
        result.rawConcerts().write()
                .mode(SaveMode.Overwrite)
                .parquet(root + "/ods/raw/" + partition + "/concerts");
        result.rawComments().write()
                .mode(SaveMode.Overwrite)
                .parquet(root + "/ods/raw/" + partition + "/comments");
        // DWD 明细层：清洗后、补齐情感分与主题标签的明细记录，是后续所有分析的基础。
        result.concerts().write()
                .mode(SaveMode.Overwrite)
                .parquet(root + "/dwd/clean/" + partition + "/concerts");
        result.comments().write()
                .mode(SaveMode.Overwrite)
                .parquet(root + "/dwd/clean/" + partition + "/comments");
        // DWS 汇总层：多维聚合指标（按城市/主题/地区等维度汇总）。
        result.metrics().coalesce(1).write()
                .mode(SaveMode.Overwrite)
                .parquet(root + "/dws/metrics/" + partition);
        result.qualityMetrics().coalesce(1).write()
                .mode(SaveMode.Overwrite)
                .parquet(root + "/dws/quality/" + partition);
        // ADS 应用层：直接喂给可视化大屏的结论数据（风险榜单）。
        result.risks().coalesce(1).write()
                .mode(SaveMode.Overwrite)
                .parquet(root + "/ads/sentiment-risks/" + partition);
    }

    private void writeWindowsFixture(PipelineResult result, Path root, String partition) {
        try {
            writeJsonLines(result.rawConcerts(), root.resolve("ods").resolve("raw").resolve(partition).resolve("concerts.jsonl"));
            writeJsonLines(result.rawComments(), root.resolve("ods").resolve("raw").resolve(partition).resolve("comments.jsonl"));
            writeJsonLines(result.concerts(), root.resolve("dwd").resolve("clean").resolve(partition).resolve("concerts.jsonl"));
            writeJsonLines(result.comments(), root.resolve("dwd").resolve("clean").resolve(partition).resolve("comments.jsonl"));
            writeJsonLines(result.metrics(), root.resolve("dws").resolve("metrics").resolve(partition).resolve("metrics.jsonl"));
            writeJsonLines(result.qualityMetrics(), root.resolve("dws").resolve("quality").resolve(partition).resolve("quality.jsonl"));
            writeJsonLines(result.risks(), root.resolve("ads").resolve("sentiment-risks").resolve(partition).resolve("sentiment-risks.jsonl"));
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to write Windows local fixture output", exception);
        }
    }

    private void writeJsonLines(Dataset<Row> data, Path target) throws IOException {
        Files.createDirectories(target.getParent());
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
        try (BufferedWriter writer = Files.newBufferedWriter(
                temporary, StandardCharsets.UTF_8)) {
            List<String> rows = data.toJSON().collectAsList();
            for (String row : rows) {
                writer.write(row);
                writer.newLine();
            }
        }
        try {
            Files.move(
                    temporary,
                    target,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private boolean isWindowsLocalPath(String root) {
        return System.getProperty("os.name", "").toLowerCase().contains("win")
                && !root.toLowerCase().startsWith("hdfs:");
    }

    private Path localPath(String value) {
        if (value.toLowerCase().startsWith("file:")) {
            return Path.of(URI.create(value));
        }
        return Path.of(value);
    }

    private String stripTrailingSlash(String value) {
        int end = value.length();
        while (end > 0 && value.charAt(end - 1) == '/') {
            end--;
        }
        return value.substring(0, end);
    }
}
