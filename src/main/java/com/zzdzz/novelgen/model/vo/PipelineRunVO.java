package com.zzdzz.novelgen.model.vo;

/** 管线触发入参：[from, to] 闭区间章号。 */
public record PipelineRunVO(String novel, Integer from, Integer to) {
}
