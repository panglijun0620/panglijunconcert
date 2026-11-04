package cn.edu.pulseatlas.spark;

import static org.apache.spark.sql.functions.avg;
import static org.apache.spark.sql.functions.callUDF;
import static org.apache.spark.sql.functions.coalesce;
import static org.apache.spark.sql.functions.col;
import static org.apache.spark.sql.functions.concat;
import static org.apache.spark.sql.functions.concat_ws;
import static org.apache.spark.sql.functions.count;
import static org.apache.spark.sql.functions.countDistinct;
import static org.apache.spark.sql.functions.current_timestamp;
import static org.apache.spark.sql.functions.date_format;
import static org.apache.spark.sql.functions.first;
import static org.apache.spark.sql.functions.greatest;
import static org.apache.spark.sql.functions.last_day;
import static org.apache.spark.sql.functions.least;
import static org.apache.spark.sql.functions.length;
import static org.apache.spark.sql.functions.lit;
import static org.apache.spark.sql.functions.lower;
import static org.apache.spark.sql.functions.regexp_replace;
import static org.apache.spark.sql.functions.round;
import static org.apache.spark.sql.functions.row_number;
import static org.apache.spark.sql.functions.sha2;
import static org.apache.spark.sql.functions.substring;
import static org.apache.spark.sql.functions.sum;
import static org.apache.spark.sql.functions.to_date;
import static org.apache.spark.sql.functions.to_timestamp;
import static org.apache.spark.sql.functions.trim;
import static org.apache.spark.sql.functions.upper;
import static org.apache.spark.sql.functions.when;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.spark.sql.Column;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.RowFactory;
import org.apache.spark.sql.SparkSession;
import org.apache.spark.sql.api.java.UDF1;
import org.apache.spark.sql.expressions.Window;
import org.apache.spark.sql.expressions.WindowSpec;
import org.apache.spark.sql.types.DataTypes;
import org.apache.spark.sql.types.StructField;
import org.apache.spark.sql.types.StructType;

public final class AnalyticsPipeline {
    public static final double NEGATIVE_THRESHOLD = -0.2;
    public static final double POSITIVE_THRESHOLD = 0.2;

    private final SparkSession spark;
    private final BatchOptions options;

    public AnalyticsPipeline(SparkSession spark, BatchOptions options) {
        this.spark = spark;
        this.options = options;
        registerFunctions();
    }

    public PipelineResult execute() {
        // 【答辩讲解】这是清洗主流程，六步按顺序走完：
        //   读演出 → 洗演出 → 读评论 → 洗评论 → 算指标 → 算质量指标和风险。
        // 关键点：演出和评论是同一个 Spark 作业里的两条处理分支，不是两个作业。
        Dataset<Row> rawConcerts = readCsv(options.concertInput()).cache();
        long concertInputCount = rawConcerts.count();
        ConcertFrames concertFrames = cleanConcerts(rawConcerts);

        Dataset<Row> rawComments = readCsv(options.commentInput()).cache();
        long commentInputCount = rawComments.count();
        CommentFrames commentFrames = cleanComments(rawComments);

        Dataset<Row> concertMarket = concertFrames.clean()
                .filter(col("category").isin("演唱会", "concert"));
        Dataset<Row> metrics = buildMetrics(concertMarket, commentFrames.clean()).cache();
        Dataset<Row> quality = buildQualityMetrics(concertFrames, commentFrames).cache();
        Dataset<Row> risks = buildRisks(commentFrames.clean()).cache();

        return new PipelineResult(
                concertFrames.clean(),
                commentFrames.clean(),
                metrics,
                quality,
                risks,
                concertInputCount,
                commentInputCount,
                concertFrames.acceptedCount(),
                commentFrames.acceptedCount(),
                concertFrames.duplicates(),
                commentFrames.duplicates(),
                rawConcerts,
                rawComments);
    }

    private Dataset<Row> readCsv(String input) {
        // 【答辩讲解】多源合并：把多个 CSV 路径按列名对齐 union 起来。
        // 支持把多个来源文件按列名对齐后合并读取。
        List<String> paths = Arrays.stream(input.split(",", -1))
                .map(String::trim)
                .toList();
        if (paths.stream().anyMatch(String::isEmpty)) {
            throw new IllegalArgumentException("CSV input contains an empty path");
        }

        Dataset<Row> combined = readCsvPath(paths.get(0));
        for (int index = 1; index < paths.size(); index++) {
            combined = combined.unionByName(readCsvPath(paths.get(index)), true);
        }
        return combined;
    }

    private Dataset<Row> readCsvPath(String path) {
        // 【答辩讲解】读 CSV 的关键配置：multiLine=true 一定要开。
        // 因为有的评论正文里带换行，不开这个选项，一条评论会被拆成两行、读错数据。
        return spark.read()
                .option("header", "true")
                .option("encoding", "UTF-8")
                .option("mode", "PERMISSIVE")
                .option("multiLine", "true")
                .option("escape", "\"")
                .csv(path);
    }

    private ConcertFrames cleanConcerts(Dataset<Row> raw) {
        // 【答辩讲解】演出清洗开始。下面这一大段是"字段规范化"：
        // 每个字段都给了多个候选列名（比如 concert_name 找不到就找 name、再找 title），
        // 因为不同平台的 CSV 列名不一样，统一回落到同一套标准字段。
        Column artist = text(raw, "artist_name", "artist");
        Column category = text(raw, "category");
        Column concertName = text(raw, "concert_name", "name", "title");
        Column city = text(raw, "city");
        Column venue = text(raw, "venue");
        Column showTimeText = text(raw, "show_time", "show_date", "event_time");
        Column showTimePrecision = text(raw, "show_time_precision");
        Column priceText = text(raw, "price_text", "price");
        Column minPriceText = text(raw, "min_price");
        Column maxPriceText = text(raw, "max_price");
        Column saleStatus = text(raw, "sale_status", "status");
        Column rawSourceType = text(raw, "source_type", "source_platform", "platform");
        Column sourceUrl = text(raw, "source_url", "url");
        Column collectedText = text(raw, "collected_at", "crawl_time");
        Column createdText = text(raw, "created_at");
        Column rawSourceRecordId = text(raw, "source_record_id", "record_id", "item_id", "project_id");
        Column platform = platform(rawSourceType, sourceUrl);
        Column sourceValid = approvedConcertSource(platform, sourceUrl);
        Column showTime = timestamp(showTimeText);
        Column collectedAt = timestamp(collectedText);
        Column createdAt = timestamp(createdText);
        Column minPrice = numeric(minPriceText);
        Column maxPrice = numeric(maxPriceText);

        Dataset<Row> staged = raw.select(
                        artist.alias("_artist_name"),
                        category.alias("_category"),
                        concertName.alias("_concert_name"),
                        city.alias("_city"),
                        venue.alias("_venue"),
                        showTime.alias("_show_time"),
                        coalesce(showTimePrecision, when(showTimeText.rlike(".*\\d{1,2}:\\d{2}.*"), lit("DATETIME"))
                                .otherwise(lit("DATE"))).alias("_show_time_precision"),
                        priceText.alias("_price_text"),
                        minPrice.alias("_min_price"),
                        maxPrice.alias("_max_price"),
                        saleStatus.alias("_sale_status"),
                        platform.alias("_source_type"),
                         sourceValid.alias("_source_valid"),
                        sourceUrl.alias("_source_url"),
                        collectedAt.alias("_collected_at"),
                        createdAt.alias("_created_at"),
                        rawSourceRecordId.alias("_raw_source_record_id"))
                .withColumn("_min_price", coalesce(col("_min_price"), callUDF("pulse_price_min", col("_price_text"))))
                // 【答辩讲解】票价拆解：原始票价是文本，比如 "380/580/780/1080"，
                // 这里用 UDF 把所有数字抠出来，取最小当 min_price、最大当 max_price，
                // 这样后面才能按价格区间做统计。演出清洗后字段从 10 列变成 12 列，就是因为拆出了这两个。
                .withColumn("_max_price", coalesce(col("_max_price"), callUDF("pulse_price_max", col("_price_text"))))
                .withColumn("_classified_category", classifiedCategory())
                // 【答辩讲解】演出 去重键：拼几个关键字段算一个 sha2 摘要，当成这条演出的唯一标识。
                // 同样的演出抓两次，摘要一样就会被后面 dropDuplicates 去掉。
                .withColumn(
                        "_record_key",
                        sha2(concat_ws(
                                        "||",
                                        coalesce(col("_source_type"), lit("unknown")),
                                        coalesce(col("_raw_source_record_id"), lit("")),
                                        coalesce(col("_source_url"), lit("")),
                                        coalesce(col("_concert_name"), lit("")),
                                        coalesce(col("_show_time").cast("string"), lit(""))),
                                256))
                // 【答辩讲解】必填校验：演出名和演出时间缺任何一个，这条记录就没法用，直接判无效。
                .withColumn("_valid", col("_concert_name").isNotNull()
                        .and(col("_show_time").isNotNull())
                        .and(col("_source_url").isNotNull())
                        .and(col("_collected_at").isNotNull())
                        .and(col("_source_valid"))
                        .and(col("_classified_category").equalTo("演唱会")))
                .cache();

        // 【答辩讲解】下面这几行是质量度量：把缺失、无效、重复的条数都数出来，
        // 最后写进 quality 表。答辩时说"缺票价多少条""剔掉多少条"用的就是这些数。
        long validCount = staged.filter(col("_valid")).count();
        long missingRequired = staged.filter(col("_valid").equalTo(false)).count();
        // 【答辩讲解】演出侧去重：先按 _valid 过滤，再按 _record_key 去重。
        // 使用稳定窗口排序去重，确保相同输入每次保留同一条记录。
        Dataset<Row> accepted = staged.filter(col("_valid"))
                .withColumn("_dedupe_rank", row_number().over(Window.partitionBy("_record_key")
                        .orderBy(col("_collected_at").desc_nulls_last(),
                                col("_source_url").asc_nulls_last(),
                                col("_raw_source_record_id").asc_nulls_last())))
                .filter(col("_dedupe_rank").equalTo(1))
                .cache();
        long acceptedCount = accepted.count();
        long duplicates = validCount - acceptedCount;
        long missingSourceUrl = staged.filter(col("_source_url").isNull()).count();
        long missingCollectedAt = staged.filter(col("_collected_at").isNull()).count();
        long invalidSource = staged.filter(col("_source_valid").equalTo(false)).count();
        long nonConcertCategory = staged.filter(col("_classified_category").notEqual("演唱会")).count();
        long missingCity = accepted.filter(col("_city").isNull()).count();
        long missingVenue = accepted.filter(callUDF("pulse_venue", col("_venue")).isNull()).count();
        long missingArtist = accepted.filter(callUDF("pulse_artist", col("_artist_name")).isNull()).count();
        long missingPrice = accepted.filter(col("_min_price").isNull().and(col("_max_price").isNull())).count();

        Dataset<Row> clean = accepted.select(
                        lit(options.batchId()).alias("batch_id"),
                        col("_record_key").alias("record_key"),
                        col("_record_key").alias("source_record_id"),
                        col("_raw_source_record_id").alias("input_source_record_id"),
                        // 【答辩讲解】下面这些 coalesce 是缺失值填充：城市、场馆、售票状态缺了就填 Unknown，
                        // 不填会导致后面的分组统计出现空值。但必填字段（演出名、时间）缺失的，
                        // 已经在上面被判无效剔掉了，不会走到这里。
                        coalesce(callUDF("pulse_artist", col("_artist_name")), lit("未知艺人")).alias("artist_name"),
                        col("_classified_category").alias("category"),
                        col("_concert_name").alias("concert_name"),
                        coalesce(col("_city"), lit("Unknown")).alias("city"),
                        coalesce(callUDF("pulse_venue", col("_venue")), lit("场馆未公布")).alias("venue"),
                        col("_show_time").alias("show_time"),
                        col("_show_time_precision").alias("show_time_precision"),
                        coalesce(col("_price_text"), lit("")).alias("price_text"),
                        col("_min_price").alias("min_price"),
                        col("_max_price").alias("max_price"),
                        coalesce(col("_sale_status"), lit("unknown")).alias("sale_status"),
                        coalesce(col("_source_type"), lit("unknown")).alias("source_type"),
                        col("_source_url").alias("source_url"),
                        col("_collected_at").alias("collected_at"),
                        coalesce(col("_created_at"), col("_collected_at")).alias("created_at"))
                .cache();

        return new ConcertFrames(
                clean,
                acceptedCount,
                missingRequired,
                duplicates,
                missingSourceUrl,
                missingCollectedAt,
                invalidSource,
                nonConcertCategory,
                missingCity,
                missingVenue,
                missingArtist,
                missingPrice);
    }

    private Column classifiedCategory() {
        // 【答辩讲解】演出分类：把名称看着像音乐会的（交响乐团、钢琴演奏会等）
        // 从"演唱会"里拆出来单独归成"音乐会"，否则统计演唱会规模会把音乐会计进去、虚高。
        // 这样可以避免把古典音乐会等其他类型计入演唱会市场指标。
        Column title = col("_concert_name");
        Column artist = col("_artist_name");
        Column wasConcert = col("_category").isin("演唱会", "concert");
        Column classicalRepertoire = artist.contains("乐团")
                .and(title.rlike("(卡农|梁祝|坂本龙一|久石让).*音乐会"));
        Column instrumental = title.rlike("交响乐团.*音乐会|交响乐.*音乐会|交响音乐会|新年音乐会.*交响乐团|钢琴演奏会|古琴.*(音乐会|演奏会)")
                .or(classicalRepertoire);

        return when(wasConcert.and(title.rlike("栋笃笑|相声小品")), lit("相声喜剧"))
                .when(wasConcert.and(title.rlike("(京剧|昆曲).*演唱会")), lit("戏曲"))
                .when(wasConcert.and(instrumental), lit("音乐会"))
                .when(col("_category").equalTo("concert"), lit("演唱会"))
                .otherwise(coalesce(col("_category"), lit("未分类")));
    }

    private CommentFrames cleanComments(Dataset<Row> raw) {
        // 【答辩讲解】评论清洗开始。字段规范化和演出那边一样的套路：
        // comment_text 找不到就找 content、再找 comment，多候选列名依次回退。
        Column commentText = text(raw, "comment_text", "content", "comment");
        Column commentTime = timestamp(text(raw, "comment_time", "created_at"));
        Column likeCount = numeric(text(raw, "like_count", "likes"));
        Column userRegion = text(raw, "user_region", "region");
        Column rawSourceType = text(raw, "source_platform", "source_type", "platform");
        Column sourceUrl = text(raw, "source_url", "url");
        Column collectedAt = timestamp(text(raw, "collected_at", "crawl_time"));
        Column rawSourceRecordId = text(raw, "source_record_id", "record_id", "comment_id");
        Column artistName = text(raw, "artist_name", "artist");
        Column concertReferenceId = text(raw, "concert_source_record_id");
        Column concertReferenceName = text(raw, "concert_name");
        Column rawSentimentText = text(raw, "sentiment_score", "sentiment");
        Column sourceType = platform(rawSourceType, sourceUrl);
        Column sourceValid = approvedCommentSource(sourceType, sourceUrl);

        Dataset<Row> staged = raw.select(
                        artistName.alias("_artist_name"),
                        concertReferenceName.alias("_concert_name"),
                        commentText.alias("_comment_text"),
                        commentTime.alias("_comment_time"),
                        coalesce(likeCount.cast("long"), lit(0L)).alias("_like_count"),
                        userRegion.alias("_user_region"),
                        sourceType.alias("_source_type"),
                         sourceValid.alias("_source_valid"),
                        sourceUrl.alias("_source_url"),
                        collectedAt.alias("_collected_at"),
                        rawSourceRecordId.alias("_raw_source_record_id"),
                        concertReferenceId.alias("_concert_reference_id"),
                        rawSentimentText.alias("_raw_sentiment"))
                // 【答辩讲解】⭐ 评论去重（本项目最核心的一步，就在这里）：
                // 评论编号按来源隔离；缺少编号时，使用来源、链接、评论时间和正文生成回退键。
                .withColumn(
                        "_record_key",
                        when(col("_raw_source_record_id").isNotNull(),
                                sha2(concat_ws(
                                                "||",
                                                coalesce(col("_source_type"), lit("unknown")),
                                                lit("id"),
                                                col("_raw_source_record_id")),
                                        256))
                                .otherwise(sha2(concat_ws(
                                                "||",
                                                coalesce(col("_source_type"), lit("unknown")),
                                                coalesce(col("_source_url"), lit("")),
                                                coalesce(col("_comment_time").cast("string"), lit("")),
                                                coalesce(col("_comment_text"), lit(""))),
                                        256)))
                // 【答辩讲解】评论有效性判断：正文为空就没法做情感分析，直接判无效。
                .withColumn("_valid", col("_comment_text").isNotNull()
                        .and(col("_source_url").isNotNull())
                        .and(col("_collected_at").isNotNull())
                        .and(col("_source_type").equalTo("damai"))
                        .and(col("_source_valid"))
                        .and(col("_concert_reference_id").isNull())
                        .and(col("_concert_name").isNull()))
                // 【答辩讲解】⭐ 情感打分：调 pulse_sentiment 这个 UDF，它读的是 TextRules.java
                // 里的正负面词表做规则匹配；输入文件即使带情感分也不采用，避免混入外部算法结果。
                // 词典未命中的文本保留为未判定，不冒充中性。
                .withColumn("_sentiment_score", callUDF("pulse_sentiment", col("_comment_text")))
                .withColumn("_sentiment_method", lit("lexicon_rule"))
                // 【答辩讲解】主题分类：同样用词典 UDF，把评论归到"场馆体验/票价/演出质量/交通"等预定义主题。
                .withColumn("_primary_topic", callUDF("pulse_topic", col("_comment_text")))
                .cache();

        Dataset<Row> valid = staged.filter(col("_valid")).cache();
        Dataset<Row> conflictKeys = valid
                .groupBy("_record_key")
                .agg(countDistinct("_comment_text").alias("_text_variants"))
                .filter(col("_text_variants").gt(1))
                .select(col("_record_key").alias("_conflict_record_key"))
                .cache();
        long conflictingRecords = conflictKeys.count();
        Dataset<Row> nonConflicting = valid.join(
                        conflictKeys,
                        valid.col("_record_key").equalTo(conflictKeys.col("_conflict_record_key")),
                        "left_anti")
                .cache();
        long validCount = nonConflicting.count();
        long missingRequired = staged.filter(col("_valid").equalTo(false)).count();
        Dataset<Row> accepted = nonConflicting
                .withColumn("_dedupe_rank", row_number().over(Window.partitionBy("_record_key")
                        .orderBy(col("_collected_at").desc_nulls_last(),
                                col("_comment_time").desc_nulls_last(),
                                length(col("_comment_text")).desc(),
                                col("_like_count").desc(),
                                col("_source_url").asc_nulls_last(),
                                col("_comment_text").asc())))
                .filter(col("_dedupe_rank").equalTo(1))
                .cache();
        long acceptedCount = accepted.count();
        long duplicates = validCount - acceptedCount;
        long missingSourceUrl = staged.filter(col("_source_url").isNull()).count();
        long missingCollectedAt = staged.filter(col("_collected_at").isNull()).count();
        long invalidSource = staged.filter(col("_source_valid").equalTo(false)).count();
        long unverifiedEventAssociation = staged.filter(
                        col("_concert_reference_id").isNotNull().or(col("_concert_name").isNotNull()))
                .count();
        long missingCommentTime = accepted.filter(col("_comment_time").isNull()).count();
        long missingUserRegion = accepted.filter(col("_user_region").isNull()).count();
        long unknownSource = accepted.filter(col("_source_type").equalTo("unknown")).count();
        long ignoredImportedSentiment = accepted.filter(col("_raw_sentiment").isNotNull()).count();
        long lexicalSentiment = accepted.filter(
                        col("_sentiment_method").equalTo("lexicon_rule")
                                .and(col("_sentiment_score").isNotNull()))
                .count();
        long lexiconUnmatched = accepted.filter(col("_sentiment_score").isNull()).count();

        // 不把旧清单中的场次编号映射到当前演出表；仅保留采集时由唯一巡演 ipId
        // 明确得到的艺人归属。具体场次外键始终为空，避免名称猜测造成伪关联。
        Dataset<Row> clean = accepted.select(
                        lit(options.batchId()).alias("batch_id"),
                        col("_record_key").alias("record_key"),
                        coalesce(col("_raw_source_record_id"), col("_record_key")).alias("source_record_id"),
                        lit(null).cast("string").alias("concert_source_record_id"),
                        col("_artist_name").alias("artist_name"),
                        lit(null).cast("string").alias("concert_name"),
                        col("_comment_text").alias("comment_text"),
                        col("_comment_time").alias("comment_time"),
                        col("_like_count").alias("like_count"),
                        col("_user_region").alias("user_region"),
                        coalesce(col("_source_type"), lit("unknown")).alias("source_type"),
                        col("_source_url").alias("source_url"),
                        col("_collected_at").alias("collected_at"),
                        col("_sentiment_score").alias("sentiment_score"),
                        col("_sentiment_method").alias("sentiment_method"),
                        col("_primary_topic").alias("primary_topic"),
                        when(col("_artist_name").isNotNull(), lit("artist"))
                                .otherwise(lit("platform")).alias("scope_type"),
                        coalesce(col("_artist_name"), col("_source_type")).alias("scope_key"),
                        coalesce(col("_artist_name"), col("_source_type")).alias("scope_label"))
                .cache();

        return new CommentFrames(
                clean,
                acceptedCount,
                missingRequired,
                duplicates,
                conflictingRecords,
                missingSourceUrl,
                missingCollectedAt,
                invalidSource,
                unverifiedEventAssociation,
                missingCommentTime,
                missingUserRegion,
                unknownSource,
                ignoredImportedSentiment,
                lexicalSentiment,
                lexiconUnmatched);
    }

    private Dataset<Row> buildMetrics(Dataset<Row> concerts, Dataset<Row> comments) {
        // 【答辩讲解】算聚合指标：网站上看板的那几张图（城市热度、票价分布、月度趋势、情感占比）
        // 数据都是从这里算出来的。每个指标都是一个 groupBy + 聚合，最后打包成统一的指标表。
        List<Dataset<Row>> frames = new ArrayList<>();
        Dataset<Row> knownCityConcerts = concerts.filter(
                col("city").isNotNull()
                        .and(length(trim(col("city"))).gt(0))
                        .and(lower(trim(col("city"))).notEqual("unknown")));

        // 【答辩讲解】城市演出热度：按城市分组数有多少场，网页上"城市热度 Top10"就用这个。
        frames.add(asMetric(
                knownCityConcerts.groupBy("city")
                        .agg(count(lit(1)).alias("_value"))
                        .select(col("city").alias("_key"), col("city").alias("_label"), col("_value"), col("_value").alias("_sample")),
                "CITY_CONCERT_COUNT", "concert", false));

        Dataset<Row> cityPrices = knownCityConcerts.withColumn(
                        "_price_mid",
                        when(col("min_price").isNotNull().and(col("max_price").isNotNull()),
                                        col("min_price").plus(col("max_price")).divide(2.0))
                                .otherwise(coalesce(col("min_price"), col("max_price"))))
                .filter(col("_price_mid").isNotNull())
                .groupBy("city")
                .agg(avg("_price_mid").alias("_value"), count(lit(1)).alias("_sample"))
                .select(col("city").alias("_key"), col("city").alias("_label"), col("_value"), col("_sample"));
        frames.add(asMetric(cityPrices, "CITY_AVERAGE_PRICE", "CNY", false));

        frames.add(asMetric(
                concerts.withColumn("_month", date_format(col("show_time"), "yyyy-MM"))
                        .groupBy("_month")
                        .agg(count(lit(1)).alias("_value"))
                        .select(col("_month").alias("_key"), col("_month").alias("_label"), col("_value"), col("_value").alias("_sample")),
                "MONTH_CONCERT_COUNT", "concert", true));

        Dataset<Row> prices = concerts.withColumn(
                        "_price_mid",
                        when(col("min_price").isNotNull().and(col("max_price").isNotNull()),
                                        col("min_price").plus(col("max_price")).divide(2.0))
                                .otherwise(coalesce(col("min_price"), col("max_price"))))
                .withColumn(
                        "_bucket",
                        when(col("_price_mid").isNull(), lit("unknown"))
                                .when(col("_price_mid").lt(300), lit("under_300"))
                                .when(col("_price_mid").lt(600), lit("300_599"))
                                .when(col("_price_mid").lt(1000), lit("600_999"))
                                .otherwise(lit("1000_plus")))
                .groupBy("_bucket")
                .agg(count(lit(1)).alias("_value"))
                .select(col("_bucket").alias("_key"), col("_bucket").alias("_label"), col("_value"), col("_value").alias("_sample"));
        frames.add(asMetric(prices, "PRICE_CONCERT_COUNT", "concert", false));

        Dataset<Row> artistConcerts = concerts
                .filter(col("artist_name").notEqual(lit("未知艺人")))
                .groupBy(lower(col("artist_name")).alias("_artist_key"))
                .agg(
                        first("artist_name").alias("_artist_label"),
                        count(lit(1)).alias("_concert_count"));
        Dataset<Row> artistComments = comments.filter(col("artist_name").isNotNull())
                .groupBy(lower(col("artist_name")).alias("_comment_artist_key"))
                .agg(
                        count(lit(1)).alias("_comment_count"),
                        sum("like_count").alias("_like_count"));
        Dataset<Row> artistHeat = artistConcerts.join(
                        artistComments,
                        artistConcerts.col("_artist_key").equalTo(artistComments.col("_comment_artist_key")),
                        "left")
                .select(
                        artistConcerts.col("_artist_key").alias("_key"),
                        artistConcerts.col("_artist_label").alias("_label"),
                        round(
                                        col("_concert_count").multiply(3.0)
                                                .plus(coalesce(col("_comment_count"), lit(0L)).multiply(2.0))
                                                .plus(least(
                                                                greatest(coalesce(col("_like_count"), lit(0L)), lit(0L)),
                                                                lit(1_000_000L))
                                                        .divide(20_000.0)),
                                        1)
                                .alias("_value"),
                        col("_concert_count")
                                .plus(coalesce(col("_comment_count"), lit(0L)))
                                .alias("_sample"));
        frames.add(asMetric(artistHeat, "ARTIST_HEAT", "score", false));

        // 场馆榜不统计"场馆未公布"这种占位值，否则榜首会出现一堆"场馆待定"
        frames.add(groupCountMetric(
                concerts.filter(col("venue").notEqual(lit("场馆未公布"))),
                "venue", "VENUE_CONCERT_COUNT", "concert"));
        frames.add(groupCountMetric(concerts, "source_type", "SOURCE_CONCERT_COUNT", "concert"));
        frames.add(groupCountMetric(comments, "source_type", "SOURCE_COMMENT_COUNT", "comment"));

        Dataset<Row> sentimentOverall = comments.withColumn("_bucket", sentimentBucket(col("sentiment_score")))
                .groupBy("_bucket")
                .agg(count(lit(1)).alias("_value"))
                .select(col("_bucket").alias("_key"), col("_bucket").alias("_label"), col("_value"), col("_value").alias("_sample"));
        frames.add(asMetric(sentimentOverall, "SENTIMENT_OVERALL_COUNT", "comment", false));

        Dataset<Row> sentimentBySource = comments.withColumn("_bucket", sentimentBucket(col("sentiment_score")))
                .groupBy("source_type", "_bucket")
                .agg(count(lit(1)).alias("_value"))
                .select(
                        concat_ws("|", col("source_type"), col("_bucket")).alias("_key"),
                        concat_ws(" / ", col("source_type"), col("_bucket")).alias("_label"),
                        col("_value"),
                        col("_value").alias("_sample"));
        frames.add(asMetric(sentimentBySource, "SENTIMENT_SOURCE_COUNT", "comment", false));

        Dataset<Row> topics = comments.groupBy("source_type", "primary_topic")
                .agg(count(lit(1)).alias("_value"))
                .select(
                        concat_ws("|", col("source_type"), col("primary_topic")).alias("_key"),
                        concat_ws(" / ", col("source_type"), col("primary_topic")).alias("_label"),
                        col("_value"),
                        col("_value").alias("_sample"));
        frames.add(asMetric(topics, "TOPIC_SOURCE_COUNT", "comment", false));

        Dataset<Row> result = frames.get(0);
        for (int index = 1; index < frames.size(); index++) {
            result = result.unionByName(frames.get(index));
        }
        return result;
    }

    private Dataset<Row> groupCountMetric(
            Dataset<Row> source,
            String dimension,
            String metricType,
            String unit) {
        Dataset<Row> grouped = source.groupBy(dimension)
                .agg(count(lit(1)).alias("_value"))
                .select(
                        col(dimension).alias("_key"),
                        col(dimension).alias("_label"),
                        col("_value"),
                        col("_value").alias("_sample"));
        return asMetric(grouped, metricType, unit, false);
    }

    private Dataset<Row> asMetric(
            Dataset<Row> grouped,
            String metricType,
            String unit,
            boolean monthPeriod) {
        Column periodStart = monthPeriod
                ? to_date(concat(col("_key"), lit("-01")))
                : lit(null).cast("date");
        Column periodEnd = monthPeriod
                ? last_day(periodStart)
                : lit(null).cast("date");
        return grouped.select(
                lit(options.batchId()).alias("batch_id"),
                lit(metricType).alias("metric_type"),
                substring(col("_key").cast("string"), 1, 160).alias("dimension_key"),
                substring(col("_label").cast("string"), 1, 200).alias("dimension_label"),
                col("_value").cast("double").alias("metric_value"),
                lit(unit).alias("unit"),
                periodStart.alias("period_start"),
                periodEnd.alias("period_end"),
                col("_sample").cast("long").alias("sample_count"),
                current_timestamp().alias("generated_at"));
    }

    private Dataset<Row> buildQualityMetrics(ConcertFrames concerts, CommentFrames comments) {
        List<Row> rows = new ArrayList<>();
        // 【答辩讲解】质量指标一条条列出来，最后写成 quality 表。
        // 答辩时念这些 key 就行：ACCEPTED_RECORDS（留下多少）、DUPLICATE_RECORDS（去掉多少重复）、
        // MISSING_REQUIRED（必填缺失剔掉多少）、MISSING_CITY / VENUE / ARTIST / PRICE（保留记录中哪些字段缺失）、
        // LEXICON_SENTIMENT（多少条是词典算的情感）。
        long concertInput = concerts.acceptedCount() + concerts.missingRequired() + concerts.duplicates();
        long commentInput = comments.acceptedCount() + comments.missingRequired() + comments.duplicates();
        addQuality(rows, "ALL_CONCERTS", "ACCEPTED_RECORDS", concerts.acceptedCount(), concertInput, 0, 0);
        addQuality(rows, "ALL_CONCERTS", "MISSING_REQUIRED", concerts.missingRequired(), concertInput, concerts.missingRequired(), 0);
        addQuality(rows, "ALL_CONCERTS", "DUPLICATE_RECORDS", concerts.duplicates(), concertInput, 0, concerts.duplicates());
        addQuality(rows, "ALL_CONCERTS", "MISSING_SOURCE_URL", concerts.missingSourceUrl(), concertInput, concerts.missingSourceUrl(), 0);
        addQuality(rows, "ALL_CONCERTS", "MISSING_COLLECTED_AT", concerts.missingCollectedAt(), concertInput, concerts.missingCollectedAt(), 0);
        addQuality(rows, "ALL_CONCERTS", "INVALID_SOURCE", concerts.invalidSource(), concertInput, concerts.invalidSource(), 0);
        addQuality(rows, "ALL_CONCERTS", "NON_CONCERT_CATEGORY", concerts.nonConcertCategory(), concertInput, concerts.nonConcertCategory(), 0);
        addQuality(rows, "ALL_CONCERTS", "MISSING_CITY", concerts.missingCity(), concerts.acceptedCount(), concerts.missingCity(), 0);
        addQuality(rows, "ALL_CONCERTS", "MISSING_VENUE", concerts.missingVenue(), concerts.acceptedCount(), concerts.missingVenue(), 0);
        addQuality(rows, "ALL_CONCERTS", "MISSING_ARTIST", concerts.missingArtist(), concerts.acceptedCount(), concerts.missingArtist(), 0);
        addQuality(rows, "ALL_CONCERTS", "MISSING_PRICE", concerts.missingPrice(), concerts.acceptedCount(), concerts.missingPrice(), 0);
        addQuality(rows, "ALL_COMMENTS", "ACCEPTED_RECORDS", comments.acceptedCount(), commentInput, 0, 0);
        addQuality(rows, "ALL_COMMENTS", "MISSING_REQUIRED", comments.missingRequired(), commentInput, comments.missingRequired(), 0);
        addQuality(rows, "ALL_COMMENTS", "DUPLICATE_RECORDS", comments.duplicates(), commentInput, 0, comments.duplicates());
        addQuality(rows, "ALL_COMMENTS", "CONFLICTING_RECORDS", comments.conflictingRecords(), commentInput,
                comments.conflictingRecords(), 0);
        addQuality(rows, "ALL_COMMENTS", "MISSING_SOURCE_URL", comments.missingSourceUrl(), commentInput, comments.missingSourceUrl(), 0);
        addQuality(rows, "ALL_COMMENTS", "MISSING_COLLECTED_AT", comments.missingCollectedAt(), commentInput, comments.missingCollectedAt(), 0);
        addQuality(rows, "ALL_COMMENTS", "INVALID_SOURCE", comments.invalidSource(), commentInput, comments.invalidSource(), 0);
        addQuality(rows, "ALL_COMMENTS", "UNVERIFIED_EVENT_ASSOCIATION", comments.unverifiedEventAssociation(), commentInput,
                comments.unverifiedEventAssociation(), 0);
        addQuality(rows, "ALL_COMMENTS", "MISSING_COMMENT_TIME", comments.missingCommentTime(), comments.acceptedCount(), comments.missingCommentTime(), 0);
        addQuality(rows, "ALL_COMMENTS", "MISSING_USER_REGION", comments.missingUserRegion(), comments.acceptedCount(), comments.missingUserRegion(), 0);
        addQuality(rows, "ALL_COMMENTS", "UNKNOWN_SOURCE", comments.unknownSource(), comments.acceptedCount(), comments.unknownSource(), 0);
        addQuality(rows, "ALL_COMMENTS", "IMPORTED_SENTIMENT_IGNORED", comments.ignoredImportedSentiment(),
                comments.acceptedCount(), comments.ignoredImportedSentiment(), 0);
        addQuality(rows, "ALL_COMMENTS", "LEXICON_SENTIMENT", comments.lexicalSentiment(), comments.acceptedCount(), 0, 0);
        addQuality(rows, "ALL_COMMENTS", "LEXICON_UNMATCHED", comments.lexiconUnmatched(), comments.acceptedCount(), comments.lexiconUnmatched(), 0);

        StructType schema = DataTypes.createStructType(new StructField[] {
            DataTypes.createStructField("batch_id", DataTypes.StringType, false),
            DataTypes.createStructField("source_type", DataTypes.StringType, false),
            DataTypes.createStructField("metric_key", DataTypes.StringType, false),
            DataTypes.createStructField("metric_value", DataTypes.DoubleType, false),
            DataTypes.createStructField("record_count", DataTypes.LongType, false),
            DataTypes.createStructField("missing_count", DataTypes.LongType, false),
            DataTypes.createStructField("duplicate_count", DataTypes.LongType, false)
        });
        Dataset<Row> aggregateQuality = spark.createDataFrame(rows, schema)
                .withColumn("generated_at", current_timestamp());
        Dataset<Row> acceptedSources = concerts.clean()
                .select(upper(col("source_type")).alias("source_type"))
                .unionByName(comments.clean().select(upper(col("source_type")).alias("source_type")));
        Dataset<Row> sourceCoverage = acceptedSources.groupBy("source_type")
                // 来源覆盖数量由当前批次实时聚合，不在代码里写死。
                .agg(count(lit(1)).alias("_record_count"))
                .select(
                        lit(options.batchId()).alias("batch_id"),
                        col("source_type"),
                        lit("SOURCE_COVERAGE").alias("metric_key"),
                        col("_record_count").cast("double").alias("metric_value"),
                        col("_record_count").cast("long").alias("record_count"),
                        lit(0L).alias("missing_count"),
                        lit(0L).alias("duplicate_count"),
                        current_timestamp().alias("generated_at"));
        return aggregateQuality.unionByName(sourceCoverage);
    }

    private void addQuality(
            List<Row> rows,
            String sourceType,
            String metricKey,
            long value,
            long records,
            long missing,
            long duplicates) {
        rows.add(RowFactory.create(
                options.batchId(), sourceType, metricKey, (double) value, records, missing, duplicates));
    }

    private Dataset<Row> buildRisks(Dataset<Row> comments) {
        // 【答辩讲解】舆情风险：找出负面评价集中的已验证艺人范围或平台范围。
        // 判定规则有两条同时满足才算风险：平均情感 ≤ -0.2，且负面评论占比 ≥ 阈值，
        // 而且样本数要够（riskMinSamples），否则两条差评就说"有风险"太草率。
        Dataset<Row> scoped = comments.filter(
                        col("scope_key").isNotNull().and(col("sentiment_score").isNotNull()))
                .select(
                        "scope_type",
                        "scope_key",
                        "scope_label",
                        "source_type",
                        "sentiment_score",
                        "primary_topic");

        Dataset<Row> aggregates = scoped.groupBy("scope_type", "scope_key", "scope_label", "source_type")
                .agg(
                        count(lit(1)).alias("sample_size"),
                        sum(when(col("sentiment_score").leq(NEGATIVE_THRESHOLD), 1).otherwise(0))
                                .alias("negative_count"),
                        avg("sentiment_score").alias("average_sentiment"))
                .withColumn("negative_ratio", col("negative_count").divide(col("sample_size")));

        Dataset<Row> topicCounts = scoped.filter(col("sentiment_score").leq(NEGATIVE_THRESHOLD))
                .groupBy("scope_type", "scope_key", "source_type", "primary_topic")
                .agg(count(lit(1)).alias("_topic_count"));
        WindowSpec topicWindow = Window.partitionBy("scope_type", "scope_key", "source_type")
                .orderBy(col("_topic_count").desc(), col("primary_topic").asc());
        Dataset<Row> mainTopics = topicCounts.withColumn("_rank", row_number().over(topicWindow))
                .filter(col("_rank").equalTo(1))
                .select(
                        col("scope_type").alias("_topic_scope_type"),
                        col("scope_key").alias("_topic_scope_key"),
                        col("source_type").alias("_topic_source_type"),
                        col("primary_topic").alias("primary_negative_topic"));

        Dataset<Row> risks = aggregates.join(
                mainTopics,
                aggregates.col("scope_type").equalTo(mainTopics.col("_topic_scope_type"))
                        .and(aggregates.col("scope_key").equalTo(mainTopics.col("_topic_scope_key")))
                        .and(aggregates.col("source_type").equalTo(mainTopics.col("_topic_source_type"))),
                "left");

        return risks.select(
                lit(options.batchId()).alias("batch_id"),
                aggregates.col("scope_type"),
                aggregates.col("scope_key").alias("scope_id"),
                aggregates.col("scope_label").alias("object_name"),
                aggregates.col("source_type"),
                col("negative_ratio"),
                col("average_sentiment"),
                coalesce(col("primary_negative_topic"), lit("none")).alias("primary_negative_topic"),
                col("sample_size"),
                lit(NEGATIVE_THRESHOLD).alias("threshold_value"),
                lit(options.riskMinSamples()).cast("long").alias("minimum_sample_size"),
                lit("sentiment <= -0.2 and negative ratio >= " + options.riskNegativeRatio())
                        .alias("trigger_rule"),
                col("sample_size").geq(options.riskMinSamples())
                        .and(col("negative_ratio").geq(options.riskNegativeRatio()))
                        .alias("triggered"),
                current_timestamp().alias("detected_at"));
    }

    private Column sentimentBucket(Column score) {
        return when(score.isNull(), lit("unclassified"))
                .when(score.leq(NEGATIVE_THRESHOLD), lit("negative"))
                .when(score.geq(POSITIVE_THRESHOLD), lit("positive"))
                .otherwise(lit("neutral"));
    }

    private Column text(Dataset<Row> data, String... aliases) {
        // 【答辩讲解】这是"多候选列名回退"的实现：按顺序找，找到第一个存在的就用。
        // 不同平台的 CSV 叫法不一样（concert_name / name / title 其实是同一个东西），
        // 这样写就不用为每个平台单独改代码。
        // 同时顺手做了两件事：把连续空白压成一个空格、空字符串当 NULL 处理。
        Set<String> names = new HashSet<>(Arrays.asList(data.columns()));
        for (String alias : aliases) {
            if (names.contains(alias)) {
                Column value = trim(regexp_replace(col(alias).cast("string"), "\\s+", " "));
                return when(length(value).equalTo(0), lit(null).cast("string")).otherwise(value);
            }
        }
        return lit(null).cast("string");
    }

    private Column numeric(Column value) {
        return regexp_replace(value, "[^0-9+\\-.]", "").cast("double");
    }

    private Column timestamp(Column value) {
        // 【答辩讲解】时间格式归一化：不同来源的时间写法不一样
        // （有的 "2026-03-07 19:30:00"、有的 "2026-03-07"、有的 "2026.03.07"、有的带 T 和时区）。
        // 这里按顺序试 8 种常见格式，谁能解析出来就用哪个，统一成标准时间类型。
        // 这样后面按月份、按年份分组统计才不会漏数据或算错。
        return coalesce(
                to_timestamp(value, "yyyy-MM-dd HH:mm:ss.SSSSSS"),
                to_timestamp(value, "yyyy-MM-dd HH:mm:ss"),
                to_timestamp(value, "yyyy-MM-dd HH:mm"),
                to_timestamp(value, "yyyy-MM-dd'T'HH:mm:ss"),
                to_timestamp(value, "yyyy-MM-dd'T'HH:mm:ss.SSSXXX"),
                to_timestamp(value, "yyyy-MM-dd'T'HH:mm:ssXXX"),
                to_timestamp(value, "yyyy/MM/dd HH:mm:ss.SSSSSS"),
                to_timestamp(value, "yyyy/MM/dd HH:mm:ss"),
                to_timestamp(value, "yyyy/MM/dd HH:mm"),
                to_timestamp(value, "yyyy/M/d H:mm:ss"),
                to_timestamp(value, "yyyy/M/d H:mm"),
                to_timestamp(value, "yyyy/MM/dd"),
                to_timestamp(value, "yyyy/M/d"),
                to_timestamp(value, "yyyy.MM.dd HH:mm:ss"),
                to_timestamp(value, "yyyy.MM.dd HH:mm"),
                to_timestamp(value, "yyyy-MM-dd"),
                to_timestamp(value, "yyyy.MM.dd"));
    }

    private Column platform(Column sourceType, Column sourceUrl) {
        // 来源类型只相信输入字段，URL 仅用于独立校验，避免用链接中的文字伪造平台身份。
        return when(sourceType.isNotNull(), substring(lower(sourceType), 1, 40))
                .otherwise(lit("unknown"));
    }

    private Column approvedConcertSource(Column sourceType, Column sourceUrl) {
        Column url = lower(coalesce(sourceUrl, lit("")));
        String hostSuffix = "(?::[0-9]{1,5})?(?:[/\\?#].*)?$";
        Column piaoniu = url.rlike("^https://([a-z0-9-]+\\.)*piaoniu\\.com" + hostSuffix);
        Column showstart = url.rlike("^https://([a-z0-9-]+\\.)*showstart\\.com" + hostSuffix);
        Column publicPage = url.rlike(
                "^https://([a-z0-9-]+\\.)*(news\\.qq\\.com|bjd\\.com\\.cn|[a-z0-9-]+\\.gov\\.cn)"
                        + hostSuffix);
        return sourceType.equalTo("piaoniu").and(piaoniu)
                .or(sourceType.equalTo("showstart").and(showstart))
                .or(sourceType.equalTo("public").and(publicPage));
    }

    private Column approvedCommentSource(Column sourceType, Column sourceUrl) {
        Column url = lower(coalesce(sourceUrl, lit("")));
        return sourceType.equalTo("damai")
                .and(url.rlike("^https://([a-z0-9-]+\\.)*damai\\.cn(?::[0-9]{1,5})?(?:[/\\?#].*)?$"));
    }

    private void registerFunctions() {
        // 【答辩讲解】注册四个 UDF：情感打分、主题分类、票价取最小、票价取最大。
        // Spark 集群会把这些函数分发到各 Executor 并行执行。
        spark.udf().register(
                "pulse_sentiment",
                (UDF1<String, Double>) TextRules::sentiment,
                DataTypes.DoubleType);
        spark.udf().register(
                "pulse_topic",
                (UDF1<String, String>) TextRules::topic,
                DataTypes.StringType);
        spark.udf().register(
                "pulse_price_min",
                (UDF1<String, Double>) value -> price(value, true),
                DataTypes.DoubleType);
        spark.udf().register(
                "pulse_price_max",
                (UDF1<String, Double>) value -> price(value, false),
                DataTypes.DoubleType);
        // 【答辩讲解】场馆名、艺人名的规范化：去掉页面装饰符号、开场时间、地址备注，
        // 以及 "Unknown venue"/"Unknown artist" 这类占位值。
        spark.udf().register(
                "pulse_venue",
                (UDF1<String, String>) TextRules::venue,
                DataTypes.StringType);
        spark.udf().register(
                "pulse_artist",
                (UDF1<String, String>) TextRules::artist,
                DataTypes.StringType);
    }

    private static Double price(String value, boolean minimum) {
        if (value == null || value.isBlank()) {
            return null;
        }
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("\\d+(?:\\.\\d+)?").matcher(value);
        Double selected = null;
        while (matcher.find()) {
            double candidate = Double.parseDouble(matcher.group());
            if (selected == null || (minimum ? candidate < selected : candidate > selected)) {
                selected = candidate;
            }
        }
        return selected;
    }

    private record ConcertFrames(
            Dataset<Row> clean,
            long acceptedCount,
            long missingRequired,
            long duplicates,
            long missingSourceUrl,
            long missingCollectedAt,
            long invalidSource,
            long nonConcertCategory,
            long missingCity,
            long missingVenue,
            long missingArtist,
            long missingPrice) {
    }

    private record CommentFrames(
            Dataset<Row> clean,
            long acceptedCount,
            long missingRequired,
            long duplicates,
            long conflictingRecords,
            long missingSourceUrl,
            long missingCollectedAt,
            long invalidSource,
            long unverifiedEventAssociation,
            long missingCommentTime,
            long missingUserRegion,
            long unknownSource,
            long ignoredImportedSentiment,
            long lexicalSentiment,
            long lexiconUnmatched) {
    }
}
