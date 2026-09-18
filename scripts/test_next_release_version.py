import unittest

from next_release_version import next_version


class NextReleaseVersionTest(unittest.TestCase):
    def test_patch_reaches_ten_before_minor_rollover(self):
        self.assertEqual(("v0.0.10", "0.0.10", 10), next_version("v0.0.9"))
        self.assertEqual(("v0.1.0", "0.1.0", 100), next_version("v0.0.10"))

    def test_minor_and_major_rollover(self):
        self.assertEqual(("v0.10.0", "0.10.0", 1000), next_version("v0.9.10"))
        self.assertEqual(("v1.0.0", "1.0.0", 10_000), next_version("v0.10.10"))

    def test_rejects_unexpected_tags(self):
        with self.assertRaises(ValueError):
            next_version("release-1")


if __name__ == "__main__":
    unittest.main()
