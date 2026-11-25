"""
Unicode Unihan(Unihan_Readings.txt, Unihan_Variants.txt)에서 한자 읽기 표를 만든다. 생성기가 초안의 한국어 이름과 현지
한자 이름이 같은 곳인지 대조할 때 쓴다(DraftPlaceResolver의 현지어 이름 확인).
  hanja-ko.tsv : 글자 → 한국 한자음(kHangul). 일본 신자체(浅·広)처럼 읽기가 없는 글자는 이체자(淺·廣)의 읽기를 쓴다.
  hanja-ja.tsv : 글자 → 일본어 음독·훈독(kJapaneseOn·kJapaneseKun, 소문자 로마자). 같은 방식으로 이체자를 따른다.
실행: python -m pipeline.unihan <Unihan_Readings.txt> <Unihan_Variants.txt> <출력 폴더>
"""
import collections
import os
import sys

_VARIANT_FIELDS = ("kTraditionalVariant", "kSemanticVariant", "kZVariant", "kSimplifiedVariant",
                   "kSpecializedSemanticVariant")


def _char(code):
    return chr(int(code.split("<")[0][2:], 16))


def readings(readings_lines, fields):
    out = collections.defaultdict(list)
    for line in readings_lines:
        if line.startswith("#") or not line.strip():
            continue
        code, field, value = line.rstrip("\n").split("\t")
        if field not in fields:
            continue
        for v in value.split():
            r = v.split(":")[0].lower()
            if r not in out[_char(code)]:
                out[_char(code)].append(r)
    return out


def variants(variant_lines):
    out = collections.defaultdict(list)
    for line in variant_lines:
        if line.startswith("#") or not line.strip():
            continue
        code, field, value = line.rstrip("\n").split("\t")
        if field in _VARIANT_FIELDS:
            out[_char(code)] += [_char(v) for v in value.split()]
    return out


def with_variants(table, variant_map):
    """읽기가 없는 글자에 이체자 가운데 읽기가 있는 첫 글자의 읽기를 준다."""
    out = dict(table)
    for c, vs in variant_map.items():
        if c in out:
            continue
        for v in vs:
            if v in table:
                out[c] = table[v]
                break
    return out


def write(table, path):
    with open(path, "w", encoding="utf-8") as f:
        for c in sorted(table):
            f.write(f"{c}\t{','.join(table[c])}\n")


def main(readings_path, variants_path, out_dir):
    with open(readings_path, encoding="utf-8") as f:
        lines = f.readlines()
    with open(variants_path, encoding="utf-8") as f:
        var = variants(f)
    ko = with_variants(readings(lines, {"kHangul"}), var)
    ja = with_variants(readings(lines, {"kJapaneseOn", "kJapaneseKun"}), var)
    os.makedirs(out_dir, exist_ok=True)
    write(ko, os.path.join(out_dir, "hanja-ko.tsv"))
    write(ja, os.path.join(out_dir, "hanja-ja.tsv"))
    return {"ko": len(ko), "ja": len(ja)}


if __name__ == "__main__":
    print(main(*sys.argv[1:4]))
