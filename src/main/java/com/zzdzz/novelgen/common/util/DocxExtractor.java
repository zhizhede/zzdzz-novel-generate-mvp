package com.zzdzz.novelgen.common.util;

import com.zzdzz.novelgen.common.web.BizException;
import com.zzdzz.novelgen.common.web.ErrorCode;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * .docx/.docm（OOXML）正文提取器：docx 本质是 zip，正文在 {@code word/document.xml}，
 * 按段落（w:p）与文本运行（w:t）取字，w:br/w:tab 保留换行与制表，删除线（w:del）与域代码（w:instrText）跳过。
 * 只依赖 JDK（zip + StAX），纯静态、无第三方库；旧版 .doc（OLE2 二进制）不支持，见 {@link DocumentTextExtractor}。
 */
public final class DocxExtractor {

    /** 正文 XML 单条目上限：正常小说文档几 MB 以内，超此值必是异常文件。 */
    private static final int MAX_XML_BYTES = 64 * 1024 * 1024;

    /** 只跳过「不是正文」的元素：修订删除内容、域代码指令。 */
    private static final Set<String> SKIP_ELEMENTS = Set.of("del", "delText", "instrText");

    private DocxExtractor() {
    }

    /** 从 docx 字节提取正文纯文本（段落之间换行）；非 OOXML/缺正文条目抛 A0001 人话错误。 */
    public static String extract(byte[] file) {
        byte[] documentXml = readEntry(file, "word/document.xml");
        if (documentXml == null) {
            throw new BizException(ErrorCode.PARAM_ERROR,
                    "不是有效的 .docx（压缩包里找不到 word/document.xml）——请用 Word 另存为 .docx 或 .txt 再导入");
        }
        return xmlToText(documentXml);
    }

    /** zip 内按名取条目（大小写不敏感；找不到返回 null）。 */
    private static byte[] readEntry(byte[] file, String wanted) {
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(file))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory() || !wanted.equalsIgnoreCase(entry.getName())) {
                    continue;
                }
                return readAll(zip);
            }
            return null;
        } catch (Exception e) {
            throw new BizException(ErrorCode.PARAM_ERROR, "读取 .docx 失败（压缩包损坏？）：" + e.getMessage());
        }
    }

    private static byte[] readAll(InputStream in) throws java.io.IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(64 * 1024);
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
            if (out.size() > MAX_XML_BYTES) {
                throw new BizException(ErrorCode.PARAM_ERROR, "文档正文过大（超过 " + (MAX_XML_BYTES / 1024 / 1024) + "MB），请分段导入");
            }
        }
        return out.toByteArray();
    }

    /**
     * word/document.xml → 纯文本（纯函数，可单测）。
     * w:t 取字；w:p 结束补换行；w:br/w:cr 换行、w:tab 制表；w:del/w:instrText 整段跳过（修订痕迹与域代码不进正文）。
     */
    static String xmlToText(byte[] xml) {
        StringBuilder out = new StringBuilder(xml.length / 4);
        int skipDepth = 0;
        String skipRoot = null;
        boolean inText = false;
        try {
            XMLInputFactory factory = XMLInputFactory.newInstance();
            // 关掉外部实体解析：文档来自用户上传，防 XXE
            factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
            factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
            XMLStreamReader reader = factory.createXMLStreamReader(new ByteArrayInputStream(xml));
            while (reader.hasNext()) {
                int event = reader.next();
                if (event == XMLStreamConstants.START_ELEMENT) {
                    String name = reader.getLocalName();
                    if (skipRoot != null) {
                        skipDepth++;
                        continue;
                    }
                    if (SKIP_ELEMENTS.contains(name)) {
                        skipRoot = name;
                        skipDepth = 1;
                        continue;
                    }
                    if ("t".equals(name)) {
                        inText = true;
                    } else if ("br".equals(name) || "cr".equals(name)) {
                        out.append('\n');
                    } else if ("tab".equals(name)) {
                        out.append('\t');
                    }
                } else if (event == XMLStreamConstants.CHARACTERS || event == XMLStreamConstants.CDATA) {
                    if (inText && skipRoot == null) {
                        out.append(reader.getText());
                    }
                } else if (event == XMLStreamConstants.END_ELEMENT) {
                    String name = reader.getLocalName();
                    if (skipRoot != null) {
                        skipDepth--;
                        if (skipDepth == 0 && name.equals(skipRoot)) {
                            skipRoot = null;
                        }
                        continue;
                    }
                    if ("t".equals(name)) {
                        inText = false;
                    } else if ("p".equals(name)) {
                        out.append('\n');
                    }
                }
            }
            reader.close();
        } catch (Exception e) {
            throw new BizException(ErrorCode.PARAM_ERROR, "解析 .docx 正文失败（XML 结构异常）：" + e.getMessage());
        }
        return tidy(out.toString());
    }

    /** 收尾整理：行尾空白去掉、连续空行压成一个、整体去首尾空白（Word 里空段落很常见，不整理会灌进几百个空行）。 */
    static String tidy(String text) {
        String[] lines = text.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        StringBuilder out = new StringBuilder(text.length());
        boolean lastBlank = true;
        for (String line : lines) {
            String s = line.strip();
            if (s.isEmpty()) {
                if (lastBlank) {
                    continue;
                }
                lastBlank = true;
                out.append('\n');
            } else {
                lastBlank = false;
                out.append(s).append('\n');
            }
        }
        return out.toString().strip();
    }
}
