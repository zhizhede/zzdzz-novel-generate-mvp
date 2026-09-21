package com.zzdzz.novelgen.model.enums;

/** 队列任务状态（generation_tasks.status 列口径）。 */
public enum TaskStatus {
    QUEUED("QUEUED"),
    RUNNING("RUNNING"),
    DONE("DONE"),
    STOPPED("STOPPED"),
    CANCELED("CANCELED"),
    INTERRUPTED("INTERRUPTED"),
    PAUSED("PAUSED");

    private final String wire;

    TaskStatus(String wire) {
        this.wire = wire;
    }

    public String wire() {
        return wire;
    }

    public boolean is(String status) {
        return wire.equals(status);
    }
}
