package com.zzdzz.novelgen.model.enums;

/** 步骤状态行状态（chapter_steps.status 列口径）。 */
public enum StepStatus {
    RUNNING("RUNNING"),
    DONE("DONE"),
    FAILED("FAILED"),
    INTERRUPTED("INTERRUPTED");

    private final String wire;

    StepStatus(String wire) {
        this.wire = wire;
    }

    public String wire() {
        return wire;
    }

    public boolean is(String status) {
        return wire.equals(status);
    }
}
