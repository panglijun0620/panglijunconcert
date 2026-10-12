# 数据采集接口(确切网址): https://www.damai.cn/   https://mtop.damai.cn/h5  （大麦 H5 JSON 接口，含评论/详情/搜索；配套网页 https://m.damai.cn/）
#1. TLS 指纹检测：普通 Python 请求会被识别成脚本。我用了 curl_cffi 的 impersonate="chrome120"，把请求伪装成真实 Chrome 120 浏览器。
#2. 接口要求签名：不带签名直接返回非法请求。我按大麦 H5 公开规则算 MD5 签名：md5(token + 时间戳 + appKey + 请求数据)。
#3. 登录凭证会过期：采到一半接口开始拒绝。我检测到返回 TOKEN 失效/RGV587/VALIDATE 错误时，自动刷新凭证并重试。
#4. 网络抖动会失败：每条请求最多 4 次重试，失败后递增等待 + 随机秒数，避免所有线程同时重发。
#5. 多线程串 Cookie：我给每个线程分配独立 Session（用 threading.local()），并发采集不互相污染数据。
#6. 范围污染：评论接口可能返回别的演出的评论，我逐条校验 itemId 是否匹配目标演出，不匹配直接丢弃。
#7. 时间格式乱：有的是字符串、有的是毫秒时间戳，我统一转成 Asia/Shanghai 标准时间。
#8. 正文位置不固定：有的在 content、有的藏在 textDOList 结构里，主字段取不到就回退取备用结构

from __future__ import annotations

import argparse
import csv
import hashlib
import json
import random
import shutil
import threading
import time
from collections import Counter, defaultdict
from concurrent.futures import ThreadPoolExecutor, as_completed
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path
from urllib.parse import parse_qs, urlparse
from zoneinfo import ZoneInfo


# 大麦 H5 请求中公开出现的 appKey。它标识调用方应用，不是个人账号密钥。
APP_KEY = "12574478"
# 大麦 H5 JSON 网关地址。后面的具体 API 名称会拼接在这个根地址后面。
API_ROOT = "https://mtop.damai.cn/h5"
# 输出给 Spark/后续分析使用的评论列名，顺序也作为 CSV 表头顺序。
COMMENT_COLUMNS = (
    "source_record_id", "concert_source_record_id", "artist_name",
    "concert_name", "comment_text", "comment_time", "like_count",
    "user_region", "source_platform", "source_url", "collected_at",
    "sentiment_score", "sentiment_scale",
)
USER_AGENT = (
    # 这是常见 iPhone Safari 的 User-Agent 文本格式。
    # 它只描述客户端类型，不会自动产生 Cookie、登录状态或验证码。
    "Mozilla/5.0 (iPhone; CPU iPhone OS 16_6 like Mac OS X) "
    "AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.6 Mobile/15E148 Safari/604.1"
)
# 【答辩讲解】反爬④ 线程隔离：threading.local() 让每个线程持有独立 Session，不串 Cookie
# ThreadPoolExecutor 会并行处理项目；threading.local() 让每个线程各自保存
# 一个 Session，避免不同线程互相覆盖 Cookie 和 _m_h5_tk。
_thread_state = threading.local()


@dataclass(frozen=True)
class Project:
    item_id: str
    source_record_id: str
    artist_name: str
    concert_name: str
    source_url: str


def load_projects(path: Path, limit: int | None = None) -> list[Project]:
    """从本地演唱会 CSV 中筛选出可定位到大麦 itemId 的项目。

    itemId 不是脚本随机生成的编号，而是原始 source_url 查询字符串中已有的
    ``?itemId=数字``。只有域名属于 damai.cn 且 itemId 为数字时才会进入队列。
    """
    projects = {}
    with path.open(encoding="utf-8-sig", newline="") as source:
        for row in csv.DictReader(source):
            # 先限制业务范围，只处理“演唱会”分类。
            if row.get("category") != "演唱会":
                continue
            url = urlparse(row.get("source_url") or "")
            # 防止把其他站点的 source_url 当成大麦项目地址。
            if not (url.hostname or "").endswith("damai.cn"):
                continue
            # parse_qs 读取 URL 查询参数，例如 itemId=123456。
            item_id = (parse_qs(url.query).get("itemId") or [""])[0]
            if not item_id.isdigit():
                continue
            project = Project(
                item_id, row["source_record_id"], row.get("artist_name") or "",
                row.get("concert_name") or "", row["source_url"],
            )
            previous = projects.setdefault(item_id, project)
            if previous.source_record_id != project.source_record_id:
                # 同一个 itemId 对应多个不同本地记录时无法安全归属评论，
                # 直接报错让使用者先处理数据冲突。
                raise ValueError(f"Ambiguous Damai itemId in snapshot: {item_id}")
            if limit and len(projects) >= limit:
                break
    return list(projects.values())

class DamaiClient:
    def __init__(self, delay: float = 0.2):
        # 延迟导入 curl_cffi，便于只做本地数据处理时不立刻要求网络依赖。
        from curl_cffi import requests

        # impersonate 是 curl_cffi 提供的客户端指纹模拟选项。
        # 它不会打开可见浏览器，也不会自动登录大麦。
        # 【答辩讲解】反爬① 指纹伪装：用 curl_cffi 把请求伪装成 Chrome 120，绕过大麦对浏览器指纹的检测
        # --------------------------------网站会检测浏览器指纹，我把请求伪装成真的 Chrome 浏览器
        self.session = requests.Session(impersonate="chrome120")
        self.session.headers.update({
            # User-Agent 是移动 Safari 的普通请求标识。
            "User-Agent": USER_AGENT,
            # H5 接口返回 JSON；Referer 表示请求从大麦移动页面发起。
            "Accept": "application/json, text/plain, */*",
            "Referer": "https://m.damai.cn/",
        })
        # delay 是本项目为同一 Session 设置的最小请求间隔（秒）。
        self.delay = delay
        self._last_request = 0.0
        # 构造客户端后先发一个轻量 H5 请求，让服务端有机会返回 _m_h5_tk Cookie。
        self._refresh_token()
# 【答辩讲解】反爬② 凭证刷新：大麦 _m_h5_tk 会过期，检测到 TOKEN/RGV587/VALIDATE 失效就自动刷新会话再重试
#-------录凭证会过期，我做了检测到失效就自动刷新再重试
    def _refresh_token(self):
        """通过一次普通 H5 请求刷新当前会话的 Cookie。
        这里没有手动制造 token。真正的 ``_m_h5_tk`` 由大麦服务器通过
        Set-Cookie 返回，curl_cffi Session 会自动保存，后续从
        ``self.session.cookies`` 读取。
        """
        self.session.get(
            # search.search/1.0 是大麦 H5 页面使用的公开接口路径；
            # 这里的 data={} 只是一个最小请求体，用来初始化会话。
            f"{API_ROOT}/mtop.damai.wireless.search.search/1.0/",
            params={"jsv": "2.7.5", "appKey": APP_KEY, "t": "1", "sign": "1", "data": "{}"},
            timeout=15,
        )

    def call(self, api: str, version: str, payload: dict) -> dict:
        """调用一个大麦 H5 JSON 接口，并处理有限重试。
        ``api``、``version`` 和 ``payload`` 分别对应接口名、版本号和业务参数。
        这些值来自大麦移动网页请求结构；本函数负责补齐通用 H5 参数和 sign。
        """
        # 使用紧凑 JSON，保证“参与签名的 data 字符串”和“实际发送的 data”
        # 完全一致；空格不同也会导致 MD5 结果不同。
        data = json.dumps(payload, ensure_ascii=False, separators=(",", ":"))
# 【答辩讲解】反爬③ 重试退避：网络抖动时每条请求最多重试 4 次，等待递增+随机数，避免线程集体卡点重发
#---------------------------网络抖动时每条请求最多重试 4 次，等待时间递增还加随机数，避免所有线程卡在同一时刻一起重发
        for attempt in range(4):
            # 保证同一个线程的相邻请求至少间隔 delay 秒。
            remaining = self.delay - (time.monotonic() - self._last_request)
            if remaining > 0:
                time.sleep(remaining)

            # _m_h5_tk 的原始格式通常类似“token_过期时间”。
            # H5 签名规则只使用下划线前的 token 片段；这里的值来自服务器 Cookie。
            token_cookie = self.session.cookies.get("_m_h5_tk") or ""
            token = str(token_cookie).split("_", 1)[0]
            # t 是当前毫秒时间戳，每次请求都重新生成。
            timestamp = str(int(time.time() * 1000))
            # 【答辩讲解】反爬⑤ 接口签名：token+时间戳+appKey+请求数据 四者拼接取 MD5；时间戳是毫秒，所以每次签名都不同、不能写死
            # ------------------"接口要求签名，算法是 token、时间戳、appKey、请求数据
            # 四个拼起来取 MD5。时间戳是当前毫秒，所以签名每次都不同，不能写死"
            sign = hashlib.md5(
                f"{token}&{timestamp}&{APP_KEY}&{data}".encode()
            ).hexdigest()
            try:
                # 下面的 jsv/appKey/t/sign/api/v/data 等字段来自 H5 请求格式。
                # H5Request/useH5 等字段说明调用的是移动网页通道。
                response = self.session.get(
                    f"{API_ROOT}/{api}/{version}/",
                    params={
                        "jsv": "2.7.5", "appKey": APP_KEY, "t": timestamp,
                        "sign": sign, "api": api, "v": version,
                        "type": "originaljson", "dataType": "json",
                        "valueType": "original", "H5Request": "true", "useH5": "true",
                        "data": data,
                    },
                    timeout=25,
                )
                self._last_request = time.monotonic()
                # 非 2xx HTTP 响应先按网络错误处理，进入有限重试。
                response.raise_for_status()
                result = response.json()
                # ret 是大麦接口返回的状态描述列表，例如 SUCCESS 或 TOKEN 错误。
                status = ",".join(map(str, result.get("ret") or []))
                if "SUCCESS" in status:
                    return result.get("data") or {}
                if "TOKEN" in status or "RGV587" in status or "VALIDATE" in status:
                    # 这些字符串来自大麦接口的 ret 返回内容。
                    # 它们表示当前 Cookie/签名/校验状态需要重新初始化；
                    # 这里只刷新会话并重试，不识别验证码或绕过校验。
                    self._refresh_token()
                elif attempt == 3:
                    raise RuntimeError(status or "Damai returned no status")
            except Exception:
                if attempt == 3:
                    raise
            # 失败后采用逐步增加的等待，并加入少量随机秒数，
            # 防止每次失败都在相同时间点重复请求。
            time.sleep((attempt + 1) * 1.5 + random.random())
        raise RuntimeError(f"Damai request failed: {api}")


def client() -> DamaiClient:
    # 每个工作线程第一次调用时创建自己的客户端；之后复用该线程的 Session/Cookie。
    if not hasattr(_thread_state, "client"):
        _thread_state.client = DamaiClient()
    return _thread_state.client


def comment_payload(item_id: str = "", ip_id: str = "", page: int = 1) -> dict:
    """组装评论接口的业务参数。

    字段名和默认值是大麦 H5 评论请求的公开参数结构；item_id 用于某个具体
    场次，ip_id 用于巡演聚合，二者通常二选一。page 从 1 开始。
    """
    return {
        "returnTouristTicketItem": True, "targetType": 0, "dataSource": 2,
        "pageIndex": page, "pageSize": 20, "isQueryReply": True,
        "isQueryIpInfo": True, "contentLabelList": "[0]",
        "isQueryCommentEntry": False, "sort": 2, "itemId": item_id,
        "isQueryContent": True, "targetId": "", "ipId": ip_id,
        "isQueryGradeStat": True, "isShowContentLabel": True,
        "commentTypes": "[32,62,65,66]", "dataModule": 1,
    }


def review_row(review: dict, project: Project | None, artist: str = "") -> dict | None:
    """把大麦接口返回的一条评论转换成项目统一 CSV 行。"""
    # commentId 和 content 都来自接口响应；没有唯一评论 ID 或正文就丢弃。
    comment_id = str(review.get("commentId") or "")
    text = review.get("content") or next(
        (part.get("value") for part in review.get("textDOList") or [] if part.get("value")), ""
    )
    if not comment_id.isdigit() or not str(text).strip():
        return None
    item_id = str(review.get("itemId") or "")
    # 【答辩讲解】反爬⑥ 范围校验：采集具体项目时再核对 itemId，防止接口返回混入其它项目的评论（范围污染）
    if project and item_id != project.item_id:
        # 具体项目采集时再次核对 itemId，防止接口响应混入其他项目评论。
        return None
    extra = review.get("extraInfo") or {}
    if isinstance(extra, str):
        try:
            # 有些响应把 extraInfo 序列化成 JSON 字符串，需要再解一次。
            extra = json.loads(extra)
        except json.JSONDecodeError:
            extra = {}
    user = review.get("userDO") or {}
    timestamp = review.get("gmtCreate") or ""
    # 【答辩讲解】健壮性：接口有时只给毫秒时间戳或只给正文片段，这里做时间回退与正文回退，保证字段不空
    if not timestamp and review.get("gmtCreateTime"):
        # 如果接口只给毫秒时间戳，就按 Asia/Shanghai 转成可读时间。
        timestamp = datetime.fromtimestamp(
            int(review["gmtCreateTime"]) / 1000, ZoneInfo("Asia/Shanghai")
        ).strftime("%Y-%m-%d %H:%M:%S")
    source_url = review.get("url") or ""
    if not source_url.startswith("https://m.damai.cn/"):
        # 接口没有给可复核链接时，根据 itemId 补一个大麦移动详情页。
        source_url = f"https://m.damai.cn/shows/item.html?itemId={item_id}"
    return {
        "source_record_id": f"damai-comment-{comment_id}",
        "concert_source_record_id": project.source_record_id if project else "",
        "artist_name": project.artist_name if project else artist,
        "concert_name": project.concert_name if project else "",
        "comment_text": str(text).strip()[:2000],
        "comment_time": timestamp,
        "like_count": (review.get("praiseInfo") or {}).get("praiseCount") or 0,
        "user_region": user.get("region") or user.get("location") or extra.get("publishCityName") or "",
        "source_platform": "damai",
        "source_url": source_url,
        "collected_at": datetime.now(ZoneInfo("Asia/Shanghai")).strftime("%Y-%m-%d %H:%M:%S"),
        "sentiment_score": "",
        "sentiment_scale": "",
    }


def collect_project(project: Project, max_pages: int) -> tuple[Project, list[dict], int, str]:
    """采集一个具体大麦项目的评论，并尝试取出巡演聚合 ID。"""
    api = "mtop.damai.wireless.comment.list.get"
    # 先请求第 1 页，用 total 判断最多需要翻多少页。
    first = client().call(api, "3.3", comment_payload(item_id=project.item_id))
    total = int(first.get("total") or 0)
    rows = []
    for page in range(1, min(max_pages, max(1, (total + 19) // 20)) + 1):
        # 第 1 页复用已经拿到的 first，其余页才发新请求。
        data = first if page == 1 else client().call(
            api, "3.3", comment_payload(item_id=project.item_id, page=page)
        )
        items = data.get("data") or []
        if not items:
            # 服务端提前返回空页时停止翻页。
            break
        rows.extend(filter(None, (review_row(item, project) for item in items)))
    # 详情接口不是评论接口；它用于从项目详情的 dynamicExtData.ipCard 中
    # 找到同一艺人巡演聚合的 ipId。
    detail = client().call(
        "mtop.alibaba.damai.detail.getdetail", "1.2",
        {"itemId": project.item_id, "platform": "8", "comboChannel": "2",
         "dmChannel": "damai@damaih5_h5"},
    )
    result = json.loads(detail.get("result") or "{}")
    item = ((result.get("detailViewComponentMap") or {}).get("item") or {})
    ip_id = str(((item.get("dynamicExtData") or {}).get("ipCard") or {}).get("ipId") or "")
    return project, rows, total, ip_id if ip_id.isdigit() else ""


def collect_tour(ip_id: str, artist: str, max_pages: int) -> tuple[str, list[dict], int]:
    """按巡演聚合 ID 读取艺人范围的评论。"""
    api = "mtop.damai.wireless.comment.list.get"
    first = client().call(api, "3.3", comment_payload(ip_id=ip_id))
    total = int(first.get("total") or 0)
    rows = []
    for page in range(1, min(max_pages, max(1, (total + 19) // 20)) + 1):
        data = first if page == 1 else client().call(
            api, "3.3", comment_payload(ip_id=ip_id, page=page)
        )
        items = data.get("data") or []
        if not items:
            break
        rows.extend(filter(None, (review_row(item, None, artist) for item in items)))
    return ip_id, rows, total


def write_csv(path: Path, rows: list[dict]) -> None:
    """把评论列表写成固定列顺序的 CSV。"""
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8", newline="") as target:
        writer = csv.DictWriter(target, fieldnames=COMMENT_COLUMNS, lineterminator="\n")
        writer.writeheader()
        writer.writerows(rows)


def collect(projects: list[Project], workers: int, site_pages: int, tour_pages: int):
    """并发采集具体场次评论，再采集无歧义的巡演评论。"""
    site_rows, tour_rows, failures = {}, {}, []
    ip_artists = defaultdict(set)
    site_totals = {}
    with ThreadPoolExecutor(max_workers=workers) as executor:
        # 每个 project 一个 Future；实际网络 Session 由 worker 线程内部按需创建。
        #----用线程池并发采集；每个线程有独立会话，不会串 Cookie
        futures = {executor.submit(collect_project, project, site_pages): project for project in projects}
        for future in as_completed(futures):
            project = futures[future]
            try:
                _, rows, total, ip_id = future.result()
                site_totals[project.item_id] = total
                for row in rows:
                    # 以 source_record_id 去重，避免同一评论重复出现。
                    site_rows[row["source_record_id"]] = row
                if ip_id and project.artist_name and project.artist_name != "以详情为准":
                    # 记录 ipId 对应的艺人名，稍后只处理“一个 ipId 只对应一个艺人”的情况。
                    ip_artists[ip_id].add(project.artist_name)
            except Exception as error:
                failures.append({"item_id": project.item_id, "stage": "project", "error": str(error)[:200]})
        # 如果同一个 ipId 对应多个艺人，无法安全判断聚合评论属于谁，主动跳过。
        tour_targets = {ip: next(iter(artists)) for ip, artists in ip_artists.items() if len(artists) == 1}
        futures = {executor.submit(collect_tour, ip, artist, tour_pages): ip for ip, artist in tour_targets.items()}
        tour_totals = {}
        for future in as_completed(futures):
            ip_id = futures[future]
            try:
                _, rows, total = future.result()
                tour_totals[ip_id] = total
                for row in rows:
                    if row["source_record_id"] not in site_rows:
                        # 项目评论优先；巡演聚合评论只补充尚未出现的评论 ID。
                        tour_rows.setdefault(row["source_record_id"], row)
            except Exception as error:
                failures.append({"ip_id": ip_id, "stage": "tour", "error": str(error)[:200]})
    report = {
        "requested_projects": len(projects),
        "successful_projects": len(site_totals),
        "projects_with_site_reviews": sum(total > 0 for total in site_totals.values()),
        "site_reviews_available": sum(site_totals.values()),
        "site_reviews_collected": len(site_rows),
        "requested_tours": len(tour_targets),
        "successful_tours": len(tour_totals),
        "tour_reviews_available": sum(tour_totals.values()),
        "tour_reviews_collected": len(tour_rows),
        "ambiguous_tour_ips_skipped": sum(len(names) > 1 for names in ip_artists.values()),
        "failures": failures,
    }
    return list(site_rows.values()), list(tour_rows.values()), report


def build_snapshot(base: Path, site: list[dict], tour: list[dict], output: Path, report: dict) -> None:
    """复制基础数据并把新增大麦评论合并为可发布快照。"""
    if output.exists():
        raise FileExistsError(f"Snapshot already exists: {output}")
    output.mkdir(parents=True)
    names = ("concerts.csv", "legacy_analysis_results.csv", "legacy_ticket_prices.csv")
    # 其他基础文件原样复制；只重新生成 comments.csv 和 manifest 计数/哈希。
    for name in names:
        shutil.copyfile(base / name, output / name)
    with (base / "comments.csv").open(encoding="utf-8-sig", newline="") as source, \
            (output / "comments.csv").open("w", encoding="utf-8", newline="") as target:
        reader = csv.DictReader(source)
        if tuple(reader.fieldnames or ()) != COMMENT_COLUMNS:
            # 列顺序不匹配时停止，避免生成 Spark 无法读取的混合文件。
            raise ValueError("Base comment columns do not match Spark input")
        writer = csv.DictWriter(target, fieldnames=COMMENT_COLUMNS, lineterminator="\n")
        writer.writeheader()
        count = 0
        for row in reader:
            writer.writerow(row)
            count += 1
        for row in site + tour:
            writer.writerow(row)
            count += 1
    manifest = json.loads((base / "manifest.json").read_text(encoding="utf-8"))
    manifest["counts"]["comments"] = count
    manifest["comment_sources"]["damai"] += len(site) + len(tour)
    manifest["scope_policy"]["damai"] = "concert_only_when_item_id_matches"
    manifest["scope_policy"]["damai_ip_aggregate"] = "artist_reference_only"
    manifest["review_crawl"] = {key: value for key, value in report.items() if key != "failures"}
    manifest["review_crawl"]["failed_requests"] = len(report["failures"])
    for name in (*names, "comments.csv"):
        manifest["files"][name] = hashlib.sha256((output / name).read_bytes()).hexdigest()
    (output / "manifest.json").write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )


def main() -> None:
    # --workers 控制并发项目数；site/tour-pages 控制两类评论最多翻多少页。
    # limit 适合调试，指定后只输出采集结果，不生成完整 snapshot。
    parser = argparse.ArgumentParser(description="批量采集大麦本站评论与巡演热评样本")
    parser.add_argument("--base", type=Path, default=Path("data/migration/current"))
    parser.add_argument("--output", type=Path, default=Path("data/migration/damai-reviews-20260923"))
    parser.add_argument("--workers", type=int, default=4)
    parser.add_argument("--site-pages", type=int, default=10)
    parser.add_argument("--tour-pages", type=int, default=1)
    parser.add_argument("--limit", type=int)
    args = parser.parse_args()
    if args.workers < 1 or args.site_pages < 1 or args.tour_pages < 1:
        parser.error("workers and page limits must be positive")
    projects = load_projects(args.base / "concerts.csv", args.limit)
    site, tour, report = collect(projects, args.workers, args.site_pages, args.tour_pages)
    args.output.mkdir(parents=True, exist_ok=True)
    write_csv(args.output / "damai_site_comments.csv", site)
    write_csv(args.output / "damai_tour_comments.csv", tour)
    #站内评论、巡演评论、采集报告。
    (args.output / "crawl_report.json").write_text(
        json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    print(json.dumps(report, ensure_ascii=False))
    if report["failures"]:
        raise SystemExit("Some requests failed; review crawl_report.json before publishing")
    if not args.limit:
        build_snapshot(args.base, site, tour, args.output / "snapshot", report)


if __name__ == "__main__":
    main()
