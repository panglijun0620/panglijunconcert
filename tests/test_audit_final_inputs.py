import csv
import tempfile
import unittest
from pathlib import Path

from scripts.audit_final_inputs import audit, audit_concerts
from scripts.build_concerts_upload import row_key, row_quality


class FinalInputAuditTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)

    def tearDown(self):
        self.temp.cleanup()

    def write_csv(self, name, fieldnames, rows):
        path = self.root / name
        with path.open("w", encoding="utf-8", newline="") as target:
            writer = csv.DictWriter(target, fieldnames=fieldnames)
            writer.writeheader()
            writer.writerows(rows)
        return path

    def concert_file(self):
        return self.write_csv(
            "concerts.csv",
            ["concert_name", "city", "venue", "show_time", "source_type", "source_url", "collected_at", "category"],
            [{
                "concert_name": "Test Show", "city": "Shanghai", "venue": "Test Hall",
                "show_time": "2026-12-01", "source_type": "piaoniu",
                "source_url": "https://www.piaoniu.com/activity/1", "collected_at": "2026-11-01 10:00:00",
                "category": "演唱会",
            }],
        )

    def test_counts_comment_ids_after_validation(self):
        comments = self.write_csv(
            "comments.csv",
            ["source_record_id", "comment_text", "source_platform", "source_url", "collected_at"],
            [
                {"source_record_id": "1", "comment_text": "good", "source_platform": "damai",
                 "source_url": "https://m.damai.cn/item/1", "collected_at": "2026-11-01"},
                {"source_record_id": "1", "comment_text": "good", "source_platform": "damai",
                 "source_url": "https://m.damai.cn/item/1", "collected_at": "2026-11-02"},
            ],
        )

        result = audit(self.concert_file(), comments)

        self.assertEqual(2, result["comments"]["input"])
        self.assertEqual(1, result["comments"]["accepted"])
        self.assertEqual(1, result["comments"]["duplicates"])
        self.assertEqual(0, result["comments"]["conflicting_records"])
        self.assertEqual([], result["comments"]["conflicting_record_ids"])

    def test_reports_same_comment_id_with_different_text_as_conflict(self):
        comments = self.write_csv(
            "comments.csv",
            ["source_record_id", "comment_text", "source_platform", "source_url", "collected_at"],
            [
                {"source_record_id": "1", "comment_text": "good", "source_platform": "damai",
                 "source_url": "https://m.damai.cn/item/1", "collected_at": "2026-11-01"},
                {"source_record_id": "1", "comment_text": "great", "source_platform": "damai",
                 "source_url": "https://m.damai.cn/item/1", "collected_at": "2026-11-02"},
            ],
        )

        result = audit(self.concert_file(), comments)

        self.assertEqual(0, result["comments"]["accepted"])
        self.assertEqual(0, result["comments"]["duplicates"])
        self.assertEqual(1, result["comments"]["conflicting_records"])
        self.assertEqual(2, result["comments"]["conflicting_rows"])
        self.assertEqual(["1"], result["comments"]["conflicting_record_ids"])

    def test_treats_whitespace_only_comment_changes_as_duplicates(self):
        comments = self.write_csv(
            "comments.csv",
            ["source_record_id", "comment_text", "source_platform", "source_url", "collected_at"],
            [
                {"source_record_id": "1", "comment_text": "good\r\nshow", "source_platform": "damai",
                 "source_url": "https://m.damai.cn/item/1", "collected_at": "2026-11-01"},
                {"source_record_id": "1", "comment_text": "good show", "source_platform": "damai",
                 "source_url": "https://m.damai.cn/item/1", "collected_at": "2026-11-02"},
            ],
        )

        result = audit(self.concert_file(), comments)

        self.assertEqual(1, result["comments"]["accepted"])
        self.assertEqual(1, result["comments"]["duplicates"])
        self.assertEqual(0, result["comments"]["conflicting_records"])

    def test_rejects_precomputed_sentiment_fields(self):
        comments = self.write_csv(
            "comments.csv",
            ["source_record_id", "comment_text", "source_platform", "source_url", "collected_at",
             "sentiment_score", "sentiment_scale"],
            [{"source_record_id": "1", "comment_text": "text", "source_platform": "damai",
              "source_url": "https://m.damai.cn/item/1", "collected_at": "2026-11-01",
              "sentiment_score": "0.8", "sentiment_scale": "-1-1"}],
        )

        with self.assertRaisesRegex(ValueError, "precomputed sentiment field"):
            audit(self.concert_file(), comments)

    def test_rejects_spoofed_damai_domain(self):
        comments = self.write_csv(
            "comments.csv",
            ["source_record_id", "comment_text", "source_platform", "source_url", "collected_at"],
            [{"source_record_id": "1", "comment_text": "text", "source_platform": "damai",
              "source_url": "https://evil-damai.cn/comment/1", "collected_at": "2026-11-01"}],
        )

        with self.assertRaisesRegex(ValueError, "not an approved Damai record"):
            audit(self.concert_file(), comments)

    def test_rejects_non_damai_comment_source(self):
        comments = self.write_csv(
            "comments.csv",
            ["source_record_id", "comment_text", "source_platform", "source_url", "collected_at"],
            [{"source_record_id": "1", "comment_text": "text", "source_platform": "other",
              "source_url": "https://example.invalid/comment/1", "collected_at": "2026-11-01"}],
        )

        with self.assertRaisesRegex(ValueError, "not an approved Damai record"):
            audit(self.concert_file(), comments)

    def test_rejects_unverified_comment_event_association(self):
        comments = self.write_csv(
            "comments.csv",
            ["source_record_id", "concert_source_record_id", "comment_text", "source_platform", "source_url", "collected_at"],
            [{"source_record_id": "1", "concert_source_record_id": "concert-1", "comment_text": "text",
              "source_platform": "damai", "source_url": "https://m.damai.cn/item/1", "collected_at": "2026-11-01"}],
        )

        with self.assertRaisesRegex(ValueError, "unverified event association"):
            audit(self.concert_file(), comments)

    def test_accepts_verified_artist_scope_without_event_association(self):
        comments = self.write_csv(
            "comments.csv",
            ["source_record_id", "artist_name", "comment_text", "source_platform", "source_url", "collected_at"],
            [{"source_record_id": "1", "artist_name": "测试艺人", "comment_text": "text",
              "source_platform": "damai", "source_url": "https://m.damai.cn/item/1",
              "collected_at": "2026-11-01"}],
        )

        result = audit(self.concert_file(), comments)

        self.assertEqual(1, result["comments"]["artist_scoped"])

    def test_rejects_cross_platform_duplicate_event_after_normalization(self):
        concerts = self.write_csv(
            "concerts.csv",
            ["concert_name", "city", "venue", "show_time", "source_type", "source_url", "collected_at", "category"],
            [
                {"concert_name": "[北京]2027新年音乐会", "city": "北京", "venue": "北京中山音乐堂",
                 "show_time": "2027-01-10", "source_type": "piaoniu",
                 "source_url": "https://www.piaoniu.com/activity/1", "collected_at": "2026-11-01", "category": "演唱会"},
                {"concert_name": "2027新年音乐会", "city": "北京", "venue": "北京中山音乐堂",
                 "show_time": "2027-01-10 19:30", "source_type": "showstart",
                 "source_url": "https://www.showstart.com/event/2", "collected_at": "2026-11-01", "category": "演唱会"},
            ],
        )

        with self.assertRaisesRegex(ValueError, "duplicates an existing performance key"):
            audit_concerts(concerts)

    def test_prefers_more_complete_duplicate_row(self):
        date_only = {
            "concert_name": "[北京]2027新年音乐会", "city": "北京", "venue": "场馆未公布",
            "show_time_precision": "DATE", "artist_name": "", "min_price": "", "max_price": "",
        }
        detailed = {
            "concert_name": "2027新年音乐会", "city": "北京", "venue": "北京中山音乐堂",
            "show_time_precision": "DATETIME", "artist_name": "北京爱乐乐团", "min_price": 180.0, "max_price": 680.0,
        }

        self.assertNotEqual(
            row_key(date_only, "2027-01-10"),
            row_key(detailed, "2027-01-10 19:30"),
        )
        same_venue = {**detailed, "source_type": "showstart", "source_url": "https://www.showstart.com/event/2"}
        self.assertEqual(row_key(detailed, "2027-01-10 19:30"), row_key(same_venue, "2027-01-10"))
        different_venue = {**detailed, "venue": "国家体育场"}
        self.assertNotEqual(row_key(detailed, "2027-01-10"), row_key(different_venue, "2027-01-10"))
        self.assertEqual(
            row_key({"concert_name": "[北京]【蓝色多瑙河】新年音乐会", "city": "北京"}, "2027-01-10"),
            row_key({"concert_name": "【蓝色多瑙河】新年音乐会", "city": "北京"}, "2027-01-10 19:30"),
        )
        self.assertGreater(row_quality(detailed), row_quality(date_only))


if __name__ == "__main__":
    unittest.main()
