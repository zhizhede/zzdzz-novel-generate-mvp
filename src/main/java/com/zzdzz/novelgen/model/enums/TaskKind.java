package com.zzdzz.novelgen.model.enums;

/** 队列任务类型（generation_tasks.kind 列口径）。 */
public enum TaskKind {
    CHAPTERS("CHAPTERS"),
    PLAN("PLAN");

    private final String wire;

    TaskKind(String wire) {
        this.wire = wire;
    }

    public String wire() {
        return wire;
    }

    public boolean is(String kind) {
        return wire.equals(kind);
    }
}
