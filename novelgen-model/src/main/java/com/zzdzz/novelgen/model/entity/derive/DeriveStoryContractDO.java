package com.zzdzz.novelgen.model.entity.derive;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 唯一故事合同：SPINE 前冻结，下游只能引用，不能重新发明真相/角色/线索。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("derive_story_contract")
public class DeriveStoryContractDO extends DeriveBaseDO {
    private Long runId;
    private Integer version;
    private String status;
    private String truthKernel;
    private String characterSlots;
    private String eventKernel;
    private String clueRegistry;
    private String knowledgeLedger;
    private String constraintKernel;
    private String sourceHash;
}
