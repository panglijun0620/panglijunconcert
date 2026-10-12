#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
大麦评论 → 上传 HDFS 的输入文件（数据准备环节，在上传前完成）
================================================================
读   : data/raw/comments_raw_all_batches.csv   （自采大麦评论合并全量，418,899 条）
写   : data/raw/comments_upload.csv            （上传文件，13 列）

【答辩讲解】这一步属于"数据准备"，不是清洗。清洗是上传到服务器后由 Spark 完成的。

本脚本只做一件事：**剥离不属于本人采集的关联字段**。

为什么要剥离：
  采集评论时，脚本会记录"这条评论属于哪场演出"（concert_source_record_id、
  artist_name、concert_name 三个字段）。这三个字段的值来自项目早期的一个演出清单，
  不是本人采集的，按数据归属原则不能使用。
  因此在本环节将它们清空，只保留**本人真实采集**的字段：
    - source_record_id  大麦评论唯一编号（本人采集，跨批次稳定）
    - comment_text      评论正文（本人采集）
    - comment_time      评论时间（本人采集）
    - like_count        点赞数（本人采集）
    - user_region       用户地域（本人采集）
    - source_platform   平台标识（本人脚本写入 "damai"）
    - source_url        评论链接（本人采集）
    - collected_at      采集时间（本人脚本写入）
  sentiment_score / sentiment_scale 采集时留空，由服务器 Spark 的情感词典计算后填入。
"""
import csv
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
RAW = ROOT / "data" / "raw"
SRC = RAW / "comments_raw_all_batches.csv"
OUT = RAW / "comments_upload.csv"

FIELDNAMES = [
    "source_record_id", "concert_source_record_id", "artist_name", "concert_name",
    "comment_text", "comment_time", "like_count", "user_region", "source_platform",
    "source_url", "collected_at", "sentiment_score", "sentiment_scale",
]

# 【答辩讲解】要剥离的字段：这三个来自早期演出清单，非本人采集，清空处理
STRIP_FIELDS = ["concert_source_record_id", "artist_name", "concert_name"]


def main():
    if not SRC.exists():
        print(f"[错误] 缺少输入文件 {SRC}")
        return

    total = 0
    kept = 0
    stripped = 0

    with SRC.open(encoding="utf-8-sig", newline="") as fin, \
            OUT.open("w", encoding="utf-8", newline="") as fout:
        reader = csv.DictReader(fin)
        writer = csv.DictWriter(fout, fieldnames=FIELDNAMES)
        writer.writeheader()

        for row in reader:
            total += 1
            text = (row.get("comment_text") or "").strip()
            if not text:
                continue  # 【答辩讲解】正文为空的评论无法做情感分析，丢弃

            out_row = {}
            for field in FIELDNAMES:
                if field in STRIP_FIELDS:
                    # 剥离非本人采集的关联字段
                    if (row.get(field) or "").strip():
                        stripped += 1
                    out_row[field] = ""
                else:
                    out_row[field] = row.get(field) or ""
            writer.writerow(out_row)
            kept += 1

    print("===== 评论数据准备完成 =====")
    print(f"  读入原始评论: {total} 条")
    print(f"  保留（正文非空）: {kept} 条")
    print(f"  剥离的关联字段值: {stripped} 处（concert_source_record_id / artist_name / concert_name）")
    print(f"  输出文件: {OUT}")


if __name__ == "__main__":
    main()
