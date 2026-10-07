package com.zzdzz.novelgen.model.enums;

/** 门禁/评审报告类型（gate_reports.gate_type 列口径）。 */
public enum GateType {
    MECHANICAL("mechanical"),
    READER_REVIEW("reader_review"),
    AI_REVIEW("ai_review");

    private final String wire;

    GateType(String wire) {
        this.wire = wire;
    }

    public String wire() {
        return wire;
    }

    public boolean is(String type) {
        return wire.equals(type);
    }
}
