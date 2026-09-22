package com.zzdzz.novelgen.model.vo;

/** 题材预设行（is_preset 风格包模板）：应用到书 = 拷贝指纹/门禁/规则。 */
public record PresetVO(long id, String name, String description) {

    public static PresetVO from(com.zzdzz.novelgen.model.dto.StylePackDTO d) {
        return new PresetVO(d.getId(), d.getName(), d.getDescription());
    }
}
