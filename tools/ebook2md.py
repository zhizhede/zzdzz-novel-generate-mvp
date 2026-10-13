#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""ebook2md —— 小说 txt / mobi 转 Markdown (.md)。

用法:
    python tools/ebook2md.py 输入.txt [更多输入...]
    python tools/ebook2md.py 输入.mobi -o 输出.md
    python tools/ebook2md.py 一批书/ --out-dir ./md   # 目录下所有 txt/mobi

约定:
- txt: 默认「一行即一段」；识别章节标题（第N章/第N回/一、标题/裸数字节号等）；
  首个非空行做一级标题，其后短行含「著/译/编」按作者行斜体。
- mobi: 解 PalmDB(BOOKMOBI) + Palmdoc(type2)/无压缩；HTML 转块——
  居中加粗/大号字当标题，连续短诗行合并为硬换行段，目录链接块丢弃，
  EXTH 书名/作者做文首元信息。HUFF/CDIC(17480) 压缩暂不支持。
- 正文段首全角缩进会去掉；UTF-8 输出，LF 换行。
"""

from __future__ import annotations

import argparse
import re
import struct
import sys
from pathlib import Path

try:
    from bs4 import BeautifulSoup
except ImportError:  # 仅 mobi 需要
    BeautifulSoup = None

try:
    from charset_normalizer import from_bytes as _from_bytes
except ImportError:
    _from_bytes = None


# ---------------------------------------------------------------- 公共

CHAP_NUM = r"[0-9０-９零〇一二三四五六七八九十百千万两]+"
PART_RE = re.compile(rf"^第{CHAP_NUM}[卷部集辑篇]$")
CHAPTER_RE = re.compile(rf"^第{CHAP_NUM}[章回节歌幕场折夜]$")
PREFIX_CHAPTER_RE = re.compile(rf"^[一二三四五六七八九十百]+、")
EN_CHAPTER_RE = re.compile(r"^(Chapter|CHAPTER|Part|PART)\s+[0-9IVXLC]+", re.I)
SPECIALS = (
    "序章", "楔子", "前言", "序言", "引子", "后记", "尾声", "完结",
    "结语", "番外", "跋", "附录", "序", "目录", "内容简介", "全书完",
)
BARE_NUM_RE = re.compile(rf"^[0-9０-９]{{1,6}}$|^[一二三四五六七八九十]{1,4}$")
TOC_NAMES = {"目录", "目次", "contents", "tableofcontents"}
HEADING_LEN_MAX = 40  # 章节标题行长度闸，防长段误判


def norm(s: str) -> str:
    """归一化：去掉所有空白（含全角空格），用于比对/分类。"""
    return re.sub(r"[\s　]+", "", s)


def classify_heading(text: str) -> str | None:
    """整行是否像标题。返回 'part' | 'chapter' | 'special'，否则 None。"""
    t = norm(text).strip("·•‧.。:：!！?？-—~～*")
    if not t:
        return None
    if PART_RE.match(t) or (t.endswith("篇") and not t.startswith("第")):
        return "part"
    if re.match(r"^[上中下终]?[卷部]([一二三四五六七八九十0-9]+)?$", t):
        return "part"
    if CHAPTER_RE.match(t):
        return "chapter"
    if t in SPECIALS:
        return "special"
    # 多字特殊标题允许带副题（如「序章：xxx」）；单字（序/跋）只认全等，防「序曲」误判
    for s in SPECIALS:
        if len(s) >= 2 and t.startswith(s) and len(t) <= len(s) + 12:
            return "special"
    if PREFIX_CHAPTER_RE.match(t) and len(t) <= 30:
        return "chapter"
    if EN_CHAPTER_RE.match(t) and len(t) <= 40:
        return "chapter"
    if BARE_NUM_RE.match(t):
        return "chapter"
    return None


def looks_like_meta(line: str) -> bool:
    """短行且含著/译/编——作者、译者、编者行。"""
    t = norm(line)
    return 0 < len(t) <= 60 and bool(re.search(r"[著译编]", t)) and "著作" not in t


def assemble_md(
    blocks: list[dict],
    title: str | None = None,
    author: str | None = None,
) -> str:
    """blocks → Markdown 文本。block: {kind, text/lines, level?}"""
    parts: list[str] = []
    if title:
        parts.append(f"# {title}")
        if author:
            parts.append("")
            parts.append(f"*{author}*")
    has_part = any(b["kind"] == "part" for b in blocks)
    prev_blank = False
    for b in blocks:
        kind = b["kind"]
        if kind == "h1":
            chunk = f"# {b['text']}"
        elif kind == "part":
            chunk = f"## {b['text']}"
        elif kind == "chapter":
            chunk = f"{'###' if has_part else '##'} {b['text']}"
        elif kind == "special":
            chunk = f"## {b['text']}"
        elif kind == "verse":
            lines = b["lines"]
            chunk = "\n".join(ln + "\\" for ln in lines[:-1]) + ("\n" + lines[-1] if lines else "")
        else:  # p / italic
            chunk = f"*{b['text']}*" if kind == "italic" else b["text"]
        if not chunk.strip():
            continue
        if not prev_blank:
            parts.append("")
        parts.append(chunk)
        prev_blank = False
    text = "\n".join(parts).strip("\n")
    return text + "\n"


def write_md(out_path: Path, text: str) -> None:
    out_path.parent.mkdir(parents=True, exist_ok=True)
    out_path.write_text(text, encoding="utf-8", newline="\n")


# ---------------------------------------------------------------- txt

def read_text_file(path: Path) -> str:
    raw = path.read_bytes()
    for enc in ("utf-8-sig", "utf-8", "gb18030", "big5"):
        try:
            return raw.decode(enc)
        except UnicodeDecodeError:
            continue
    if _from_bytes is not None:
        best = _from_bytes(raw).best()
        if best is not None:
            return str(best)
    return raw.decode("utf-8", errors="replace")


def txt_to_blocks(text: str) -> list[dict]:
    lines = text.replace("\r\n", "\n").replace("\r", "\n").split("\n")
    blocks: list[dict] = []
    nonempty_idx = -1  # 已处理的非空行序号（0=标题行）
    for raw in lines:
        line = raw.strip().lstrip("　").rstrip()
        if not line:
            continue
        nonempty_idx += 1
        kind = classify_heading(line)
        # 长度闸：太长不像标题
        if kind and len(norm(line)) > HEADING_LEN_MAX:
            kind = None
        if nonempty_idx == 0:
            blocks.append({"kind": "h1", "text": line})
            continue
        if kind:
            blocks.append({"kind": kind, "text": line})
            continue
        # 标题后紧跟的 1-2 行若是著/译/编短行 → 作者行斜体
        if nonempty_idx <= 2 and looks_like_meta(line):
            blocks.append({"kind": "italic", "text": line})
            continue
        blocks.append({"kind": "p", "text": line})
    return blocks


# ---------------------------------------------------------------- mobi

class MobiError(RuntimeError):
    pass


def _palmdoc_decompress(data: bytes, limit: int) -> bytes:
    """Palmdoc LZ77。limit=本记录解压上限（通常 4096；末记录为余量）。

    距离按 4096 环形缓冲取模，拷贝起点 = 当前位置 - dist（不减一）；
    达到 limit 即停，尾部填充字节忽略。
    """
    ring = bytearray(4096)
    rpos = 0
    out = bytearray()
    i, n = 0, len(data)
    while i < n and len(out) < limit:
        b = data[i]
        i += 1
        if b == 0:
            c = 0
        elif 1 <= b <= 8:
            for _ in range(b):
                if i >= n or len(out) >= limit:
                    break
                c = data[i]
                i += 1
                ring[rpos & 0xFFF] = c
                rpos += 1
                out.append(c)
            continue
        elif b <= 0x7F:
            c = b
        elif b <= 0xBF:
            if i >= n:
                break
            b2 = data[i]
            i += 1
            dist = ((b << 8) | b2) >> 3 & 0xFFF
            for _ in range((b2 & 7) + 3):
                if len(out) >= limit:
                    break
                cc = ring[(rpos - dist) & 0xFFF]
                ring[rpos & 0xFFF] = cc
                rpos += 1
                out.append(cc)
            continue
        else:
            for c in (0x20, b ^ 0x80):
                if len(out) >= limit:
                    break
                ring[rpos & 0xFFF] = c
                rpos += 1
                out.append(c)
            continue
        ring[rpos & 0xFFF] = c
        rpos += 1
        out.append(c)
    return bytes(out)


def _decode_text(buf: bytes, encoding: int) -> str:
    enc = "utf-8" if encoding == 65001 else "cp1252"
    while buf:
        try:
            return buf.decode(enc)
        except UnicodeDecodeError as e:
            # 尾部不完整的多字节序列：截掉再解
            if len(buf) - e.start < 4:
                buf = buf[: e.start]
                continue
            raise MobiError(f"文本解码失败（{enc}）：{e}") from e
    return ""


def parse_mobi(data: bytes) -> tuple[str, dict]:
    """返回 (html全文, meta{'title','author'})。"""
    if len(data) < 78:
        raise MobiError("文件太小，不是有效 mobi")
    file_type = data[60:68]
    if file_type != b"BOOKMOBI":
        raise MobiError(f"不是 BOOKMOBI 容器（type={file_type!r}），暂不支持")
    num_rec = struct.unpack(">H", data[76:78])[0]
    if num_rec < 2 or len(data) < 78 + num_rec * 8:
        raise MobiError("PalmDB 记录表损坏")
    offs = [struct.unpack(">I", data[78 + i * 8 : 82 + i * 8])[0] for i in range(num_rec)]
    rec0 = data[offs[0] : (offs[1] if num_rec > 1 else len(data))]
    if len(rec0) < 16 + 0x74:
        raise MobiError("记录0过短")

    compression, = struct.unpack(">H", rec0[0:2])
    text_len, = struct.unpack(">I", rec0[4:8])
    text_rec_cnt, = struct.unpack(">H", rec0[8:10])
    rec_size, = struct.unpack(">H", rec0[10:12])
    encryption, = struct.unpack(">H", rec0[12:14])
    if encryption not in (0,):
        raise MobiError(f"含 DRM/加密（encryption={encryption}），无法转换")
    if text_rec_cnt < 1 or text_rec_cnt + 1 > num_rec:
        raise MobiError("文本记录数与记录表不符")
    if not rec_size:
        rec_size = 4096
    if compression not in (1, 2):
        raise MobiError(
            f"压缩类型 {compression} 暂不支持（HUFF/CDIC 需先用 Calibre "
            f"ebook-convert 转成无压缩 mobi 或另行处理）"
        )

    encoding, = struct.unpack(">I", rec0[16 + 0x0C : 20 + 0x0C])
    full_name = ""
    try:
        name_off, name_len = struct.unpack(">II", rec0[16 + 0x44 : 20 + 0x48])
        if name_off + name_len <= len(rec0):
            full_name = _decode_text(rec0[name_off : name_off + name_len], encoding).strip()
    except Exception:
        pass

    # EXTH
    meta: dict = {"title": "", "author": ""}
    try:
        mobi_hdr_len, = struct.unpack(">I", rec0[16 + 4 : 20 + 4])
        exth_flags, = struct.unpack(">I", rec0[16 + 0x70 : 20 + 0x70])
        if exth_flags & 0x40:
            pos = 16 + mobi_hdr_len
            if rec0[pos : pos + 4] == b"EXTH":
                exth_len, exth_n = struct.unpack(">II", rec0[pos + 4 : pos + 12])
                pos += 12
                authors = []
                for _ in range(exth_n):
                    if pos + 8 > len(rec0):
                        break
                    rt, rl = struct.unpack(">II", rec0[pos : pos + 8])
                    if rl < 8 or pos + rl > len(rec0):
                        break
                    val = rec0[pos + 8 : pos + rl]
                    pos += rl
                    if rt == 100:
                        authors.append(_decode_text(val, encoding).strip())
                    elif rt == 503 and not meta["title"]:
                        meta["title"] = _decode_text(val, encoding).strip()
                if authors:
                    meta["author"] = "、".join(a for a in authors if a)
    except Exception:
        pass
    if not meta["title"]:
        meta["title"] = full_name
    meta["full_name"] = full_name

    # 拼接文本记录
    chunks: list[bytes] = []
    last_limit = max(text_len - rec_size * (text_rec_cnt - 1), 1)
    for i in range(1, text_rec_cnt + 1):
        rd = data[offs[i] : offs[i + 1]]
        limit = rec_size if i < text_rec_cnt else last_limit
        if compression == 2:
            chunks.append(_palmdoc_decompress(rd, limit))
        else:
            chunks.append(rd[:limit])
    return _decode_text(b"".join(chunks), encoding), meta


# ---- HTML → blocks

def _inline_text(el) -> str:
    parts: list[str] = []

    def walk(n):
        name = getattr(n, "name", None)
        if name is None:  # NavigableString
            parts.append(str(n))
            return
        if name == "br":
            parts.append("\n")
            return
        if name in ("img", "script", "style"):
            return
        for c in getattr(n, "children", ()):
            walk(c)

    walk(el)
    s = "".join(parts).replace("\xa0", " ")
    s = re.sub(r"[ \t]+\n", "\n", s)
    s = re.sub(r"\n[ \t]+", "\n", s)
    # 块内连续空行压成一个换行
    s = re.sub(r"\n{3,}", "\n\n", s)
    return s.strip()


def _is_link_only(p) -> bool:
    a_text = "".join(a.get_text() for a in p.find_all("a"))
    total = p.get_text()
    return bool(p.find("a")) and norm(a_text) == norm(total) and len(norm(total)) <= 60


def _is_verse_p(p, text: str) -> bool:
    attrs = p.attrs or {}
    h = str(attrs.get("height", ""))
    w = str(attrs.get("width", ""))
    return 'height="8pt"' in f'height="{h}"' and "2em" in w and len(norm(text)) <= 60


def _centered(p) -> bool:
    return str((p.attrs or {}).get("align", "")).lower() == "center"


def _whole_bold_or_big(p) -> bool:
    """整段加粗，或整段包在 size≥6 的 font 里（非居中标题常见写法）。"""
    txt = norm(p.get_text())
    if not txt or len(txt) > HEADING_LEN_MAX:
        return False
    if p.find(["b", "strong"]) is not None and norm(p.find(["b", "strong"]).get_text()) == txt:
        return True
    fonts = p.find_all("font")
    if fonts and all(norm(f.get_text()) == txt for f in fonts):
        try:
            sizes = [int(str(f.get("size", "0"))) for f in fonts]
            return all(s >= 6 for s in sizes)
        except ValueError:
            return False
    return False


def _centered_is_big(p) -> bool:
    fonts = p.find_all("font")
    if not fonts:
        return False
    try:
        return all(int(str(f.get("size", "0"))) >= 7 for f in fonts if f.get_text().strip())
    except ValueError:
        return False


def html_to_blocks(html: str) -> list[dict]:
    if BeautifulSoup is None:
        raise MobiError("mobi 转换需要 beautifulsoup4（pip install beautifulsoup4）")
    soup = BeautifulSoup(html, "html.parser")
    for t in soup(["head", "guide", "reference", "script", "style", "title"]):
        t.decompose()
    body = soup.body or soup

    elems = body.find_all(["p", "h1", "h2", "h3", "h4", "h5", "h6", "mbp:pagebreak"])
    if not any(getattr(e, "name", "") in ("p", "h1", "h2", "h3", "h4", "h5", "h6") for e in elems):
        # 兜底：没有块级标签，按行切
        lines = [ln.strip() for ln in body.get_text("\n").split("\n")]
        return [{"kind": "p", "text": ln} for ln in lines if ln]

    blocks: list[dict] = []
    pagebreak_seen = False
    for el in elems:
        name = el.name or ""
        if name == "mbp:pagebreak":
            pagebreak_seen = True
            continue
        if name.startswith("h") and len(name) == 2 and name[1].isdigit():
            t = _inline_text(el)
            if t:
                blocks.append({"kind": "chapter", "text": t})
            continue
        # <p>
        p = el
        text = _inline_text(p)
        if not text:
            continue
        if _is_link_only(p):
            blocks.append({"kind": "link", "text": text})
            continue
        tnorm = norm(text).rstrip("·‧.。:：")
        if tnorm in TOC_NAMES:
            blocks.append({"kind": "toc-h", "text": text})
            continue
        if not pagebreak_seen:
            blocks.append({"kind": "front", "text": text})
            continue
        if _centered(p):
            kind = classify_heading(text)
            if kind:
                blocks.append({"kind": kind, "text": text})
            elif _centered_is_big(p) or p.find(["b", "strong"]) is not None:
                # 居中大字/加粗但模式未命中（译本序、无编号章题等）→ 结构标题
                blocks.append({"kind": "special", "text": text})
            else:
                blocks.append({"kind": "italic", "text": text})
            continue
        if _whole_bold_or_big(p):
            kind = classify_heading(text) or "chapter"
            if kind == "part":
                blocks.append({"kind": "part", "text": text})
            else:
                blocks.append({"kind": kind, "text": text})
            continue
        if _is_verse_p(p, text):
            blocks.append({"kind": "verse", "lines": text.split("\n")})
            continue
        blocks.append({"kind": "p", "text": text})
    return blocks


def merge_verses(blocks: list[dict]) -> list[dict]:
    """连续 verse 块合并成一段硬换行诗。"""
    out: list[dict] = []
    for b in blocks:
        if b["kind"] == "verse" and out and out[-1]["kind"] == "verse":
            out[-1]["lines"].extend(b["lines"])
        else:
            out.append(b if b["kind"] != "verse" else {"kind": "verse", "lines": list(b["lines"])})
    return out


def drop_toc_runs(blocks: list[dict]) -> list[dict]:
    """连续 ≥3 个纯目录链接块丢弃；前面的「目录」标题一并丢弃。"""
    out: list[dict] = []
    i = 0
    n = len(blocks)
    while i < n:
        if blocks[i]["kind"] != "link":
            out.append(blocks[i])
            i += 1
            continue
        j = i
        while j < n and blocks[j]["kind"] == "link":
            j += 1
        if j - i >= 3:
            if out and out[-1]["kind"] == "toc-h":
                out.pop()
        else:
            out.extend(blocks[i:j])
        i = j
    return out


def drop_front_dup(blocks: list[dict], title: str, author: str) -> list[dict]:
    """扉页行与元信息重复的丢掉（如扉页上的书名）。"""
    tnorm = norm(title)
    anorm = norm(author)
    out = []
    for b in blocks:
        if b["kind"] == "front":
            ln = norm(b["text"])
            if ln and len(ln) >= 2 and (
                ln == tnorm
                or ln in tnorm
                or tnorm in ln
                or ln == anorm
            ):
                continue
        out.append(b)
    return out


def front_to_italic(blocks: list[dict]) -> list[dict]:
    for b in blocks:
        if b["kind"] == "front":
            b["kind"] = "italic"
    return blocks


def mobi_to_md_text(data: bytes, fallback_title: str) -> tuple[str, dict]:
    html, meta = parse_mobi(data)
    title = meta.get("title") or fallback_title
    author = meta.get("author") or ""
    blocks = html_to_blocks(html)
    blocks = drop_toc_runs(blocks)
    blocks = drop_front_dup(blocks, title, author)
    blocks = front_to_italic(blocks)
    blocks = merge_verses(blocks)
    md = assemble_md(blocks, title=title, author=author or None)
    return md, meta


# ---------------------------------------------------------------- CLI

TXT_EXTS = {".txt", ".text", ".md"}
MOBI_EXTS = {".mobi", ".azw", ".azw3", ".prc"}


def collect_inputs(paths: list[str]) -> list[Path]:
    files: list[Path] = []
    for p in paths:
        path = Path(p)
        if path.is_dir():
            for sub in sorted(path.rglob("*")):
                if sub.is_file() and sub.suffix.lower() in TXT_EXTS | MOBI_EXTS:
                    files.append(sub)
        elif path.is_file():
            files.append(path)
        else:
            raise SystemExit(f"找不到输入：{path}")
    return files


def convert_file(src: Path, out_path: Path, force_title: str | None = None) -> tuple[int, str]:
    ext = src.suffix.lower()
    raw = src.read_bytes()
    # 扩展名之外再嗅探一次（.txt 里塞了 mobi 之类）
    if raw[:68][60:68] == b"BOOKMOBI" or ext in MOBI_EXTS:
        md, _meta = mobi_to_md_text(raw, force_title or src.stem)
    else:
        text = read_text_file(src)
        blocks = txt_to_blocks(text)
        md = assemble_md(blocks, title=None)
        if force_title:
            md = f"# {force_title}\n\n" + md
    write_md(out_path, md)
    chars = len(re.sub(r"\s", "", md))
    headings = len(re.findall(r"^#{1,6} ", md, re.M))
    return chars, headings


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(
        description="txt / mobi 转 Markdown（小说向）",
        epilog="示例：python tools/ebook2md.py 书.txt 书.mobi --out-dir ./md",
    )
    ap.add_argument("inputs", nargs="+", help="输入文件或目录")
    ap.add_argument("-o", "--output", help="输出 .md 路径（仅单个输入时可用）")
    ap.add_argument("--out-dir", help="输出目录（多文件时用）")
    ap.add_argument("--title", help="覆盖文首一级标题")
    args = ap.parse_args(argv)

    files = collect_inputs(args.inputs)
    if not files:
        raise SystemExit("没有可转换的文件（支持 .txt/.md/.mobi/.azw/.azw3/.prc）")
    if args.output and args.out_dir:
        ap.error("-o 与 --out-dir 不能同时用")
    if args.output and len(files) > 1:
        ap.error(f"-o 只能用于单个输入（当前 {len(files)} 个文件）；多文件请用 --out-dir")

    ok = 0
    for src in files:
        if args.output:
            out = Path(args.output)
        elif args.out_dir:
            out = Path(args.out_dir) / (src.stem + ".md")
        else:
            out = src.with_suffix(".md")
        try:
            chars, headings = convert_file(src, out, force_title=args.title)
        except (MobiError, OSError) as e:
            print(f"[失败] {src.name}: {e}", file=sys.stderr)
            continue
        print(f"[完成] {src.name} -> {out}（约 {chars} 字，{headings} 个标题）")
        ok += 1
    if ok != len(files):
        print(f"成功 {ok}/{len(files)}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
