package com.zzdzz.novelgen.model.dto;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/** imported_samples：用户导入的小说样本台账（切块在 preset_corpus，分析结论全文在 analysis）。 */
@Data
@TableName("imported_samples")
public class ImportedSampleDTO {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String title;

    private String genre;

    private Integer chunks;

    private Long totalChars;

    private String source;

    /** 完整分析快照（指标基线/章长带/相似度/建议/notes）；jsonb 写入走 XML ::jsonb 转型。 */
    private String analysis;

    /** 本样本所在品类采纳出的预设（回链，采纳时统一更新）。 */
    private Long presetId;

    private Boolean isDeleted;

    private OffsetDateTime createTime;

    private OffsetDateTime updateTime;

    private OffsetDateTime deleteTime;
}
