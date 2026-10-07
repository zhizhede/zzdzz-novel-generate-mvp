package com.zzdzz.novelgen.model.vo;

/** 提示词详情（含完整模板正文）。 */
public record PromptDetailVO(long id, String node, String phase, String title,
        String content, boolean exact, int version, boolean custom, boolean enabled) {}
