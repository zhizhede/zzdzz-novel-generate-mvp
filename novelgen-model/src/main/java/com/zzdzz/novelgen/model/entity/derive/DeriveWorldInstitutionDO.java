package com.zzdzz.novelgen.model.entity.derive;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 世界制度：实际影响角色选择的法律、宗教、技术或社会规范。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("derive_world_institution")
public class DeriveWorldInstitutionDO extends DeriveBaseDO {
    private Long runId;
    private String institutionKey;
    private String institutionType;
    private String name;
    private String lawOrDoctrine;
    private String enforcement;
    private String benefit;
    private String cost;
    private String affectedCharacters;
    private String contract;
}