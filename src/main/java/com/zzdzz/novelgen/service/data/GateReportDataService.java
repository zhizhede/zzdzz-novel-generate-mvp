package com.zzdzz.novelgen.service.data;

import com.baomidou.mybatisplus.extension.service.IService;
import com.zzdzz.novelgen.model.entity.GateReportDO;

/** gate_reports 数据服务接口（原 GateReportDAO）。 */
public interface GateReportDataService extends IService<GateReportDO> {

    record LatestChapterReport(boolean passed, java.time.OffsetDateTime createTime, String resultJson) {
    }

    record LatestReview(boolean passed, java.time.OffsetDateTime createTime, String resultJson) {
    }

    void insert(long chapterId, Long sceneId, String gateType, int round,
                boolean passed, Object result);

    int deleteByChapter(long chapterId);

    String findLatestFailureJson(long chapterId);

    String findLatestSceneFailureJson(long chapterId, long sceneId);

    LatestChapterReport findLatestChapterReport(long chapterId);

    LatestReview findLatestChapterReview(long chapterId);
}
