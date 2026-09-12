package com.zzdzz.novelgen.model.entity;

/** tuning 表 DO：平台级行为参数（门禁阈值/重试轮数/提示词阈值等），SQL 只在 dao。 */
public record TuningDO(Long id, String key, String value, String description) {
}
