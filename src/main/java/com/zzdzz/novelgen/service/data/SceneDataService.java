package com.zzdzz.novelgen.service.data;

import com.baomidou.mybatisplus.extension.service.IService;
import com.zzdzz.novelgen.model.entity.SceneDO;

import java.util.List;

/** chapter_scenes 数据服务接口（原 SceneDAO）。 */
public interface SceneDataService extends IService<SceneDO> {

    int countByChapter(long chapterId);

    List<SceneDO> findByChapter(long chapterId);

    /** 跨章全量场景（章号+场景号排序）——章纲 tab 全景展示。 */
    List<SceneDO> listByNovel(long novelId);

    Long findId(long chapterId, int sceneNo);

    int saveDraft(long chapterId, int sceneNo, String draftText);

    int applyRevise(long sceneId, String draftText);

    int updateGateStatus(long sceneId, String status);

    List<String> findPassedDrafts(long chapterId);

    /** 章纲重出：清旧场景后按新拆解重建（顺序写入）。timeAnchors 与 goals 等长，元素可空（null=本场未定时间）。 */
    void replaceAll(long chapterId, List<String> goals, List<String> presentJson,
                    List<String> mustRevealJson, List<String> mustNotJson, List<Integer> words,
                    List<String> timeAnchors);
}
