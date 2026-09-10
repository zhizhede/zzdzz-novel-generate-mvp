package com.zzdzz.novelgen.model.vo;

/** 管线运行状态。 */
public record PipelineStatusVO(boolean running, String lastMessage) {
}
