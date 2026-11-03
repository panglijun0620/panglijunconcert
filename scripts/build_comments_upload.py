#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
大麦评论 → 上传 HDFS 的输入文件（数据准备环节，在上传前完成）
================================================================
读   : data/raw/comments_raw_all_batches.csv   （自采大麦评论合并全量）
写   : data/raw/comments_upload.csv            （上传文件，13 列）

【答辩讲解】这一步属于"数据准备"，不是清洗。清洗是上传到服务器后由 Spark 完成的。

本脚本只保留能够由本项目采集流程证明的关联信息。

具体场次字段来自项目早期演出清单，无法与当前三来源演出批次建立可靠外键，因此清空；
巡演聚合评论的 artist_name 是采集脚本通过大麦详情接口取得唯一 ipId 后写入的，且原始行
没有具体场次编号和名称，这部分可验证的艺人归属予以保留。其余字段均为本人真实采集：
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
import hashlib
import re
from datetime import datetime
from pathlib import Path
from urllib.parse import urlparse

ROOT = Path(__file__).resolve().parents[1]
RAW = ROOT / "data" / "raw"
SRC = RAW / "comments_raw_all_batches.csv"
OUT = RAW / "comments_upload.csv"
EXCLUDED_OUT = RAW / "comments_excluded.csv"

FIELDNAMES = [
    "source_record_id", "concert_source_record_id", "artist_name", "concert_name",
    "comment_text", "comment_time", "like_count", "user_region", "source_platform",
    "source_url", "collected_at", "sentiment_score", "sentiment_scale",
]

# 具体场次字段来自早期清单，不能映射到当前演出批次。
STRIP_FIELDS = ["concert_source_record_id", "concert_name"]
ARTIST_PLACEHOLDERS = {"", "以详情为准", "未知艺人", "艺人待定", "待定"}


def verified_artist_name(row: dict) -> str:
    """仅保留无场次字段的巡演聚合评论艺人名。"""
    if (row.get("concert_source_record_id") or "").strip():
        return ""
    if (row.get("concert_name") or "").strip():
        return ""
    artist = (row.get("artist_name") or "").strip()
    return "" if artist in ARTIST_PLACEHOLDERS else artist


def normalize_collected_at(value: str) -> str:
    raw = (value or "").strip()
    for pattern in ("%Y-%m-%d %H:%M:%S", "%Y/%m/%d %H:%M"):
        try:
            return datetime.strptime(raw, pattern).strftime("%Y-%m-%d %H:%M:%S")
        except ValueError:
            continue
    raise ValueError(f"unsupported collection timestamp: {raw}")


def normalize_comment_text(value: str) -> str:
    """Normalize whitespace the same way as the Spark cleaning stage."""
    return re.sub(r"\s+", " ", (value or "").strip())


def prepare_comment(row: dict) -> tuple[dict | None, str | None, int]:
    record_id = (row.get("source_record_id") or "").strip()
    text = (row.get("comment_text") or "").strip()
    if not record_id:
        return None, "missing source record ID", 0
    if not text:
        return None, "missing comment text", 0

    platform = (row.get("source_platform") or row.get("source_type") or "").strip().lower()
    source_url = (row.get("source_url") or "").strip()
    collected_at = (row.get("collected_at") or "").strip()
    if not source_url or not collected_at:
        return None, "missing source URL or collection timestamp", 0
    try:
        normalized_collected_at = normalize_collected_at(collected_at)
    except ValueError:
        return None, "invalid collection timestamp", 0
    parsed_url = urlparse(source_url)
    host = (parsed_url.hostname or "").lower().rstrip(".")
    if (parsed_url.scheme != "https" or parsed_url.username or parsed_url.password
            or platform != "damai"
            or not (host == "damai.cn" or host.endswith(".damai.cn"))):
        return None, "final comment batch accepts Damai sources only", 0

    stripped = 0
    verified_artist = verified_artist_name(row)
    out_row = {}
    for field in FIELDNAMES:
        if field in STRIP_FIELDS:
            if (row.get(field) or "").strip():
                stripped += 1
            out_row[field] = ""
        elif field == "artist_name":
            if (row.get(field) or "").strip() and not verified_artist:
                stripped += 1
            out_row[field] = verified_artist
        elif field == "source_record_id":
            out_row[field] = record_id
        elif field == "comment_text":
            out_row[field] = text
        elif field == "source_platform":
            out_row[field] = "damai"
        elif field == "source_url":
            out_row[field] = source_url
        elif field == "collected_at":
            out_row[field] = normalized_collected_at
        elif field in {"sentiment_score", "sentiment_scale"}:
            out_row[field] = ""
        else:
            out_row[field] = row.get(field) or ""
    return out_row, None, stripped


def find_conflicting_ids(source_path: Path) -> set[str]:
    """Return IDs that still have multiple bodies after whitespace normalization."""
    first_fingerprint = {}
    conflicting_ids = set()
    with source_path.open(encoding="utf-8-sig", newline="") as source:
        for row in csv.DictReader(source):
            prepared, reason, _ = prepare_comment(row)
            if reason:
                continue
            record_id = prepared["source_record_id"]
            fingerprint = hashlib.sha256(
                normalize_comment_text(prepared["comment_text"]).encode("utf-8")
            ).digest()
            previous = first_fingerprint.setdefault(record_id, fingerprint)
            if previous != fingerprint:
                conflicting_ids.add(record_id)
    return conflicting_ids


def build(source_path: Path = SRC, output_path: Path = OUT,
          excluded_path: Path = EXCLUDED_OUT) -> dict:
    conflicting_ids = find_conflicting_ids(source_path)
    total = 0
    kept = 0
    stripped = 0
    artist_scoped = 0
    excluded = 0
    conflicting_rows = 0

    with source_path.open(encoding="utf-8-sig", newline="") as fin, \
            output_path.open("w", encoding="utf-8", newline="") as fout, \
            excluded_path.open("w", encoding="utf-8", newline="") as excluded_file:
        reader = csv.DictReader(fin)
        writer = csv.DictWriter(fout, fieldnames=FIELDNAMES)
        excluded_writer = csv.DictWriter(
            excluded_file,
            fieldnames=[*FIELDNAMES, "exclusion_reason"],
            extrasaction="ignore",
        )
        writer.writeheader()
        excluded_writer.writeheader()

        for row in reader:
            total += 1
            prepared, reason, stripped_values = prepare_comment(row)
            if reason:
                excluded_writer.writerow({**row, "exclusion_reason": reason})
                excluded += 1
                continue
            record_id = prepared["source_record_id"]
            if record_id in conflicting_ids:
                excluded_writer.writerow({
                    **row,
                    "exclusion_reason": "conflicting text variants for the same source record ID",
                })
                excluded += 1
                conflicting_rows += 1
                continue

            writer.writerow(prepared)
            stripped += stripped_values
            kept += 1
            if prepared["artist_name"]:
                artist_scoped += 1

    return {
        "input": total,
        "kept": kept,
        "excluded": excluded,
        "stripped": stripped,
        "artist_scoped": artist_scoped,
        "conflicting_ids": len(conflicting_ids),
        "conflicting_rows": conflicting_rows,
    }


def main():
    if not SRC.exists():
        print(f"[错误] 缺少输入文件 {SRC}")
        return

    stats = build()

    print("===== 评论数据准备完成 =====")
    print(f"  读入原始评论: {stats['input']} 条")
    print(f"  保留可发布记录: {stats['kept']} 条")
    print(f"  保留可验证艺人归属: {stats['artist_scoped']} 条")
    print(f"  剥离的未经验证关联字段值: {stats['stripped']} 处")
    print(
        f"  隔离正文冲突: {stats['conflicting_ids']} 个评论编号，"
        f"涉及 {stats['conflicting_rows']} 条采集记录"
    )
    print(f"  输出文件: {OUT}")
    print(f"  排除记录: {stats['excluded']} 条 ({EXCLUDED_OUT})")


if __name__ == "__main__":
    main()
