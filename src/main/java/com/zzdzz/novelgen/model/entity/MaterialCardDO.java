package com.zzdzz.novelgen.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import java.util.List;
import lombok.Data;

/** 素材卡：设定层结构化实体（kind 区分角色/物品/地点/现象/地标/灾害/组织）。 */
@Data
@TableName(value = "material_cards")
public class MaterialCardDO {

    public static final String KIND_CHARACTER = "character";
    public static final String KIND_ITEM = "item";
    public static final String KIND_LOCATION = "location";
    public static final String KIND_LANDMARK = "landmark";
    public static final String KIND_PHENOMENON = "phenomenon";
    public static final String KIND_DISASTER = "disaster";
    public static final String KIND_ORG = "org";
    public static final String KIND_MISC = "misc";

    @TableId(type = IdType.AUTO)
    private Long id;
    private long novelId;
    private String kind;
    private String name;
        @TableField(typeHandler = com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler.class)
    private List<String> aliases;
    private String summary;
    private String contentMd;
    private boolean pinned;
    private String status;
    private Integer sourceChapter;
    private boolean isDeleted;








    @Deprecated
    public boolean pinned() {
        return isPinned();
    }



    public boolean active() {
        return !"dead".equals(status) && !"merged".equals(status) && !"retired".equals(status);
    }
}
