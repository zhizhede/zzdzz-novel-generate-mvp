package com.zzdzz.novelgen.model.entity;

public record SceneDO(Long id, long chapterId, int sceneNo, String goal, String present,
        String mustReveal, String mustNot, int wordsBudget, String draftText, String gateStatus,
        int revisionRound, boolean isDeleted) {}
