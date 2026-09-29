package com.zzdzz.novelgen.model.dto;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** NovelDTO。 */
@Data
@TableName(value = "novels")
public class NovelDTO {
    @TableId(type = IdType.AUTO)
    private Long id;
    private long userId;
    private String title;
    private String description;
    private Long stylePackId;
    private String approvalMode;
    private String status;
    private java.time.OffsetDateTime createTime;
    private java.time.OffsetDateTime updateTime;
    private boolean isDeleted;







}
