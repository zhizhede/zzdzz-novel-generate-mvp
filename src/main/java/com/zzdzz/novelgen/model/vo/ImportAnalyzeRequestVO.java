package com.zzdzz.novelgen.model.vo;

import java.util.List;

/**
 * 导入书籍「解析链」提交入参：勾选要跑哪些步骤（键见 ImportAnalyzeStep，未知键忽略、按执行顺序重排）。
 * **不传/空 = 不解析**（API 语义显式 opt-in，避免纯接口建书就默默烧一轮 LLM）；界面默认全勾。
 */
public record ImportAnalyzeRequestVO(List<String> steps) {
}
