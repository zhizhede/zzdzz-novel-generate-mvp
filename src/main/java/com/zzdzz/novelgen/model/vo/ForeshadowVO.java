package com.zzdzz.novelgen.model.vo;

import com.zzdzz.novelgen.model.entity.ForeshadowDO;

/** 伏笔台账行：软删除内部字段不出 API。 */
public record ForeshadowVO(Long id, long novelId, String code, String content,
                           Integer plantedIn, Integer recoveredIn, Integer proposedIn, String status) {

    public static ForeshadowVO from(ForeshadowDO d) {
        return new ForeshadowVO(d.getId(), d.getNovelId(), d.getCode(), d.getContent(),
                d.getPlantedIn(), d.getRecoveredIn(), d.getProposedIn(), d.getStatus());
    }
}
