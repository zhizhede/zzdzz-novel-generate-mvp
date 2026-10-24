package com.zzdzz.novelgen.model.entity.derive;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 卷级规划：本卷事件、局部闭合和下一卷钩子。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("derive_volume_plan")
public class VolumePlanDO extends DeriveBaseDO {

    private Long runId;
    private Long novelId;
    private Integer volumeNo;
    private Integer fromChapter;
    private Integer toChapter;
    private String arc;
    private String localEvent;
    private String localClosure;
    private String nextHook;
    private String globalRole;
    private String status;
    /** 卷级问题、错解、真解、主案增量和线程引用。 */
    private String contract;
}
