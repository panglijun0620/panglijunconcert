-- 1) 评论表的组合索引
--
-- clean_comment 原来只有 4 个单列索引（concert_id / data_batch_id / comment_time / source_type）。
-- 舆情页的聚合查询几乎都是「先按批次圈范围，再叠一个维度，最后按情感分算负面占比」，
-- 例如 negativeByTopicScoped / negativeByRegionScoped / negativeByArtistScoped
-- 和汇总用的 summarizeSentiment。这种 where + group by 的组合用单列索引时
-- MySQL 只能命中 data_batch_id 那一个，剩下的条件全部回表逐行过滤。
--
-- 这里按实际查询维度各建一条组合索引，把「批次」放在最左列，
-- 保证仍然能服务只按批次查的场景（也能顶掉原来的 idx_clean_comment_batch）。
CREATE INDEX idx_comment_batch_source_score
    ON clean_comment (data_batch_id, source_type, sentiment_score);

CREATE INDEX idx_comment_batch_region_score
    ON clean_comment (data_batch_id, user_region, sentiment_score);

CREATE INDEX idx_comment_batch_topic_score
    ON clean_comment (data_batch_id, primary_topic, sentiment_score);

CREATE INDEX idx_comment_batch_artist_score
    ON clean_comment (data_batch_id, artist_name, sentiment_score);

-- 情感分区间统计（strongly_negative / negative / neutral / positive / strongly_positive）
-- 只筛批次和分数，上面几条索引的最左前缀不够用，单独补一条。
CREATE INDEX idx_comment_batch_collected
    ON clean_comment (data_batch_id, collected_at);

-- 2) 采集任务的数字口径
--
-- 任务表的列叫 input_count / success_count，页面上并排显示成「入库 / 采集」。
-- 但五个任务原本填的数字不是一个口径：
--   秀动是「列表去重事件 60 条」，产出却是「详情页抓取后入库 104 场」——一个事件带多场，104 > 60；
--   票牛是「列表页 3252 条」进到「入库 957 场」；
--   公开页是「快照 66 条」到「入库 53 场」；
--   大麦是「三轮合并 416530 条评论」到「去重后入库 168958 条」。
-- 数字本身没有错，错在没有说清楚每一行比的是什么，页面上看就像成功率算出 173%。
--
-- 这里不动任何统计数字（那属于原始采集记录，不能改），只把每一行的口径写进 worker_summary，
-- 让页面在任务名下面直接显示成「列表 60 条 → 入库 104 场」这种能自解释的写法。
UPDATE spark_job_run
SET worker_summary = '列表去重 60 条 → 详情抓取入库 104 场'
WHERE job_type = 'CRAWL_SHOWSTART';

UPDATE spark_job_run
SET worker_summary = '列表页 3252 条 → 入库 957 场'
WHERE job_type = 'CRAWL_PIAONIU';

UPDATE spark_job_run
SET worker_summary = '公开页快照 66 条 → 入库 53 场'
WHERE job_type = 'CRAWL_PUBLIC';

UPDATE spark_job_run
SET worker_summary = '三轮合并 416530 条 → 去重入库 168958 条'
WHERE job_type = 'CRAWL_DAMAI';

UPDATE spark_job_run
SET worker_summary = '评论 417644 条 → 清洗后入库 168958 条'
WHERE job_type = 'CONCERT_COMMENT_AGGREGATION';
