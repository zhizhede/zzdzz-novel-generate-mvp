package com.zzdzz.novelgen.common.util;

import com.zzdzz.novelgen.common.web.BizException;
import com.zzdzz.novelgen.common.web.ErrorCode;

/**
 * 上传文档 → 正文纯文本的统一入口：按**文件头**分派（不是按扩展名——拖拽进来的文件扩展名可被伪造/缺失）。
 * 支持的形态：OOXML（zip，即 .docx/.docm）→ {@link DocxExtractor}；MOBI/AZW → {@link MobiExtractor}。
 * 明确拒绝并给人话出路：旧版 .doc（OLE2 二进制）需 Word 另存为 .docx/.txt（解析二进制 doc 要引入 POI，不值得）。
 */
public final class DocumentTextExtractor {

    private static final byte[] ZIP_MAGIC = {0x50, 0x4B, 0x03, 0x04};
    private static final byte[] OLE2_MAGIC = {(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0};

    private DocumentTextExtractor() {
    }

    /**
     * 前端上传的 base64（裸串或 data URL）→ 正文纯文本。
     * 体积上限由调用方给（不同导入入口的业务上限可不同），超限报人话错误。
     */
    public static String extractFromBase64(String base64OrDataUrl, long maxBytes) {
        if (base64OrDataUrl == null || base64OrDataUrl.isBlank()) {
            throw new BizException(ErrorCode.PARAM_ERROR, "文件内容为空，请重新选择文件");
        }
        String payload = base64OrDataUrl.contains(",")
                ? base64OrDataUrl.substring(base64OrDataUrl.indexOf(',') + 1)
                : base64OrDataUrl;
        byte[] file;
        try {
            file = java.util.Base64.getDecoder().decode(payload);
        } catch (IllegalArgumentException e) {
            throw new BizException(ErrorCode.PARAM_ERROR, "文件解码失败，请重新选择文件");
        }
        if (file.length > maxBytes) {
            throw new BizException(ErrorCode.PARAM_ERROR, "文件过大（>" + (maxBytes / 1024 / 1024) + "MB）");
        }
        return extract(file);
    }

    /** 文件头分派（纯函数）：zip → docx；OLE2 → 旧版 doc 拒绝；其余按 MOBI/AZW 解析。 */
    public static String extract(byte[] file) {
        if (file == null || file.length < 8) {
            throw new BizException(ErrorCode.PARAM_ERROR, "文件过小，无法识别格式");
        }
        if (startsWith(file, ZIP_MAGIC)) {
            return DocxExtractor.extract(file);
        }
        if (startsWith(file, OLE2_MAGIC)) {
            throw new BizException(ErrorCode.PARAM_ERROR,
                    "这是旧版 .doc（二进制格式），暂不支持——请用 Word 打开后「另存为」.docx 或 .txt 再导入");
        }
        return MobiExtractor.extract(file).text();
    }

    private static boolean startsWith(byte[] file, byte[] magic) {
        if (file.length < magic.length) {
            return false;
        }
        for (int i = 0; i < magic.length; i++) {
            if (file[i] != magic[i]) {
                return false;
            }
        }
        return true;
    }
}
