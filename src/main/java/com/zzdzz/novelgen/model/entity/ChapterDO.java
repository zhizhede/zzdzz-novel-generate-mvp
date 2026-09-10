package com.zzdzz.novelgen.model.entity;

public record ChapterDO(Long id, long novelId, int chapterNo, Integer volumeNo, String arc,
        String title, String pov, String outlineYaml, String fullText, String goal, String hook,
        String ruleRefs, String foreshadowRefs, int budgetMin, int budgetMax, String status,
        int round, boolean isDeleted) {}
