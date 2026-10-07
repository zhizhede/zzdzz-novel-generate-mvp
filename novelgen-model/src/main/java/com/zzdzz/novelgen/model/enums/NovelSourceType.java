package com.zzdzz.novelgen.model.enums;

/**
 * 入库类型（novels.source_type 列口径）：这本书是怎么来的——用户在书籍管理页上传 txt/mobi 导入、
 * 向导选了参考样本做衍生、还是向导无样本的纯 AI 原创。wire 值与 DB/前端逐字一致。
 */
public enum NovelSourceType {
    /** 手动导入：用户上传 txt/mobi/azw 或粘贴正文，或用 CLI ImportRunner 导原稿。 */
    IMPORTED("IMPORTED"),
    /** 系统衍生：向导选了参考样本（derive_config.sourceSampleId 非空）。 */
    DERIVED("DERIVED"),
    /** 系统纯原创：向导未选样本，AI 直接生成大纲与设定。 */
    ORIGINAL("ORIGINAL");

    private final String wire;

    NovelSourceType(String wire) {
        this.wire = wire;
    }

    public String wire() {
        return wire;
    }

    /** 与 DB 读出的字符串比较（替代手写 "X".equals(...)）。 */
    public boolean is(String type) {
        return wire.equals(type);
    }

    /** 入库类型入参/库值归一（大小写不敏感；空白或未知取值按纯原创兜底——派生列不该成为写入失败的原因）。 */
    public static String normalize(String type) {
        if (type == null || type.isBlank()) {
            return ORIGINAL.wire();
        }
        for (NovelSourceType t : values()) {
            if (t.wire.equalsIgnoreCase(type.strip())) {
                return t.wire();
            }
        }
        return ORIGINAL.wire();
    }

    /** 按「是否选了参考样本」判衍生/原创（开书路径的唯一判据）。 */
    public static String ofSample(Long sampleId) {
        return sampleId == null ? ORIGINAL.wire() : DERIVED.wire();
    }
}
