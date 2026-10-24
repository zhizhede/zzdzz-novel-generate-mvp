package com.zzdzz.novelgen.model.entity.derive;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** work_fingerprint · 成品查重登记（v1.2 §3.15；⑤抽样查同构弧、⑧登记成品）。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("work_fingerprint")
public class WorkFingerprintDO extends DeriveBaseDO {

    /** 成品书 → novels.id */
    private Long novelId;

    /** 指纹种类：PROTAGONIST_NAME / BRIDGE / TROUPE */
    private String kind;

    /** 登记值（如主角名、桥段名） */
    private String value;

    /** 结构指纹（标签组合哈希，同构弧检测用） */
    private String fingerprint;
}
