#!/usr/bin/env python3
"""Validate final domestic-source CSV files before Spark publishing."""

from __future__ import annotations

import argparse
import csv
import hashlib
import json
import re
from collections import Counter
from datetime import datetime
from pathlib import Path
from urllib.parse import urlparse

try:
    from scripts.build_concerts_upload import row_key
except ModuleNotFoundError:
    from build_concerts_upload import row_key


APPROVED_PUBLIC_HOSTS = {"news.qq.com", "bjd.com.cn"}


def host_is(value: str, domain: str) -> bool:
    host = (urlparse(value).hostname or "").lower().rstrip(".")
    return host == domain or host.endswith(f".{domain}")


def valid_concert_host(source_type: str, source_url: str) -> bool:
    parsed = urlparse(source_url)
    if parsed.scheme != "https" or parsed.username or parsed.password or not parsed.hostname:
        return False
    host = parsed.hostname.lower().rstrip(".")
    if source_type == "piaoniu":
        return host_is(source_url, "piaoniu.com")
    if source_type == "showstart":
        return host_is(source_url, "showstart.com")
    if source_type == "public":
        return host in APPROVED_PUBLIC_HOSTS or host.endswith(".gov.cn") or host.endswith(".bjd.com.cn")
    return False


def validate_timestamp(value: str, label: str) -> None:
    try:
        datetime.fromisoformat(value.strip())
    except ValueError as exception:
        raise ValueError(f"Invalid {label}: {value!r}") from exception


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def normalize_comment_text(value: str) -> str:
    """Normalize whitespace consistently with the Spark cleaning stage."""
    return re.sub(r"\s+", " ", (value or "").strip())


def audit_concerts(path: Path) -> dict:
    rows = 0
    sources = Counter()
    dedupe_keys = set()
    with path.open(encoding="utf-8-sig", newline="") as source:
        reader = csv.DictReader(source)
        required_columns = {
            "concert_name", "city", "venue", "show_time", "source_type", "source_url", "collected_at", "category"
        }
        missing_columns = required_columns.difference(reader.fieldnames or ())
        if missing_columns:
            raise ValueError(f"Concert CSV is missing columns: {sorted(missing_columns)}")
        for line_number, row in enumerate(reader, start=2):
            rows += 1
            name = (row.get("concert_name") or "").strip()
            show_time = (row.get("show_time") or "").strip()
            source_type = (row.get("source_type") or "").strip().lower()
            source_url = (row.get("source_url") or "").strip()
            collected_at = (row.get("collected_at") or "").strip()
            if not name or not show_time or not source_url or not collected_at:
                raise ValueError(f"Concert row {line_number} is missing a required value")
            if (row.get("category") or "").strip() != "演唱会":
                raise ValueError(f"Concert row {line_number} is outside the concert-only scope")
            validate_timestamp(show_time, f"concert show_time at row {line_number}")
            validate_timestamp(collected_at, f"concert collected_at at row {line_number}")
            if not valid_concert_host(source_type, source_url):
                raise ValueError(f"Concert row {line_number} has an unapproved source: {source_type} {source_url}")
            dedupe_key = row_key(row, show_time)
            if dedupe_key in dedupe_keys:
                raise ValueError(f"Concert row {line_number} duplicates an existing performance key")
            dedupe_keys.add(dedupe_key)
            sources[source_type] += 1
    return {"input": rows, "accepted": rows, "duplicates": 0, "sources": dict(sorted(sources.items()))}


def audit_comments(path: Path) -> dict:
    rows = 0
    identifiers = set()
    comment_text_by_id = {}
    rows_by_id = Counter()
    conflicting_ids = set()
    sources = Counter()
    selected_by_id = {}
    with path.open(encoding="utf-8-sig", newline="") as source:
        reader = csv.DictReader(source)
        required_columns = {
            "source_record_id", "comment_text", "source_platform", "source_url", "collected_at"
        }
        missing_columns = required_columns.difference(reader.fieldnames or ())
        if missing_columns:
            raise ValueError(f"Comment CSV is missing columns: {sorted(missing_columns)}")
        for line_number, row in enumerate(reader, start=2):
            rows += 1
            record_id = (row.get("source_record_id") or "").strip()
            text = normalize_comment_text(row.get("comment_text") or "")
            source_type = (row.get("source_platform") or "").strip().lower()
            source_url = (row.get("source_url") or "").strip()
            collected_at = (row.get("collected_at") or "").strip()
            if not record_id or not text or not source_url or not collected_at:
                raise ValueError(f"Comment row {line_number} is missing a required value")
            for association_field in ("concert_source_record_id", "concert_name"):
                if (row.get(association_field) or "").strip():
                    raise ValueError(
                        f"Comment row {line_number} contains an unverified event association: {association_field}")
            for sentiment_field in ("sentiment_score", "sentiment_scale"):
                if (row.get(sentiment_field) or "").strip():
                    raise ValueError(
                        f"Comment row {line_number} contains a precomputed sentiment field: {sentiment_field}")
            parsed_url = urlparse(source_url)
            if (parsed_url.scheme != "https" or parsed_url.username or parsed_url.password
                    or source_type != "damai" or not host_is(source_url, "damai.cn")):
                raise ValueError(f"Comment row {line_number} is not an approved Damai record")
            validate_timestamp(collected_at, f"comment collected_at at row {line_number}")
            previous_text = comment_text_by_id.setdefault(record_id, text)
            if previous_text != text:
                conflicting_ids.add(record_id)
            try:
                like_count = int(float((row.get("like_count") or "0").strip()))
            except ValueError:
                like_count = 0
            selection_key = (
                collected_at,
                (row.get("comment_time") or "").strip(),
                len(text),
                like_count,
            )
            previous = selected_by_id.get(record_id)
            if previous is None or selection_key > previous[0]:
                selected_by_id[record_id] = (selection_key, row)
            identifiers.add(record_id)
            rows_by_id[record_id] += 1
            sources[source_type] += 1
    accepted_ids = identifiers.difference(conflicting_ids)
    accepted = len(accepted_ids)
    duplicates = sum(rows_by_id[record_id] - 1 for record_id in accepted_ids)
    conflicting_rows = sum(rows_by_id[record_id] for record_id in conflicting_ids)
    artist_scoped = sum(
        bool((row.get("artist_name") or "").strip())
        for record_id, (_, row) in selected_by_id.items()
        if record_id in accepted_ids
    )
    return {
        "input": rows,
        "accepted": accepted,
        "duplicates": duplicates,
        "conflicting_records": len(conflicting_ids),
        "conflicting_rows": conflicting_rows,
        "conflicting_record_ids": sorted(conflicting_ids),
        "artist_scoped": artist_scoped,
        "sources": dict(sorted(sources.items())),
    }


def audit(concerts: Path, comments: Path) -> dict:
    concert_result = audit_concerts(concerts)
    comment_result = audit_comments(comments)
    return {
        "files": {
            "concerts": {"path": str(concerts), "sha256": sha256(concerts)},
            "comments": {"path": str(comments), "sha256": sha256(comments)},
        },
        "concerts": concert_result,
        "comments": comment_result,
        "totals": {
            "input": concert_result["input"] + comment_result["input"],
            "accepted": concert_result["accepted"] + comment_result["accepted"],
            "duplicates": concert_result["duplicates"] + comment_result["duplicates"],
            "conflicting_records": comment_result["conflicting_records"],
        },
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--concerts", type=Path, required=True)
    parser.add_argument("--comments", type=Path, required=True)
    parser.add_argument("--manifest", type=Path)
    parser.add_argument("--format", choices=("json", "tsv"), default="json")
    args = parser.parse_args()

    result = audit(args.concerts, args.comments)
    payload = json.dumps(result, ensure_ascii=False, indent=2) + "\n"
    if args.manifest:
        args.manifest.write_text(payload, encoding="utf-8")
    if args.format == "tsv":
        print(
            result["concerts"]["input"],
            result["comments"]["input"],
            result["concerts"]["accepted"],
            result["comments"]["accepted"],
            sep="\t",
        )
    else:
        print(payload, end="")


if __name__ == "__main__":
    main()
