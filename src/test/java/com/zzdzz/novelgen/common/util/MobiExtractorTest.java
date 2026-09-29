package com.zzdzz.novelgen.common.util;

import com.zzdzz.novelgen.common.web.BizException;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** MOBI 提取纯函数基线：PalmDOC 解压/trailing 剥除/HTML 剥取/DRM 拒绝。 */
class MobiExtractorTest {

    @Test
    void palmDocLiteralsAndSpaceCombos() {
        // 0x41 直出 'A'；0xE1 → 空格+'a'；0x00 直出 NUL；0xC3 → 空格+'C'
        byte[] in = {(byte) 0x41, (byte) 0xE1, 0x00, (byte) 0xC3};
        byte[] out = MobiExtractor.decompressPalmDoc(in);
        assertThat(new String(out, StandardCharsets.ISO_8859_1)).isEqualTo("A a\u0000 C");
    }

    @Test
    void palmDocLiteralRun() {
        // 0x03 = 后随 3 个字面量；'x' 是下一个 token 的字面量照常解出
        byte[] in = {0x03, 'h', 'i', '!', 'x'};
        byte[] out = MobiExtractor.decompressPalmDoc(in);
        assertThat(new String(out, StandardCharsets.ISO_8859_1)).isEqualTo("hi!x");
    }

    @Test
    void palmDocBackReferenceWithOverlap() {
        // "abc" 后跟回抄对：pair=(distance<<3)|(len-3)=(3<<3)|2=0x1A → 编码 0x80,0x1A；
        // distance=3 重叠回抄 5 字节 → 总输出 abc+abcab
        byte[] in = {'a', 'b', 'c', (byte) 0x80, 0x1A};
        byte[] out = MobiExtractor.decompressPalmDoc(in);
        assertThat(new String(out, StandardCharsets.ISO_8859_1)).isEqualTo("abcabcab");
    }

    @Test
    void trailingEntriesStrippedByFlags() {
        // extraFlags=0x2：条目=数据在前、varint(条目总长=4)在尾 → 'X''Y''Z'+0x84，正文 "OK"
        byte[] rec = {'O', 'K', 'X', 'Y', 'Z', (byte) 0x84};
        byte[] out = MobiExtractor.trimTrailingEntries(rec, 0x2);
        assertThat(new String(out, StandardCharsets.ISO_8859_1)).isEqualTo("OK");
    }

    @Test
    void multibyteOverlapStripped() {
        // extraFlags=0x1：尾字节低 2 位=1 → 剥 2 字节（尾字节自身+1 字节），正文 "AB"
        byte[] rec = {'A', 'B', 'c', 0x01};
        byte[] out = MobiExtractor.trimTrailingEntries(rec, 0x1);
        assertThat(new String(out, StandardCharsets.ISO_8859_1)).isEqualTo("AB");
    }

    @Test
    void htmlStrippedToPlainLines() {
        String html = "<html><head><style>body{}</style></head><body>"
                + "<p>第一段&nbsp;文字。</p><p>第二段&amp;收束</p><div>结束<br/>了</div></body></html>";
        String text = MobiExtractor.htmlToText(html);
        assertThat(text).isEqualTo("第一段 文字。\n第二段&收束\n结束\n了");
    }

    @Test
    void drmFileRejectedWithHumanMessage() {
        byte[] file = new byte[200];
        file[76] = 0;
        file[77] = 2;                      // numRecords=2
        file[78] = 0;
        file[79] = 0;
        file[80] = 0;
        file[81] = (byte) 132;             // offsets[0]=132
        file[86] = 0;
        file[87] = 0;
        file[88] = 0;
        file[89] = (byte) 200;             // offsets[1]=200（rec0 覆盖 132..200）
        file[132 + 12] = 0;
        file[132 + 13] = 1;                // encryption=1 → DRM
        assertThatThrownBy(() -> MobiExtractor.extract(file))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("DRM");
    }
}
