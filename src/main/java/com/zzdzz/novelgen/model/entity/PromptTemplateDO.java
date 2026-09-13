package com.zzdzz.novelgen.model.entity;

import java.time.OffsetDateTime;

public record PromptTemplateDO(Long id, String node, String phase, String title,
        String content, boolean exact, int version, boolean custom, boolean enabled,
        boolean isDeleted, OffsetDateTime updateTime) {}
