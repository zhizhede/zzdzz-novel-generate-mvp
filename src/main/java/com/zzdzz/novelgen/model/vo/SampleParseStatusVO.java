package com.zzdzz.novelgen.model.vo;

import com.zzdzz.novelgen.model.dto.SampleParseTaskDTO;

/**
 * 导入样本解析任务状态（含资产计数，前端进度条与按钮态依据）。
 */
public record SampleParseStatusVO(Long taskId, Long sampleId, String mode, String status,
                                  Integer totalUnits, Integer doneUnits, String stage, String message,
                                  int chapterCount, int volumeCount, int cardCount) {

    public static SampleParseStatusVO from(SampleParseTaskDTO task, int chapters, int volumes, int cards) {
        if (task == null) {
            return new SampleParseStatusVO(null, null, null, "NONE", 0, 0, "", null, 0, 0, 0);
        }
        return new SampleParseStatusVO(task.getId(), task.getSampleId(), task.getMode(), task.getStatus(),
                task.getTotalUnits(), task.getDoneUnits(), task.getStage(), task.getMessage(),
                chapters, volumes, cards);
    }
}
