#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
演出数据 → 上传 HDFS 的输入文件（数据准备环节，在上传前完成）
================================================================
读   : data/raw/piaoniu_concerts.csv      （票牛，自动翻页爬取）
     : data/raw/showstart_concerts.csv    （秀动，自动翻页爬取）
     : data/raw/concerts_public.csv       （公开新闻/政府公开页，人工整理快照）
写   : data/raw/concerts_full_category.csv（全类别审计快照）
     : data/raw/concerts_upload.csv       （正式上传文件，仅保留演唱会，14 列）

【答辩讲解】这一步属于"数据准备"，不是清洗。清洗是上传到服务器后由 Spark 完成的。
本脚本只做四件事：
  1. 多源合并：三个来源的演出合并成一份，按演出名+城市+场馆+日期去重。
  2. 时间归一化：网页抓到的时间写法很乱（"2026.09.12 19:00"、
     "2026.10.24 赣州市全民健身中心"、"时间待定"），统一成 yyyy-MM-dd [HH:mm]；
     抠不出日期的留空，不瞎填。
  3. 票价拆解：票价原文是文本（如 "280/480/680/880/1080"），
     用正则抠出所有数字，最小的存 min_price、最大的存 max_price。
     这样后续才能按价格区间做统计。拆不出来的（如"待定"）留空，不瞎填。
  4. 分类与范围控制：按演出名关键词判定 category（演唱会/音乐会/脱口秀…），
     全类别写入审计快照，正式批次只保留“演唱会”；同时按来源文件标注
     source_type（piaoniu/showstart/public）。
"""
import csv
import re
from collections import Counter
from datetime import date
from html import unescape
from pathlib import Path
from urllib.parse import urlparse

ROOT = Path(__file__).resolve().parents[1]
RAW = ROOT / "data" / "raw"

SOURCES = [
    ("piaoniu", RAW / "piaoniu_concerts.csv"),
    ("showstart", RAW / "showstart_concerts.csv"),
    ("public", RAW / "concerts_public.csv"),
]

OUT = RAW / "concerts_upload.csv"
FULL_CATEGORY_OUT = RAW / "concerts_full_category.csv"
EXCLUDED_OUT = RAW / "concerts_excluded.csv"
REVIEW_OUT = RAW / "concerts_review.csv"

DOMESTIC_PUBLIC_HOSTS = ("news.qq.com", "bjd.com.cn")


def source_url_reason(source_type: str, source_url: str) -> str:
    """Allow only the declared domestic source domains in the final batch."""
    value = (source_url or "").strip()
    if not value:
        return "missing source URL"
    parsed = urlparse(value)
    host = (parsed.hostname or "").lower().rstrip(".")
    if parsed.scheme != "https" or parsed.username or parsed.password or not host:
        return "invalid source URL"
    allowed = {
        "piaoniu": host == "piaoniu.com" or host.endswith(".piaoniu.com"),
        "showstart": host == "showstart.com" or host.endswith(".showstart.com"),
        "public": (
            host in DOMESTIC_PUBLIC_HOSTS
            or host.endswith(".gov.cn")
            or host.endswith(".bjd.com.cn")
        ),
    }
    if not allowed.get(source_type, False):
        return f"source host is not an approved domestic {source_type} source"
    return ""

# 13 列：爬虫原始 10 列 + 本脚本拆解出的 min_price / max_price + 分类 category
FIELDNAMES = [
    "artist_name", "concert_name", "city", "venue", "show_time", "show_time_precision",
    "price_text", "sale_status", "source_type", "source_url", "collected_at",
    "min_price", "max_price", "category",
]

# 【答辩讲解】票价拆解：匹配文本里所有数字（含小数），例如 "380/580/780" → [380, 580, 780]
PRICE_PATTERN = re.compile(r"\d+(?:\.\d+)?")
NON_EVENT_PATTERN = re.compile(r"选排勿拍|验收演出|拼团勿拍|勿拍不发货|会员.*免服务费|优先配票")


def split_price(price_text: str):
    """从票价文本中拆出最低价与最高价；拆不出来返回 (None, None)。"""
    if not price_text:
        return None, None
    numbers = [float(x) for x in PRICE_PATTERN.findall(price_text)]
    numbers = [number for number in numbers if 0 < number <= 10000]
    if not numbers:
        return None, None
    return min(numbers), max(numbers)


# 【答辩讲解】判定类别用的两组关键词，分开写是为了让规则一眼能看懂：
#   LIVE_WORDS  —— 命中就算「演唱会」（演唱会、巡演、音乐节、Livehouse、世界巡演 TOUR）
#   CLASSIC_WORDS —— 命中算「音乐会」（交响、钢琴、民乐、合唱…古典/器乐类）
# 注意 TOUR 是英文写法：海外团体和爱豆的场次在票牛上写的是 "WORLD TOUR"，
# 不写中文"巡演"。两种写法要一视同仁，否则会把 ENHYPEN、BABYMONSTER
# 这类世界巡演误判成"其他演出"。
LIVE_WORDS = r"演唱会|巡演|巡唱|巡回|\bTOUR\b|Fan ?-?Con|Livehouse|Live House|音乐节|电音"
CLASSIC_WORDS = r"音乐会|交响|室内乐|演奏会|钢琴|小提琴|古琴|民乐|合唱|独奏|协奏"


def classify(name: str) -> str:
    """【答辩讲解】按演出名关键词判定类别。
    为什么要分：票牛/秀动返回的是演出全量（含脱口秀、音乐会、话剧），
    直接全算成"演唱会"会虚高市场规模，所以这里先粗分，Spark 再细分。

    判定顺序有讲究，踩过两个坑才定下来：
      1) 古典/器乐要排在"巡演"前面 —— 否则"国王歌手合唱团音乐会 2026巡演"
         会因为含"巡演"被错判成演唱会。
      2) 但名字里明写"演唱会"的除外 —— 像"大型交响演唱会"本质是演唱会，
         不能因为含"交响"就被归到音乐会。
    """
    if not name:
        return "未分类"
    if re.search(r"脱口秀|相声|喜剧|栋笃笑|漫才", name):
        return "脱口秀"
    if re.search(r"话剧|舞台剧|音乐剧|舞剧|戏曲|京剧|昆曲|黄梅|越剧|豫剧", name):
        return "戏剧"
    # 古典/器乐优先；但明写"演唱会"的不算古典
    if re.search(CLASSIC_WORDS, name) and not re.search("演唱会", name):
        return "音乐会"
    if re.search(LIVE_WORDS, name, re.I):
        return "演唱会"
    # 纯"乐团/乐队"但没有巡演字样的，按音乐会处理
    if re.search(r"乐团|乐队", name):
        return "音乐会"
    return "其他演出"


# 【答辩讲解】演出时间归一化用的正则：匹配 "2026.09.12" / "2026-09-12" / "2026/09/12" 这类日期
DATE_PATTERN = re.compile(r"(\d{4})[.\-/年](\d{1,2})[.\-/月](\d{1,2})")
TIME_PATTERN = re.compile(r"(\d{1,2}):(\d{2})")
CITY_PREFIX_PATTERN = re.compile(r"^\s*[\[【（(]([^\]】）)]{1,12})[\]】）)]\s*")
EVENT_IDENTITY_NOISE = re.compile(r"[^0-9a-z\u4e00-\u9fff]+")
IDENTITY_PLACEHOLDERS = {
    "unknown", "场馆未公布", "场馆待定", "未公布", "待定", "暂无",
    "未知艺人", "艺人待定",
}


def normalize_show_time(text: str) -> str:
    """【答辩讲解】演出时间归一化。
    爬虫抓下来的是网页上的原始文本，写法很乱，例如：
        "2026.09.12 19:00"                 → 2026-09-12 19:00
        "2026.10.24 赣州市全民健身中心"      → 2026-10-24
        "演出筹备中，时间待定 场馆待定"        → （空）
    这里把日期抠出来统一写成 yyyy-MM-dd，有时间就补 HH:mm。
    抠不出日期的（"时间待定"这类）返回空字符串，不做任何猜测填充，
    交给后面 Spark 按"必填字段缺失"正常剔除。"""
    if not text:
        return ""
    m = DATE_PATTERN.search(text)
    if not m:
        return ""
    year, month, day = m.groups()
    try:
        result = date(int(year), int(month), int(day)).isoformat()
    except ValueError:
        return ""
    t = TIME_PATTERN.search(text)
    if t:
        result += f" {int(t.group(1)):02d}:{t.group(2)}"
    return result


def normalize_event_identity(value: str) -> str:
    """Remove punctuation and HTML entities before event-level deduplication."""
    return EVENT_IDENTITY_NOISE.sub("", unescape(value or "").casefold())


def normalize_event_name(value: str, city: str) -> str:
    """Remove a leading bracket only when it is the listing's city label."""
    normalized = unescape(value or "").casefold()
    match = CITY_PREFIX_PATTERN.match(normalized)
    city_key = normalize_event_identity(city)
    if match and city_key:
        prefix_key = normalize_event_identity(match.group(1))
        if prefix_key in {city_key, f"{city_key}站"}:
            normalized = normalized[match.end():]
    return EVENT_IDENTITY_NOISE.sub("", normalized)


def meaningful_identity(value: str) -> str:
    normalized = normalize_event_identity(value)
    placeholders = {normalize_event_identity(item) for item in IDENTITY_PLACEHOLDERS}
    return "" if not normalized or normalized in placeholders else normalized


def event_base_key(row, show_time: str) -> tuple:
    return (
        normalize_event_name(row.get("concert_name") or "", row.get("city") or ""),
        normalize_event_identity(row.get("city") or ""),
        show_time[:10] if show_time else "",
    )


def row_key(row, show_time: str) -> tuple:
    """Use venue first; fall back to artist/source evidence instead of merging different venues."""
    base = event_base_key(row, show_time)
    venue = meaningful_identity(row.get("venue") or "")
    if venue:
        return base + ("venue", venue)
    artist = meaningful_identity(row.get("artist_name") or "")
    if artist:
        return base + ("artist", artist)
    source_id = meaningful_identity(row.get("source_record_id") or "")
    source_url = (row.get("source_url") or "").strip().casefold()
    return base + ("source", (row.get("source_type") or "").strip().casefold(), source_id or source_url)


def row_quality(row) -> tuple:
    """Prefer the most complete traceable row when several listings describe one event."""
    venue = normalize_event_identity(row.get("venue") or "")
    artist = normalize_event_identity(row.get("artist_name") or "")
    return (
        1 if row.get("show_time_precision") == "DATETIME" else 0,
        1 if venue and venue not in {"unknown", "场馆未公布", "场馆待定"} else 0,
        1 if artist and artist not in {"unknown", "未知艺人", "艺人待定"} else 0,
        1 if row.get("min_price") != "" or row.get("max_price") != "" else 0,
    )


def exclusion_reason(row) -> str:
    evidence = " ".join((row.get(field) or "") for field in (
        "artist_name", "concert_name", "venue", "price_text", "sale_status"))
    if NON_EVENT_PATTERN.search(evidence):
        return "ticketing/test listing rather than a public performance"
    url_reason = source_url_reason(row.get("source_type", ""), row.get("source_url", ""))
    if url_reason:
        return url_reason
    if not (row.get("collected_at") or "").strip():
        return "missing collection timestamp"
    return ""


def price_needs_review(price_text: str) -> bool:
    numbers = [float(x) for x in PRICE_PATTERN.findall(price_text or "")]
    valid = [number for number in numbers if 0 < number <= 10000]
    return len(valid) >= 2 and min(valid) < 1000 and max(valid) > 3000


def main():
    merged = []
    excluded = []
    seen = {}
    stats = {}

    for source_type, path in SOURCES:
        if not path.exists():
            print(f"[跳过] 缺少文件 {path}")
            continue
        with path.open(encoding="utf-8-sig", newline="") as fh:
            rows = list(csv.DictReader(fh))
        for raw_row in rows:
            row = {**raw_row, "source_type": source_type}
            reason = exclusion_reason(row)
            if reason:
                excluded.append({**row, "source_type": source_type, "exclusion_reason": reason})
                continue
            name = (row.get("concert_name") or "").strip()
            if not name:
                excluded.append({**row, "source_type": source_type, "exclusion_reason": "missing performance name"})
                continue  # 【答辩讲解】演出名为空的记录无法使用，直接丢弃
            show_time = normalize_show_time(row.get("show_time") or "")
            if not show_time:
                excluded.append({**row, "source_type": source_type, "exclusion_reason": "missing or invalid performance date"})
                continue
            source_price = row.get("price_text") or ""
            min_price, max_price = split_price(source_price)
            price_review = price_needs_review(source_price)
            if price_review:
                min_price, max_price = None, None
            output_row = {
                "artist_name": (row.get("artist_name") or "").strip(),
                "concert_name": name,
                "city": (row.get("city") or "").strip(),
                "venue": (row.get("venue") or "").strip(),
                "show_time": show_time,
                "show_time_precision": "DATETIME" if TIME_PATTERN.search(row.get("show_time") or "") else "DATE",
                "price_text": (row.get("price_text") or "").strip(),
                "sale_status": (row.get("sale_status") or "").strip(),
                "source_type": source_type,
                "source_url": (row.get("source_url") or "").strip(),
                "collected_at": (row.get("collected_at") or "").strip(),
                "min_price": "" if min_price is None else min_price,
                "max_price": "" if max_price is None else max_price,
                "category": classify(name),
            }
            key = row_key(output_row, show_time)
            existing_index = seen.get(key)
            if existing_index is not None:
                existing = merged[existing_index]
                if row_quality(output_row) > row_quality(existing):
                    excluded.append({
                        **existing,
                        "exclusion_reason": "duplicate normalized event; replaced by a more complete source row",
                    })
                    merged[existing_index] = output_row
                else:
                    excluded.append({
                        **output_row,
                        "exclusion_reason": "duplicate normalized event; a more complete source row was retained",
                    })
                continue
            seen[key] = len(merged)
            merged.append(output_row)
        stats[source_type] = {"原始": len(rows)}

    full_category = list(merged)
    non_concerts = [row for row in full_category if row["category"] != "演唱会"]
    excluded.extend({
        **row,
        "exclusion_reason": f"category excluded from concert-only batch: {row['category']}",
    } for row in non_concerts)
    merged = [row for row in full_category if row["category"] == "演唱会"]

    retained_by_source = Counter(row["source_type"] for row in merged)
    for source_type in stats:
        stats[source_type]["保留"] = retained_by_source[source_type]
    review = [
        {**row, "review_reason": "mixed price tiers include values above 3000 CNY"}
        for row in merged
        if price_needs_review(row.get("price_text") or "")
    ]
    base_groups = {}
    for row in merged:
        base_groups.setdefault(event_base_key(row, row.get("show_time") or ""), []).append(row)
    for group in base_groups.values():
        venues = {meaningful_identity(row.get("venue") or "") for row in group}
        venues.discard("")
        if len(venues) > 1:
            review.extend({
                **row,
                "review_reason": "same normalized name, city and date but different venues; manual verification required",
            } for row in group)

    OUT.parent.mkdir(parents=True, exist_ok=True)
    with FULL_CATEGORY_OUT.open("w", encoding="utf-8", newline="") as fh:
        writer = csv.DictWriter(fh, fieldnames=FIELDNAMES)
        writer.writeheader()
        writer.writerows(full_category)
    with OUT.open("w", encoding="utf-8", newline="") as fh:
        writer = csv.DictWriter(fh, fieldnames=FIELDNAMES)
        writer.writeheader()
        writer.writerows(merged)

    audit_fields = list(dict.fromkeys([*FIELDNAMES, "exclusion_reason", "review_reason"]))
    for path, records in ((EXCLUDED_OUT, excluded), (REVIEW_OUT, review)):
        with path.open("w", encoding="utf-8", newline="") as fh:
            writer = csv.DictWriter(fh, fieldnames=audit_fields, extrasaction="ignore")
            writer.writeheader()
            writer.writerows(records)

    # 【答辩讲解】统计：各来源分别贡献多少、票价拆出多少、分类分布
    with_price = sum(1 for r in merged if r["min_price"] != "")
    categories = {}
    for r in full_category:
        categories[r["category"]] = categories.get(r["category"], 0) + 1

    print("===== 演出数据准备完成 =====")
    for k, v in stats.items():
        print(f"  {k:10s} 原始 {v['原始']:6d} → 保留 {v['保留']:6d}")
    print(f"  全类别去重后: {len(full_category)} 条 ({FULL_CATEGORY_OUT})")
    print(f"  正式演唱会批次: {len(merged)} 条")
    print(f"  排除并留痕: {len(excluded)} 条 ({EXCLUDED_OUT})")
    print(f"  票价待核验: {len(review)} 条 ({REVIEW_OUT})")
    with_time = sum(1 for r in merged if r["show_time"])
    print(f"  时间归一化成功: {with_time} 条，时间待定(留空待 Spark 剔除): {len(merged) - with_time} 条")
    print(f"  票价拆解成功: {with_price} 条，缺失: {len(merged) - with_price} 条")
    print("  分类分布:")
    for k, v in sorted(categories.items(), key=lambda x: -x[1]):
        print(f"    {k}: {v}")
    print(f"  输出文件: {OUT}")


if __name__ == "__main__":
    main()
