package com.zzdzz.novelgen.model.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** imported_samples：用户导入的小说样本台账（切块在 preset_corpus，分析结论全文在 analysis）。 */
@Data
@TableName("imported_samples")
public class ImportedSampleDO extends BaseDO {

    private String title;

    private String genre;

    private Integer chunks;

    private Long totalChars;

    private String source;

    /** 完整分析快照（指标基线/章长带/相似度/建议/notes）；jsonb 写入走 XML ::jsonb 转型。 */
    private String analysis;

    /** 本样本所在品类采纳出的预设（回链，采纳时统一更新）。 */
    private Long presetId;

    /** 类型/特征标签（AI 深度解析提取，衍生开书沿用起点）；JSON 数组文本，XML 内 ::jsonb 转型。 */
    private String tags;
}
