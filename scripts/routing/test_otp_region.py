"""otp_region.py의 피드 목록·회귀 비교 테스트."""
import importlib.util
import datetime
import http.server
import threading
import shutil
import zipfile
import json
import re
import sys
import os
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location("otp_region", os.path.join(HERE, "otp_region.py"))
otp_region = importlib.util.module_from_spec(spec)
spec.loader.exec_module(otp_region)


class FeedLifecycleTest(unittest.TestCase):

    def setUp(self):
        self.saved = {k: getattr(otp_region, k) for k in ("ROOT", "regions", "FAILED_FEEDS", "config", "fetch",
                                                         "clip", "run_measured", "SOURCES")}
        otp_region.SOURCES = tempfile.mkdtemp()
        self.root = tempfile.mkdtemp()
        otp_region.ROOT = self.root
        os.makedirs(os.path.join(self.root, "kr", "graph"))
        otp_region.regions = lambda: {"kr": {"feeds": ["kr-ktdb", "kr-korail"]}}

    def tearDown(self):
        for k, v in self.saved.items():
            setattr(otp_region, k, v)
        shutil.rmtree(self.root, ignore_errors=True)

    def write_current(self, trips):
        with open(os.path.join(self.root, "kr", "graph", "feeds-manifest.json"), "w") as f:
            json.dump({fid: {"trips": t} for fid, t in trips.items()}, f)

    def test_핵심_피드_운행이_반_밑으로_줄면_교체하지_않는다(self):
        self.write_current({"kr-ktdb": 1000, "kr-korail": 100})
        problems = otp_region.compare_with_current("kr", {"kr-ktdb": {"trips": 400}, "kr-korail": {"trips": 100}})
        self.assertEqual(problems, ["kr-ktdb trips 1000->400"])

    def test_핵심_피드가_빠져도_교체하지_않는다(self):
        self.write_current({"kr-ktdb": 1000, "kr-korail": 100})
        self.assertIn("kr-korail trips 100->0", otp_region.compare_with_current("kr", {"kr-ktdb": {"trips": 990}}))

    def test_처음_짓는_그래프는_비교하지_않는다(self):
        os.remove(os.path.join(self.root, "kr", "graph", "feeds-manifest.json")) if os.path.exists(
            os.path.join(self.root, "kr", "graph", "feeds-manifest.json")) else None
        self.assertEqual(otp_region.compare_with_current("kr", {}), [])

    def test_피드_목록에_운행_기간과_운행_수를_남긴다(self):
        graph = os.path.join(self.root, "kr", "staging")
        os.makedirs(graph)
        report = ["use  kr-korail: stops=267 trips=1069 stop_times=11310 services=13 service_days=20261101-20271101",
                  "skip odpt-x: needs ODPT_ACCESS_TOKEN"]
        m = otp_region.feed_manifest(graph, report, {}, {"feeds": {"kr-korail": {"url": "https://mkuran.pl/gtfs/korail.zip"}}})
        self.assertEqual(m["kr-korail"]["serviceTo"], "20271101")
        self.assertEqual(m["kr-korail"]["trips"], 1069)
        self.assertNotIn("odpt-x", m)

    def test_롤백은_이전_그래프와_자리를_바꾼다(self):
        base = os.path.join(self.root, "kr")
        os.makedirs(os.path.join(base, "previous"))
        open(os.path.join(base, "previous", "marker"), "w").write("old")
        otp_region.rollback("kr")
        self.assertTrue(os.path.exists(os.path.join(base, "graph", "marker")))
        self.assertTrue(os.path.exists(os.path.join(base, "previous")))


    def test_교체가_끊겨_그래프가_없으면_롤백은_이전_그래프를_되살린다(self):
        base = os.path.join(self.root, "kr")
        os.rmdir(os.path.join(base, "graph"))
        os.makedirs(os.path.join(base, "previous"))
        otp_region.rollback("kr")
        self.assertTrue(os.path.exists(os.path.join(base, "graph")))
        self.assertFalse(os.path.exists(os.path.join(base, "previous")))

    def test_격리한_피드는_사용_목록에서_빠지고_설정에서도_빠진다(self):
        report = ["use  a: trips=10", "use  b: trips=5", "skip c: no trips"]
        self.assertEqual(otp_region.quarantine_report(report, ["b"]),
                         ["use  a: trips=10", "quarantined b: OTP build error", "skip c: no trips"])
        graph = os.path.join(self.root, "kr", "staging")
        os.makedirs(graph)
        cfg = {"feeds": {"b": {"realtime": {"url": "https://example.org/rt.pb"}}}}
        otp_region.write_configs(graph, {"timezone": "Asia/Seoul"}, cfg, ["a"])
        build = json.load(open(os.path.join(graph, "build-config.json")))
        router = json.load(open(os.path.join(graph, "router-config.json")))
        self.assertEqual([f["feedId"] for f in build["transitFeeds"]], ["a"])
        self.assertEqual(router["updaters"], [])

    def test_실패_기록_파일이_깨져도_카탈로그_피드를_계속_받는다(self):
        failed = os.path.join(self.root, "failed-feeds.json")
        open(failed, "w").write('{"x": 1')
        otp_region.FAILED_FEEDS = failed
        self.assertEqual(otp_region.load_failed_feeds(), {})

    def test_다시_받기에_실패하면_전에_받은_피드를_쓴다(self):
        otp_region.FAILED_FEEDS = os.path.join(self.root, "failed-feeds.json")
        src = os.path.join(self.root, "old.gtfs.zip")
        with zipfile.ZipFile(src, "w") as z:
            z.writestr("stops.txt", "stop_id\n")
        original, original_config = otp_region.fetch, otp_region.config
        otp_region.config = lambda: {"catalogRefreshDays": 0}

        def broken(*a, **k):
            raise TimeoutError("down")
        otp_region.fetch = broken
        try:
            self.assertEqual(otp_region.catalog_fetch("x", "https://example.org/x.zip", src), src)
            self.assertIn("x", otp_region.load_failed_feeds())
        finally:
            otp_region.fetch, otp_region.config = original, original_config

    def test_대표_시각표_기간은_실제_달력_핵심_피드가_모두_운행하는_기간이다(self):
        self.assertEqual(otp_region.calendar_window(["stops=1 trips=9 service_days=20261101-20271101", None]),
                         ("20261101", "20271101"))
        self.assertEqual(otp_region.calendar_window(["service_days=20261101-20271101", "service_days=20261120-20270228"]),
                         ("20261120", "20270228"))
        self.assertIsNone(otp_region.calendar_window([None, "service_days=none"]))

    def test_판_기준월이_오래되면_새_판을_신청할_때다(self):
        feed = {"edition": "2025-03", "editionMaxAgeDays": 450}
        self.assertFalse(otp_region.edition_stale(feed, datetime.date(2026, 5, 1)))
        self.assertTrue(otp_region.edition_stale(feed, datetime.date(2026, 6, 1)))
        self.assertFalse(otp_region.edition_stale({}, datetime.date(2030, 1, 1)))

    def test_S3_주소는_버킷과_키_형식만_받는다(self):
        for bad in ("s3://b/../x; rm", "https://x/y.zip", "s3://Bad_Bucket/k.zip"):
            with self.assertRaises(ValueError):
                otp_region.fetch_s3(bad, os.path.join(self.root, "x.zip"))

    def test_KTDB_열차는_코레일_피드에_없는_공항철도_직통만_남긴다(self):
        rule = re.compile(json.load(open(os.path.join(HERE, "regions.json")))["feeds"]["kr-ktdb"]["dropRouteNameRegex"])
        kept = ["[일반]서울공-인천국"]
        dropped = ["[고속]수서천안아산부산", "[고속]수서동대구진주", "[고속]행신오송공주목포", "[고속]1경부II행신수원부산",
                   "[일반]1경부서울부산", "[일반]교외서울의정부", "[일반]ITX청춘(천마산.신내)"]
        self.assertEqual([n for n in kept if rule.search(n)], [])
        self.assertEqual([n for n in dropped if not rule.search(n)], [])

    def clip_with(self, results):
        calls = []

        def fake_clip(src, dst, bbox, feed=None, window=None):
            calls.append((os.path.basename(dst), window))
            return results[os.path.basename(dst)], "no trips in area/period"
        original = otp_region.clip
        otp_region.clip = fake_clip
        try:
            cfg = {"feeds": {"kr-ktdb": {"representativeCalendar": True}, "kr-korail": {}}}
            region = {"bbox": [33, 124, 39, 132], "feeds": ["kr-ktdb", "kr-korail"]}
            report = otp_region.clip_feeds("g", region, cfg, [("kr-ktdb", "a.zip"), ("kr-korail", "b.zip")])
        finally:
            otp_region.clip = original
        return report, calls

    def test_핵심_피드_밖의_모든_피드를_핵심_피드_기간으로_자른다(self):
        calls = []

        def fake_clip(src, dst, bbox, feed=None, window=None):
            calls.append((os.path.basename(dst), window))
            return {"jp-rail.gtfs.zip": "trips=9 service_days=20261023-20270228"}.get(os.path.basename(dst), "trips=3"), None
        original = otp_region.clip
        otp_region.clip = fake_clip
        try:
            report = otp_region.clip_feeds("g", {"bbox": [0, 0, 1, 1], "feeds": ["jp-rail"]}, {"feeds": {}},
                                           [("atlas-bus", "a.zip"), ("jp-rail", "r.zip")])
        finally:
            otp_region.clip = original
        self.assertEqual(calls, [("jp-rail.gtfs.zip", None), ("atlas-bus.gtfs.zip", ("20261023", "20270228"))])
        self.assertIn("calendar_window=20261023-20270228", report[1])

    def test_대표_시각표는_실제_달력_피드를_자른_뒤_그_기간으로_자른다(self):
        report, calls = self.clip_with({"kr-korail.gtfs.zip": "trips=9 service_days=20261101-20271101",
                                        "kr-ktdb.gtfs.zip": "trips=5 service_days=20170101-20301231"})
        self.assertEqual(calls, [("kr-korail.gtfs.zip", None), ("kr-ktdb.gtfs.zip", ("20261101", "20271101"))])
        self.assertIn("calendar_window=20261101-20271101", report[1])

    def test_실제_달력_피드가_빠지면_대표_시각표도_싣지_않는다(self):
        report, calls = self.clip_with({"kr-korail.gtfs.zip": None, "kr-ktdb.gtfs.zip": "trips=5"})
        self.assertEqual([c[0] for c in calls], ["kr-korail.gtfs.zip"])
        self.assertEqual(report[1], "skip kr-ktdb: no calendar window from core feeds")

    def test_지금_그래프에_있는_핵심_피드가_빠지면_빌드_전에_멈춘다(self):
        self.write_current({"kr-ktdb": 1000, "kr-korail": 100})
        self.assertEqual(otp_region.missing_core_feeds("kr", ["kr-korail"]), ["kr-ktdb"])
        self.assertEqual(otp_region.missing_core_feeds("kr", ["kr-korail", "kr-ktdb"]), [])

    def test_역_대_역_검사_구간은_정류장_id로_묻는다(self):
        self.assertEqual(otp_region.smoke_place({"fromStop": "jp-rail:1748"}, "from"),
                         {"location": {"stopLocation": {"stopLocationId": "jp-rail:1748"}}})
        self.assertEqual(otp_region.smoke_place({"to": [35.0, 139.0]}, "to"),
                         {"location": {"coordinate": {"latitude": 35.0, "longitude": 139.0}}})

    def test_자식_프로세스_최대_메모리를_따로_잰다(self):
        code, out, _, peak = otp_region.run_measured([sys.executable, "-c", "x = bytearray(50_000_000); print('ok')"])
        self.assertEqual((code, out.strip()), (0, "ok"))
        self.assertGreater(peak, 50_000_000)

    def test_일본어_피드_id는_서로_다른_고유_id가_된다(self):
        a, b = otp_region.safe_id("f-ひたち", 120), otp_region.safe_id("f-いわき", 120)
        self.assertNotEqual(a, b)
        self.assertEqual(otp_region.safe_id("mdb-1076", 120), "mdb-1076")
        self.assertEqual(otp_region.feed_id("kr-ktdb"), "kr-ktdb")
        self.assertLessEqual(len(otp_region.feed_id("atlas-" + "x" * 200)), 60)

    def test_OTP_피드_id가_겹치면_빌드_전에_멈춘다(self):
        graph = os.path.join(self.root, "kr", "staging")
        os.makedirs(graph)
        with self.assertRaises(SystemExit):
            otp_region.write_configs(graph, {"timezone": "Asia/Seoul"}, {"feeds": {}}, ["mdb-1", "mdb-1"])

    def test_교체는_그_전_백업을_지우지_않고_보관한다(self):
        base = os.path.join(self.root, "kr")
        for d, marker in (("previous", "old"), ("graph", "cur"), ("staging", "new")):
            os.makedirs(os.path.join(base, d), exist_ok=True)
            open(os.path.join(base, d, "marker"), "w").write(marker)
        otp_region.promote("kr")
        self.assertEqual(open(os.path.join(base, "graph", "marker")).read(), "new")
        self.assertEqual(open(os.path.join(base, "previous", "marker")).read(), "cur")
        archived = os.listdir(os.path.join(base, "archive"))
        self.assertEqual(len(archived), 1)
        self.assertEqual(open(os.path.join(base, "archive", archived[0], "marker")).read(), "old")

    def test_같은_피드는_카탈로그와_주소_표기가_달라도_원천_주소_하나로_본다(self):
        aliases = {"api-public.odpt.org": "api.odpt.org"}
        a = otp_region.source_key("https://api-public.odpt.org/api/v4/files/odpt/UnoBus/AllLines.zip?date=20261201", aliases)
        b = otp_region.source_key("https://API.odpt.org/api/v4/files/odpt/UnoBus/AllLines.zip/", aliases)
        self.assertEqual(a, b)
        self.assertNotEqual(a, otp_region.source_key("https://api.odpt.org/api/v4/files/odpt/Other/AllLines.zip"))

    def test_http가_아닌_주소는_받지_않는다(self):
        with self.assertRaises(ValueError):
            otp_region.fetch("file:///etc/passwd", os.path.join(self.root, "x.zip"))

    def test_깨진_zip은_쓸_수_있는_사본으로_보지_않는다(self):
        good, bad = os.path.join(self.root, "g.zip"), os.path.join(self.root, "b.zip")
        with zipfile.ZipFile(good, "w") as z:
            z.writestr("stops.txt", "stop_id\n")
        open(bad, "w").write("<html>error</html>")
        self.assertTrue(otp_region.usable_copy(good))
        self.assertFalse(otp_region.usable_copy(bad))

    def test_하위_폴더에_든_피드의_정류장_중심을_구한다(self):
        path = os.path.join(self.root, "nested.zip")
        with zipfile.ZipFile(path, "w") as z:
            z.writestr("data/stops.txt", "stop_id,stop_lat,stop_lon\nA,35.0,139.0\nB,36.0,140.0\n")
        self.assertEqual(otp_region.stops_center(path), (35.5, 139.5))

    def test_GTFS_읽기에서_난_실패만_그_피드를_격리한다(self):
        feed = "reading GTFS bundle at /g/atlas-x.gtfs.zip\n"
        gtfs_error = feed + "java.lang.RuntimeException\n\tat org.onebusaway.csv_entities.CsvEntityReader.readEntities\n"
        self.assertEqual(otp_region.quarantine_target(gtfs_error), "/g/atlas-x.gtfs.zip")
        self.assertIsNone(otp_region.quarantine_target(feed + "java.lang.OutOfMemoryError: Java heap space\n"
                                                        "\tat org.opentripplanner.gtfs.mapping.X\n"))
        self.assertIsNone(otp_region.quarantine_target(feed + "java.lang.NullPointerException\n"
                                                        "\tat org.opentripplanner.routing.linking.VertexLinker\n"))
        self.assertIsNone(otp_region.quarantine_target("no feed read\n"))

    def test_핵심_피드는_격리하지_않고_멈춘다(self):
        graph = os.path.join(self.root, "kr", "staging")
        os.makedirs(graph)
        bad = os.path.join(graph, "kr-korail.gtfs.zip")
        open(bad, "w").write("x")

        def failing_build(cmd, log=None):
            log.write(f"reading GTFS bundle at {bad}\nboom\n\tat org.onebusaway.gtfs.X\n")
            return 1, "", "", 0
        otp_region.run_measured = failing_build
        with self.assertRaises(Exception):
            otp_region.build_with_quarantine(graph, "1G", lambda fid: None, {"kr-korail"})
        self.assertTrue(os.path.exists(bad))

    def make_feed(self, name, **files):
        base = {"agency.txt": "agency_id,agency_name\nA,Op\n", "stops.txt": "stop_id\nS\n",
                "routes.txt": "route_id\nR\n", "trips.txt": "trip_id\nT\n", "stop_times.txt": "trip_id\nT\n",
                "calendar.txt": "service_id\nWK\n"}
        base.update(files)
        path = os.path.join(self.root, name)
        with zipfile.ZipFile(path, "w") as z:
            for n, text in base.items():
                if text is not None:
                    z.writestr(n, text)
        return path

    def test_경로_계산_파일이_모두_같을_때만_같은_피드로_뺀다(self):
        cfg = {"feeds": {}}
        same = self.make_feed("a.zip"), self.make_feed("b.zip", **{"feed_info.txt": "feed_publisher_name\nOther\n"})
        other_calendar = self.make_feed("c.zip", **{"calendar.txt": "service_id\nSA\n"})
        other_agency = self.make_feed("d.zip", **{"agency.txt": "agency_id,agency_name\nB,Other bus\n"})
        extra_transfers = self.make_feed("e.zip", **{"transfers.txt": "from_stop_id\nS\n"})
        kept, report, dropped = otp_region.drop_same_feeds(
            [("mdb-1", same[0]), ("odpt-x", same[1]), ("atlas-c", other_calendar), ("atlas-d", other_agency),
             ("atlas-e", extra_transfers)], cfg)
        self.assertEqual([f for f, _ in kept], ["mdb-1", "atlas-c", "atlas-d", "atlas-e"])
        self.assertEqual(report, ["dup  odpt-x: same routing files as mdb-1 (sha256)"])
        self.assertEqual(dropped["mdb-1"][0]["publisher"], "Other")
        self.assertEqual(dropped["mdb-1"][0]["agencies"], ["Op"])

    def test_뺀_피드의_메타데이터_파일을_그래프_폴더에_남긴다(self):
        graph = os.path.join(self.root, "kr", "staging")
        os.makedirs(graph)
        a = self.make_feed("a.zip")
        b = self.make_feed("b.zip", **{"translations.txt": "table_name,field_name,language,translation,record_id\nstops,stop_name,en,Station,S\n",
                                        "feed_info.txt": "feed_publisher_name\nOther\n"})
        _, _, dropped = otp_region.drop_same_feeds([("mdb-1", a), ("odpt-x", b)], {"feeds": {}}, graph)
        files = dropped["mdb-1"][0]["metadataFiles"]
        self.assertEqual(sorted(files), ["duplicates-meta/odpt-x/feed_info.txt", "duplicates-meta/odpt-x/translations.txt"])
        self.assertIn("Station", open(os.path.join(graph, "duplicates-meta/odpt-x/translations.txt")).read())

    def test_CRC와_크기가_같아도_내용이_다르면_같은_피드로_보지_않는다(self):
        a, b = self.make_feed("a.zip"), self.make_feed("b.zip")
        original = otp_region.same_routing_content
        otp_region.same_routing_content = lambda x, y: False
        try:
            kept, report, _ = otp_region.drop_same_feeds([("x", a), ("y", b)], {"feeds": {}})
        finally:
            otp_region.same_routing_content = original
        self.assertEqual(len(kept), 2)
        self.assertTrue(report[0].startswith("warn y: same CRC and size as x"))
        self.assertTrue(otp_region.same_routing_content(a, b))

    def test_필수_파일이나_달력이_없으면_같은_피드로_판정하지_않는다(self):
        a = self.make_feed("a.zip", **{"calendar.txt": None})
        b = self.make_feed("b.zip", **{"calendar.txt": None})
        kept, report, _ = otp_region.drop_same_feeds([("x", a), ("y", b)], {"feeds": {}})
        self.assertEqual(len(kept), 2)
        self.assertEqual(report, [])

    def test_깨진_사본은_최근_파일이어도_다시_받고_대신_쓰지_않는다(self):
        otp_region.FAILED_FEEDS = os.path.join(self.root, "failed-feeds.json")
        otp_region.config = lambda: {"catalogRefreshDays": 7}
        src = os.path.join(self.root, "x.gtfs.zip")
        open(src, "w").write("<html>moved</html>")
        calls = []

        def fake_fetch(url, path, **kw):
            calls.append(url)
            raise TimeoutError("down")
        otp_region.fetch = fake_fetch
        with self.assertRaises(TimeoutError):
            otp_region.catalog_fetch("x", "https://example.org/x.zip", src)
        self.assertEqual(calls, ["https://example.org/x.zip"])
        self.assertTrue(os.path.exists(src))

    def serve_once(self, body, length=None):
        class Handler(http.server.BaseHTTPRequestHandler):
            def do_GET(self):
                self.send_response(200)
                self.send_header("Content-Length", str(length if length is not None else len(body)))
                self.end_headers()
                self.wfile.write(body)

            def log_message(self, *a):
                pass
        server = http.server.HTTPServer(("127.0.0.1", 0), Handler)
        threading.Thread(target=server.serve_forever, daemon=True).start()
        self.addCleanup(server.shutdown)
        return f"http://127.0.0.1:{server.server_port}/feed"

    def test_새로_받은_zip이_깨지면_이전_사본을_남긴다(self):
        path = os.path.join(self.root, "f.gtfs.zip")
        with zipfile.ZipFile(path, "w") as z:
            z.writestr("stops.txt", "stop_id\n")
        before = open(path, "rb").read()
        url = self.serve_once(b"<html>moved</html>")
        with self.assertRaises(zipfile.BadZipFile):
            otp_region.fetch(url, path, refresh=True, retries=1)
        self.assertEqual(open(path, "rb").read(), before)

    def test_잘린_응답은_저장하지_않는다(self):
        path = os.path.join(self.root, "region.osm.pbf")
        url = self.serve_once(b"partial", length=1000)
        with self.assertRaises(Exception):
            otp_region.fetch(url, path, retries=1)
        self.assertFalse(os.path.exists(path))

    def test_기간_인자는_핵심_피드_기간과_오늘_기준_기간의_교집합이다(self):
        seen = []
        otp_region.run_measured = lambda cmd, log=None: (seen.append(cmd), (0, "trips=1 service_days=x", "", 0))[1]
        otp_region.clip("s.zip", os.path.join(self.root, "d.zip"), [0, 0, 1, 1], {}, ("20000101", "20991231"))
        cmd = seen[0]
        today = datetime.date.today()
        self.assertEqual(cmd[cmd.index("--from") + 1], (today - datetime.timedelta(days=otp_region.PAST_DAYS)).strftime("%Y%m%d"))
        self.assertIn("--clamp-calendar", cmd)
        otp_region.clip("s.zip", os.path.join(self.root, "d.zip"), [0, 0, 1, 1], {}, ("20000101", "20261231"))
        self.assertEqual(seen[1][seen[1].index("--to") + 1], "20261231")

    def test_모든_피드를_읽은_뒤의_GtfsModule_오류는_격리하지_않는다(self):
        log = ("reading GTFS bundle at /g/atlas-x.gtfs.zip\njava.lang.IllegalStateException: multiple time zones\n"
               "\tat org.opentripplanner.gtfs.graphbuilder.GtfsModule.buildGraph(GtfsModule.java:180)\n")
        self.assertIsNone(otp_region.quarantine_target(log))

    def test_MDB_피드도_원천_주소와_카탈로그를_기록한다(self):
        catalog = os.path.join(self.root, "feeds_v2.csv")
        with open(catalog, "w", encoding="utf-8") as f:
            f.write("id,data_type,location.country_code,status,urls.authentication_type,urls.latest,urls.direct_download,"
                    "location.subdivision_name,urls.license\n"
                    "mdb-9,gtfs,JP,active,0,https://files.example/latest.zip,https://op.example/gtfs.zip,Tokyo,CC-BY\n")
        otp_region.fetch = lambda *a, **k: catalog
        otp_region.config = lambda: {"catalogRefreshDays": 7}
        self.assertEqual(otp_region.mdb_feeds("JP", ["Tokyo"]), [("mdb-9", "https://files.example/latest.zip")])
        self.assertEqual(otp_region.ORIGINS["mdb-9"]["source"], "https://op.example/gtfs.zip")
        self.assertEqual(otp_region.ORIGINS["mdb-9"]["catalog"], "mobility-database")

    def test_판_쿼리만_다른_주소는_같은_피드이고_다른_쿼리는_다른_피드다(self):
        k = otp_region.source_key
        self.assertEqual(k("https://api.odpt.org/a/X.zip?date=20261201"), k("https://api.odpt.org/a/X.zip?date=current"))
        self.assertNotEqual(k("https://drive.google.com/uc?id=A"), k("https://drive.google.com/uc?id=B"))

    def test_형식상_달력의_핵심_피드는_기간_계산에서_뺀다(self):
        self.assertEqual(otp_region.calendar_window(["service_days=20261101-20271101", "service_days=20000101-20991231"]),
                         ("20261101", "20271101"))
        self.assertIsNone(otp_region.calendar_window(["service_days=20000101-20991231"]))

    def test_보관본은_설정한_개수만_남기고_설정이_없으면_모두_남긴다(self):
        folder = os.path.join(self.root, "archive")
        for name in ("20261101T000000", "20261102T000000", "20261103T000000"):
            os.makedirs(os.path.join(folder, name))
        self.assertEqual(otp_region.prune_archive(folder, None), [])
        self.assertEqual(otp_region.prune_archive(folder, 2), ["20261101T000000"])
        self.assertEqual(sorted(os.listdir(folder)), ["20261102T000000", "20261103T000000"])

if __name__ == "__main__":
    unittest.main()
