package com.zzdzz.novelgen.model.entity;

import java.util.List;

/** 素材卡：设定层结构化实体（kind 区分角色/物品/地点/现象/地标/灾害/组织）。 */
public record MaterialCardDO(Long id, long novelId, String kind, String name, List<String> aliases,
                             String summary, String contentMd, boolean pinned, String status,
                             Integer sourceChapter, boolean isDeleted) {

    public static final String KIND_CHARACTER = "character";
    public static final String KIND_ITEM = "item";
    public static final String KIND_LOCATION = "location";
    public static final String KIND_LANDMARK = "landmark";
    public static final String KIND_PHENOMENON = "phenomenon";
    public static final String KIND_DISASTER = "disaster";
    public static final String KIND_ORG = "org";
    public static final String KIND_MISC = "misc";

    public boolean active() {
        return !"dead".equals(status) && !"merged".equals(status) && !"retired".equals(status);
    }
}
