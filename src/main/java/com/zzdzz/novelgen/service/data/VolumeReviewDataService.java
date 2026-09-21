package com.zzdzz.novelgen.service.data;

import com.baomidou.mybatisplus.extension.service.IService;
import com.fasterxml.jackson.databind.JsonNode;
import com.zzdzz.novelgen.model.dto.VolumeReviewDTO;

import java.util.List;

/** volume_reviews 数据服务接口（原 VolumeReviewDAO）。 */
public interface VolumeReviewDataService extends IService<VolumeReviewDTO> {

    int upsert(long novelId, int volNo, JsonNode report);

    String findJson(long novelId, int volNo);
}
