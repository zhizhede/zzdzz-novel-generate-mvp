package com.zzdzz.novelgen.model.enums;

/**
 * 章状态机（chapters.status 列口径）。wire 值与 DB/前端逐字一致；
 * DO 字段保持 String，service 层用本枚举具名比较与赋值。
 */
public enum ChapterStatus {
    NEW("NEW"),
    OUTLINED("OUTLINED"),
    OUTLINE_APPROVED("OUTLINE_APPROVED"),
    GATE_MECHANICAL("GATE_MECHANICAL"),
    GATE_AI_REVIEW("GATE_AI_REVIEW"),
    REVISING("REVISING"),
    PENDING_APPROVAL("PENDING_APPROVAL"),
    DIGESTED("DIGESTED"),
    FAILED("FAILED"),
    INTERRUPTED("INTERRUPTED");

    private final String wire;

    ChapterStatus(String wire) {
        this.wire = wire;
    }

    public String wire() {
        return wire;
    }

    /** 与 DB 读出的字符串比较（替代手写 "X".equals(...)）。 */
    public boolean is(String status) {
        return wire.equals(status);
    }
}
