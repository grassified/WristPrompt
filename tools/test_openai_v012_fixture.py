"""Check the frozen upgrade input with host SQLite, independently of Android's writer."""
from contextlib import closing
import hashlib
import json
from pathlib import Path
import sqlite3
import unittest


ANDROID_TEST = Path(__file__).resolve().parents[1] / "wear/src/androidTest"
FIXTURE = ANDROID_TEST / "assets/openai-v0.1.2"


class OpenAiV012FixtureTest(unittest.TestCase):
    def setUp(self):
        self.db = sqlite3.connect((FIXTURE / "conversations.db").as_uri() + "?mode=ro", uri=True)
        self.db.row_factory = sqlite3.Row
        self.addCleanup(self.db.close)

    def test_database_matches_frozen_sql(self):
        with closing(sqlite3.connect(":memory:")) as seed:
            seed.executescript((ANDROID_TEST / "fixtures/openai-v0.1.2.sql").read_text(encoding="utf-8"))
            self.assertEqual(list(seed.iterdump()), list(self.db.iterdump()))
        self.assertEqual(self.db.execute("PRAGMA user_version").fetchone()[0], 1)
        self.assertEqual(self.db.execute("PRAGMA integrity_check").fetchone()[0], "ok")
        self.assertEqual(list(self.db.execute("PRAGMA foreign_key_check")), [])
        self.assertEqual(self.db.execute("SELECT COUNT(*) FROM owners").fetchone()[0], 2)
        self.assertEqual(self.db.execute("SELECT COUNT(*) FROM conversations WHERE deleted = 1").fetchone()[0], 1)

    def test_replay_and_archives_have_distinct_ownership(self):
        rows = self.db.execute("SELECT * FROM conversations WHERE deleted = 0 ORDER BY updated DESC").fetchall()
        self.assertEqual([row["route"] for row in rows], ["CHATGPT_PLAN", "OPENAI_API_KEY", "OPENAI_API_KEY"])
        self.assertNotEqual(rows[0]["owner"], rows[1]["owner"])
        for row in rows:
            display = json.loads(row["display"])
            self.assertEqual(display["version"], 4)
            self.assertEqual(display["replay"], [])
            self.assertEqual(row["bytes"], len((row["display"] + row["replay"]).encode()))
            self.assertNotIn("data:image", row["replay"])

        plan, generated, failed = rows
        plan_replay = json.loads(plan["replay"])
        self.assertEqual([item.get("type") for item in plan_replay], [None, "reasoning", "message"])
        self.assertEqual(plan_replay[1]["encrypted_content"], "synthetic-opaque-replay")
        sent_dir = FIXTURE / "conversation_images_v1" / plan["id"]
        record, = json.loads((sent_dir / "catalog.json").read_text(encoding="utf-8"))["records"]
        self.assertEqual(plan_replay[0]["content"][1]["attachment_id"], record["id"])
        self.assert_jpeg(sent_dir / f"{record['id']}-1-image.jpg", record["imageBytes"], record["imageDigest"])
        self.assert_jpeg(sent_dir / f"{record['id']}-1-thumb.jpg", record["thumbBytes"], record["thumbDigest"])

        generated_dir = FIXTURE / "generated_images_v1" / generated["owner"] / generated["id"]
        reference = json.loads(generated["display"])["messages"][1]["parts"][1]["reference"]
        self.assert_jpeg(generated_dir / f"{reference['id']}-image.jpg", reference["bytes"], reference["sha256"])
        self.assert_jpeg(generated_dir / f"{reference['id']}-thumb.jpg", reference["thumbBytes"], reference["thumbSha256"])
        self.assertEqual(len(list(generated_dir.glob("*.jpg.part"))), 1)
        self.assertEqual(json.loads(generated["replay"])[1], {"type": "image_generation_call", "id": "ig_fixture_only"})
        self.assertEqual(json.loads(failed["replay"]), [])

    def assert_jpeg(self, path, size, digest):
        data = path.read_bytes()
        self.assertTrue(data.startswith(b"\xff\xd8") and data.endswith(b"\xff\xd9"))
        self.assertEqual(len(data), size)
        self.assertEqual(hashlib.sha256(data).hexdigest(), digest)
