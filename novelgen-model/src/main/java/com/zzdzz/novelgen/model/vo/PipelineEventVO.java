package com.zzdzz.novelgen.model.vo;

/** 管线事件流水条目：payloadJson 保留原始事件负载（含门禁失败原因、重写轮次、场景文本等）。 */
public record PipelineEventVO(long id, Long novelId, Integer chapterNo, String stage,
                              String phase, String payloadJson, String createTime) {
}
