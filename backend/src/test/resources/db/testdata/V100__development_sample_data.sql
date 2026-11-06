INSERT INTO analytics_batch
    (id, batch_id, dataset_version, source_type, raw_path, status, input_count, success_count,
     failed_count, missing_count, duplicate_count, collected_at, started_at, finished_at, published_at, notes)
VALUES
    (1, 'sample-2025-04-18', 'sample-v1', 'MULTI_SOURCE', '/concert/raw/sample-2025-04-18', 'PUBLISHED',
     12, 12, 0, 1, 1, '2025-04-18 10:00:00', '2025-04-18 10:10:00', '2025-04-18 10:12:30',
     '2025-04-18 10:13:00', 'Automated test fixture; never loaded by local or production profiles');

INSERT INTO clean_concert
    (id, data_batch_id, artist_name, category, concert_name, city, venue, show_time, price_text,
     min_price, max_price, sale_status, source_type, source_record_id, source_url, collected_at, created_at)
VALUES
    (1, 1, '周杰伦', '演唱会', '嘉年华 - 上海场', '上海', '梅赛德斯-奔驰文化中心',
     TIMESTAMPADD(MINUTE, 1170, TIMESTAMPADD(DAY, 45, CURRENT_DATE)),
     '580 / 880 / 1280 / 1680', 580, 1680, '已售罄', 'DAMAI', 'sample-concert-1',
     'https://example.invalid/concert/1', '2025-04-18 10:00:00', '2025-04-18 10:13:00'),
    (2, 1, '周杰伦', '演唱会', '嘉年华 - 北京场', '北京', '国家体育场',
     TIMESTAMPADD(MINUTE, 1170, TIMESTAMPADD(DAY, 90, CURRENT_DATE)),
     '680 / 980 / 1380 / 1880', 680, 1880, '售票中', 'DAMAI', 'sample-concert-2',
     'https://example.invalid/concert/2', '2025-04-18 10:00:00', '2025-04-18 10:13:00'),
    (3, 1, '陈奕迅', '演唱会', 'FEAR and DREAMS - 广州场', '广州', '宝能广州国际体育演艺中心',
     TIMESTAMPADD(MINUTE, 1170, TIMESTAMPADD(DAY, 135, CURRENT_DATE)),
     '480 / 780 / 1180 / 1580', 480, 1580, '售票中', 'SHOWSTART', 'sample-concert-3',
     'https://example.invalid/concert/3', '2025-04-18 10:00:00', '2025-04-18 10:13:00'),
    (4, 1, '五月天', '演唱会', '回到那一天 - 成都场', '成都', '东安湖体育公园主体育场',
     TIMESTAMPADD(MINUTE, 1140, TIMESTAMPADD(DAY, 180, CURRENT_DATE)),
     '355 / 655 / 955 / 1255', 355, 1255, '预售', 'PIAONIU', 'sample-concert-4',
     'https://example.invalid/concert/4', '2025-04-18 10:00:00', '2025-04-18 10:13:00');

INSERT INTO clean_comment
    (id, concert_id, data_batch_id, artist_name, comment_text, comment_time, like_count, user_region,
     source_type, source_record_id, source_url, collected_at, sentiment_score, primary_topic)
VALUES
    (1, 1, 1, '周杰伦', '现场编曲和灯光很有层次。', '2025-03-05 12:00:00', 328, '上海', 'DAMAI', 'sample-comment-1',
     'https://example.invalid/comment/1', '2025-04-18 10:00:00', 0.82, '现场体验'),
    (2, 1, 1, '周杰伦', '排队时间太长，入场指引不清楚。', '2025-03-05 13:00:00', 214, '江苏', 'DAMAI', 'sample-comment-2',
     'https://example.invalid/comment/2', '2025-04-18 10:00:00', -0.46, '入场组织'),
    (3, NULL, 1, '陈奕迅', '歌曲很好听，期待下一场。', '2025-03-06 08:00:00', 92, '广东', 'DAMAI', 'sample-comment-3',
     'https://example.invalid/comment/3', '2025-04-18 10:00:00', 0.65, '曲目'),
    (4, NULL, 1, '五月天', '票价还能接受，座位视野一般。', '2025-03-07 09:30:00', 48, '四川', 'DAMAI', 'sample-comment-4',
     'https://example.invalid/comment/4', '2025-04-18 10:00:00', -0.05, '票价与座位'),
    (5, 3, 1, '陈奕迅', '音响偶尔听不清，人流疏导需要改进。', '2025-03-08 10:30:00', 35, '广东', 'DAMAI', 'sample-comment-5',
     'https://example.invalid/comment/5', '2025-04-18 10:00:00', -0.32, '音响与疏导');

INSERT INTO analysis_metric
    (data_batch_id, metric_type, dimension_key, dimension_label, metric_value, unit, period_start, period_end, generated_at)
VALUES
    (1, 'CITY_CONCERT_COUNT', 'shanghai', '上海', 1, '场', NULL, NULL, '2025-04-18 10:12:30'),
    (1, 'CITY_CONCERT_COUNT', 'beijing', '北京', 1, '场', NULL, NULL, '2025-04-18 10:12:30'),
    (1, 'CITY_CONCERT_COUNT', 'guangzhou', '广州', 1, '场', NULL, NULL, '2025-04-18 10:12:30'),
    (1, 'CITY_CONCERT_COUNT', 'chengdu', '成都', 1, '场', NULL, NULL, '2025-04-18 10:12:30'),
    (1, 'CITY_AVERAGE_PRICE', 'shanghai', '上海', 1130, '元', NULL, NULL, '2025-04-18 10:12:30'),
    (1, 'CITY_AVERAGE_PRICE', 'beijing', '北京', 1280, '元', NULL, NULL, '2025-04-18 10:12:30'),
    (1, 'CITY_AVERAGE_PRICE', 'guangzhou', '广州', 1030, '元', NULL, NULL, '2025-04-18 10:12:30'),
    (1, 'CITY_AVERAGE_PRICE', 'chengdu', '成都', 805, '元', NULL, NULL, '2025-04-18 10:12:30'),
    (1, 'ARTIST_HEAT', 'zhou-jielun', '周杰伦', 92, 'score', NULL, NULL, '2025-04-18 10:12:30'),
    (1, 'ARTIST_HEAT', 'chen-yixun', '陈奕迅', 83, 'score', NULL, NULL, '2025-04-18 10:12:30'),
    (1, 'ARTIST_HEAT', 'mayday', '五月天', 79, 'score', NULL, NULL, '2025-04-18 10:12:30'),
    (1, 'VENUE_CONCERT_COUNT', 'mercedes-benz-arena', '梅赛德斯-奔驰文化中心', 1, '场', NULL, NULL, '2025-04-18 10:12:30'),
    (1, 'VENUE_CONCERT_COUNT', 'birds-nest', '国家体育场', 1, '场', NULL, NULL, '2025-04-18 10:12:30');

INSERT INTO analysis_metric
    (data_batch_id, metric_type, dimension_key, dimension_label, metric_value, unit,
     period_start, period_end, generated_at)
SELECT
    1,
    'MONTH_CONCERT_COUNT',
    FORMATDATETIME(show_time, 'yyyy-MM'),
    FORMATDATETIME(show_time, 'yyyy-MM'),
    COUNT(*),
    '场',
    CAST(DATE_TRUNC('MONTH', show_time) AS DATE),
    LAST_DAY(show_time),
    '2025-04-18 10:12:30'
FROM clean_concert
WHERE data_batch_id = 1
GROUP BY
    FORMATDATETIME(show_time, 'yyyy-MM'),
    CAST(DATE_TRUNC('MONTH', show_time) AS DATE),
    LAST_DAY(show_time);

INSERT INTO analysis_metric
    (data_batch_id, metric_type, dimension_key, dimension_label, metric_value, unit,
     period_start, period_end, generated_at)
SELECT
    1,
    'PRICE_CONCERT_COUNT',
    price_bucket,
    CASE price_bucket
        WHEN 'under_300' THEN '300 元以下'
        WHEN '300_599' THEN '300-599 元'
        WHEN '600_999' THEN '600-999 元'
        WHEN '1000_plus' THEN '1000 元及以上'
        ELSE '价格未知'
    END,
    COUNT(*),
    '场',
    NULL,
    NULL,
    '2025-04-18 10:12:30'
FROM (
    SELECT CASE
        WHEN price_midpoint IS NULL THEN 'unknown'
        WHEN price_midpoint < 300 THEN 'under_300'
        WHEN price_midpoint < 600 THEN '300_599'
        WHEN price_midpoint < 1000 THEN '600_999'
        ELSE '1000_plus'
    END AS price_bucket
    FROM (
        SELECT
            (COALESCE(min_price, max_price) + COALESCE(max_price, min_price)) / 2 AS price_midpoint
        FROM clean_concert
        WHERE data_batch_id = 1
    ) price_midpoints
) price_buckets
GROUP BY price_bucket;

INSERT INTO analysis_metric
    (data_batch_id, metric_type, dimension_key, dimension_label, metric_value, unit,
     period_start, period_end, generated_at)
SELECT
    1,
    'WEEKDAY_CONCERT_COUNT',
    CAST(weekday_number AS VARCHAR(2)),
    CASE weekday_number
        WHEN 1 THEN '星期日'
        WHEN 2 THEN '星期一'
        WHEN 3 THEN '星期二'
        WHEN 4 THEN '星期三'
        WHEN 5 THEN '星期四'
        WHEN 6 THEN '星期五'
        ELSE '星期六'
    END,
    COUNT(*),
    '场',
    NULL,
    NULL,
    '2025-04-18 10:12:30'
FROM (
    SELECT DAY_OF_WEEK(show_time) AS weekday_number
    FROM clean_concert
    WHERE data_batch_id = 1
) concert_weekdays
GROUP BY weekday_number;

INSERT INTO data_quality_metric
    (data_batch_id, source_type, metric_key, metric_value, record_count, missing_count, duplicate_count, generated_at)
VALUES
    (1, 'DAMAI', 'COMPLETENESS_RATE', 0.98, 7, 1, 0, '2025-04-18 10:12:30'),
    (1, 'SHOWSTART', 'COMPLETENESS_RATE', 1.00, 2, 0, 0, '2025-04-18 10:12:30'),
    (1, 'PIAONIU', 'COMPLETENESS_RATE', 0.96, 2, 0, 1, '2025-04-18 10:12:30'),
    (1, 'PUBLIC', 'COMPLETENESS_RATE', 1.00, 1, 0, 0, '2025-04-18 10:12:30');

INSERT INTO sentiment_risk
    (data_batch_id, scope_type, scope_id, object_name, negative_ratio, risk_level, primary_negative_topic,
     sample_size, threshold_value, minimum_sample_size, trigger_rule, detected_at)
VALUES
    (1, 'ARTIST', 'chen-yixun', '陈奕迅', 0.50, 'ALERT', '音响与疏导', 128, -0.20, 20,
     '负面占比 >= 40% 且样本量 >= 20', '2025-04-18 10:12:30'),
    (1, 'ARTIST', 'watch-artist', '关注艺人', 0.30, 'WATCH', '票务', 80, -0.20, 20,
     '负面占比 >= 20% 且 < 40%，样本量 >= 20', '2025-04-18 10:12:31');

INSERT INTO spark_job_run
    (id, data_batch_id, job_type, execution_engine, status, application_id, worker_summary, input_count,
     success_count, failed_count, duration_ms, message, failure_reason, started_at, finished_at)
VALUES
    (1, 1, 'CONCERT_COMMENT_AGGREGATION', 'SPARK_ON_YARN', 'SUCCEEDED', 'application_sample_001',
     'worker-1, worker-2', 12, 12, 0, 150000, 'Development sample run', NULL,
     '2025-04-18 10:10:00', '2025-04-18 10:12:30');
