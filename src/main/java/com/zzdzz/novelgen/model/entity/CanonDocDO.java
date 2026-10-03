package com.zzdzz.novelgen.model.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** CanonDocDO。 */
@Data
@TableName(value = "canon_docs")
public class CanonDocDO extends BaseDO {
    private long novelId;
    private String kind;
    private String name;
    private String content;
    private int sortNo;
}
