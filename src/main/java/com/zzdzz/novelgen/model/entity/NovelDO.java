package com.zzdzz.novelgen.model.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** NovelDO。 */
@Data
@TableName(value = "novels")
public class NovelDO extends BaseDO {
    private long userId;
    private String title;
    private String description;
    private Long stylePackId;
    private String approvalMode;
    private String status;
    /** 入库类型（NovelSourceType：IMPORTED 手动导入 / DERIVED 系统衍生 / ORIGINAL 系统纯原创）。 */
    private String sourceType;
    private java.time.OffsetDateTime createTime;
    private java.time.OffsetDateTime updateTime;



}
