package com.zzdzz.novelgen.service.data;

import com.baomidou.mybatisplus.extension.service.IService;
import com.fasterxml.jackson.databind.JsonNode;
import com.zzdzz.novelgen.model.entity.VolumeReviewDO;

import java.util.List;

/** volume_reviews 数据服务接口（原 VolumeReviewDAO）。 */
public interface VolumeReviewDataService extends IService<VolumeReviewDO> {

    int upsert(long novelId, int volNo, JsonNode report);

    String findJson(long novelId, int volNo);
}
