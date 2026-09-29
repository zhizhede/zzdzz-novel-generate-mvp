package com.zzdzz.novelgen.common.util;

import com.zzdzz.novelgen.common.web.BizException;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 文档提取：docx（OOXML）正文还原 + 文件头分派（按字节而不是扩展名判类型）。 */
class DocumentTextExtractorTest {

    /** 造一个最小 OOXML docx：zip 里只有 word/document.xml（真实 docx 的正文就在这个条目）。 */
    private static byte[] docx(String bodyXml) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("[Content_Types].xml"));
            zip.write("<Types/>".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("word/document.xml"));
            zip.write(("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                    + "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">"
                    + "<w:body>" + bodyXml + "</w:body></w:document>").getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return out.toByteArray();
    }

    private static String p(String text) {
        return "<w:p><w:r><w:t>" + text + "</w:t></w:r></w:p>";
    }

    @Test
    void docxParagraphsBecomeLines() throws Exception {
        byte[] file = docx(p("第一段落") + p("第二段落"));

        assertThat(DocumentTextExtractor.extract(file)).isEqualTo("第一段落\n第二段落");
    }

    @Test
    void docxKeepsBrAsNewlineAndSkipsTrackedDeletionsAndFieldCodes() throws Exception {
        String body = "<w:p><w:r><w:t>开头</w:t><w:br/><w:t>换行后</w:t></w:r></w:p>"
                + "<w:p><w:del><w:r><w:t>删掉的字</w:t></w:r></w:del></w:p>"
                + "<w:p><w:r><w:instrText>PAGE</w:instrText><w:t>正文</w:t></w:r></w:p>"
                + "<w:p><w:r><w:t>前</w:t><w:tab/><w:t>后</w:t></w:r></w:p>";

        String text = DocumentTextExtractor.extract(docx(body));

        assertThat(text)
                .contains("开头\n换行后")
                .doesNotContain("删掉的字")   // 修订删除的内容不进正文
                .doesNotContain("PAGE")       // 域代码指令不进正文
                .contains("正文")
                .contains("前\t后");            // 行内制表保留；行首缩进制表会在收尾整理时去掉（Word 缩进不是正文）
    }

    @Test
    void lineEdgeWhitespaceAndIndentTabsAreTrimmed() throws Exception {
        String text = DocumentTextExtractor.extract(docx(
                "<w:p><w:r><w:tab/><w:t>缩进段落</w:t></w:r></w:p>" + p("  两边空白  ")));

        assertThat(text).isEqualTo("缩进段落\n两边空白");
    }

    @Test
    void docxCollapsesBlankParagraphs() throws Exception {
        // Word 文档里空段落很常见：不整理会灌进成百上千空行
        String text = DocumentTextExtractor.extract(docx(p("甲") + p("") + p("   ") + p("乙")));
        assertThat(text).isEqualTo("甲\n\n乙");
    }

    @Test
    void zipWithoutDocumentXmlRejected() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("other.xml"));
            zip.write("<x/>".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        assertThatThrownBy(() -> DocumentTextExtractor.extract(out.toByteArray()))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("word/document.xml");
    }

    @Test
    void legacyDocRejectedWithHumanAdvice() {
        byte[] ole2 = new byte[512];
        byte[] magic = {(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1};
        System.arraycopy(magic, 0, ole2, 0, magic.length);

        assertThatThrownBy(() -> DocumentTextExtractor.extract(ole2))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("旧版 .doc")
                .hasMessageContaining("另存为");
    }

    @Test
    void nonZipNonOle2FallsThroughToMobiParser() {
        // 既不是 zip 也不是 OLE2 → 交给 MOBI 解析器，它会给自己的「不是有效 MOBI」错误（而不是静默返回空）
        byte[] garbage = "这不是任何受支持的文档格式，只是一段普通文字。".getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> DocumentTextExtractor.extract(garbage))
                .isInstanceOf(BizException.class);
    }

    @Test
    void base64DataUrlAndSizeGuard() throws Exception {
        byte[] file = docx(p("正文"));
        String dataUrl = "data:application/vnd.openxmlformats-officedocument.wordprocessingml.document;base64,"
                + java.util.Base64.getEncoder().encodeToString(file);

        assertThat(DocumentTextExtractor.extractFromBase64(dataUrl, 1024 * 1024)).isEqualTo("正文");
        // 裸 base64（无 data URL 前缀）同样可用
        assertThat(DocumentTextExtractor.extractFromBase64(java.util.Base64.getEncoder().encodeToString(file),
                1024 * 1024)).isEqualTo("正文");
        assertThatThrownBy(() -> DocumentTextExtractor.extractFromBase64(dataUrl, 10))
                .isInstanceOf(BizException.class).hasMessageContaining("过大");
        assertThatThrownBy(() -> DocumentTextExtractor.extractFromBase64("", 1024))
                .isInstanceOf(BizException.class).hasMessageContaining("为空");
    }
}
