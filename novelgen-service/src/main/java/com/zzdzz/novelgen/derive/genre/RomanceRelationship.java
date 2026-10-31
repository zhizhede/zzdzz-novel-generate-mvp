package com.zzdzz.novelgen.derive.genre;

/** 言情合同的主关系线。 */
public record RomanceRelationship(String threadKey, String leftKey, String rightKey,
                                  String kind, String title) {

    public RomanceRelationship {
        threadKey = required(threadKey, "threadKey");
        leftKey = required(leftKey, "leftKey");
        rightKey = required(rightKey, "rightKey");
        kind = required(kind, "kind");
        title = required(title, "title");
    }

    private static String required(String value, String field) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
        return normalized;
    }
}
