package com.zzdzz.novelgen.derive.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 按章标题行切章（纯函数，可单测）。
 *
 * <p><b>口径与 zzdzz {@code NovelService.splitChapters} 逐条对齐</b>（AGENTS 已知坑 #28：
 * 两条切章实现口径必须一致，前言并入首章——122 万字长源的整理者声明页眉若单独成章，
 * 整本书的章节映射会错位一章）：
 * <ul>
 *   <li>标题行本身不计入正文；首章前的残余文字（前言/页眉）并入第 1 章；完全没有标题行时整篇作第 1 章；</li>
 *   <li>三种标题样式：①「第N章」阿拉伯数字（无长度闸）；②「第X章」中文数字；③「X、标题」中文数字+顿号/点/冒号；
 *       ②③ 受 30 字长度闸保护（防「一、他想起……」正文行被误判）；</li>
 *   <li>章号不从 1 连续时按出现顺序重排 1..N；标题为空回退「第N章」。</li>
 * </ul>
 */
public final class TextSplitter {

    private TextSplitter() {
    }

    /** 「第N章」章标题（阿拉伯数字）。 */
    private static final Pattern CHAPTER_HEADING =
            Pattern.compile("^\\s*第\\s*(\\d{1,4})\\s*章\\s*(.*)$");

    /** 「第X章」章标题（中文数字：第一章 / 第十一章）。 */
    private static final Pattern CHAPTER_HEADING_CN =
            Pattern.compile("^\\s*第\\s*([一二三四五六七八九十百零两]{1,6})\\s*章\\s*(.*)$");

    /** 「X、标题」式分节（中文数字 + 顿号/点/冒号：一、登船 / 十二、漂流）。 */
    private static final Pattern SECTION_HEADING_CN =
            Pattern.compile("^\\s*([一二三四五六七八九十百零两]{1,4})\\s*[、.．，,:：]\\s*(.*)$");

    /** 中文数字标题样式的整行长度上限（同 zzdzz CN_HEADING_MAX_CHARS）。 */
    private static final int CN_HEADING_MAX_CHARS = 30;

    /** 切章结果（no=原章号或重排后章号；title 不含「第N章」前缀）。 */
    public record ChapterSlice(int no, String title, String content) {
    }

    /** 识别出的章标题行（no=解析出的章号；title=去掉编号后的标题）。 */
    private record Heading(int no, String title) {
    }

    /** 切章：标题行不计入正文；前言并入第 1 章；无标题整篇作第 1 章。 */
    public static List<ChapterSlice> split(String text) {
        String[] lines = text.split("\\r?\\n", -1);
        List<int[]> heads = new ArrayList<>();
        List<String> headTitles = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            Heading heading = parseHeading(lines[i]);
            if (heading != null) {
                heads.add(new int[]{i, heading.no()});
                headTitles.add(heading.title());
            }
        }
        if (heads.isEmpty()) {
            return List.of(new ChapterSlice(1, "第1章", text.strip()));
        }
        String preamble = String.join("\n", java.util.Arrays.copyOfRange(lines, 0, heads.get(0)[0])).strip();
        List<ChapterSlice> out = new ArrayList<>();
        for (int k = 0; k < heads.size(); k++) {
            int start = heads.get(k)[0];
            int end = k + 1 < heads.size() ? heads.get(k + 1)[0] : lines.length;
            String body = String.join("\n", java.util.Arrays.copyOfRange(lines, start + 1, end)).strip();
            if (k == 0 && !preamble.isEmpty()) {
                body = preamble + "\n\n" + body;
            }
            int no = heads.get(k)[1];
            String heading = headTitles.get(k);
            out.add(new ChapterSlice(no, heading.isEmpty() ? "第" + no + "章" : heading, body));
        }
        return out;
    }

    /** 章标题行识别（null=不是标题）；②③ 受长度闸保护。 */
    static Heading parseHeading(String line) {
        if (line == null || line.isBlank()) {
            return null;
        }
        String s = line.strip();
        Matcher m = CHAPTER_HEADING.matcher(s);
        if (m.matches()) {
            return new Heading(Integer.parseInt(m.group(1)), group2(m));
        }
        if (s.length() > CN_HEADING_MAX_CHARS) {
            return null;
        }
        m = CHAPTER_HEADING_CN.matcher(s);
        if (m.matches()) {
            return new Heading(chineseToInt(m.group(1)), group2(m));
        }
        m = SECTION_HEADING_CN.matcher(s);
        if (m.matches()) {
            return new Heading(chineseToInt(m.group(1)), group2(m));
        }
        return null;
    }

    private static String group2(Matcher m) {
        return m.group(2) == null ? "" : m.group(2).strip();
    }

    /** 中文数字转整数（一→1、十一→11、二十一→21、一百→100）；识别不出回 0，由重排兜底。 */
    static int chineseToInt(String cn) {
        Map<Character, Integer> digits = Map.ofEntries(
                Map.entry('零', 0), Map.entry('一', 1), Map.entry('二', 2),
                Map.entry('两', 2), Map.entry('三', 3), Map.entry('四', 4),
                Map.entry('五', 5), Map.entry('六', 6), Map.entry('七', 7),
                Map.entry('八', 8), Map.entry('九', 9));
        int section = 0;
        int number = 0;
        for (char c : cn.toCharArray()) {
            Integer d = digits.get(c);
            if (d != null) {
                number = d;
            } else if (c == '十') {
                section += (number == 0 ? 1 : number) * 10;
                number = 0;
            } else if (c == '百') {
                section += (number == 0 ? 1 : number) * 100;
                number = 0;
            }
        }
        return section + number;
    }

    /** 章号重排（原地）：已是 1..N 则不动返回 false；否则按顺序重排（「第N章」占位标题一并改写）。 */
    public static boolean renumber(List<ChapterSlice> slices) {
        boolean already = true;
        for (int i = 0; i < slices.size(); i++) {
            if (slices.get(i).no() != i + 1) {
                already = false;
                break;
            }
        }
        if (already) {
            return false;
        }
        for (int i = 0; i < slices.size(); i++) {
            ChapterSlice s = slices.get(i);
            int next = i + 1;
            String title = ("第" + s.no() + "章").equals(s.title()) ? "第" + next + "章" : s.title();
            slices.set(i, new ChapterSlice(next, title, s.content()));
        }
        return true;
    }
}
