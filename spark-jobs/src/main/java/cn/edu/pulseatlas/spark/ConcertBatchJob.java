package cn.edu.pulseatlas.spark;

import java.time.Instant;
import org.apache.spark.SparkConf;
import org.apache.spark.sql.SparkSession;

public final class ConcertBatchJob {
    private ConcertBatchJob() {
    }

    public static void main(String[] args) throws Exception {
        BatchOptions options;
        try {
            options = BatchOptions.parse(args, System.getenv());
        } catch (IllegalArgumentException exception) {
            System.err.println(exception.getMessage());
            System.err.println(usage());
            System.exit(2);
            return;
        }

        Instant startedAt = Instant.now();
        SparkSession spark = createSession(options);
        JdbcBatchPublisher publisher = options.jdbcEnabled()
                ? new JdbcBatchPublisher(options, startedAt)
                : null;
        String applicationId = spark.sparkContext().applicationId();
        String workerSummary = workerSummary(spark);

        try {
            if (publisher != null) {
                publisher.start(applicationId, workerSummary);
            }
            PipelineResult result = new AnalyticsPipeline(spark, options).execute();
            // 【答辩讲解】清洗完调用 HdfsOutputWriter 落盘：传 --output-root hdfs:///concert 就写 HDFS，
            // 不传就写本地文件系统（本次演示即本地落盘）
            new HdfsOutputWriter().write(result, options);
            if (publisher != null) {
                publisher.publish(result, applicationId, workerSummary(spark));
            }
            System.out.printf(
                    "Batch %s completed: input=%d success=%d failed=%d applicationId=%s%n",
                    options.batchId(),
                    result.inputCount(),
                    result.successCount(),
                    result.failedCount(),
                    applicationId);
        } catch (Throwable failure) {
            if (publisher != null) {
                publisher.fail(failure);
            }
            throw failure;
        } finally {
            spark.stop();
        }
    }

    // 【答辩讲解】Spark 会话配置：本地用 local[2]、集群用 yarn，由 --master 参数切换（双模式），
    // 所以同一套代码既能在笔记本跑，也能提交到 Hadoop 集群
    static SparkSession createSession(BatchOptions options) {
        SparkConf configuration = new SparkConf()
                .setAppName("concert-analytics-" + options.batchId())
                .set("spark.sql.session.timeZone", "Asia/Shanghai")
                .set("spark.sql.legacy.timeParserPolicy", "CORRECTED")
                .set("spark.serializer", "org.apache.spark.serializer.KryoSerializer")
                .set("spark.hadoop.mapreduce.fileoutputcommitter.algorithm.version", "2");
        if (options.master() != null) {
            configuration.setMaster(options.master());
            if (options.master().startsWith("local")) {
                configuration.set("spark.driver.host", "127.0.0.1");
                configuration.set("spark.driver.bindAddress", "127.0.0.1");
                configuration.set("spark.sql.shuffle.partitions", "4");
                configuration.set("spark.default.parallelism", "2");
            }
        } else if (!configuration.contains("spark.master")) {
            configuration.setMaster("local[2]");
            configuration.set("spark.driver.host", "127.0.0.1");
            configuration.set("spark.driver.bindAddress", "127.0.0.1");
            configuration.set("spark.sql.shuffle.partitions", "4");
            configuration.set("spark.default.parallelism", "2");
        }
        return SparkSession.builder().config(configuration).getOrCreate();
    }

    private static String workerSummary(SparkSession spark) {
        int executorCount = Math.max(0, spark.sparkContext().getExecutorMemoryStatus().size() - 1);
        return "executors=" + executorCount + ", master=" + spark.sparkContext().master();
    }

    static String usage() {
        return "Usage: ConcertBatchJob --batch-id ID --concert-input PATH[,PATH...] "
                + "--comment-input PATH[,PATH...] "
                + "[--output-root hdfs:///concert] [--master local[2]] "
                + "[--jdbc-url URL --jdbc-user USER --jdbc-password-env MYSQL_PASSWORD] "
                + "[--risk-min-samples 20 --risk-negative-ratio 0.40]";
    }
}
