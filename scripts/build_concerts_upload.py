#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
演出数据 → 上传 HDFS 的输入文件（数据准备环节，在上传前完成）
================================================================
读   : data/raw/piaoniu_concerts.csv      （票牛，自动翻页爬取）
     : data/raw/showstart_concerts.csv    （秀动，自动翻页爬取）
     : data/raw/concerts_public.csv       （公开新闻/政府公开页，人工整理快照）
写   : data/raw/concerts_upload.csv       （合并后的上传文件，13 列）

【答辩讲解】这一步属于"数据准备"，不是清洗。清洗是上传到服务器后由 Spark 完成的。
本脚本只做四件事：
  1. 多源合并：三个来源的演出合并成一份，按演出名+城市+场馆+日期去重。
  2. 时间归一化：网页抓到的时间写法很乱（"2026.09.12 19:00"、
     "2026.10.24 赣州市全民健身中心"、"时间待定"），统一成 yyyy-MM-dd [HH:mm]；
     抠不出日期的留空，不瞎填。
  3. 票价拆解：票价原文是文本（如 "280/480/680/880/1080"），
     用正则抠出所有数字，最小的存 min_price、最大的存 max_price。
     这样后续才能按价格区间做统计。拆不出来的（如"待定"）留空，不瞎填。
  4. 分类与来源标注：按演出名关键词判定 category（演唱会/音乐会/脱口秀…），
     并按来源文件标注 source_type（piaoniu/showstart/public）。
"""
import csv
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
RAW = ROOT / "data" / "raw"

SOURCES = [
    ("piaoniu", RAW / "piaoniu_concerts.csv"),
    ("showstart", RAW / "showstart_concerts.csv"),
    ("public", RAW / "concerts_public.csv"),
]

OUT = RAW / "concerts_upload.csv"

# 13 列：爬虫原始 10 列 + 本脚本拆解出的 min_price / max_price + 分类 category
FIELDNAMES = [
    "artist_name", "concert_name", "city", "venue", "show_time",
    "price_text", "sale_status", "source_type", "source_url", "collected_at",
    "min_price", "max_price", "category",
]

# 【答辩讲解】票价拆解：匹配文本里所有数字（含小数），例如 "380/580/780" → [380, 580, 780]
PRICE_PATTERN = re.compile(r"\d+(?:\.\d+)?")


def split_price(price_text: str):
    """从票价文本中拆出最低价与最高价；拆不出来返回 (None, None)。"""
    if not price_text:
        return None, None
    numbers = [float(x) for x in PRICE_PATTERN.findall(price_text)]
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
    result = f"{int(year):04d}-{int(month):02d}-{int(day):02d}"
    t = TIME_PATTERN.search(text)
    if t:
        result += f" {int(t.group(1)):02d}:{t.group(2)}"
    return result


def row_key(row, show_time: str) -> tuple:
    """【答辩讲解】去重键：演出名+城市+场馆+日期，四者都相同才认为是同一场。"""
    return (
        (row.get("concert_name") or "").strip().casefold(),
        (row.get("city") or "").strip().casefold(),
        (row.get("venue") or "").strip().casefold(),
        show_time[:10],
    )


def main():
    merged = []
    seen = set()
    stats = {}

    for source_type, path in SOURCES:
        if not path.exists():
            print(f"[跳过] 缺少文件 {path}")
            continue
        with path.open(encoding="utf-8-sig", newline="") as fh:
            rows = list(csv.DictReader(fh))
        kept = 0
        for row in rows:
            name = (row.get("concert_name") or "").strip()
            if not name:
                continue  # 【答辩讲解】演出名为空的记录无法使用，直接丢弃
            show_time = normalize_show_time(row.get("show_time") or "")
            key = row_key(row, show_time)
            if key in seen:
                continue  # 跨源重复，只保留第一次出现
            seen.add(key)

            min_price, max_price = split_price(row.get("price_text") or "")
            merged.append({
                "artist_name": (row.get("artist_name") or "").strip(),
                "concert_name": name,
                "city": (row.get("city") or "").strip(),
                "venue": (row.get("venue") or "").strip(),
                "show_time": show_time,
                "price_text": (row.get("price_text") or "").strip(),
                "sale_status": (row.get("sale_status") or "").strip(),
                "source_type": source_type,
                "source_url": (row.get("source_url") or "").strip(),
                "collected_at": (row.get("collected_at") or "").strip(),
                "min_price": "" if min_price is None else min_price,
                "max_price": "" if max_price is None else max_price,
                "category": classify(name),
            })
            kept += 1
        stats[source_type] = {"原始": len(rows), "保留": kept}

    OUT.parent.mkdir(parents=True, exist_ok=True)
    with OUT.open("w", encoding="utf-8", newline="") as fh:
        writer = csv.DictWriter(fh, fieldnames=FIELDNAMES)
        writer.writeheader()
        writer.writerows(merged)

    # 【答辩讲解】统计：各来源分别贡献多少、票价拆出多少、分类分布
    with_price = sum(1 for r in merged if r["min_price"] != "")
    categories = {}
    for r in merged:
        categories[r["category"]] = categories.get(r["category"], 0) + 1

    print("===== 演出数据准备完成 =====")
    for k, v in stats.items():
        print(f"  {k:10s} 原始 {v['原始']:6d} → 保留 {v['保留']:6d}")
    print(f"  合并后总计: {len(merged)}")
    with_time = sum(1 for r in merged if r["show_time"])
    print(f"  时间归一化成功: {with_time} 条，时间待定(留空待 Spark 剔除): {len(merged) - with_time} 条")
    print(f"  票价拆解成功: {with_price} 条，缺失: {len(merged) - with_price} 条")
    print("  分类分布:")
    for k, v in sorted(categories.items(), key=lambda x: -x[1]):
        print(f"    {k}: {v}")
    print(f"  输出文件: {OUT}")


if __name__ == "__main__":
    main()
