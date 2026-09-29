package com.zzdzz.novelgen.model.dto;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/** sample_cards：导入样本的结构化资产卡（平台级全员可复用；kind 对齐 material_cards 全类 + world）。 */
@Data
@TableName("sample_cards")
public class SampleCardDTO {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long sampleId;

    /** character/item/location/phenomenon/landmark/disaster/org/misc/world。 */
    private String kind;

    private String name;

    /** 别名数组 JSON 文本；jsonb 写入走 XML ::jsonb 转型。 */
    private String aliases;

    private String summary;

    private String contentMd;

    /** 关系数组 JSON 文本：[{target,kind,note}]；jsonb 写入走 XML ::jsonb 转型。 */
    private String relations;

    /** 1-3；克隆开书时 importance≥3 的卡置 pinned。 */
    private Integer importance;

    /** 首次出现章序。 */
    private Integer firstSeq;

    /** 出现章数（重要度依据）。 */
    private Integer mentions;

    private Boolean isDeleted;

    private OffsetDateTime createTime;

    private OffsetDateTime updateTime;

    private OffsetDateTime deleteTime;
}
