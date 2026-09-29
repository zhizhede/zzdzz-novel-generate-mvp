package com.zzdzz.novelgen.model.dto;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/** outline_draft_tasks：AI 大纲草稿异步任务（向导点击秒回，后台并发生成，结果可轮询拉取）。 */
@Data
@TableName("outline_draft_tasks")
public class OutlineDraftTaskDTO {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String title;

    /** 入参快照（NovelCreateVO 序列化，worker 据此重放生成）；jsonb 写入走 XML ::jsonb 转型。 */
    private String request;

    /** QUEUED/RUNNING/DONE/FAILED。 */
    private String status;

    /** 生成的 markdown 大纲。 */
    private String result;

    private String message;

    private Boolean isDeleted;

    private OffsetDateTime createTime;

    private OffsetDateTime updateTime;

    private OffsetDateTime deleteTime;
}
