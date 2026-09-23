package com.zzdzz.novelgen.model.vo;

/** 开书向导「AI 帮我定」：依据导入样本结构画像推荐的衍生参数（前端回填表单可改）。 */
public record SampleParamsVO(Integer water, String pov, String povCharacter, Integer chaptersPerVolume,
                             Integer targetChapters, String pacingNote, String reason,
                             Integer budgetMin, Integer budgetMax, java.util.List<String> tags) {
}
