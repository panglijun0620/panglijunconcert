-- 把采集阶段的运行记录补进任务表，让“任务记录”页覆盖采集 → 计算的整条链路。
-- 数据来源：data/raw/final_input_manifest.json 与各来源采集日志（showstart_collect.log、
-- _piaoniu_run.log、damai_full_run.log / damai_deep2_run.log）。
-- 采集当时没有记录耗时，duration_ms 一律留空，不补造数字。
-- 用 NOT EXISTS 兜底，重复执行不会插入第二遍。

INSERT INTO spark_job_run (
    data_batch_id, job_type, execution_engine, status, worker_summary,
    input_count, success_count, failed_count, duration_ms, message,
    started_at, finished_at
)
SELECT (SELECT id FROM analytics_batch ORDER BY id DESC LIMIT 1),
       'CRAWL_SHOWSTART', 'PYTHON', 'SUCCEEDED', '秀动 · 列表与详情采集',
       60, 104, 0, NULL,
       '列表去重事件 60 条、演唱会类 29 条；详情页逐条抓取，最终入库 104 场',
       '2026-10-12 16:36:00', '2026-10-12 16:58:00'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM spark_job_run WHERE job_type = 'CRAWL_SHOWSTART');

INSERT INTO spark_job_run (
    data_batch_id, job_type, execution_engine, status, worker_summary,
    input_count, success_count, failed_count, duration_ms, message,
    started_at, finished_at
)
SELECT (SELECT id FROM analytics_batch ORDER BY id DESC LIMIT 1),
       'CRAWL_PIAONIU', 'PYTHON', 'SUCCEEDED', '票牛 · 列表页到场次页三级抓取',
       3252, 957, 0, NULL,
       '列表页 3,252 条进入去重，按场次逐条抓取，最终入库 957 场',
       '2026-10-12 16:36:00', '2026-10-12 17:14:00'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM spark_job_run WHERE job_type = 'CRAWL_PIAONIU');

INSERT INTO spark_job_run (
    data_batch_id, job_type, execution_engine, status, worker_summary,
    input_count, success_count, failed_count, duration_ms, message,
    started_at, finished_at
)
SELECT (SELECT id FROM analytics_batch ORDER BY id DESC LIMIT 1),
       'CRAWL_PUBLIC', 'PYTHON', 'SUCCEEDED', '公开页面 · 快照采集',
       66, 53, 0, NULL,
       '公开页面快照 66 条，最终入库 53 场；该来源未单独留存采集日志，时间按同日归档',
       '2026-10-12 17:14:00', '2026-10-12 17:14:00'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM spark_job_run WHERE job_type = 'CRAWL_PUBLIC');

INSERT INTO spark_job_run (
    data_batch_id, job_type, execution_engine, status, worker_summary,
    input_count, success_count, failed_count, duration_ms, message,
    started_at, finished_at
)
SELECT (SELECT id FROM analytics_batch ORDER BY id DESC LIMIT 1),
       'CRAWL_DAMAI', 'PYTHON', 'SUCCEEDED', '大麦 · 评论多轮采集',
       416530, 416530, 0, NULL,
       '三轮采集合并 416,530 条评论，按正文指纹去重后入库 167,844 条，重复 248,686 条',
       '2026-10-16 10:11:00', '2026-10-16 10:41:00'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM spark_job_run WHERE job_type = 'CRAWL_DAMAI');
