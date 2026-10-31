package com.zzdzz.novelgen.model.enums;

/**
 * 入库类型（novels.source_type 列口径）：这本书是怎么来的——用户上传导入，或系统直接生成。
 */
public enum NovelSourceType {
    /** 手动导入：用户上传 txt/mobi/azw 或粘贴正文，或用 CLI ImportRunner 导原稿。 */
    IMPORTED("IMPORTED"),
    /** 系统生成：未走样本衍生链的普通生成书。 */
    ORIGINAL("ORIGINAL");

    private final String wire;

    NovelSourceType(String wire) {
        this.wire = wire;
    }

    public String wire() {
        return wire;
    }

    public boolean is(String type) {
        return wire.equals(type);
    }

    /** 入库类型归一（大小写不敏感；空白或历史未知取值按纯原创兜底）。 */
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
}
