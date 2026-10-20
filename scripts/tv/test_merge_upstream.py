import os
from pathlib import Path
import subprocess
import tempfile
import unittest


SCRIPT = Path(__file__).with_name("merge-upstream.sh").resolve()


class MergeUpstreamTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.upstream = self.root / "upstream"
        self.fork = self.root / "fork"
        self.git(self.root, "init", "-b", "main", str(self.upstream))
        self.configure(self.upstream)
        self.commit_file(self.upstream, "shared.txt", "base\n")
        self.git(self.root, "clone", str(self.upstream), str(self.fork))
        self.configure(self.fork)
        self.commit_file(self.fork, "tv.txt", "remote interface\n")

    def git(self, directory, *args):
        return subprocess.run(
            ["git", "-C", str(directory), *args],
            check=True, text=True, capture_output=True,
        ).stdout.strip()

    def configure(self, directory):
        self.git(directory, "config", "user.name", "TV sync test")
        self.git(directory, "config", "user.email", "tv-test@example.invalid")

    def commit_file(self, directory, filename, content):
        (directory / filename).write_text(content)
        self.git(directory, "add", filename)
        self.git(directory, "commit", "-m", "fixture update")

    def sync(self):
        return subprocess.run(
            ["bash", str(SCRIPT)], cwd=self.fork,
            env={**os.environ, "FLOW_UPSTREAM_URL": str(self.upstream)},
            text=True, capture_output=True,
        )

    def test_no_change_does_not_create_a_commit(self):
        original = self.git(self.fork, "rev-parse", "HEAD")
        result = self.sync()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("changed=false", result.stdout)
        self.assertEqual(self.git(self.fork, "rev-parse", "HEAD"), original)

    def test_merge_keeps_tv_work_and_upstream_ancestry(self):
        self.commit_file(self.upstream, "shared.txt", "new upstream fix\n")
        upstream_head = self.git(self.upstream, "rev-parse", "HEAD")
        result = self.sync()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("changed=true", result.stdout)
        self.assertEqual((self.fork / "tv.txt").read_text(), "remote interface\n")
        self.assertEqual((self.fork / "shared.txt").read_text(), "new upstream fix\n")
        self.git(self.fork, "merge-base", "--is-ancestor", upstream_head, "HEAD")
        self.assertIn("changed=false", self.sync().stdout)

    def test_conflict_aborts_without_changing_main(self):
        self.commit_file(self.fork, "shared.txt", "TV specific change\n")
        self.commit_file(self.upstream, "shared.txt", "conflicting upstream fix\n")
        original = self.git(self.fork, "rev-parse", "HEAD")
        result = self.sync()
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(self.git(self.fork, "rev-parse", "HEAD"), original)
        self.assertEqual(self.git(self.fork, "status", "--porcelain"), "")
        self.assertFalse((self.fork / ".git" / "MERGE_HEAD").exists())


if __name__ == "__main__":
    unittest.main()
