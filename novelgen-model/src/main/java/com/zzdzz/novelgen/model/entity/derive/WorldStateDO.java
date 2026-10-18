package com.zzdzz.novelgen.model.entity.derive;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * world_states · 衍生世界状态账（V119；⑦每章写完抽取一快照，写下一章注入）。
 *
 * <p>jsonb 列 {@code state} 禁走 MP save()，写入走
 * {@code WorldStateMapper.upsert}（XML 显式 {@code ::jsonb}）。一章一份，
 * 唯一键 (novel_id, chapter_no) 覆盖更新。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("world_states")
public class WorldStateDO extends DeriveBaseDO {

    /** 所属成品书 → novels.id */
    private Long novelId;

    /** 章号（与 novel_id 唯一） */
    private Integer chapterNo;

    /** 状态快照 jsonb：time/locations/present/statuses/key_facts/open_loops */
    private String state;
}
