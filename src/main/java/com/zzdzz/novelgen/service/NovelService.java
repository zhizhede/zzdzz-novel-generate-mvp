package com.zzdzz.novelgen.service;

import lombok.RequiredArgsConstructor;
import com.zzdzz.novelgen.model.enums.PlanMode;
import com.zzdzz.novelgen.common.web.BizException;
import com.zzdzz.novelgen.common.web.ErrorCode;
import com.zzdzz.novelgen.service.data.NovelDataService;
import com.zzdzz.novelgen.model.vo.NovelVO;
import org.springframework.stereotype.Service;

import java.util.List;

/** 作品查询与审批模式切换。 */
@Service
@RequiredArgsConstructor
public class NovelService {

    private final NovelDataService novelData;


    public List<NovelVO> list() {
        return novelData.listAlive().stream()
                .map(n -> new NovelVO(n.getId(), n.getTitle(), n.getDescription(), n.getApprovalMode(),
                        n.getStatus(), novelData.chapterCount(n.getId())))
                .toList();
    }

    public void setApprovalMode(long novelId, String mode) {
        if (!PlanMode.AUTO.is(mode) && !PlanMode.MANUAL.is(mode)) {
            throw new BizException(ErrorCode.PARAM_ERROR, "审批模式只支持 auto / manual");
        }
        novelData.updateApprovalMode(novelId, mode);
    }
}
