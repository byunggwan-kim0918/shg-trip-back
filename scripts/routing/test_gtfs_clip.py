"""gtfs_clip.py 회귀 테스트. 나라마다 다른 피드 결함이 OTP 빌드를 멈추지 않는지 본다.

  python3 -I -m unittest discover -s scripts/routing -p 'test_*.py'
"""
import csv
import io
import os
import subprocess
import sys
import tempfile
import unittest
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
CLIP = os.path.join(HERE, "gtfs_clip.py")
BBOX = "35.0,139.0,36.0,140.0"


def feed(files, folder=""):
    tmp = tempfile.NamedTemporaryFile(suffix=".gtfs.zip", delete=False)
    with zipfile.ZipFile(tmp.name, "w") as z:
        for name, text in files.items():
            z.writestr(folder + name, text)
    return tmp.name


def base(**over):
    files = {
        "agency.txt": "agency_id,agency_name,agency_url,agency_timezone\nA,Op,http://x,Asia/Tokyo\n",
        "stops.txt": "stop_id,stop_name,stop_lat,stop_lon,parent_station\n"
                     "S1,One,35.5,139.5,\nS2,Two,35.6,139.6,\nFAR,Far,10.0,10.0,\n",
        "routes.txt": "route_id,agency_id,route_short_name,route_type\nR1,A,1,3\n",
        "trips.txt": "route_id,service_id,trip_id\nR1,WK,T1\n",
        "stop_times.txt": "trip_id,arrival_time,departure_time,stop_id,stop_sequence\n"
                          "T1,08:00:00,08:00:00,S1,1\nT1,08:10:00,08:10:00,S2,2\n",
        "calendar.txt": "service_id,monday,tuesday,wednesday,thursday,friday,saturday,sunday,start_date,end_date\n"
                        "WK,1,1,1,1,1,0,0,20261101,20270131\n",
    }
    files.update(over)
    return files


def clip(files, *extra, folder=""):
    src = feed(files, folder)
    dst = src.replace(".gtfs.zip", ".out.gtfs.zip")
    r = subprocess.run([sys.executable, "-I", CLIP, src, dst, "--bbox", BBOX, "--from", "20261120", "--to", "20270131",
                        *extra], capture_output=True, text=True)
    if r.returncode != 0:
        raise AssertionError(r.stderr)
    return zipfile.ZipFile(dst), r.stdout.strip()


def rows(z, name):
    return list(csv.DictReader(io.TextIOWrapper(z.open(name), "utf-8-sig")))


class GtfsClipTest(unittest.TestCase):

    def test_중복_운행_id는_첫_행만_남긴다(self):
        z, _ = clip(base(**{"trips.txt": "route_id,service_id,trip_id\nR1,WK,T1\nR1,WK,T1\n"}))
        self.assertEqual(len(rows(z, "trips.txt")), 1)

    def test_열_수가_깨진_행은_버린다(self):
        z, _ = clip(base(**{"feed_info.txt": "feed_publisher_name,feed_publisher_url,feed_lang\nX,http://x,ja\nbroken,row\n",
                            "translations.txt": "table_name,field_name,language,translation,record_id\n"
                                                "stops,stop_name,en,One,S1\nstops,stop_name,en,\"bad\"x,S2,extra\n"}))
        self.assertEqual(len(rows(z, "feed_info.txt")), 1)
        self.assertEqual(len(rows(z, "translations.txt")), 1)

    def test_옛_GTFS_JP_번역_파일은_뺀다(self):
        z, _ = clip(base(**{"translations.txt": "trans_id,lang,translation\nOne,en,One\n"}))
        self.assertNotIn("translations.txt", z.namelist())

    def test_예약_규칙을_가리키는_운행이_있으면_예약_규칙을_남긴다(self):
        z, _ = clip(base(**{"booking_rules.txt": "booking_rule_id,booking_type\nB1,0\n"}))
        self.assertIn("booking_rules.txt", z.namelist())

    def test_표준이_아닌_노선_종류를_바꾸고_겹치는_종류를_뺀다(self):
        z, out = clip(base(**{"routes.txt": "route_id,agency_id,route_short_name,route_type\nR1,A,1,0\nR2,A,KTX,6\n",
                              "trips.txt": "route_id,service_id,trip_id\nR1,WK,T1\nR2,WK,T2\n",
                              "stop_times.txt": "trip_id,arrival_time,departure_time,stop_id,stop_sequence\n"
                                                "T1,08:00:00,08:00:00,S1,1\nT1,08:10:00,08:10:00,S2,2\n"
                                                "T2,09:00:00,09:00:00,S1,1\nT2,09:30:00,09:30:00,S2,2\n"}),
                      "--route-type-map", "0:3", "--drop-route-types", "6")
        self.assertEqual([(r["route_id"], r["route_type"]) for r in rows(z, "routes.txt")], [("R1", "3")])
        self.assertIn("trips=1", out)

    def test_이름_규칙으로_겹치는_노선만_빼고_나머지는_남긴다(self):
        z, _ = clip(base(**{"routes.txt": "route_id,agency_id,route_short_name,route_type\n"
                                          "K,A,[KTX]경부선,6\nS,A,[SRT]경부선,6\n",
                              "trips.txt": "route_id,service_id,trip_id\nK,WK,T1\nS,WK,T2\n",
                              "stop_times.txt": "trip_id,arrival_time,departure_time,stop_id,stop_sequence\n"
                                                "T1,08:00:00,08:00:00,S1,1\nT1,08:10:00,08:10:00,S2,2\n"
                                                "T2,09:00:00,09:00:00,S1,1\nT2,09:30:00,09:30:00,S2,2\n"}),
                    "--drop-route-name-regex", r"^\[(KTX|새마을)\]", "--route-type-map", "6:2")
        self.assertEqual([(r["route_id"], r["route_type"]) for r in rows(z, "routes.txt")], [("S", "2")])

    def test_없는_상위_역은_지운다(self):
        z, _ = clip(base(**{"stops.txt": "stop_id,stop_name,stop_lat,stop_lon,parent_station\n"
                                         "S1,One,35.5,139.5,GONE\nS2,Two,35.6,139.6,\n"}))
        self.assertEqual({r["stop_id"]: r["parent_station"] for r in rows(z, "stops.txt")}, {"S1": "", "S2": ""})

    def test_없는_노선이나_운행일을_가리키는_운행은_뺀다(self):
        z, out = clip(base(**{"trips.txt": "route_id,service_id,trip_id\nR1,WK,T1\nNOPE,WK,T2\nR1,GONE,T3\n",
                              "stop_times.txt": "trip_id,arrival_time,departure_time,stop_id,stop_sequence\n"
                                                + "".join(f"{t},08:00:00,08:00:00,S1,1\n{t},08:10:00,08:10:00,S2,2\n"
                                                          for t in ("T1", "T2", "T3"))}))
        self.assertEqual([r["trip_id"] for r in rows(z, "trips.txt")], ["T1"])

    def test_영역_밖_정류장을_가리키는_환승은_뺀다(self):
        z, _ = clip(base(**{"transfers.txt": "from_stop_id,to_stop_id,transfer_type\nS1,S2,0\nS1,FAR,0\n"}))
        self.assertEqual(len(rows(z, "transfers.txt")), 1)

    def test_기간_밖_달력만_있으면_운행이_없고_운행_기간을_적는다(self):
        _, out = clip(base(**{"calendar.txt": "service_id,monday,tuesday,wednesday,thursday,friday,saturday,sunday,"
                                              "start_date,end_date\nWK,1,1,1,1,1,0,0,20200101,20201231\n"}))
        self.assertIn("trips=0", out)
        _, ok = clip(base())
        self.assertIn("service_days=20261101-20270131", ok)


    def test_대표_시각표는_기간까지_달력을_자르고_원래_운행_기간을_적는다(self):
        long_cal = {"calendar.txt": "service_id,monday,tuesday,wednesday,thursday,friday,saturday,sunday,"
                                    "start_date,end_date\nWK,1,1,1,1,1,1,1,20170101,20301231\n"}
        z, out = clip(base(**long_cal), "--clamp-calendar")
        cal = rows(z, "calendar.txt")[0]
        self.assertEqual((cal["start_date"], cal["end_date"]), ("20261120", "20270131"))
        self.assertIn("service_days=20170101-20301231", out)
        z, _ = clip(base(**long_cal))
        self.assertEqual(rows(z, "calendar.txt")[0]["end_date"], "20301231")

    def test_하위_폴더에_든_피드도_맨_위_파일처럼_읽는다(self):
        files = dict(base(), **{"guide.pdf": "x"})
        z, out = clip(files, folder="202503_GTFS_DataSet/")
        self.assertIn("trips=1", out)
        self.assertIn("stops.txt", z.namelist())
        self.assertNotIn("guide.pdf", z.namelist())

    def test_같은_깊이에_GTFS_폴더가_둘이면_고르지_않고_실패한다(self):
        src = feed(dict(base(), **{"b/stops.txt": base()["stops.txt"]}), "a/")
        with zipfile.ZipFile(src, "a") as z:
            z.writestr("b/stops.txt", base()["stops.txt"])
        r = subprocess.run([sys.executable, "-I", CLIP, src, src + ".out", "--bbox", BBOX, "--from", "20261120",
                            "--to", "20270131"], capture_output=True, text=True)
        self.assertNotEqual(r.returncode, 0)
        self.assertIn("several GTFS folders", r.stderr)

    def test_제외한_노선_수를_적는다(self):
        _, out = clip(base(), "--drop-route-types", "6")
        self.assertIn("dropped_routes=0", out)

if __name__ == "__main__":
    unittest.main()
