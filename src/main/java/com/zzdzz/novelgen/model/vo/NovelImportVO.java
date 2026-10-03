package com.zzdzz.novelgen.model.vo;

import java.util.List;

/**
 * 导入书籍入参（书籍管理页「导入书籍」）：正文来自粘贴文本框或上传 txt/docx/mobi/azw 文件（二选一）。
 * presetId 可选——导入的书必须有一个风格包（门禁阈值载体），但来源二选一：
 * ①选了品类预设：克隆该预设的指纹/门禁/规则（沿用其口径）；
 * ②不选：建空风格包（门禁回退代码/tuning 默认值），导入后由前端自动按本书正文提指纹回填——
 * 导入的书本就自带全书正文，比任何预设都更贴这本书的真实文风。
 */
public record NovelImportVO(
        String title,
        String description,
        Long presetId,
        String text,
        String fileBase64,
        /**
         * 导入后要跑的解析链步骤键（ImportAnalyzeStep；不传/空 = 只落库不解析，界面默认全勾）。
         * 落库是短事务、解析是长时间 LLM，故二者在同一次请求里**先落库再入队**（提交在事务外，见 NovelController）。
         */
        List<String> analyzeSteps,
        /**
         * analyzeSteps 里遇到**已有内容**选择跳过的步骤键（默认不传/空 = 不跳过 = 覆盖重做）。
         * 只对 DIGESTS / CARDS / EMBEDDINGS 生效，语义同 ImportAnalyzeRequestVO.skipExistingSteps。
         */
        List<String> analyzeSkipExistingSteps) {
}
