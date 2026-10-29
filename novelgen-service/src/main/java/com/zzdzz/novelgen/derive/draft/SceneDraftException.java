package com.zzdzz.novelgen.derive.draft;

/** 单场正文的稳定失败码与精确问题；scene retry 只把本场问题反馈给本场。 */
public final class SceneDraftException extends RuntimeException {

    private final String code;

    /** 创建带稳定 code 的场景失败。 */
    public SceneDraftException(String code, String message) {
        super(code + "：" + message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
