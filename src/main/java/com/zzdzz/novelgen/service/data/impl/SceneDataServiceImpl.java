package com.zzdzz.novelgen.service.data.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.zzdzz.novelgen.dao.SceneMapper;
import com.zzdzz.novelgen.model.entity.SceneDO;
import com.zzdzz.novelgen.service.data.SceneDataService;
import org.springframework.stereotype.Service;

import java.util.List;

/** chapter_scenes 数据服务实现。 */
@Service
public class SceneDataServiceImpl extends ServiceImpl<SceneMapper, SceneDO> implements SceneDataService {

    @Override
    public int countByChapter(long chapterId) {
        return baseMapper.countByChapter(chapterId);
    }

    @Override
    public List<SceneDO> findByChapter(long chapterId) {
        return baseMapper.findByChapter(chapterId);
    }

    @Override
    public Long findId(long chapterId, int sceneNo) {
        return baseMapper.findId(chapterId, sceneNo);
    }

    @Override
    public int saveDraft(long chapterId, int sceneNo, String draftText) {
        return baseMapper.saveDraft(chapterId, sceneNo, draftText);
    }

    @Override
    public int applyRevise(long sceneId, String draftText) {
        return baseMapper.applyRevise(sceneId, draftText);
    }

    @Override
    public int updateGateStatus(long sceneId, String status) {
        return baseMapper.updateGateStatus(sceneId, status);
    }

    @Override
    public List<String> findPassedDrafts(long chapterId) {
        return baseMapper.findPassedDrafts(chapterId);
    }

    @Override
    public void replaceAll(long chapterId, List<String> goals, List<String> presentJson,
                           List<String> mustRevealJson, List<String> mustNotJson, List<Integer> words) {
        baseMapper.deleteByChapter(chapterId);
        for (int i = 0; i < goals.size(); i++) {
            baseMapper.insertScene(chapterId, i + 1, goals.get(i), presentJson.get(i),
                    mustRevealJson.get(i), mustNotJson.get(i), words.get(i));
        }
    }
}
