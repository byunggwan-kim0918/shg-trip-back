"""
통합 후보 풀과 장소별 실재·체인·웹 증거. 점수는 매기지 않고 근거만 열로 남긴다(scoring이 쓴다).
  풀       : Overture 적재 적합 행 + FSQ로 보완된 Overture 검토 행 + Overture와 이어지지 않은 FSQ 적재 적합 행
             + 보류된 FSQ 행 가운데 풀에 없는 Wikidata 항목의 본체.
  FSQ 증거 : Overture 행은 identity.build_links의 AUTO 연결로, FSQ 행은 자기 기록으로 등록일·확인일·폐업을 본다.
  독립 확인 : FSQ가 아닌 출처에서 온 Overture 행이 FSQ 행과 이어졌다. Overture가 FSQ 레코드를 옮겨 온 행은 아니다.
  체인     : 브랜드·체인 매장 공급처·공유 호스트(상업 분류만)와 참고용 같은 이름 수를 근거별로 따로 둔다. 체인이라고 빼지 않는다.
  웹사이트 : 가게 고유 도메인(own)과 플랫폼·SNS·백과·체인 본사(shared)를 나눈다.
  Wikidata : 이름이 같은 가장 가까운 항목의 위키백과 언어판 수(지명도)와 한국어 이름, 항목 좌표까지 거리(wd_meters).
"""
import csv
import os

from . import identity, policy
from .rules import literal as _literal
from .rules import load_name_rules, strip_sql

_BASIS_ORDER = "CASE l.basis WHEN 'source_id' THEN 1 WHEN 'exact_name' THEN 2 WHEN 'phone_name' THEN 3 ELSE 4 END"

_HOST = "^[a-zA-Z]+://(?:www\\.)?([^/:?#]+)"

_URL_HOSTS_FILE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "url_hosts.tsv")

# FSQ와 독립된 근거가 아닌 Overture 출처. Overture 항목은 confidence 계산 기록이다.
NOT_INDEPENDENT_DATASETS = ("Foursquare", "Overture", "Overture-signals")
# 출처가 자기 원본 페이지를 socials에 넣는 공급처와 그 호스트. 가게가 SNS를 운영한다는 근거가 아니다.
SOURCE_PAGE_HOSTS = (("meta", "facebook.com"),)
# 체인 매장 목록을 공급하는 출처. 매장 찾기 페이지·매장 정보 관리 서비스라 행 자체가 체인 지점이다.
CHAIN_DATASETS = ("AllThePlaces", "Uberall", "PinMeTo")
# 사찰·신사·공원은 같은 이름이 흔해서 상업 분류만 센다.
SAME_NAME_CATEGORY_PREFIXES = ("Dining and Drinking", "Retail", "Travel and Transportation > Lodging")
# 목록에 없는 호스트를 이만큼 넘는 장소가 쓰면 가게 고유 도메인이 아니다.
SHARED_HOST_MIN_PLACES = 5
# 공유 호스트를 체인 근거로 보는 장소 수. 이보다 적으면 지역 명물 가게의 지점이나 상점가 공동 사이트가 많다.
CHAIN_MIN_SHARED_HOST = 15


def build(con, candidates="candidates", fsq_raw=None, table="evidence", wikidata_raw=None, wikidata_areas=None,
          wikidata_brands=None, wikidata_businesses=None, wikidata_kinds=None):
    """풀의 행마다 증거 한 줄(키: source, source_id). fsq_raw·wikidata_raw가 없으면 그 열은 NULL이다.
    wikidata_areas(열 qid)는 지명 항목 목록이다. 주면 이름을 품은 행으로도 항목을 잇는다(identity.wikidata_links).
    wikidata_brands(열 qid)는 브랜드·체인 항목 목록이다(wikidata.brand_qids). 그 항목은 잇지 않는다.
    wikidata_businesses(열 qid)는 사업체 항목 목록이다(wikidata.business_qids). 가게·숙소 행은 그 밖의 항목에 가까이서만 잇는다.
    wikidata_kinds(열 qid, kind)는 종류별 항목 목록이다(wikidata.kind_qids). 휴게소 행은 별칭으로 휴게소 항목에만 잇는다.
    wikidata_raw를 주면 네 목록도 줘야 한다. 빠지면 지명·브랜드 항목이 가게·지점에 붙고 이름을 딴 가게가 먼 명소에 붙는다."""
    if wikidata_raw and None in (wikidata_areas, wikidata_brands, wikidata_businesses, wikidata_kinds):
        raise ValueError("Wikidata를 이으려면 wikidata_areas·wikidata_brands·wikidata_businesses·wikidata_kinds 표가 있어야 한다")
    lists = (wikidata_areas, wikidata_brands, wikidata_businesses, wikidata_kinds)
    identity.build_links(con, candidates, "source_links")
    identity.fsq_only(con, candidates, "source_links", "fsq_only")
    _pool(con, candidates)
    if wikidata_raw:
        _promote_notable_held(con, candidates, wikidata_raw, lists)
    _fsq_evidence(con, fsq_raw)
    _websites(con)
    _chain(con)
    _wikidata(con, candidates, wikidata_raw, lists)
    con.execute(f"""
        CREATE OR REPLACE TABLE {table} AS
        SELECT t.source, t.source_id, t.pool_basis, t.category AS pool_category, t.exclusion AS source_exclusion,
               f.* EXCLUDE (source, source_id), c.* EXCLUDE (source, source_id),
               w.* EXCLUDE (source, source_id, shared_host_places), d.* EXCLUDE (source, source_id)
        FROM targets t
        JOIN fsq_evidence f USING (source, source_id)
        JOIN chain_evidence c USING (source, source_id)
        JOIN web_evidence w USING (source, source_id)
        JOIN wikidata_evidence d USING (source, source_id)
    """)
    return table


def _wikidata(con, candidates, wikidata_raw, lists):
    """지명도(위키백과 언어판 수)와 한국어 이름. 항목이 없으면 NULL이다."""
    if wikidata_raw:
        identity.wikidata_links(con, "targets", candidates, wikidata_raw, "wikidata_links", *lists)
        joined = f"""LEFT JOIN wikidata_links l USING (source, source_id)
                     LEFT JOIN {wikidata_raw} w ON w.qid = l.qid"""
        # 한국어 라벨 자리에 원어 표기(Zepp, HEP NAVIO)나 위키백과 구분 괄호(영빈관 (일본))를 둔 항목이 있다.
        cols = ("l.qid AS wd_qid, w.sitelinks AS wd_sitelinks, "
                "wikidata_korean_label(w.ko::VARCHAR, t.name) AS wd_name_ko, w.kowiki AS wd_kowiki, l.meters AS wd_meters")
    else:
        joined = ""
        cols = ("NULL::VARCHAR AS wd_qid, NULL::INTEGER AS wd_sitelinks, NULL::VARCHAR AS wd_name_ko, "
                "NULL::BOOLEAN AS wd_kowiki, NULL::DOUBLE AS wd_meters")
    con.execute(f"""
        CREATE OR REPLACE TEMP TABLE wikidata_evidence AS
        SELECT t.source, t.source_id, {cols} FROM targets t {joined}
    """)


def load_url_hosts(path=_URL_HOSTS_FILE):
    with open(path, encoding="utf-8") as f:
        hosts = [(r["host_suffix"].lower(), r["kind"])
                 for r in csv.DictReader((line for line in f if not line.startswith("#")), delimiter="\t")]
    duplicated = {h for h, _ in hosts if sum(1 for x, _ in hosts if x == h) > 1}
    if duplicated:
        raise ValueError(f"중복 호스트: {sorted(duplicated)}")
    return hosts


def _pool(con, candidates):
    """
    풀 = Overture 적재 적합 + 보완된 Overture 검토 행 + FSQ 단독. pool_basis가 어느 경로인지 남긴다.
    보완: 분류를 모르는 검토 행은 자동 연결된 FSQ 적재 적합 행의 분류를 받고, stale 행은 그 FSQ 행의 확인일로 산다.
    """
    supplementable = ", ".join(f"'{_literal(e)}'" for e in policy.SUPPLEMENTABLE_EXCLUSIONS)
    con.execute(f"""
        CREATE OR REPLACE TEMP TABLE targets AS
        WITH supplement AS (
            SELECT l.a_id AS source_id, arg_min(f.category, {_BASIS_ORDER}) AS fsq_category
            FROM source_links l JOIN {candidates} f ON f.source = 'fsq' AND f.source_id = l.b_id AND f.exclusion IS NULL
            WHERE l.decision = 'AUTO' GROUP BY 1
        )
        SELECT *, 'overture' AS pool_basis FROM {candidates} WHERE source = 'overture' AND exclusion IS NULL
        UNION ALL BY NAME
        SELECT c.* REPLACE (CASE WHEN c.exclusion = 'stale' THEN c.category ELSE s.fsq_category END AS category),
               'supplemented' AS pool_basis
        FROM {candidates} c JOIN supplement s USING (source_id)
        WHERE c.source = 'overture' AND c.exclusion IN ({supplementable})
        UNION ALL BY NAME
        SELECT c.*, 'fsq_only' AS pool_basis FROM {candidates} c JOIN fsq_only o ON o.source_id = c.source_id
        WHERE c.source = 'fsq' AND o.status = 'only'
    """)


def _promote_notable_held(con, candidates, wikidata_raw, lists):
    """
    중복 가능·검토로 보류된 FSQ 행 가운데 풀에서 이름이 정확히 같은 행으로 이어지지 않은 Wikidata 항목에 이어지는 행을 풀에
    올린다. Overture에 경내 부속 시설(櫛田神社 中神門)만 있으면 본체 FSQ 행(櫛田神社)이 중복으로 보류되고, 항목 이름을
    품은 부속 시설(大涌谷延命地蔵尊)이 포함 연결로 항목을 가져간다.
    """
    identity.wikidata_links(con, "targets", candidates, wikidata_raw, "pool_wikidata", *lists)
    con.execute("CREATE OR REPLACE TEMP TABLE held AS SELECT 'fsq' AS source, source_id FROM fsq_only WHERE status <> 'only'")
    identity.wikidata_links(con, "held", candidates, wikidata_raw, "held_wikidata", *lists)
    con.execute(f"""
        INSERT INTO targets BY NAME
        SELECT c.*, 'fsq_notable' AS pool_basis
        FROM (SELECT source_id FROM held_wikidata
              WHERE rule = 'exact'
                AND NOT EXISTS (SELECT 1 FROM pool_wikidata p WHERE p.rule = 'exact' AND p.qid = held_wikidata.qid)
              QUALIFY row_number() OVER (PARTITION BY qid ORDER BY meters, source_id) = 1) h
        JOIN {candidates} c ON c.source = 'fsq' AND c.source_id = h.source_id
    """)


def _fsq_evidence(con, fsq_raw):
    """
    폐업·신고는 자동 연결된 FSQ 행이 모두 그럴 때만 참이다. 옛 기록과 새 기록이 함께 걸리면 영업 중으로 본다.
    출처별로 나눠 합친다. 조인 조건에 출처 비교를 섞으면 DuckDB가 중첩 루프 조인을 고른다.
    """
    certain_flags = "[" + ", ".join(f"'{_literal(f)}'" for f in policy.FSQ_CERTAIN_FLAGS) + "]"
    if fsq_raw:
        raw = f"""SELECT fsq_place_id, try_cast(date_created AS DATE) AS created,
                         try_cast(date_refreshed AS DATE) AS refreshed, date_closed IS NOT NULL AS closed,
                         list_has_any(coalesce(unresolved_flags, []), {certain_flags}) AS flagged
                  FROM {fsq_raw}"""
    else:
        raw = """SELECT NULL::VARCHAR AS fsq_place_id, NULL::DATE AS created, NULL::DATE AS refreshed,
                        NULL::BOOLEAN AS closed, NULL::BOOLEAN AS flagged WHERE false"""
    independent = "[" + ", ".join(f"'{_literal(d)}'" for d in NOT_INDEPENDENT_DATASETS) + "]"
    con.execute(f"""
        CREATE OR REPLACE TEMP TABLE fsq_evidence AS
        WITH raw AS ({raw}),
        auto AS (
            SELECT l.a_id, arg_min(l.basis, {_BASIS_ORDER}) AS basis, min(r.created) AS created,
                   max(r.refreshed) AS refreshed,
                   bool_and(r.closed) AS closed, bool_and(r.closed OR r.flagged) AND bool_or(r.flagged) AS flagged
            FROM source_links l JOIN raw r ON r.fsq_place_id = l.b_id
            WHERE l.decision = 'AUTO' GROUP BY 1
        ),
        review AS (SELECT a_id, count(*) AS n FROM source_links WHERE decision = 'REVIEW' GROUP BY 1)
        SELECT t.source, t.source_id, a.basis AS fsq_basis, a.created AS fsq_created, a.refreshed AS fsq_refreshed,
               a.closed AS fsq_closed, a.flagged AS fsq_flagged, coalesce(v.n, 0) AS fsq_review_links,
               a.a_id IS NOT NULL
                   AND len(list_filter(t.datasets, d -> NOT list_contains({independent}, d))) > 0 AS independent_confirm
        FROM targets t LEFT JOIN auto a ON a.a_id = t.source_id LEFT JOIN review v ON v.a_id = t.source_id
        WHERE t.source = 'overture'
        UNION ALL
        SELECT t.source, t.source_id, 'self', s.created, s.refreshed, s.closed, s.flagged, 0, false
        FROM targets t LEFT JOIN raw s ON s.fsq_place_id = t.source_id
        WHERE t.source = 'fsq'
    """)


def _chain(con):
    rules = load_name_rules()
    datasets = ", ".join(f"'{_literal(d)}'" for d in CHAIN_DATASETS)
    commercial = " OR ".join(f"starts_with(category, '{_literal(p)}')" for p in SAME_NAME_CATEGORY_PREFIXES)
    con.execute(f"""
        CREATE OR REPLACE TEMP TABLE chain_evidence AS
        WITH stems AS (
            SELECT source, source_id, name_key({strip_sql('name', 'country', rules['branch_suffix'])}) AS stem,
                   {commercial} AS commercial
            FROM targets
        ),
        same AS (SELECT stem, count(*) AS n FROM stems WHERE commercial AND length(stem) >= 2 GROUP BY 1)
        SELECT t.source, t.source_id, t.brand AS chain_brand,
               list_has_any(t.datasets, [{datasets}]) AS chain_dataset,
               CASE WHEN s.commercial THEN coalesce(m.n, 0) ELSE 0 END AS same_name_count,
               s.commercial AND coalesce(w.shared_host_places, 0) >= {CHAIN_MIN_SHARED_HOST} AS chain_shared_host
        FROM targets t JOIN stems s USING (source, source_id) LEFT JOIN same m ON m.stem = s.stem
        LEFT JOIN web_evidence w USING (source, source_id)
    """)


def _websites(con):
    """호스트마다 kind를 정하고 장소마다 가장 쓸모 있는 하나(own > wiki > sns > shared > platform)를 남긴다."""
    hosts = load_url_hosts()
    source_page = " OR ".join(f"(list_contains(datasets, '{_literal(d)}') AND (host = '{_literal(h)}' "
                              f"OR ends_with(host, '.{_literal(h)}')))" for d, h in SOURCE_PAGE_HOSTS)
    known = " ".join(f"WHEN host = '{_literal(h)}' OR ends_with(host, '.{_literal(h)}') THEN '{_literal(k)}'"
                     for h, k in hosts)
    con.execute(f"""
        CREATE OR REPLACE TEMP TABLE web_evidence AS
        WITH urls AS (
            SELECT t.source, t.source_id, url, lower(regexp_extract(url, '{_HOST}', 1)) AS host
            FROM targets t, unnest(t.websites) AS u(url)
            UNION ALL
            SELECT source, source_id, url, host FROM (
                SELECT t.source, t.source_id, t.datasets, url, lower(regexp_extract(url, '{_HOST}', 1)) AS host
                FROM targets t, unnest(t.socials) AS u(url))
            WHERE NOT ({source_page})
        ),
        usage AS (SELECT host, count(DISTINCT (source, source_id)) AS places FROM urls GROUP BY 1),
        kinds AS (
            SELECT u.source, u.source_id, u.url, u.host, g.places,
                   CASE WHEN u.host = '' THEN 'invalid' {known}
                        WHEN g.places >= {SHARED_HOST_MIN_PLACES} THEN 'shared' ELSE 'own' END AS kind
            FROM urls u JOIN usage g USING (host)
        ),
        ranked AS (
            SELECT *, row_number() OVER (PARTITION BY source, source_id ORDER BY
                       CASE kind WHEN 'own' THEN 0 WHEN 'wiki' THEN 1 WHEN 'sns' THEN 2 WHEN 'shared' THEN 3
                                 WHEN 'platform' THEN 4 ELSE 5 END, url) AS rn
            FROM kinds
        ),
        per_place AS (
            SELECT source, source_id, bool_or(kind = 'wiki') AS has_wiki, bool_or(kind = 'sns') AS has_sns,
                   max(CASE WHEN kind = 'shared' THEN places END) AS shared_host_places
            FROM kinds GROUP BY 1, 2
        )
        SELECT t.source, t.source_id, r.kind AS web_kind, r.host AS web_host,
               coalesce(p.has_wiki, false) AS web_wiki, coalesce(p.has_sns, false) AS web_sns, p.shared_host_places
        FROM targets t
        LEFT JOIN ranked r ON r.source = t.source AND r.source_id = t.source_id AND r.rn = 1
        LEFT JOIN per_place p ON p.source = t.source AND p.source_id = t.source_id
    """)
