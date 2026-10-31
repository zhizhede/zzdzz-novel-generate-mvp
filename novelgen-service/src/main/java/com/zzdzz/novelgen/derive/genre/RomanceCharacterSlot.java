package com.zzdzz.novelgen.derive.genre;

/** 言情合同角色槽位；字段与悬疑 cast 分开建模。 */
public record RomanceCharacterSlot(String key, String name, String role,
                                   String gender, String identity) {

    public RomanceCharacterSlot {
        key = required(key, "key");
        name = required(name, "name");
        role = required(role, "role");
    }

    private static String required(String value, String field) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
        return normalized;
    }
}
