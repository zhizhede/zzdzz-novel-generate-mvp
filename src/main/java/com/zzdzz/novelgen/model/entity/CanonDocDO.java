package com.zzdzz.novelgen.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** CanonDocDO。 */
@Data
@TableName(value = "canon_docs")
public class CanonDocDO {
    @TableId(type = IdType.AUTO)
    private Long id;
    private long novelId;
    private String kind;
    private String name;
    private String content;
    private int sortNo;
    private boolean isDeleted;






}
