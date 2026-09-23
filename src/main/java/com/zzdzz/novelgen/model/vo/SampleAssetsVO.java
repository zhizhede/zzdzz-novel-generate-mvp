package com.zzdzz.novelgen.model.vo;

import java.util.List;

/** 导入样本解析资产总览：剧情结构（书/卷/章）+ 结构化资产卡（分类）。 */
public record SampleAssetsVO(Long sampleId, String title, String genre,
                             SamplePlotVO book, List<SamplePlotVO> volumes,
                             List<SamplePlotVO> chapters, List<SampleCardVO> cards) {
}
