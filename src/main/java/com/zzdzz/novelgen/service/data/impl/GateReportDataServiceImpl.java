package com.zzdzz.novelgen.service.data.impl;

import lombok.RequiredArgsConstructor;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzdzz.novelgen.dao.GateReportMapper;
import com.zzdzz.novelgen.model.entity.GateReportDO;
import com.zzdzz.novelgen.service.data.GateReportDataService;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/** gate_reports 数据服务实现。 */
@Service
@RequiredArgsConstructor
public class GateReportDataServiceImpl extends ServiceImpl<GateReportMapper, GateReportDO>
        implements GateReportDataService {

    private final ObjectMapper mapper;


    @Override
    public void insert(long chapterId, Long sceneId, String gateType, int round,
                       boolean passed, Object result) {
        try {
            baseMapper.insert(chapterId, sceneId, gateType, round, passed, mapper.writeValueAsString(result));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public int deleteByChapter(long chapterId) {
        return baseMapper.deleteByChapter(chapterId);
    }

    @Override
    public String findLatestFailureJson(long chapterId) {
        return baseMapper.findLatestFailureJson(chapterId);
    }

    @Override
    public String findLatestSceneFailureJson(long chapterId, long sceneId) {
        return baseMapper.findLatestSceneFailureJson(chapterId, sceneId);
    }

    @Override
    public LatestChapterReport findLatestChapterReport(long chapterId) {
        List<GateReportDO> rows = baseMapper.findLatestChapterReport(chapterId);
        if (rows.isEmpty()) {
            return null;
        }
        GateReportDO r = rows.get(0);
        return new LatestChapterReport(r.isPassed(), r.getCreateTime(), r.getResult());
    }

    @Override
    public LatestReview findLatestChapterReview(long chapterId) {
        List<GateReportDO> rows = baseMapper.findLatestChapterReview(chapterId);
        if (rows.isEmpty()) {
            return null;
        }
        GateReportDO r = rows.get(0);
        return new LatestReview(r.isPassed(), r.getCreateTime(), r.getResult());
    }

    @Override
    public List<GateReportDO> listByChapter(long chapterId) {
        return baseMapper.listByChapter(chapterId);
    }

}