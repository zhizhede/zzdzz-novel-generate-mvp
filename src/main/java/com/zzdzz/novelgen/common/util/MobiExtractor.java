package com.zzdzz.novelgen.common.util;

import com.zzdzz.novelgen.common.web.BizException;
import com.zzdzz.novelgen.common.web.ErrorCode;

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * MOBI/AZW 电子书文本提取器（MOBI6/PalmDOC 变体）：PalmDB 头解析 → DRM 检测 → 逐记录解压
 * → trailing bytes 剥除 → HTML 转纯文本。纯静态工具，解压与剥取为纯函数可单测。
 * 已知边界：HUFF/CDIC 压缩（compression=17480，多见于 AZW）与 DRM 加密不支持——报人话错误让用户转 txt。
 */
public final class MobiExtractor {

    private static final int COMPRESSION_NONE = 1;
    private static final int COMPRESSION_PALMDOC = 2;
    private static final int COMPRESSION_HUFF = 17480;
    private static final int ENCODING_UTF8 = 65001;

    private MobiExtractor() {
    }

    /** 提取结果：纯文本 + 元信息（日志/提示用）。 */
    public record Extracted(String text, int textChars, int records) {
    }

    /** 从 mobi/azw 文件字节提取正文纯文本；不支持的变体抛 A0001 人话错误。 */
    public static Extracted extract(byte[] file) {
        if (file == null || file.length < 132) {
            throw new BizException(ErrorCode.PARAM_ERROR, "文件过小或不是有效的 MOBI 电子书");
        }
        int numRecords = u16(file, 76);
        if (numRecords < 2 || numRecords > 4096) {
            throw new BizException(ErrorCode.PARAM_ERROR, "不是有效的 MOBI 电子书（记录表异常）");
        }
        int[] offsets = new int[numRecords + 1];
        for (int i = 0; i < numRecords; i++) {
            offsets[i] = u32(file, 78 + i * 8);
        }
        offsets[numRecords] = file.length;

        byte[] rec0 = slice(file, offsets[0], offsets[1]);
        int compression = u16(rec0, 0);
        int textLength = u32(rec0, 4);
        int textRecords = u16(rec0, 8);
        int encryption = u16(rec0, 12);
        if (encryption != 0) {
            throw new BizException(ErrorCode.PARAM_ERROR,
                    "该电子书带 DRM 加密保护，无法解析——请用无 DRM 版本或转成 txt 再导入");
        }
        if (compression != COMPRESSION_NONE && compression != COMPRESSION_PALMDOC) {
            throw new BizException(ErrorCode.PARAM_ERROR,
                    compression == COMPRESSION_HUFF
                            ? "该电子书为 AZW/KF8 新格式变体，暂不支持——请转成 txt 再导入"
                            : "该电子书压缩变体暂不支持（compression=" + compression + "）——请转成 txt 再导入");
        }
        Charset charset = StandardCharsets.UTF_8;
        int extraFlags = 0;
        if (rec0.length >= 32 && "MOBI".equals(new String(rec0, 16, 4, StandardCharsets.US_ASCII))) {
            int headerLength = u32(rec0, 20);
            int encoding = u32(rec0, 28);
            if (encoding == 1252) {
                charset = Charset.forName("windows-1252");
            }
            if (headerLength >= 0xE4 && rec0.length >= 16 + 0xF4) {
                extraFlags = u16(rec0, 16 + 0xF2);
            }
        }
        if (textRecords < 1 || textRecords > numRecords - 1) {
            textRecords = numRecords - 1;
        }

        ByteArrayOutputStream html = new ByteArrayOutputStream(Math.max(textLength, 1024));
        for (int i = 1; i <= textRecords; i++) {
            byte[] rec = slice(file, offsets[i], offsets[i + 1]);
            byte[] trimmed = trimTrailingEntries(rec, extraFlags);
            html.writeBytes(compression == COMPRESSION_PALMDOC ? decompressPalmDoc(trimmed) : trimmed);
        }
        String text = htmlToText(html.toString(charset));
        if (text.length() < 200) {
            throw new BizException(ErrorCode.PARAM_ERROR,
                    "电子书解析出的正文过短（" + text.length() + " 字），可能是不支持的变体——请转成 txt 再导入");
        }
        return new Extracted(text, text.length(), textRecords);
    }

    /**
     * PalmDOC（LZ77 变体）解压，纯函数。
     * 口径：0x00=字节直出；0x01-0x08=后随 n 个字面量；0x09-0x7F=字面量；
     * 0x80-0xBF=与后一字节组成距离/长度对（距离 11 位、长度 3-10，从已解压尾部回抄，允许重叠）；
     * 0xC0-0xFF=空格 + (b&0x7F) 组合。
     */
    static byte[] decompressPalmDoc(byte[] in) {
        byte[] out = new byte[Math.max(in.length * 4, 4096)];
        int size = 0;
        int i = 0;
        while (i < in.length) {
            int b = in[i++] & 0xFF;
            if (b == 0x00) {
                out[size++] = 0;
            } else if (b <= 0x08) {
                for (int j = 0; j < b && i < in.length; j++) {
                    if (size == out.length) {
                        out = grow(out);
                    }
                    out[size++] = in[i++];
                }
            } else if (b <= 0x7F) {
                if (size == out.length) {
                    out = grow(out);
                }
                out[size++] = (byte) b;
            } else if (b <= 0xBF) {
                int pair = (b << 8) | (i < in.length ? in[i++] & 0xFF : 0);
                int distance = (pair >> 3) & 0x7FF;
                int length = (pair & 0x7) + 3;
                if (distance == 0 || distance > size) {
                    continue;
                }
                if (size + length > out.length) {
                    out = grow(out);
                }
                // 逐字节回抄（允许重叠：distance < length 时即游程复制）
                for (int j = 0; j < length; j++) {
                    out[size] = out[size - distance];
                    size++;
                }
            } else {
                if (size + 2 > out.length) {
                    out = grow(out);
                }
                out[size++] = 0x20;
                out[size++] = (byte) (b & 0x7F);
            }
        }
        return java.util.Arrays.copyOf(out, size);
    }

    private static byte[] grow(byte[] buf) {
        byte[] bigger = new byte[buf.length * 2];
        System.arraycopy(buf, 0, bigger, 0, buf.length);
        return bigger;
    }

    /**
     * 剥每条文本记录尾的 trailing entries（MOBI extra_data_flags）。条目布局=数据在前、
     * varint(条目总长)在尾部；自记录尾回读 varint 即得该条目总长。0x1=multibyte 重叠
     * （尾字节低 2 位给重叠字节数），其余 set 位（自低向高遍历）各剥一条。
     */
    static byte[] trimTrailingEntries(byte[] rec, int extraFlags) {
        int end = rec.length;
        int flags = extraFlags >> 1;
        while (flags != 0) {
            if ((flags & 1) != 0 && end > 0) {
                end -= sizeOfTrailingEntry(rec, end);
            }
            flags >>= 1;
        }
        if ((extraFlags & 1) != 0 && end > 0) {
            end -= (rec[end - 1] & 0x3) + 1;
        }
        if (end < 0 || end > rec.length) {
            return rec;
        }
        byte[] out = new byte[end];
        System.arraycopy(rec, 0, out, 0, end);
        return out;
    }

    /** 自 end 回读 varint（7 位组、高位 1 终止）＝该 trailing entry 总长。 */
    private static int sizeOfTrailingEntry(byte[] rec, int end) {
        int bitPos = 0;
        int result = 0;
        int pos = end;
        while (pos > 0) {
            int v = rec[pos - 1] & 0xFF;
            result |= (v & 0x7F) << bitPos;
            bitPos += 7;
            pos--;
            if ((v & 0x80) != 0 || bitPos >= 28) {
                return result;
            }
        }
        return result;
    }

    /** HTML → 纯文本：剥 style/script 块、块级标签转行、剥全部标签、解常见实体、压缩空行。纯函数。 */
    static String htmlToText(String html) {
        String s = html;
        s = s.replaceAll("(?is)<\\s*(style|script)[^>]*>.*?<\\s*/\\s*\\1\\s*>", "");
        s = s.replaceAll("(?i)<\\s*(br|/p|/div|/h[1-6]|/li|/tr)[^>]*>", "\n");
        s = s.replaceAll("(?i)<[^>]{1,4000}>", "");
        Map<String, String> entities = new LinkedHashMap<>();
        entities.put("&amp;", "&");
        entities.put("&lt;", "<");
        entities.put("&gt;", ">");
        entities.put("&quot;", "\"");
        entities.put("&apos;", "'");
        entities.put("&nbsp;", " ");
        entities.put("&#160;", " ");
        entities.put("&#8230;", "…");
        entities.put("&#8220;", "「");
        entities.put("&#8221;", "」");
        for (Map.Entry<String, String> e : entities.entrySet()) {
            s = s.replace(e.getKey(), e.getValue());
        }
        StringBuilder decoded = new StringBuilder(s.length());
        java.util.regex.Matcher em = java.util.regex.Pattern.compile("&#(\\d{1,5});").matcher(s);
        while (em.find()) {
            String rep;
            try {
                rep = String.valueOf((char) Integer.parseInt(em.group(1)));
            } catch (Exception ex) {
                rep = "";
            }
            em.appendReplacement(decoded, java.util.regex.Matcher.quoteReplacement(rep));
        }
        em.appendTail(decoded);
        s = decoded.toString();
        StringBuilder out = new StringBuilder(s.length());
        boolean lastNewline = true;
        // C0 控制字符清零（PalmDOC 原文的 NUL/设备控制符，PostgreSQL 拒收 0x00）
        s = s.replaceAll("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F]", "");
        for (String line : s.split("\n", -1)) {
            String t = line.replaceAll("[ \\t\\x0B\\f\\r]+", " ").strip();
            if (t.isEmpty()) {
                if (!lastNewline) {
                    out.append('\n');
                    lastNewline = true;
                }
                continue;
            }
            out.append(t).append('\n');
            lastNewline = false;
        }
        return out.toString().strip();
    }

    private static int u16(byte[] b, int off) {
        return ((b[off] & 0xFF) << 8) | (b[off + 1] & 0xFF);
    }

    private static int u32(byte[] b, int off) {
        return ((b[off] & 0xFF) << 24) | ((b[off + 1] & 0xFF) << 16) | ((b[off + 2] & 0xFF) << 8) | (b[off + 3] & 0xFF);
    }

    private static byte[] slice(byte[] b, int from, int to) {
        int lo = Math.max(0, from);
        int hi = Math.min(b.length, Math.max(lo, to));
        byte[] out = new byte[hi - lo];
        System.arraycopy(b, lo, out, 0, hi - lo);
        return out;
    }
}
