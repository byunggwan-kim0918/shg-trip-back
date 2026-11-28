"""피드 판 보관·만료·판 연결·운행 지문 중복 판정 테스트. 센다이 시영버스 월별 판(10/07~11/30, 12/01~12/30) 구조를 본뜬다."""
import datetime
import importlib.util
import json
import os
import shutil
import sys
import tempfile
import unittest
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
sys.dont_write_bytecode = True
if HERE not in sys.path:
    sys.path.insert(0, HERE)
import feed_compare  # noqa: E402
import feed_versions  # noqa: E402

spec = importlib.util.spec_from_file_location("otp_region", os.path.join(HERE, "otp_region.py"))
otp_region = importlib.util.module_from_spec(spec)
spec.loader.exec_module(otp_region)

STOPS = [("s1", "仙台駅前", 38.2601, 140.8822), ("s2", "広瀬通", 38.2631, 140.8731), ("s3", "川内", 38.2614, 140.8512)]


def gtfs(path, start, end, trips=None, stops=None, routes=None, dates=(), agency="仙台市交通局", weekdays="1111111"):
    """trips: [(trip_id, route_id, [(stop_id, 'HH:MM:SS'), ...])], 모든 운행은 service_id 'W'."""
    stops = stops or STOPS
    routes = routes or [("r1", "3", "13")]
    trips = trips if trips is not None else [("t1", "r1", [("s1", "08:00:00"), ("s2", "08:05:00"), ("s3", "08:10:00")]),
                                             ("t2", "r1", [("s1", "09:00:00"), ("s2", "09:05:00"), ("s3", "09:10:00")])]
    with zipfile.ZipFile(path, "w") as z:
        z.writestr("agency.txt", f"agency_id,agency_name,agency_url,agency_timezone\na1,{agency},https://x,Asia/Tokyo\n")
        z.writestr("stops.txt", "stop_id,stop_name,stop_lat,stop_lon\n" + "".join(f"{i},{n},{la},{lo}\n" for i, n, la, lo in stops))
        z.writestr("routes.txt", "route_id,agency_id,route_type,route_short_name,route_long_name\n"
                   + "".join(f"{r},a1,{t},{s},\n" for r, t, s in routes))
        z.writestr("trips.txt", "route_id,service_id,trip_id\n" + "".join(f"{r},W,{t}\n" for t, r, _ in trips))
        z.writestr("stop_times.txt", "trip_id,arrival_time,departure_time,stop_id,stop_sequence\n" + "".join(
            f"{t},{hm},{hm},{s},{i + 1}\n" for t, _, st in trips for i, (s, hm) in enumerate(st)))
        z.writestr("calendar.txt", "service_id,monday,tuesday,wednesday,thursday,friday,saturday,sunday,start_date,end_date\n"
                   f"W,{','.join(weekdays)},{start},{end}\n")
        z.writestr("calendar_dates.txt", "service_id,date,exception_type\n" + "".join(f"W,{d},{e}\n" for d, e in dates))
        z.writestr("feed_info.txt", f"feed_publisher_name,feed_publisher_url,feed_lang,feed_start_date,feed_end_date\n"
                   f"{agency},https://x,ja,{start},{end}\n")
    return path


class Base(unittest.TestCase):

    def setUp(self):
        self.dir = tempfile.mkdtemp()
        self.store = feed_versions.FeedVersions(os.path.join(self.dir, "versions"))

    def tearDown(self):
        shutil.rmtree(self.dir, ignore_errors=True)

    def path(self, name):
        return os.path.join(self.dir, name)

    def fz(self, path):
        return otp_region.feed_zip(path)


class StoreTest(Base):

    def test_새_판은_따로_보관하고_원본을_덮어쓰지_않는다(self):
        src = gtfs(self.path("a.zip"), "20261007", "20261130")
        first, created = self.store.register("sendai", src, {"source": "https://api-public.odpt.org/x.zip?date=current"})
        self.assertTrue(created)
        gtfs(src, "20261201", "20261230")
        second, created = self.store.register("sendai", src)
        self.assertTrue(created)
        self.assertNotEqual(first["file"], second["file"])
        kept = feed_compare.profile(self.fz(self.store.path("sendai", first)))
        self.assertEqual(kept["serviceTo"], "20261130")
        self.assertFalse(os.access(self.store.path("sendai", first), os.W_OK))
        self.assertEqual(len(self.store.index("sendai")["versions"]), 2)

    def test_같은_내용은_판을_늘리지_않고_마지막_확인_시각만_바꾼다(self):
        src = gtfs(self.path("a.zip"), "20261007", "20261130")
        self.store.register("f", src, now=datetime.datetime(2026, 11, 1))
        v, created = self.store.register("f", src, now=datetime.datetime(2026, 11, 20))
        self.assertFalse(created)
        self.assertEqual((v["fetchedAt"][:10], v["lastSeenAt"][:10]), ("2026-11-01", "2026-11-20"))

    def test_원천을_모르고_남긴_판은_같은_판을_다시_볼_때_원천을_채운다(self):
        src = gtfs(self.path("a.zip"), "20261007", "20261130")
        self.store.register("f", src)
        v, created = self.store.register("f", src, {"source": "https://x/f.zip", "license": "CC-BY-4.0"})
        self.assertFalse(created)
        self.assertEqual((v["source"], v["license"]), ("https://x/f.zip", "CC-BY-4.0"))

    def test_원천_운영기관_라이선스_수집시각과_실제_운행_기간을_남긴다(self):
        src = gtfs(self.path("a.zip"), "20261007", "20261130", dates=[("20261203", "1"), ("20261130", "2")])
        v, _ = self.store.register("f", src, {"source": "https://x/f.zip", "catalog": "mobility-database",
                                              "license": "CC-BY-4.0", "official": True},
                                   now=datetime.datetime(2026, 11, 2, 9))
        self.assertEqual((v["source"], v["license"], v["official"], v["agencies"]),
                         ("https://x/f.zip", "CC-BY-4.0", True, ["仙台市交通局"]))
        self.assertEqual((v["serviceFrom"], v["serviceTo"], v["fetchedAt"]), ("20261007", "20261203", "2026-11-02T09:00:00"))
        self.assertEqual(v["serviceDays"], 55 - 1 + 1)

    def test_운행_수가_반_밑으로_준_판은_쓰지_않고_이전_정상_판을_쓴다(self):
        trips = [(f"t{i}", "r1", [("s1", f"{6 + i:02d}:00:00"), ("s2", f"{6 + i:02d}:05:00")]) for i in range(10)]
        good, _ = self.store.register("f", gtfs(self.path("a.zip"), "20261101", "20261231", trips=trips),
                                      now=datetime.datetime(2026, 11, 1))
        bad, _ = self.store.register("f", gtfs(self.path("b.zip"), "20261101", "20261231", trips=trips[:3]),
                                     now=datetime.datetime(2026, 11, 20))
        self.assertTrue(bad["problems"])
        plan, _ = self.store.plan("f", datetime.date(2026, 11, 27))
        self.assertEqual([p[0]["sha256"] for p in plan], [good["sha256"]])

    def test_읽을_수_없는_판도_원본은_남기고_쓰지_않는다(self):
        src = self.path("broken.zip")
        with zipfile.ZipFile(src, "w") as z:
            z.writestr("stops.txt", "stop_id\n")
        v, _ = self.store.register("f", src)
        self.assertTrue(v["problems"])
        self.assertTrue(os.path.exists(self.store.path("f", v)))
        self.assertEqual(self.store.plan("f", datetime.date(2026, 11, 27)), ([], None))


class ExpiryTest(Base):

    def test_만료_14일_전부터_갱신_우선순위를_올린다(self):
        self.store.register("f", gtfs(self.path("a.zip"), "20261007", "20261130"))
        today = datetime.date(2026, 11, 20)
        self.assertEqual(self.store.status("f", today)["state"], "expiring")
        self.assertEqual(self.store.refresh_days("f", 7, today), feed_versions.EXPIRING_REFRESH_DAYS)
        self.assertEqual(self.store.status("f", datetime.date(2026, 11, 10))["state"], "ok")
        self.assertEqual(self.store.refresh_days("f", 7, datetime.date(2026, 11, 10)), 7)
        self.assertEqual(self.store.status("f", datetime.date(2026, 12, 2))["state"], "expired")

    def test_다음_판이_있으면_다음_판_끝으로_만료를_센다(self):
        self.store.register("f", gtfs(self.path("a.zip"), "20261007", "20261130"))
        self.store.register("f", gtfs(self.path("b.zip"), "20261201", "20261230"))
        s = self.store.status("f", datetime.date(2026, 11, 27))
        self.assertEqual((s["state"], s["serviceTo"]), ("ok", "20261230"))

    def test_다음_판_주소는_지금_판_끝_다음_날로_판_쿼리만_바꾼다(self):
        rules = [{"host": "api-public.odpt.org", "param": "date", "format": "%Y%m%d"}]
        url = "https://api-public.odpt.org/api/v4/files/odpt/SendaiMunicipal/bus_realtime_information.zip?date=current"
        nxt = feed_versions.next_edition_url(url, {"serviceTo": "20261130"}, rules)
        self.assertEqual(nxt, url.replace("date=current", "date=20261201"))
        self.assertEqual(feed_versions.edition_of(nxt, rules), "20261201")
        self.assertIsNone(feed_versions.edition_of(url, rules))
        self.assertIsNone(feed_versions.next_edition_url("https://files.mobilitydatabase.org/x/latest.zip",
                                                         {"serviceTo": "20261130"}, rules))


class LinkTest(Base):

    def register(self, name, start, end, **kw):
        return self.store.register("sendai", gtfs(self.path(name), start, end, **kw))[0]

    def test_센다이_월별_판은_기간이_이어지면_판을_나눠_함께_싣는다(self):
        old = self.register("a.zip", "20261007", "20261130")
        # 12월판은 같은 trip_id·service_id를 다른 노선 번호로 다시 쓴다.
        new = self.register("b.zip", "20261201", "20261230", routes=[("r1", "3", "130")])
        plan, report = self.store.plan("sendai", datetime.date(2026, 11, 27))
        self.assertEqual(report["decision"], "link")
        self.assertFalse(report["mergeable"])
        self.assertEqual(report["routes"]["differentContent"], 1)
        self.assertEqual(report["serviceIds"], {"shared": 1, "differentDates": 1})
        self.assertEqual((report["gapDays"], report["overlapDays"], report["duplicateTripsInOverlap"]), (0, 0, 0))
        self.assertEqual(report["linkedDays"]["withoutService"], [])
        self.assertEqual([(p[0]["sha256"], p[1], p[2]) for p in plan],
                         [(old["sha256"], None, "20261130"), (new["sha256"], "20261201", None)])

    def test_판_사이_빈_날짜는_공백으로_남긴다(self):
        self.register("a.zip", "20261007", "20261130")
        self.register("b.zip", "20261205", "20261230")
        _, report = self.store.plan("sendai", datetime.date(2026, 11, 27))
        self.assertEqual(report["gapDays"], 4)
        self.assertEqual(report["gapDates"], ["20261201", "20261202", "20261203", "20261204"])
        self.assertEqual(report["linkedDays"]["withoutService"][:1], ["20261201"])

    def test_정류장_id가_다른_곳을_가리키면_잇지_않고_기존_판만_싣는다(self):
        old = self.register("a.zip", "20261007", "20261130")
        moved = [(i, n, la + 0.01, lo) for i, n, la, lo in STOPS]
        self.register("b.zip", "20261201", "20261230", stops=moved)
        plan, report = self.store.plan("sendai", datetime.date(2026, 11, 27))
        self.assertEqual(report["decision"], "separate")
        self.assertIn("stop ids reused", report["reasons"][0])
        self.assertEqual([p[0]["sha256"] for p in plan], [old["sha256"]])

    def test_겹치는_기간은_새_판이_대신하고_겹친_날의_같은_운행을_센다(self):
        self.register("a.zip", "20261007", "20261130")
        self.register("b.zip", "20261125", "20261230")
        plan, report = self.store.plan("sendai", datetime.date(2026, 11, 20))
        self.assertEqual(report["overlapDays"], 6)
        self.assertEqual(report["duplicateTripsInOverlap"], 12)
        self.assertEqual((plan[0][2], plan[1][1]), ("20261124", "20261125"))


    def test_개정으로_짧아진_새_판_뒤에_예전의_긴_판이_끼어들지_않는다(self):
        long_old = self.store.register("f", gtfs(self.path("a.zip"), "20261101", "20270331"),
                                       now=datetime.datetime(2026, 11, 1))[0]
        short_new = self.store.register("f", gtfs(self.path("b.zip"), "20261101", "20261213",
                                                  routes=[("r1", "3", "13X")]), now=datetime.datetime(2026, 11, 20))[0]
        plan, report = self.store.plan("f", datetime.date(2026, 11, 27))
        self.assertIsNone(report)
        self.assertEqual([(p[0]["sha256"], p[1], p[2]) for p in plan], [(short_new["sha256"], None, None)])
        self.assertIsNone(self.store.successor("f", short_new))
        self.assertNotEqual(long_old["sha256"], short_new["sha256"])

    def test_다음_판이_이어지지_않으면_만료_예정으로_보고_이유를_남긴다(self):
        self.register("a.zip", "20261007", "20261130")
        moved = [(i, n, la + 0.01, lo) for i, n, la, lo in STOPS]
        self.register("b.zip", "20261201", "20261230", stops=moved)
        s = self.store.status("sendai", datetime.date(2026, 11, 27))
        self.assertEqual((s["state"], s["serviceTo"], s["nextUnlinked"]), ("expiring", "20261130", True))
        self.assertIn("links", self.store.index("sendai"))

    def test_큰_피드는_운행_지문_없이_기간과_정류장으로만_잇는다(self):
        self.register("a.zip", "20261007", "20261130")
        self.register("b.zip", "20261201", "20261230")
        saved = feed_compare.MAX_COMPARE_BYTES
        feed_compare.MAX_COMPARE_BYTES = 10
        try:
            plan, report = self.store.plan("sendai", datetime.date(2026, 11, 27))
        finally:
            feed_compare.MAX_COMPARE_BYTES = saved
        self.assertEqual((report["decision"], report["tripChecks"]), ("link", "skipped (stop_times too large)"))
        self.assertEqual(len(plan), 2)

    def test_판_목록이_깨지면_빈_목록으로_덮지_않고_예외를_낸다(self):
        self.register("a.zip", "20261007", "20261130")
        with open(os.path.join(self.store.folder("sendai"), "versions.json"), "w") as f:
            f.write("{broken")
        with self.assertRaises(ValueError):
            self.register("b.zip", "20261201", "20261230")

    def test_설정에서_허용하면_운행_수가_줄어든_새_판을_받아들인다(self):
        trips = [(f"t{i}", "r1", [("s1", f"{6 + i:02d}:00:00"), ("s2", f"{6 + i:02d}:05:00")]) for i in range(10)]
        self.store.register("f", gtfs(self.path("a.zip"), "20261101", "20261231", trips=trips))
        v, _ = self.store.register("f", gtfs(self.path("b.zip"), "20261101", "20261231", trips=trips[:3]),
                                   allow_trip_drop=True)
        self.assertEqual(v["problems"], [])


class LifecycleRobustnessTest(Base):

    def two_editions(self):
        a = self.store.register("f", gtfs(self.path("a.zip"), "20261007", "20261130"), now=datetime.datetime(2026, 11, 1))[0]
        b = self.store.register("f", gtfs(self.path("b.zip"), "20261201", "20261230"), now=datetime.datetime(2026, 11, 27))[0]
        return a, b

    def test_판_zip을_못_읽어도_상태는_지금_판_끝으로_만료를_센다(self):
        _, b = self.two_editions()
        os.chmod(self.store.path("f", b), 0o644)
        os.remove(self.store.path("f", b))
        s = self.store.status("f", datetime.date(2026, 11, 27))
        self.assertEqual((s["state"], s["serviceTo"], s["nextUnlinked"]), ("expiring", "20261130", True))
        self.assertTrue(s["linkError"])

    def test_연결_결과를_쓸_때_그사이_더해진_판을_지우지_않는다(self):
        a, b = self.two_editions()
        report = feed_versions.link(otp_region.feed_zip(self.store.path("f", a)), a,
                                    otp_region.feed_zip(self.store.path("f", b)), b)
        self.store.register("f", gtfs(self.path("c.zip"), "20261231", "20270131"))
        self.store.save_link("f", "x..y", report)
        idx = self.store.index("f")
        self.assertEqual(len(idx["versions"]), 3)
        self.assertIn("x..y", idx["links"])

    def test_빌드는_저장한_연결_결과를_다시_쓴다(self):
        self.two_editions()
        self.store.plan("f", datetime.date(2026, 11, 27))
        saved = feed_versions.link
        feed_versions.link = lambda *a: self.fail("link recomputed")
        try:
            plan, report = self.store.plan("f", datetime.date(2026, 11, 27))
        finally:
            feed_versions.link = saved
        self.assertEqual((report["decision"], len(plan), report["gapDays"]), ("link", 2, 0))

    def test_이미_거부된_판도_설정에서_허용하면_다시_쓴다(self):
        trips = [(f"t{i}", "r1", [("s1", f"{6 + i:02d}:00:00"), ("s2", f"{6 + i:02d}:05:00")]) for i in range(10)]
        self.store.register("f", gtfs(self.path("a.zip"), "20261101", "20261231", trips=trips))
        small = gtfs(self.path("b.zip"), "20261101", "20261231", trips=trips[:3])
        v, _ = self.store.register("f", small)
        self.assertTrue(v["problems"])
        v, _ = self.store.register("f", small, allow_trip_drop=True)
        self.assertEqual(v["problems"], [])

    def test_원천이_예전_판으로_되돌리면_최근에_보인_판을_쓴다(self):
        trips = [(f"t{i}", "r1", [("s1", f"{6 + i:02d}:00:00"), ("s2", f"{6 + i:02d}:05:00")]) for i in range(4)]
        old = gtfs(self.path("a.zip"), "20261101", "20261231", trips=trips)
        self.store.register("f", old, now=datetime.datetime(2026, 11, 1))
        self.store.register("f", gtfs(self.path("b.zip"), "20261101", "20261231", trips=trips[:3]),
                            now=datetime.datetime(2026, 11, 10))
        reverted, _ = self.store.register("f", old, now=datetime.datetime(2026, 11, 20))
        self.assertEqual(self.store.active("f", datetime.date(2026, 11, 27))["sha256"], reverted["sha256"])

    def test_판_목록이_깨져도_다시_받기_전_보관은_건너뛰고_멈추지_않는다(self):
        os.makedirs(self.store.folder("f"))
        with open(os.path.join(self.store.folder("f"), "versions.json"), "w") as f:
            f.write("{broken")
        otp_region.keep_current(self.store, "f", gtfs(self.path("a.zip"), "20261101", "20261231"))
        with open(os.path.join(self.store.folder("f"), "versions.json")) as f:
            self.assertEqual(f.read(), "{broken")


class FingerprintTest(Base):

    def fp(self, name, start="20261101", end="20261130", **kw):
        return feed_compare.Fingerprint(self.fz(gtfs(self.path(name), start, end, **kw)))

    def test_시각_표기가_달라도_같은_운행이다(self):
        a = self.fp("a.zip")
        b = self.fp("b.zip", trips=[("x1", "r9", [("s1", "8:00:00"), ("s2", "8:05:00"), ("s3", "8:10:00")]),
                                    ("x2", "r9", [("s1", "9:00:00"), ("s2", "9:05:00"), ("s3", "9:10:00")])],
                    routes=[("r9", "3", "13")])
        self.assertTrue(feed_compare.contained(a, b) and feed_compare.contained(b, a))

    def test_운행일이_더_긴_피드에_포함된다(self):
        self.assertTrue(feed_compare.contained(self.fp("a.zip"), self.fp("b.zip", end="20261231")))
        self.assertFalse(feed_compare.contained(self.fp("c.zip", end="20261231"), self.fp("d.zip")))

    def test_승하차_제한이_다르면_다른_운행이다(self):
        a = self.fp("a.zip")
        src = gtfs(self.path("b.zip"), "20261101", "20261130")
        with zipfile.ZipFile(src) as z:
            files = {n: z.read(n).decode() for n in z.namelist()}
        files["stop_times.txt"] = files["stop_times.txt"].replace("stop_sequence\n", "stop_sequence,pickup_type\n") \
            .replace(",1\n", ",1,0\n").replace(",2\n", ",2,1\n").replace(",3\n", ",3,0\n")
        with zipfile.ZipFile(src, "w") as z:
            for n, body in files.items():
                z.writestr(n, body)
        self.assertFalse(feed_compare.contained(a, feed_compare.Fingerprint(self.fz(src))))


class DedupeTest(Base):

    def setUp(self):
        super().setUp()
        self.origins = dict(otp_region.ORIGINS)

    def tearDown(self):
        otp_region.ORIGINS.clear()
        otp_region.ORIGINS.update(self.origins)
        super().tearDown()

    def run_dedupe(self, feeds, cfg=None):
        return otp_region.drop_same_feeds(feeds, cfg or {"feeds": {}}, self.path("graph"))

    def test_운행이_모두_포함된_피드는_엄격_검증_뒤_뺀다(self):
        small = gtfs(self.path("small.zip"), "20261101", "20261130", trips=[
            ("t1", "r1", [("s1", "08:00:00"), ("s2", "08:05:00"), ("s3", "08:10:00")])])
        big = gtfs(self.path("big.zip"), "20261101", "20261231")
        kept, report, dropped, removed = self.run_dedupe([("mdb-1", big), ("atlas-x", small)])
        self.assertEqual([f for f, _ in kept], ["mdb-1"])
        self.assertIn("dup  atlas-x: all 1 trips contained in mdb-1 (service fingerprint)", report)
        self.assertEqual(dropped["mdb-1"][0]["feedId"], "atlas-x")
        self.assertEqual([f for f, _ in removed], ["atlas-x"])

    def test_일부_노선만_겹치면_빼지_않는다(self):
        a = gtfs(self.path("a.zip"), "20261101", "20261130")
        b = gtfs(self.path("b.zip"), "20261101", "20261130", trips=[
            ("t1", "r1", [("s1", "08:00:00"), ("s2", "08:05:00"), ("s3", "08:10:00")]),
            ("t3", "r1", [("s1", "10:00:00"), ("s2", "10:05:00"), ("s3", "10:10:00")])])
        kept, report, _, _ = self.run_dedupe([("a", a), ("b", b)])
        self.assertEqual(len(kept), 2)
        self.assertFalse(any(l.startswith("dup ") for l in report))

    def test_95퍼센트_이상_비슷하면_후보로만_기록한다(self):
        many = [(f"t{i}", "r1", [("s1", f"{5 + i // 4:02d}:{i % 4 * 15:02d}:00"), ("s2", f"{5 + i // 4:02d}:{i % 4 * 15 + 5:02d}:00")])
                for i in range(40)]
        a = gtfs(self.path("a.zip"), "20261101", "20261130", trips=many)
        b = gtfs(self.path("b.zip"), "20261101", "20261130", trips=many[:39] + [
            ("z", "r1", [("s1", "23:00:00"), ("s2", "23:05:00")])])
        kept, report, _, _ = self.run_dedupe([("a", a), ("b", b)])
        self.assertEqual(len(kept), 2)
        self.assertTrue(any("97.5% service overlap with b, candidate only" in l for l in report))

    def test_운영기관이_같아도_운행이_다르면_빼지_않는다(self):
        a = gtfs(self.path("a.zip"), "20261101", "20261130")
        b = gtfs(self.path("b.zip"), "20261101", "20261130", trips=[
            ("t9", "r1", [("s1", "12:00:00"), ("s2", "12:05:00"), ("s3", "12:10:00")])])
        kept, _, _, _ = self.run_dedupe([("a", a), ("b", b)])
        self.assertEqual(len(kept), 2)

    def test_형식상_달력_피드는_정상_피드를_대신하지_못한다(self):
        small = gtfs(self.path("small.zip"), "20261101", "20261130")
        suspect = gtfs(self.path("suspect.zip"), "20261001", "20301231")
        kept, report, _, _ = self.run_dedupe([("suspect", suspect), ("small", small)])
        self.assertEqual(len(kept), 2)
        self.assertTrue(any("small: service contained in suspect but kept (suspect has no confirmed calendar)" in l
                            for l in report))

    def test_공식_피드는_비공식_피드로_대체하지_않는다(self):
        otp_region.ORIGINS["official"] = {"official": True}
        otp_region.ORIGINS["community"] = {"official": False}
        small = gtfs(self.path("small.zip"), "20261101", "20261130")
        big = gtfs(self.path("big.zip"), "20261101", "20261231")
        kept, report, _, _ = self.run_dedupe([("community", big), ("official", small)])
        self.assertEqual(len(kept), 2)
        self.assertTrue(any("official is official, community is not" in l for l in report))


class DedupeGuardTest(Base):

    def run_dedupe(self, feeds, cfg=None):
        return otp_region.drop_same_feeds(feeds, cfg or {"feeds": {}}, self.path("graph"))

    def contained_pair(self):
        small = gtfs(self.path("small.zip"), "20261101", "20261130")
        big = gtfs(self.path("big.zip"), "20261101", "20261231")
        return small, big

    def test_설정_피드는_다른_피드에_포함돼도_빼지_않는다(self):
        small, big = self.contained_pair()
        kept, report, _, _ = self.run_dedupe([("mdb-1", big), ("core", small)], {"feeds": {"core": {}}})
        self.assertEqual(len(kept), 2)
        self.assertTrue(any("core is a configured feed" in l for l in report))

    def test_남길_피드에_자르기_규칙이_있으면_빼지_않는다(self):
        small, big = self.contained_pair()
        kept, report, _, _ = self.run_dedupe([("cfg", big), ("atlas-x", small)],
                                             {"feeds": {"cfg": {"dropRouteTypes": "2"}}})
        self.assertEqual(len(kept), 2)
        self.assertTrue(any("cfg has clip rules" in l for l in report))

    def test_뺄_피드에만_환승_요금_파일이_있으면_빼지_않는다(self):
        small, big = self.contained_pair()
        with zipfile.ZipFile(small, "a") as z:
            z.writestr("transfers.txt", "from_stop_id,to_stop_id,transfer_type,min_transfer_time\ns1,s2,2,120\n")
        kept, report, _, _ = self.run_dedupe([("mdb-1", big), ("odpt-x", small)])
        self.assertEqual(len(kept), 2)
        self.assertTrue(any("only odpt-x has transfers.txt" in l for l in report))


class VersionDedupeTest(Base):

    def test_이어_실은_판도_원래_피드의_공식_여부로_보호한다(self):
        saved = dict(otp_region.ORIGINS)
        otp_region.ORIGINS.update({"mdb-1": {"official": True}, "atlas-x": {"official": False}})
        try:
            small = gtfs(self.path("small.zip"), "20261201", "20261230")
            big = gtfs(self.path("big.zip"), "20261101", "20261231")
            kept, report, _, _ = otp_region.drop_same_feeds(
                [("atlas-x", big), ("mdb-1-v20261201", small)], {"feeds": {}}, self.path("g"),
                {"mdb-1-v20261201": {"feed": "mdb-1"}})
        finally:
            otp_region.ORIGINS.clear()
            otp_region.ORIGINS.update(saved)
        self.assertEqual(len(kept), 2)
        self.assertTrue(any("mdb-1-v20261201 is official" in l for l in report))


class PipelineTest(Base):

    def setUp(self):
        super().setUp()
        self.saved = {k: getattr(otp_region, k) for k in ("SOURCES", "ROOT", "clip", "fetch", "config", "ORIGINS")}
        otp_region.SOURCES = os.path.join(self.dir, "sources")
        otp_region.ROOT = os.path.join(self.dir, "root")
        otp_region.ORIGINS = {}
        self.cfg = {"feeds": {"core": {"url": "https://x/core.zip"}}, "regions": {"r": {"feeds": ["core"], "bbox": [0, 0, 90, 180]}},
                    "nextEdition": [{"host": "api-public.odpt.org", "param": "date", "format": "%Y%m%d"}]}
        otp_region.config = lambda: self.cfg

    def tearDown(self):
        for k, v in self.saved.items():
            setattr(otp_region, k, v)
        super().tearDown()

    def test_이어지는_판은_피드_id를_나눠_판별_기간으로_자른다(self):
        store = otp_region.versions_store()
        store.register("sendai", gtfs(self.path("a.zip"), "20261007", "20261130"))
        src = gtfs(self.path("b.zip"), "20261201", "20261230")
        report = []
        out, windows, versions, links = otp_region.select_versions([("sendai", src)], self.cfg, report,
                                                                   today=datetime.date(2026, 11, 27))
        self.assertEqual([f for f, _ in out], ["sendai", "sendai-v20261201"])
        self.assertEqual(windows, {"sendai": (None, "20261130"), "sendai-v20261201": ("20261201", None)})
        self.assertEqual(versions["sendai-v20261201"]["feed"], "sendai")
        self.assertEqual(links["sendai"]["decision"], "link")
        calls = []
        otp_region.clip = lambda src, dst, bbox, feed=None, window=None: (
            calls.append((os.path.basename(dst), window)) or "trips=2 service_days=20261120-20270228", None)
        otp_region.clip_feeds(self.path("g"), {"feeds": [], "bbox": [0, 0, 90, 180]}, self.cfg, out, windows, versions)
        today = datetime.date.today()
        start = (today - datetime.timedelta(days=otp_region.PAST_DAYS)).strftime("%Y%m%d")
        end = (today + datetime.timedelta(days=otp_region.FUTURE_DAYS)).strftime("%Y%m%d")
        self.assertEqual(calls, [("sendai.gtfs.zip", (start, min(end, "20261130"))),
                                 ("sendai-v20261201.gtfs.zip", (max(start, "20261201"), end))])

    def test_핵심_피드의_판을_이어_실어도_실제_자르기가_판별_기간으로_자른다(self):
        store = otp_region.versions_store()
        store.register("core", gtfs(self.path("a.zip"), "20261007", "20261130"))
        src = gtfs(self.path("b.zip"), "20261201", "20261230")
        report = []
        out, windows, versions, _ = otp_region.select_versions([("core", src)], self.cfg, report,
                                                               today=datetime.date(2026, 11, 27))
        graph = self.path("g")
        os.makedirs(graph)
        lines = otp_region.clip_feeds(graph, self.cfg["regions"]["r"], self.cfg, out, windows, versions)
        self.assertEqual([l.split(":")[0] for l in lines], ["use  core", "use  core-v20261201"])
        self.assertIn("service_days=20261201-20261230", lines[1])

    def test_판_사이_공백이_있으면_핵심_기간은_앞_판까지다(self):
        source = lambda fid: "core"
        merged = otp_region.merge_versions({"core": "service_days=20261007-20261130",
                                            "core-v20261205": "service_days=20261205-20261230"}, source)
        self.assertEqual(merged, ["service_days=20261007-20261130"])
        joined = otp_region.merge_versions({"core": "service_days=20261007-20261130",
                                            "core-v20261201": "service_days=20261201-20261230"}, source)
        self.assertEqual(joined, ["service_days=20261007-20261230"])

    def test_이어_실은_다음_판에도_실시간_갱신기를_붙인다(self):
        graph = self.path("g")
        os.makedirs(graph)
        self.cfg["feeds"]["core"]["realtime"] = {"url": "https://x/rt"}
        otp_region.write_configs(graph, {"timezone": "Asia/Tokyo"}, self.cfg, ["core", "core-v20261201"],
                                 {"core-v20261201": {"feed": "core"}})
        router = json.load(open(os.path.join(graph, "router-config.json")))
        self.assertEqual([u["feedId"] for u in router["updaters"]], ["core", "core-v20261201"])

    def test_판_목록이_깨진_피드도_받은_사본으로_짓는다(self):
        store = otp_region.versions_store()
        os.makedirs(store.folder("f"))
        with open(os.path.join(store.folder("f"), "versions.json"), "w") as f:
            f.write("{broken")
        src = gtfs(self.path("a.zip"), "20261101", "20261231")
        report = []
        out, _, _, _ = otp_region.select_versions([("f", src)], self.cfg, report, today=datetime.date(2026, 11, 27))
        self.assertEqual(out, [("f", src)])
        self.assertTrue(any("version store unusable" in l for l in report))

    def test_판_쿼리_rid는_GTFS_데이터_리포지토리에서만_같은_피드로_본다(self):
        a = otp_region.source_key("https://api.gtfs-data.jp/v2/organizations/x/feeds/y/files/feed.zip?rid=current")
        b = otp_region.source_key("https://api.gtfs-data.jp/v2/organizations/x/feeds/y/files/feed.zip")
        self.assertEqual(a, b)
        self.assertNotEqual(otp_region.source_key("https://example.org/feed.zip?rid=1"),
                            otp_region.source_key("https://example.org/feed.zip?rid=2"))

    def test_새로_받은_판이_품질_문제면_경고하고_이전_판을_쓴다(self):
        trips = [(f"t{i}", "r1", [("s1", f"{6 + i:02d}:00:00"), ("s2", f"{6 + i:02d}:05:00")]) for i in range(10)]
        store = otp_region.versions_store()
        store.register("f", gtfs(self.path("a.zip"), "20261101", "20261231", trips=trips), now=datetime.datetime(2026, 11, 1))
        report = []
        bad = gtfs(self.path("b.zip"), "20261101", "20261231", trips=trips[:2])
        out, _, _, _ = otp_region.select_versions([("f", bad)], self.cfg, report, today=datetime.date(2026, 11, 27))
        self.assertTrue(any(l.startswith("warn f: new version") for l in report))
        self.assertTrue(any("instead of newest download" in l for l in report))
        self.assertNotEqual(otp_region.sha256(out[0][1]), otp_region.sha256(bad))

    def test_만료가_가까운_피드는_다음_판을_미리_받고_하루_안에는_다시_묻지_않는다(self):
        store = otp_region.versions_store()
        url = "https://api-public.odpt.org/api/v4/files/odpt/SendaiMunicipal/bus.zip?date=current"
        store.register("sendai", gtfs(self.path("a.zip"), "20261007", "20261130"), {"source": url})
        asked = []

        def fake_fetch(u, path, **kw):
            asked.append(u)
            gtfs(path, "20261201", "20261230")
            return path
        otp_region.fetch = fake_fetch
        lines = otp_region.prefetch_next("sendai", store, url, self.cfg["nextEdition"], datetime.date(2026, 11, 27))
        self.assertEqual(asked, [url.replace("date=current", "date=20261201")])
        self.assertIn("next edition found 20261201", lines[0])
        self.assertEqual(store.successor("sendai", store.active("sendai", datetime.date(2026, 11, 27)))["edition"],
                         "20261201")
        store2 = otp_region.versions_store()
        self.assertEqual(otp_region.prefetch_next("sendai", store2, url, self.cfg["nextEdition"],
                                                  datetime.date(2026, 11, 27)), [])
        self.assertEqual(len(asked), 1)

    def test_다음_판이_아직_없으면_기존_판을_그대로_쓰고_하루_뒤에_다시_묻는다(self):
        store = otp_region.versions_store()
        url = "https://api-public.odpt.org/x.zip?date=current"
        store.register("f", gtfs(self.path("a.zip"), "20261007", "20261130"), {"source": url})
        asked = []

        def not_found(u, path, **kw):
            asked.append(u)
            raise otp_region.urllib.error.HTTPError(u, 404, "Not Found", {}, None)
        otp_region.fetch = not_found
        lines = otp_region.prefetch_next("f", store, url, self.cfg["nextEdition"], datetime.date(2026, 11, 27))
        self.assertIn("not published (HTTPError)", lines[0])
        again = otp_region.prefetch_next("f", store, url, self.cfg["nextEdition"], datetime.date(2026, 11, 27))
        self.assertEqual(len(asked), 1)
        self.assertIn("checked not published", again[0])
        self.assertEqual(len(store.index("f")["versions"]), 1)

    def test_판_규칙이_없는_원천은_만료만_알린다(self):
        store = otp_region.versions_store()
        store.register("f", gtfs(self.path("a.zip"), "20261007", "20261130"), {"source": "https://example.org/f.zip"})
        lines = otp_region.prefetch_next("f", store, "https://example.org/f.zip", self.cfg["nextEdition"],
                                         datetime.date(2026, 11, 27))
        self.assertIn("no next-edition rule", lines[0])

    def test_다시_받기_전에_지금_사본을_판으로_남긴다(self):
        src = gtfs(os.path.join(self.dir, "copy.zip"), "20261007", "20261130")
        store = otp_region.versions_store()
        otp_region.keep_current(store, "f", src, {"source": "https://x"})
        gtfs(src, "20261201", "20261230")
        otp_region.keep_current(store, "f", src)
        self.assertEqual(sorted(v["serviceFrom"] for v in store.index("f")["versions"]), ["20261007", "20261201"])

    def test_피드_범위는_판을_원천_피드로_묶고_대표_시각표는_넣지_않는다(self):
        graph = self.path("graph")
        os.makedirs(graph)
        gtfs(os.path.join(graph, "sendai.gtfs.zip"), "20261120", "20261130")
        gtfs(os.path.join(graph, "sendai-v20261201.gtfs.zip"), "20261201", "20261230")
        gtfs(os.path.join(graph, "ktdb.gtfs.zip"), "20170101", "20301231")
        manifest = {
            "sendai": {"otpFeedId": "sendai", "serviceFrom": "20261007", "serviceTo": "20261130", "loadedFrom": "20261120"},
            "sendai-v20261201": {"otpFeedId": "sendai-v20261201", "versionOf": "sendai", "serviceFrom": "20261201",
                                 "serviceTo": "20261230", "loadedTo": "20270228"},
            "ktdb": {"otpFeedId": "ktdb", "serviceFrom": "20261120", "serviceTo": "20270228"}}
        self.cfg["feeds"]["ktdb"] = {"representativeCalendar": True}
        feeds = otp_region.write_coverage(graph, manifest, self.cfg, {})
        self.assertEqual(len(feeds), 1)
        self.assertEqual(feeds[0]["feed"], "sendai")
        self.assertEqual(feeds[0]["otpFeedIds"], ["sendai", "sendai-v20261201"])
        self.assertEqual(feeds[0]["periods"], [["20261120", "20261130"], ["20261201", "20261230"]])
        self.assertEqual(feeds[0]["modes"], ["BUS"])

    def test_입력이_같으면_다시_짓지_않고_판이_바뀌면_바뀐_피드를_이유로_든다(self):
        self.cfg["regions"]["r"]["port"] = 1
        versions = {"core": {"feed": "core", "sha256": "a" * 64}}
        inputs = otp_region.build_inputs("r", self.cfg, None, versions, {})
        self.assertEqual(otp_region.rebuild_reason("r", inputs, self.cfg), "no inputs record for current graph")
        os.makedirs(os.path.join(otp_region.ROOT, "r", "graph"))
        with open(os.path.join(otp_region.ROOT, "r", "graph", "inputs.json"), "w") as f:
            json.dump(inputs, f)
        self.assertIsNone(otp_region.rebuild_reason("r", inputs, self.cfg))
        changed = otp_region.build_inputs("r", self.cfg, None, dict(versions, core={"feed": "core", "sha256": "b" * 64}), {})
        self.assertEqual(otp_region.rebuild_reason("r", changed, self.cfg), "new versions 1 (core)")
        later = datetime.date.today() + datetime.timedelta(days=otp_region.GRAPH_MAX_AGE_DAYS + 1)
        self.assertIn("days old", otp_region.rebuild_reason("r", inputs, self.cfg, today=later))

    def test_입력이_지금_그래프와_같으면_빌드_흐름이_짓지_않고_재사용한다(self):
        feed = gtfs(self.path("core.zip"), "20261101", "20271031")
        otp_region.fetch = lambda url, path, **kw: path
        saved = otp_region.collect
        otp_region.collect = lambda name, region, cfg: ([("core", feed)], [], {"core": "https://x/core.zip"})
        try:
            downloaded, report, _ = otp_region.collect("r", self.cfg["regions"]["r"], self.cfg)
            _, windows, versions, _ = otp_region.select_versions(downloaded, self.cfg, report)
            inputs = otp_region.build_inputs("r", self.cfg, None, versions, windows)
            os.makedirs(os.path.join(otp_region.ROOT, "r", "graph"))
            with open(os.path.join(otp_region.ROOT, "r", "graph", "inputs.json"), "w") as f:
                json.dump(inputs, f)
            result = otp_region.build("r", force=False, promote_graph=False)
        finally:
            otp_region.collect = saved
        self.assertEqual(result, {"region": "r", "action": "reused"})
        self.assertFalse(os.path.exists(os.path.join(otp_region.ROOT, "r", "staging")))

    def current_graph(self, report_lines, manifest):
        graph = os.path.join(otp_region.ROOT, "r", "graph")
        os.makedirs(graph)
        open(os.path.join(graph, "graph.obj"), "w").write("g")
        open(os.path.join(graph, "feeds-report.txt"), "w").write("\n".join(report_lines) + "\n")
        json.dump(manifest, open(os.path.join(graph, "feeds-manifest.json"), "w"))
        json.dump({"builtAt": "2026-11-27T10:00:00", "smoke": {"failed": []}}, open(os.path.join(graph, "build-metrics.json"), "w"))
        return graph

    def baseline_with(self, feed, clip_summary):
        otp_region.collect = lambda name, region, cfg: ([("core", feed)], [], {"core": "https://x/core.zip"})
        otp_region.clip = lambda src, dst, bbox, feed=None, window=None: (clip_summary, None)
        return otp_region.baseline("r")

    def test_기준선은_다시_돌린_결과가_지금_그래프와_같을_때만_기록하고_다음_갱신은_재사용한다(self):
        feed = gtfs(self.path("core.zip"), "20261101", "20271031")
        summary = "stops=3 trips=2 stop_times=6 services=1 service_days=20261101-20271031 dropped_routes=0"
        graph = self.current_graph(["use  core: " + summary, "skip odpt-x: needs ODPT_ACCESS_TOKEN"],
                                   {"core": {"sha256": otp_region.sha256(feed)}})
        saved = otp_region.collect
        try:
            result = self.baseline_with(feed, summary)
            self.assertEqual(result["action"], "baselined")
            self.assertTrue(os.path.exists(os.path.join(graph, "inputs.json")))
            self.assertFalse(otp_region.OFFLINE[0])
            otp_region.fetch = lambda url, path, **kw: path
            self.assertEqual(otp_region.build("r", force=False, promote_graph=False)["action"], "reused")
        finally:
            otp_region.collect = saved

    def test_기준선은_자르기_결과나_원본이_다르면_기록하지_않는다(self):
        feed = gtfs(self.path("core.zip"), "20261101", "20271031")
        graph = self.current_graph(["use  core: stops=3 trips=9 stop_times=6 services=1 service_days=20261101-20271031"],
                                   {"core": {"sha256": "0" * 64}})
        saved = otp_region.collect
        try:
            result = self.baseline_with(feed, "stops=3 trips=2 stop_times=6 services=1 service_days=20261101-20271031")
        finally:
            otp_region.collect = saved
        self.assertEqual(result["action"], "mismatch")
        self.assertTrue(any(p.startswith("clip differs: core") for p in result["problems"]))
        self.assertIn("source differs: core", result["problems"])
        self.assertFalse(os.path.exists(os.path.join(graph, "inputs.json")))

    def test_기준선은_내려받지_않는다(self):
        otp_region.OFFLINE[0] = True
        try:
            with self.assertRaises(OSError):
                otp_region.fetch("https://example.org/x.zip", self.path("missing.zip"))
        finally:
            otp_region.OFFLINE[0] = False

    def test_롤백한_지역은_정기_갱신이_보류하고_빌드나_해제로_푼다(self):
        os.makedirs(os.path.join(otp_region.ROOT, "r", "graph"))
        os.makedirs(os.path.join(otp_region.ROOT, "r", "previous"))
        saved_regions = otp_region.regions
        otp_region.regions = lambda: {"r": {"feeds": []}}
        try:
            otp_region.rollback("r")
            self.assertEqual(otp_region.held("r")["reason"], "rollback")
            self.assertEqual(otp_region.refresh(["r"]), 0)
            os.makedirs(os.path.join(otp_region.ROOT, "r", "staging"))
            otp_region.promote("r")
            self.assertIsNone(otp_region.held("r"))
        finally:
            otp_region.regions = saved_regions

    def test_서버는_띄울_때_그래프의_피드_범위를_serving에_복사한다(self):
        graph = os.path.join(otp_region.ROOT, "r", "graph")
        os.makedirs(graph)
        json.dump({"feeds": [1]}, open(os.path.join(graph, "feeds-coverage.json"), "w"))
        json.dump({"builtAt": "2026-11-27T10:00:00"}, open(os.path.join(graph, "build-metrics.json"), "w"))
        self.cfg["regions"]["r"].update(port=18999, heap="1G")
        execs = []
        saved = otp_region.os.execvp
        otp_region.os.execvp = lambda prog, args: execs.append(args)
        try:
            otp_region.serve("r")
        finally:
            otp_region.os.execvp = saved
        serving = os.path.join(otp_region.ROOT, "r", "serving")
        self.assertEqual(json.load(open(os.path.join(serving, "feeds-coverage.json"))), {"feeds": [1]})
        self.assertEqual(json.load(open(os.path.join(serving, "graph.json")))["builtAt"], "2026-11-27T10:00:00")
        self.assertIn(graph, execs[0])

    def test_재기동은_그_포트를_다른_프로세스가_쓰면_멈춘다(self):
        self.cfg["regions"]["r"].update(port=18999)
        saved = otp_region.listening_pids, otp_region.regions
        otp_region.listening_pids = lambda port: [4242]
        otp_region.regions = lambda: self.cfg["regions"]
        try:
            with self.assertRaises(SystemExit) as e:
                otp_region.restart("r")
        finally:
            otp_region.listening_pids, otp_region.regions = saved
        self.assertIn("used by another process", str(e.exception))

    def test_정기_갱신은_실패한_지역을_건너뛰고_나머지를_계속한다(self):
        results = {"a": SystemExit("a: smoke test failed, current graph kept"), "b": {"region": "b", "action": "reused"}}
        saved = otp_region.build

        def fake_build(name, force=True, promote_graph=True):
            self.assertFalse(force)
            r = results[name]
            if isinstance(r, BaseException):
                raise r
            return r
        otp_region.build = fake_build
        try:
            self.assertEqual(otp_region.refresh(["a", "b"]), 1)
            results["a"] = SystemExit(otp_region.TERMINATED)
            with self.assertRaises(SystemExit):
                otp_region.refresh(["a", "b"])
        finally:
            otp_region.build = saved

    def test_남긴_피드가_격리되면_같아서_뺀_피드를_다시_싣는다(self):
        calls = []
        otp_region.clip = lambda src, dst, bbox, feed=None, window=None: (
            calls.append((os.path.basename(dst), window)) or "trips=2 service_days=20261101-20261130", None)
        otp_region.CORE_WINDOW[0] = ("20261120", "20270228")
        duplicates = {"keeper": [{"feedId": "copy"}], "other": [{"feedId": "x"}]}
        restored = otp_region.restore_duplicates(self.path("g"), {"bbox": [0, 0, 90, 180]}, self.cfg, "keeper",
                                                 duplicates, {"copy": self.path("copy.zip")}, {}, {})
        self.assertEqual(restored, {"copy": "trips=2 service_days=20261101-20261130"})
        self.assertEqual(calls, [("copy.gtfs.zip", ("20261120", "20270228"))])
        self.assertNotIn("keeper", duplicates)
        self.assertIn("other", duplicates)

    def test_MDB는_현_이름이_없으면_bbox로_고르고_공식_여부_실시간_대체_관계를_남긴다(self):
        os.makedirs(os.path.join(otp_region.SOURCES, "catalog"))
        cols = ["id", "data_type", "location.country_code", "location.subdivision_name", "status", "is_official",
                "static_reference", "urls.direct_download", "urls.authentication_type", "urls.latest", "urls.license",
                "location.bounding_box.minimum_latitude", "location.bounding_box.maximum_latitude",
                "location.bounding_box.minimum_longitude", "location.bounding_box.maximum_longitude", "redirect.id", "features"]
        rows = [["mdb-1", "gtfs", "SG", "", "active", "True", "", "https://a/x.zip", "0", "https://m/1.zip", "", "1.2", "1.4", "103.7", "104.0", "", ""],
                ["mdb-2", "gtfs", "SG", "", "active", "False", "", "", "0", "https://m/2.zip", "", "5", "6", "100", "101", "", ""],
                ["mdb-3", "gtfs_rt", "SG", "", "active", "True", "mdb-1", "https://a/rt", "0", "", "", "", "", "", "", "", "Trip updates"],
                ["mdb-0", "gtfs", "SG", "", "deprecated", "True", "", "", "0", "https://m/0.zip", "", "", "", "", "", "mdb-1", ""]]
        with open(os.path.join(otp_region.SOURCES, "catalog", "feeds_v2.csv"), "w") as f:
            f.write(",".join(cols) + "\n" + "".join(",".join(r) + "\n" for r in rows))
        out = otp_region.mdb_feeds("SG", [], [1.15, 103.55, 1.48, 104.1])
        self.assertEqual(out, [("mdb-1", "https://m/1.zip")])
        origin = otp_region.ORIGINS["mdb-1"]
        self.assertEqual((origin["official"], origin["replaces"], origin["realtime"][0]["id"]), (True, ["mdb-0"], "mdb-3"))


if __name__ == "__main__":
    unittest.main()
