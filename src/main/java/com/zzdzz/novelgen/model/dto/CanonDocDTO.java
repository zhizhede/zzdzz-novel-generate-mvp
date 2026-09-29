package com.zzdzz.novelgen.model.dto;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** CanonDocDTO。 */
@Data
@TableName(value = "canon_docs")
public class CanonDocDTO {
    @TableId(type = IdType.AUTO)
    private Long id;
    private long novelId;
    private String kind;
    private String name;
    private String content;
    private int sortNo;
    private boolean isDeleted;
    /** 仅在全列投影里填充（规划资产页按更新时间排序/展示）；只取 content 的查询里保持 null。 */
    private java.time.OffsetDateTime createTime;
    private java.time.OffsetDateTime updateTime;






}
