package com.zzdzz.novelgen.model.vo;

/** 管线触发入参：[from, to] 闭区间章号；priority 0-2 可选（null=按书衍生配置，缺省 1）。 */
public record PipelineRunVO(String novel, Integer from, Integer to, Integer priority) {
}
