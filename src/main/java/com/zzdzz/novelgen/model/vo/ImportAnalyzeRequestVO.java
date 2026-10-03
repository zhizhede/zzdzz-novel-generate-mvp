package com.zzdzz.novelgen.model.vo;

import java.util.List;

/**
 * 导入书籍「解析链」提交入参：勾选要跑哪些步骤（键见 ImportAnalyzeStep，未知键忽略、按执行顺序重排）。
 * **不传/空 = 不解析**（API 语义显式 opt-in，避免纯接口建书就默默烧一轮 LLM）；界面默认全勾。
 *
 * skipExistingSteps = 这些步遇到**已有内容**时跳过（默认不传/空 = 不跳过 = 覆盖重做）。
 * 只对 DIGESTS / CARDS / EMBEDDINGS 生效；其余步的已有内容处置固定（大纲/世界观/规则覆盖、卷纲新增一卷、
 * 章纲把新卷章行入队，其中已有正文的章由生成侧守卫跳过——重出章纲会把章状态回退并删该章场景/门禁报告）。
 */
public record ImportAnalyzeRequestVO(List<String> steps, List<String> skipExistingSteps) {
}
