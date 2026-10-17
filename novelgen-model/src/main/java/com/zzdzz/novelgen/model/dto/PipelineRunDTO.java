package com.zzdzz.novelgen.model.dto;

/** 管线触发入参：[from, to] 闭区间章号；priority 0-2 可选（null=按书衍生配置，缺省 1）；
 * autoContinue 任务级自动续跑（null=开：本批跑完自动排下一批至规划用尽；显式 false 才关）。 */
public record PipelineRunDTO(String novel, Integer from, Integer to, Integer priority, Boolean autoContinue) {
}
