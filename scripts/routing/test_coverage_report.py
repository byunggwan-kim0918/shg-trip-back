"""coverage_report.py 구역 판정 회귀 테스트.

  python3 -I -m unittest discover -s scripts/routing -p 'test_*.py'
"""
import importlib.util
import json
import os
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location("coverage_report", os.path.join(HERE, "coverage_report.py"))
coverage_report = importlib.util.module_from_spec(spec)
spec.loader.exec_module(coverage_report)


def square(lat, lng, half):
    return [[lat - half, lng - half], [lat - half, lng + half], [lat + half, lng + half], [lat + half, lng - half]]


class AreaIndexTest(unittest.TestCase):
    def setUp(self):
        areas = {"KR-41": [square(37.5, 127.0, 1.0)], "KR-11": [square(37.5, 127.0, 0.1)]}
        tmp = tempfile.NamedTemporaryFile("w", suffix=".json", delete=False)
        json.dump(areas, tmp)
        tmp.close()
        self.addCleanup(os.remove, tmp.name)
        self.index = coverage_report.AreaIndex(tmp.name)

    def test_enclave_point_goes_to_smallest_ring(self):
        self.assertEqual("KR-11", self.index.locate(37.5, 127.0))
        self.assertEqual("KR-41", self.index.locate(37.9, 127.0))

    def test_point_within_buffer_of_edge_counts_as_inside(self):
        just_outside = 38.5 + 2 / coverage_report.KM_PER_DEG
        self.assertEqual("KR-41", self.index.locate(just_outside, 127.0))

    def test_point_beyond_buffer_is_outside(self):
        far = 38.5 + 5 / coverage_report.KM_PER_DEG
        self.assertIsNone(self.index.locate(far, 127.0))


if __name__ == "__main__":
    unittest.main()
