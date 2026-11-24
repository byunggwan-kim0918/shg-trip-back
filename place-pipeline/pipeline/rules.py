"""파일로 둔 규칙(name_rules.tsv)을 읽는다. 나라·언어가 늘면 코드가 아니라 파일에 줄을 더한다."""
import csv
import os
import re

_NAME_RULES_FILE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "name_rules.tsv")
COMMON = "*"
USES = ("all", "non_food", "transit", "sight", "review", "branch_suffix", "generic", "sight_notable", "sight_general",
        "part_marker", "flagship", "flagship_suffix")


def load_name_rules(path=_NAME_RULES_FILE):
    """{use: {region: 정규식 하나}}. 같은 용도·지역의 줄을 대소문자 무시 OR로 묶는다."""
    patterns = {use: {} for use in USES}
    with open(path, encoding="utf-8") as f:
        for row in csv.DictReader((line for line in f if not line.startswith("#")), delimiter="\t"):
            re.compile(row["pattern"])
            patterns[row["use"]].setdefault(row["region"], []).append(row["pattern"])
    return {use: {region: "(?i)(" + "|".join(p) + ")" for region, p in by_region.items()}
            for use, by_region in patterns.items()}


def match_sql(text, country, rule):
    """text가 규칙에 맞는지. 공통 패턴은 모든 행에, 국가 패턴은 country가 그 나라인 행에만 건다."""
    terms = []
    for region, pattern in sorted(rule.items()):
        test = f"regexp_matches({text}, '{literal(pattern)}')"
        terms.append(test if region == COMMON else f"({country} = '{literal(region)}' AND {test})")
    return "(" + " OR ".join(terms) + ")" if terms else "false"


def strip_sql(text, country, rule):
    """규칙에 맞는 부분을 지운 text. 국가 패턴은 그 나라 행에만 쓴다."""
    expr = text
    if COMMON in rule:
        expr = f"regexp_replace({expr}, '{literal(rule[COMMON])}', '')"
    regional = [(r, p) for r, p in sorted(rule.items()) if r != COMMON]
    if regional:
        cases = " ".join(f"WHEN {country} = '{literal(r)}' THEN regexp_replace({expr}, '{literal(p)}', '')"
                         for r, p in regional)
        expr = f"CASE {cases} ELSE {expr} END"
    return expr


def literal(value):
    """SQL 문자열 리터럴 안에 넣을 값. 작은따옴표만 겹친다."""
    return str(value).replace("'", "''")
