package com.zzdzz.novelgen.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** NovelDO。 */
@Data
@TableName(value = "novels")
public class NovelDO {
    @TableId(type = IdType.AUTO)
    private Long id;
    private long userId;
    private String title;
    private String description;
    private Long stylePackId;
    private String approvalMode;
    private String status;
    private boolean isDeleted;







}
