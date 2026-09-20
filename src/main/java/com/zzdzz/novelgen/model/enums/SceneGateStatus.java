package com.zzdzz.novelgen.model.enums;

/** 场景门禁状态（chapter_scenes.gate_status 列口径）。 */
public enum SceneGateStatus {
    PASSED("PASSED"),
    FAILED("FAILED");

    private final String wire;

    SceneGateStatus(String wire) {
        this.wire = wire;
    }

    public String wire() {
        return wire;
    }

    public boolean is(String status) {
        return wire.equals(status);
    }
}
