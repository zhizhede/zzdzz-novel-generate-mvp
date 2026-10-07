package com.zzdzz.novelgen.model.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** sample_plot_nodes：导入样本剧情结构树（书→卷→章；章行 = 逐章解析结果与断点 checkpoint）。 */
@Data
@TableName("sample_plot_nodes")
public class SamplePlotNodeDO extends BaseDO {

    private Long sampleId;

    /** book/volume/chapter。 */
    private String level;

    /** 同层内序号（章=章序，卷=卷序，书=1）；UNIQUE(sample_id, level, seq) 活跃行。 */
    private Integer seq;

    /** 卷/书的父层 seq；章=所属卷 seq（无卷标记时 0）。 */
    private Integer parentSeq;

    private String title;

    private String summary;

    /** 场景级拆解（章）：[{goal,conflict,outcome}]；jsonb 写入走 XML ::jsonb 转型。 */
    private String beats;

    /** 扩展元（字数/抽样标记/arc 节奏/主题等）；jsonb 写入走 XML ::jsonb 转型。 */
    private String meta;
}
