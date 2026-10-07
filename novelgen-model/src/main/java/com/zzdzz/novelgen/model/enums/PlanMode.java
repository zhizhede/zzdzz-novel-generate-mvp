package com.zzdzz.novelgen.model.enums;

/** 规划/审批模式（novels.plan_mode、approval_mode 口径）。 */
public enum PlanMode {
    AUTO("auto"),
    MANUAL("manual");

    private final String wire;

    PlanMode(String wire) {
        this.wire = wire;
    }

    public String wire() {
        return wire;
    }

    public boolean is(String mode) {
        return wire.equals(mode);
    }
}
