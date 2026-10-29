package com.zzdzz.novelgen.model.entity.derive;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 题材能力包：通用角色业务模型的可选题材附加规则。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("derive_genre_capability")
public class DeriveGenreCapabilityDO extends DeriveBaseDO {
    private Long runId;
    private String capabilityKey;
    private Boolean enabled;
    private String requiredContract;
    private String forbiddenRules;
    private String eventRoles;
    private String validationRules;
    private Integer priority;
}