package com.zzdzz.novelgen.model.vo;

import java.time.OffsetDateTime;

/** 提示词列表行（不含正文，正文走详情接口）。 */
public record PromptTemplateVO(long id, String node, String phase, String title,
        boolean exact, int version, boolean custom, boolean enabled, int contentLength,
        OffsetDateTime updateTime) {}
