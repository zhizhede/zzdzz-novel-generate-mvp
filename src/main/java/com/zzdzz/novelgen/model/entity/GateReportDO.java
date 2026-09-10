package com.zzdzz.novelgen.model.entity;

public record GateReportDO(Long id, long chapterId, Long sceneId, String gateType,
        int round, boolean passed, String result, boolean isDeleted) {}
