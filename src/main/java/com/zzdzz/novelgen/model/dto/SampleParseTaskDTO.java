package com.zzdzz.novelgen.model.dto;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/** sample_parse_tasks：导入小说深度解析任务（每样本一行活跃任务，断点续跑按章行幂等跳过）。 */
@Data
@TableName("sample_parse_tasks")
public class SampleParseTaskDTO {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long sampleId;

    /** FAST=抽样快速骨架 / FULL=全书完整解析。 */
    private String mode;

    /** QUEUED/RUNNING/DONE/FAILED/INTERRUPTED。 */
    private String status;

    private Integer totalUnits;

    private Integer doneUnits;

    /** chapter/merge/volume/outline/world。 */
    private String stage;

    private String message;

    private Boolean isDeleted;

    private OffsetDateTime createTime;

    private OffsetDateTime updateTime;

    private OffsetDateTime deleteTime;
}
