package com.zzdzz.novelgen.service.data.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.zzdzz.novelgen.dao.VolumeReviewMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.zzdzz.novelgen.model.dto.VolumeReviewDTO;
import com.zzdzz.novelgen.service.data.VolumeReviewDataService;
import org.springframework.stereotype.Service;

import java.util.List;

/** volume_reviews 数据服务实现。 */
@Service
public class VolumeReviewDataServiceImpl extends ServiceImpl<VolumeReviewMapper, VolumeReviewDTO> implements VolumeReviewDataService {

    @Override
    public int upsert(long novelId, int volNo, JsonNode report) {
        return baseMapper.upsert(novelId, volNo, report.toString());
    }

    @Override
    public String findJson(long novelId, int volNo) {
        return baseMapper.findJson(novelId, volNo);
    }
}
