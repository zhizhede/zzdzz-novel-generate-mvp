package com.zzdzz.novelgen.service.data;

import com.baomidou.mybatisplus.extension.service.IService;
import com.zzdzz.novelgen.model.entity.WorldStateDO;

import java.util.List;

/** world_states 数据服务接口（原 WorldStateDAO）。 */
public interface WorldStateDataService extends IService<WorldStateDO> {

    /** 素材库查看：按章列出（含人工编辑目标定位）。 */
    record StateRow(int chapterNo, String stateJson, String updateTime) {
    }

    void upsert(long novelId, int chapterNo, Object state);

    String findLatestBefore(long novelId, int beforeChapter);

    List<StateRow> listByNovel(long novelId, int limit);

    void updateByChapter(long novelId, int chapterNo, String stateJson);
}
