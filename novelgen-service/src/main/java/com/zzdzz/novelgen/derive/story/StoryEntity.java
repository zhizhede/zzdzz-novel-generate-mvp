package com.zzdzz.novelgen.derive.story;

import java.util.Map;
import java.util.Objects;

/**
 * 通用故事实体：人物、组织、地点、物件、现象等可被合同引用的命名对象。
 *
 * <p>只承载跨类型通用信息，不包含凶手、动机、手法、揭示章等类型专属字段。
 */
public record StoryEntity(
        String key,
        String kind,
        String name,
        Map<String, Object> attributes,
        Integer introducedChapter) {

    public StoryEntity {
        key = requireText(key, "key");
        kind = requireText(kind, "kind");
        name = requireText(name, "name");
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }

    static String requireText(String value, String field) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
        return normalized;
    }
}
