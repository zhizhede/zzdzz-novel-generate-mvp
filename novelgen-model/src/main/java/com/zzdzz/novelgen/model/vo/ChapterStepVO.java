package com.zzdzz.novelgen.model.vo;

/** 章节步骤状态行（契约②读模型）：章详情「步骤」tab 用。 */
public record ChapterStepVO(String step, String subKey, int attempt, String status,
                            String detail, String updateTime) {
}
