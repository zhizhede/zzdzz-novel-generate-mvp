package com.zzdzz.novelgen.model.dto;

/** 管线触发入参：[from, to] 闭区间章号。 */
public record PipelineRunDTO(String novel, Integer from, Integer to) {
}
