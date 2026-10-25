package com.zzdzz.novelgen.model.entity.derive;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** P1 FactLedger：规划知识事实的规范化来源账。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("derive_fact")
public class DeriveFactDO extends DeriveBaseDO {

    private Long runId;
    private Long novelId;
    private String factKey;
    private String canonicalText;
    private String writers;
    private String readers;
    private Integer plantedChapter;
    private Integer revealedChapter;
    private String status;
    private String contract;
}