package com.zzdzz.novelgen.model.entity.derive;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 线索、关系或物件线程账。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("derive_story_thread")
public class StoryThreadDO extends DeriveBaseDO {

    private Long runId;
    private Long novelId;
    private String threadKey;
    private String kind;
    private String title;
    private Integer openedVolume;
    private Integer payoffVolume;
    private String ownerCharacterKey;
    private String status;
    private String contract;
}
