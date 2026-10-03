package com.zzdzz.novelgen.model.dto;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** SceneDTO。 */
@Data
@TableName(value = "chapter_scenes")
public class SceneDTO extends BaseDTO {
    private long chapterId;
    private int sceneNo;
    private String goal;
    private String present;
    private String mustReveal;
    private String mustNot;
    private int wordsBudget;
    private String draftText;
    private String gateStatus;
    private int revisionRound;











}
