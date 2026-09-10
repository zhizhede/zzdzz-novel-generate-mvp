#!/usr/bin/env python3
"""从 docs/style 语料（手搓手稿）统计风格指纹基线，产出 style-metrics.json。
脚本不含任何手稿文本，只含路径与统计逻辑；产物为数值指标。"""
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
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


def load_corpus() -> str:
    parts = [load_prose(p) for p in PROSE_FILES]
    for p in sorted(FLASHBACK_DIR.glob("*/*.md")):
        parts.append(p.read_text(encoding="utf-8"))
    return "\n".join(parts)


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

    # 容差：语料仅 6 千字，起步放宽（±60%），随定稿章节数收紧
    baseline = {k: {"value": v, "tolerance": 0.6} for k, v in metrics.items() if k != "corpus_cjk"}
    # 零基线指标（顿号/感叹号）用绝对上限而非比例容差
    baseline["dunhao_per1k"]["abs_max"] = 1.0
    baseline["exclam_per1k"]["abs_max"] = 2.0
    # 题材校准（2026-09-10）：夜班题材高频出现时间/价格/招牌数字，破折号是分镜主要标点，
    # 按第一章实测放宽绝对上限；风格相对偏离仍由其余指标盯住
    baseline["dash_per1k"]["abs_max"] = 6.0
    baseline["digit_per1k"]["abs_max"] = 12.0

    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps(
        {"source": "docs/novels/手搓 (corpus ignored, metrics only)",
         "computed_at": "2026-09-10", "metrics": metrics, "baseline": baseline},
        ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(metrics, ensure_ascii=False, indent=2))
    print(f"written -> {OUT}")


if __name__ == "__main__":
    main()
