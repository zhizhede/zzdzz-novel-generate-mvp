package com.zzdzz.novelgen.model.vo;

/** 章级门禁报告：checks 为 gate_reports.result 原始检查项（check/value/baseline/abs_max/ok）。 */
public record GateReportVO(String gateType, boolean passed, String createTime,
                           java.util.List<java.util.Map<String, Object>> checks) {
}
