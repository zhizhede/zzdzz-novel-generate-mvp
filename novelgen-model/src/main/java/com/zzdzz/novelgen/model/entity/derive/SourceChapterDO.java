package com.zzdzz.novelgen.model.entity.derive;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * source_chapter · 源书切章（v1.2 §3.2；D2 拍板正文入表）。
 * 全标量列，可走 MP save()/BaseMapper 标准 CRUD。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("source_chapter")
public class SourceChapterDO extends DeriveBaseDO {

    /** 所属源书 → novels.id */
    private Long novelId;

    /** 章序（①切章，与 novel_id 唯一） */
    private Integer seq;

    /** 章题 */
    private String title;

    /** 原文正文 */
    private String content;

    /** 字数 */
    private Integer charCount;
}
