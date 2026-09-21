package com.zzdzz.novelgen.model.dto;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** tuning 表 DO：平台级行为参数（门禁阈值/重试轮数/提示词阈值等），SQL 只在 dao。 */
@Data
@TableName(value = "tuning")
public class TuningDTO {
    @TableId(type = IdType.AUTO)
    private Long id;
    @TableField("tkey")
    private String key;
    @TableField("tvalue")
    private String value;
    private String description;




}
