package com.zzdzz.novelgen.model.enums;

/** 伏笔台账状态机（foreshadows.status 列口径）：提议→计划→已埋→已收，可弃用。 */
public enum ForeshadowStatus {
    PROPOSED("proposed"),
    PLANNED("planned"),
    PLANTED("planted"),
    RECOVERED("recovered"),
    DROPPED("dropped");

    private final String wire;

    ForeshadowStatus(String wire) {
        this.wire = wire;
    }

    public String wire() {
        return wire;
    }

    public boolean is(String status) {
        return wire.equals(status);
    }
}
