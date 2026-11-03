import csv
import tempfile
import unittest
from pathlib import Path

from scripts.collect_damai_reviews import Project, load_projects, review_row


class DamaiReviewTests(unittest.TestCase):
    def test_only_exact_platform_item_id_is_linked_as_site_review(self):
        project = Project("1076121233739", "legacy-concert-1", "华晨宇", "南京站", "https://m.damai.cn/shows/item.html?itemId=1076121233739")
        same_item = {"commentId": 123, "itemId": 1076121233739, "textDOList": [{"value": "南京站售前讨论"}], "gmtCreate": "2026-09-08 09:38:00"}
        other_item = {**same_item, "itemId": 1000000000000}

        self.assertEqual(review_row(same_item, project)["concert_source_record_id"], "legacy-concert-1")
        self.assertIsNone(review_row(other_item, project))
        self.assertEqual(review_row(other_item, None, "华晨宇")["concert_source_record_id"], "")

    def test_duplicate_item_id_in_snapshot_is_rejected(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "concerts.csv"
            with path.open("w", encoding="utf-8", newline="") as target:
                writer = csv.DictWriter(target, fieldnames=["source_record_id", "artist_name", "category", "concert_name", "source_url"])
                writer.writeheader()
                for record_id in ("legacy-concert-1", "legacy-concert-2"):
                    writer.writerow({"source_record_id": record_id, "artist_name": "华晨宇", "category": "演唱会", "concert_name": "南京站", "source_url": "https://m.damai.cn/shows/item.html?itemId=1076121233739"})
            with self.assertRaisesRegex(ValueError, "Ambiguous Damai itemId"):
                load_projects(path)


if __name__ == "__main__":
    unittest.main()
