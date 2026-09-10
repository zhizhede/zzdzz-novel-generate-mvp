#!/usr/bin/env python3
"""从 docs/style 语料（手搓手稿）统计风格指纹基线，产出 style-metrics.json。
脚本不含任何手稿文本，只含路径与统计逻辑；产物为数值指标。"""
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
# 用法：python style_baseline.py [语料目录] [输出json] [语料标签]
# 目录模式下递归收集全部 .md/.txt，剔除 # 标题行；缺省保持手搓语料行为
CORPUS_DIR = ROOT / "docs" / "novels" / "手搓" / "人类、法师、地下城（暂定）"
OUT = ROOT / "novel" / "夜班守则" / "style-metrics.json"

PROSE_FILES = [
    CORPUS_DIR / "第一卷/第一章/第一章-手搓.md",
    CORPUS_DIR / "第一卷/第二章/第二章-手搓.md",
    CORPUS_DIR / "番外/莉莉日记-终将老去的我和永远存续的他们.txt",
]
FLASHBACK_DIR = CORPUS_DIR / "闪回素材"


def load_prose(path: Path) -> str:
    t = path.read_text(encoding="utf-8")
    if "正文" in t:
        t = t.split("正文", 1)[1]
    return t.split("废弃")[0]


def strip_headers(text: str) -> str:
    return chr(10).join(l for l in text.splitlines() if not l.strip().startswith("#"))


def load_corpus() -> str:
    import sys
    if len(sys.argv) > 1:
        corpus_dir = Path(sys.argv[1])
        parts = []
        for f in sorted(list(corpus_dir.rglob("*.md")) + list(corpus_dir.rglob("*.txt"))):
            parts.append(strip_headers(f.read_text(encoding="utf-8")))
        return chr(10).join(parts)
    parts = [load_prose(p) for p in PROSE_FILES]
    for p in sorted(FLASHBACK_DIR.glob("*/*.md")):
        parts.append(p.read_text(encoding="utf-8"))
    return chr(10).join(parts)


def count(text: str, needle: str) -> int:
    return text.count(needle)


def dialogue_end_punct_ratio(lines: list[str]) -> float:
    """以」结尾的对话行中，句末带标点（。？！…）的比例——手搓惯例是常不带。"""
    total = punct = 0
    for line in lines:
        s = line.strip()
        if s.endswith("」"):
            total += 1
            inner = s[: s.rindex("」")]
            if inner and inner[-1] in "。？！…":
                punct += 1
    return round(punct / total, 3) if total else 0.0


def main() -> None:
    corpus = load_corpus()
    cjk = len(re.findall(r"[\u4e00-\u9fff]", corpus))
    if cjk < 1000:
        sys.exit(f"语料过少: {cjk}")
    lines = [l for l in corpus.splitlines() if l.strip()]

    per1k = lambda n: round(n * 1000 / cjk, 2)
    metrics = {
        "corpus_cjk": cjk,
        "line_avg_len": round(sum(len(l.strip()) for l in lines) / len(lines), 1),
        "dialogue_density_per1k": per1k(count(corpus, "「")),
        "dunhao_per1k": per1k(count(corpus, "、")),
        "dash_per1k": per1k(count(corpus, "——")),
        "ellipsis_per1k": per1k(count(corpus, "……")),
        "exclam_per1k": per1k(count(corpus, "！")),
        "digit_per1k": per1k(len(re.findall(r"[0-9]", corpus))),
        "tic_laizhe_per1k": per1k(count(corpus, "来着")),
        "tic_shunbian_per1k": per1k(count(corpus, "顺便")),
        "tic_haiyou_per1k": per1k(count(corpus, "还有")),
        "dialogue_end_punct_ratio": dialogue_end_punct_ratio(lines),
    }

    # 容差：语料起步放宽（±60%），随定稿章节数收紧
    baseline = {k: {"value": v, "tolerance": 0.6} for k, v in metrics.items() if k != "corpus_cjk"}
    # 稀疏/题材型指标给绝对上限：max(经验默认值, 基线*1.8)，允许题材合理上浮
    baseline["dunhao_per1k"]["abs_max"] = round(max(1.0, metrics["dunhao_per1k"] * 1.8), 2)
    baseline["exclam_per1k"]["abs_max"] = round(max(2.0, metrics["exclam_per1k"] * 1.8), 2)
    baseline["dash_per1k"]["abs_max"] = round(max(6.0, metrics["dash_per1k"] * 1.8), 2)
    baseline["digit_per1k"]["abs_max"] = round(max(12.0, metrics["digit_per1k"] * 1.8), 2)

    import sys
    label = sys.argv[3] if len(sys.argv) > 3 else "docs/novels/手搓 (corpus ignored, metrics only)"
    out = Path(sys.argv[2]) if len(sys.argv) > 2 else OUT
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(
        {"source": label,
         "computed_at": "2026-09-10", "metrics": metrics, "baseline": baseline},
        ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(metrics, ensure_ascii=False, indent=2))
    print(f"written -> {OUT}")


if __name__ == "__main__":
    main()
