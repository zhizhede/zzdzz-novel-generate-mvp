package com.zzdzz.novelgen.service.data;

import com.baomidou.mybatisplus.extension.service.IService;
import com.fasterxml.jackson.databind.JsonNode;
import com.zzdzz.novelgen.model.entity.VolumeReviewDO;

import java.util.List;

/** volume_reviews 数据服务接口（原 VolumeReviewDAO）。 */
public interface VolumeReviewDataService extends IService<VolumeReviewDO> {

    int upsert(long novelId, int volNo, JsonNode report);

    String findJson(long novelId, int volNo);

    /** 全库卷复盘（规划资产页「卷纲」层显示复盘有无与摘要用）。 */
    List<VolumeReviewRow> listAll();

    /** 卷复盘行（report 为 JSON 文本，解析交给 service）。 */
    record VolumeReviewRow(long novelId, int volNo, String reportJson, java.time.OffsetDateTime updateTime) {
    }
}
