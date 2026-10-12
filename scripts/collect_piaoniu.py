#!/usr/bin/env python3
# -*- coding: utf-8 -*-
# 数据采集页面(确切网址): https://www.piaoniu.com/sh-all?page=N  （票牛演出列表静态页，翻页爬取）
"""
票牛网（piaoniu.com）演唱会/演出全量采集脚本
================================================================
数据源 : https://www.piaoniu.com/sh-all?page=N （静态渲染演出列表）
  - 列表页: /sh-all?page=N 每页 10 个巡演聚合(stag)
  - 聚合页: /stag/{id} 列出该演出所有城市的独立场次(activity)
  - 场次页: /activity/{id} 含时间/场馆/票价/状态
这些 URL 不是程序运行时“猜出来”的，而是根据票牛公开页面的链接层级整理出来的：
先打开列表页，查看页面中的 /stag/ 链接；再打开聚合页，查看其中的
/activity/ 链接；最后打开场次页读取详情。
采集策略:
  - 低频采集: 列表页/聚合页间隔 >= 3 秒
  - User-Agent 等请求头使用普通浏览器格式，请求失败重试 2 次
  - 不绕过验证码/反爬；如果页面拒绝访问，就记录失败并继续处理后续页面
输出   : data/raw/piaoniu_concerts.csv (UTF-8 with BOM)
列     : artist_name, concert_name, city, venue, show_time, price_text,
         sale_status, source_type, source_url, collected_at

  - HEADERS 中的值来自常见 Chrome 浏览器访问网页时会发送的 HTTP 请求头格式。
    这些值只是“请求长什么样”的描述，不是账号、密码或验证码。
  - 低频等待 3 秒是本项目主动设置的礼貌访问间隔，不是票牛官方规定的数值。
  - retries=2 表示第一次失败后最多再试两次，属于普通网络容错，不是无限重试。
  - 本脚本没有代理池、Cookie 登录、验证码识别、Token 破解或封禁绕过。
"""

import argparse
import csv
import re
import sys
import time
from datetime import date
from pathlib import Path

import requests
from bs4 import BeautifulSoup

BASE_URL = "https://www.piaoniu.com"
LIST_URL = BASE_URL + "/sh-all?page={page}"
STAG_URL = BASE_URL + "/stag/{sid}"
ACTIVITY_URL = BASE_URL + "/activity/{aid}"

HEADERS = {
    # User-Agent 用来告诉服务器“请求来自哪一类客户端”。
    # 这里填写的是常见 Chrome 126 的请求头文本，参考普通浏览器请求格式；
    # 它不会让 requests 真的变成浏览器，也不能证明请求来自真人。
    "User-Agent": (
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
        "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
    ),
    # Accept 表示客户端希望接收 HTML 等网页内容。
    "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
    # Accept-Language 表示优先接受中文页面；它不是身份认证信息。
    "Accept-Language": "zh-CN,zh;q=0.9,en;q=0.8",
}

# 只采集演唱会/音乐现场类（标题命中任一关键字）
INCLUDE_KEYWORDS = (
    "演唱会", "音乐会", "巡演", "现场", "Live", "live", "音乐节",
    "专场", "演出", "巡", "tour", "电音节", "音乐秀", "音乐史诗",
    "巡唱", "巡回", "摇滚", "爵士", "金属", "后朋", "乐队", "视听交响",
)
EXCLUDE_KEYWORDS = (
    "话剧", "喜剧", "脱口秀", "展览", "舞台剧", "音乐剧", "折子戏",
    "儿童剧", "亲子", "放映", "魔术秀", "年票", "景区", "大赛", "运动会",
    "体育", "博览会", "市集", "书展", "漫展", "剧本杀", "密室", "露营",
    "讲座", "见面会", "沙龙", "昆曲", "黄梅", "京剧", "豫剧", "越剧",
    "沪剧", "评剧", "川剧", "粤剧", "舞剧", "木偶", "皮影", "杂技",
    "曲艺", "评弹", "舞蹈", "相声", "二人转",
    "单口", "脱口秀专场", "喜剧专场", "互动", "巡游", "烟花秀",
    "灯光秀", "无人机秀", "体育", "足球", "篮球",
)

FIELDNAMES = [
    "artist_name", "concert_name", "city", "venue", "show_time",
    "price_text", "sale_status", "source_type", "source_url", "collected_at",
]


def _normalize(text):
    """清理网页文本中的空白和常见 HTML 实体。

    网页源码经常把空格写成 ``&nbsp;``、把连接符写成 ``&middot;``。
    这里先转换成普通字符，再把连续空白压缩成一个空格，便于后面的正则解析。
    """
    if not text:
        return ""
    text = text.replace("\u00a0", " ").replace("&middot;", "·")
    text = text.replace("&ldquo;", """""").replace("&rdquo;", """""").replace("&amp;", "&")
    return re.sub(r"\s+", " ", text).strip()


def _fetch(url, retries=2):
    """读取一个公开 HTML 页面，失败时做有限次数重试。

    参数说明：
    - ``url``：要读取的列表页、聚合页或场次页。
    - ``retries=2``：第一次请求之外，最多额外尝试两次。
    - ``timeout=15``：单次请求最多等待 15 秒。

    这里的等待时间和重试次数都是本项目代码写的网络容错策略。它们不是
    反爬破解；如果服务器返回验证码或拒绝访问，脚本不会处理验证码。
    """
    for attempt in range(retries + 1):
        try:
            # requests 会把 HEADERS 作为普通 HTTP 请求头发送。
            # 这里没有传 Cookie、代理或登录信息。
            resp = requests.get(url, headers=HEADERS, timeout=15)
            if resp.status_code == 200:
                # 只有 HTTP 200 才当作成功页面返回；其他状态码交给重试逻辑。
                return resp.text
        except requests.RequestException:
            # DNS、连接超时、断网等 requests 网络异常会进入这里。
            # 不打印异常正文，避免一次失败刷屏；最终失败由上层记录。
            pass
        # 第一次失败等 2 秒，后续等待时间逐步增加。
        # 这是简单的退避等待，避免立即连续撞击同一个地址。
        time.sleep(2 + attempt * 2)
    return None


def _artist_from_title(title):
    """从标题中保守提取艺人名。

    例如 ``[北京]王俊凯演唱会北京站`` 会尝试得到 ``王俊凯``。
    这不是票牛提供的独立艺人字段，而是根据标题文字做的启发式猜测；
    如果标题不符合规则，就返回空字符串，调用方不会凭空编造艺人名。
    """
    t = title
    # 1) 删除票牛标题常见的 [城市] 前缀。
    t = re.sub(r"^\[[^\]]+\]", "", t)
    # 2) 删除价格/代拍类营销前缀，避免把它们误识别成艺人名。
    t = re.sub(r"^\s*[\[【（(]?(代拍费|预定金|代拍|定金|限时立减\d*\.?\d*元)[\]】）)]?\s*", "", t)
    t = re.sub(r"&times;|×", "/", t)
    # 3) 通常“演唱会/巡演/音乐会”等关键词后面是站点、场次等信息，
    #    所以只保留第一个关键词前面的文字。
    cut = re.split(r"(演唱会|巡回演唱会|巡演|音乐会|音乐节|专场|巡唱|LIVE|Live)", t, maxsplit=1)
    t = cut[0]
    # 4) 去掉年份、书名号和引号装饰。
    t = re.sub(r"20\d\d", " ", t)
    t = re.sub(r"「.*?」|“.*?”|《.*?》|‘.*?’", " ", t)
    # 5) 去掉多余空格和标题两端的装饰符号。
    t = re.sub(r"\s+", " ", t).strip(" .·-&|/、：:！!？?（）()")
    if not t:
        return ""
    # 5a) 以【】开头通常是主题或营销文案，保守地不作为艺人名。
    if t.startswith("【"):
        return ""
    # 5b) [艺人A/艺人B] 或 [艺人A、艺人B] 可能是真实多艺人组合，保留内容；
    #     只有单个方括号内容时不擅自判断它是艺人。
    if t.startswith("[") and "]" in t:
        inner = t[1:t.index("]")]
        if "/" in inner or "、" in inner:
            return inner.strip()[:40]
        return ""
    # 6) 没有中文字符的纯英文/品牌标题，无法可靠区分艺人和活动名，暂不填。
    if not re.search(r"[一-龥]", t):
        return ""
    return t[:40].strip() if t else ""

def _parse_stag(sid, html):
    """从 ``/stag/{id}`` 聚合页找到所有城市的 ``/activity/{id}`` 链接。

    正则中的 class、href 和 title 来自票牛页面当前的 HTML 结构，而不是
    Python 或 requests 自带的规则。网站改版后，这个正则可能需要重新检查。
    ``sid`` 保留在函数签名中，方便调用方知道当前正在解析哪个聚合页。
    """
    items = []
    for m in re.finditer(
        r'class="title"><a href="//www\.piaoniu\.com/activity/(\d+)"[^>]*title="([^"]+)"',
        html,
    ):
        aid, title = m.group(1), m.group(2)
        # aid 是 HTML 链接里的场次编号；title 是链接的原始标题。
        items.append({"aid": aid, "title": _normalize(title)})
    return items


def _fetch_activity(aid, title):
    """请求一个场次详情页，并把页面文字整理成一行演唱会数据。"""
    # 场次页 URL 的结构来自上面的 ACTIVITY_URL 模板。
    html = _fetch(ACTIVITY_URL.format(aid=aid))
    if not html:
        return None
    soup = BeautifulSoup(html, "html.parser")
    # concert_name 使用聚合页链接标题；它通常含有 [城市] 前缀。
    name = _normalize(title)
    city = ""
    # 从标题开头的 [城市] 中提取城市；没有该格式就留空。
    cm = re.match(r"^\[([^\]]+)\]", title)
    if cm:
        city = _normalize(cm.group(1))

    # 票牛当前页面把时间和场馆放在 class="desc" 的文本中。
    # 下面先取日期时间，再在同一段文本里取场馆；这是针对当前 HTML 的解析规则。
    cm2 = re.search(r'class="desc">([^<]+)</div>', html)
    show_time = ""
    if cm2:
        show_time = _normalize(cm2.group(1)).split("  ")[0]
        if len(show_time.split(" ")) >= 2:
            show_time = show_time.split(" ")[0] + " " + show_time.split(" ")[1]
    venue = ""
    vm = re.search(r'class="desc">([^<]+)</div>', html)
    if vm:
        parts = _normalize(vm.group(1)).split(" ")
        # 常见文本类似“2026.10.25 19:30 场馆名”或“10.06 - 国家体育场”。
        # 前两段通常是日期/时间，所以从第三段开始拼场馆。
        if len(parts) >= 3:
            venue_parts = parts[2:]
            # 如果场馆片段混入“10.07”这类日期词，过滤掉它。
            venue_parts = [p for p in venue_parts if not re.match(r"^\d{1,2}\.\d{1,2}$", p)]
            venue = " ".join(venue_parts)

    # 票价从页面中的 sale-price 区块读取；如果页面没有这个区块，就保持空。
    price_text = ""
    pm = re.search(r'class="sale-price">\s*&#x00a5;\s*<span class="strong">(\d+)</span>', html)
    if pm:
        price_text = pm.group(1) + " 起"

    # 销售状态来自页面 class 名称。这里的 booking/sale/sold/end 是票牛页面
    # HTML 中实际使用的状态标记，不是本项目自行定义的 HTTP 反爬状态。
    status = "待定"
    for s, v in (("booking", "预售中"), ("sale", "售票中"), ("sold", "已售罄"), ("end", "已结束")):
        if f'class="status {s}"' in html or f'class="eticket status {s}"' in html:
            status = v
            break
    artist = _artist_from_title(name)
    return {
        "artist_name": artist,
        "concert_name": name,
        "city": city,
        "venue": venue,
        "show_time": show_time,
        "price_text": price_text,
        "sale_status": status,
        "source_type": "爬虫采集",
        "source_url": ACTIVITY_URL.format(aid=aid),
        "collected_at": date.today().isoformat(),
    }


def collect_list(max_pages=200, resume=True):
    """按“列表页 -> 聚合页 -> 场次页”的三级结构采集票牛数据。"""
    out = Path("data/raw/piaoniu_concerts.csv")
    fieldnames = ["stag_id", "activity_id", "title"]
    seen = set()
    rows = []

    # 断点续采：如果输出 CSV 已经存在，就读取其中 source_url 最后一段的
    # activity_id，后面遇到相同场次直接跳过，避免重复写入。
    collected = set()
    if resume and out.exists():
        with out.open(encoding="utf-8-sig", newline="") as handle:
            for row in csv.DictReader(handle):
                collected.add(row.get("source_url", "").split("/")[-1])

    # 失败的列表页会写入单独日志，便于稍后人工重新运行。
    log = Path("data/raw/piaoniu_collect.log")
    with out.open("a", encoding="utf-8-sig", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=FIELDNAMES)
        if not out.exists() or out.stat().st_size == 0:
            writer.writeheader()

        for page in range(1, max_pages + 1):
            # 第一步：读取列表页，找出 /stag/{id} 聚合页编号。
            html = _fetch(LIST_URL.format(page=page))
            if not html:
                print(f"page{page}: fetch failed, retry later")
                log.write_text(f"{date.today()} page{page}: fetch failed\n", encoding="utf-8")
                continue
            stags = set(re.findall(r'href="//www\.piaoniu\.com/stag/(\d+)"', html))
            for sid in stags:
                # 第二步：打开一个聚合页，找出这个巡演在不同城市的 activity。
                stag_html = _fetch(STAG_URL.format(sid=sid))
                if not stag_html:
                    continue
                activities = _parse_stag(sid, stag_html)
                print(f"  stag {sid}: {len(activities)} activities")
                for act in activities:
                    if act["aid"] in collected:
                        continue
                    # 第三步：打开具体场次页，读取时间、场馆、票价和状态。
                    rec = _fetch_activity(act["aid"], act["title"])
                    if rec and rec.get("concert_name"):
                        writer.writerow(rec)
                        handle.flush()
                        collected.add(act["aid"])
                # 这是礼貌访问间隔，避免连续快速请求多个聚合页。
                time.sleep(3)
            if not stags:
                print(f"page{page}: empty, stop")
                break
            print(f"page{page}: {len(stags)} stags")
            # 列表页之间也保持 3 秒间隔；该数值来自本项目命令式设置。
            time.sleep(3)
    print(f"done. total activities: {len(collected)}")


if __name__ == "__main__":
    # max-pages 是安全上限，不代表网站一定有这么多页。
    # --no-resume 用于明确要求从头处理，而不是复用已有 CSV 的场次编号。
    parser = argparse.ArgumentParser()
    parser.add_argument("--max-pages", type=int, default=200)
    parser.add_argument("--no-resume", action="store_true")
    args = parser.parse_args()
    collect_list(max_pages=args.max_pages, resume=not args.no_resume)
