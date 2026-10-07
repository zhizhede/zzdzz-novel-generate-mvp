package com.zzdzz.novelgen.model.enums;

/** 队列任务类型（generation_tasks.kind 列口径）。 */
public enum TaskKind {
    CHAPTERS("CHAPTERS"),
    PLAN("PLAN"),
    OUTLINE("OUTLINE"),
    /** 剧情换皮（RESKIN）：保留样本剧情骨架，把世界观/大纲与逐章章纲全部换成本书新外衣。 */
    RESKIN("RESKIN");

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
