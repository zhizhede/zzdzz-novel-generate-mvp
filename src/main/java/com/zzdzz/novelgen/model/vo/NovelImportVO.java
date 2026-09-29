package com.zzdzz.novelgen.model.vo;

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
        /** 导入后为最新章节补 AI 事实账的章数（0/空 = 不补；补则续写有前情链，但要花 LLM 时间与费用）。 */
        Integer digestRecent) {
}
