import csv
import tempfile
import unittest
from pathlib import Path

from scripts.build_comments_upload import FIELDNAMES, build


class BuildCommentsUploadTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)

    def tearDown(self):
        self.temp.cleanup()

    def test_quarantines_true_conflicts_but_keeps_whitespace_duplicates(self):
        source = self.root / "raw.csv"
        output = self.root / "upload.csv"
        excluded = self.root / "excluded.csv"
        rows = [
            self.row("same", "good\r\nshow", "2026-11-01 10:00:00"),
            self.row("same", "good show", "2026-11-02 10:00:00"),
            self.row("conflict", "good show", "2026-11-01 10:00:00"),
            self.row("conflict", "bad show", "2026-11-02 10:00:00"),
        ]
        with source.open("w", encoding="utf-8", newline="") as target:
            writer = csv.DictWriter(target, fieldnames=FIELDNAMES)
            writer.writeheader()
            writer.writerows(rows)

        stats = build(source, output, excluded)

        with output.open(encoding="utf-8", newline="") as result:
            kept = list(csv.DictReader(result))
        with excluded.open(encoding="utf-8", newline="") as result:
            removed = list(csv.DictReader(result))

        self.assertEqual(1, stats["conflicting_ids"])
        self.assertEqual(2, stats["conflicting_rows"])
        self.assertEqual(["same", "same"], [row["source_record_id"] for row in kept])
        self.assertEqual(["conflict", "conflict"], [row["source_record_id"] for row in removed])
        self.assertTrue(all("conflicting text variants" in row["exclusion_reason"] for row in removed))

    @staticmethod
    def row(record_id: str, text: str, collected_at: str) -> dict:
        return {
            "source_record_id": record_id,
            "concert_source_record_id": "",
            "artist_name": "",
            "concert_name": "",
            "comment_text": text,
            "comment_time": "2026-10-01 10:00:00",
            "like_count": "0",
            "user_region": "上海",
            "source_platform": "damai",
            "source_url": f"https://m.damai.cn/item/{record_id}",
            "collected_at": collected_at,
            "sentiment_score": "",
            "sentiment_scale": "",
        }


if __name__ == "__main__":
    unittest.main()
