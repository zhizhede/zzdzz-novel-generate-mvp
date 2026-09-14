package com.zzdzz.novelgen.service;

import com.zzdzz.novelgen.common.web.BizException;
import com.zzdzz.novelgen.common.web.ErrorCode;
import com.zzdzz.novelgen.service.data.NovelDataService;
import com.zzdzz.novelgen.model.vo.NovelVO;
import org.springframework.stereotype.Service;

import java.util.List;

/** 作品查询与审批模式切换。 */
@Service
public class NovelService {

    private final NovelDataService novelData;

    public NovelService(NovelDataService novelData) {
        this.novelData = novelData;
    }

    public List<NovelVO> list() {
        return novelData.listAlive().stream()
                .map(n -> new NovelVO(n.id(), n.title(), n.description(), n.approvalMode(),
                        n.status(), novelData.chapterCount(n.id())))
                .toList();
    }

    public void setApprovalMode(long novelId, String mode) {
        if (!"auto".equals(mode) && !"manual".equals(mode)) {
            throw new BizException(ErrorCode.PARAM_ERROR, "审批模式只支持 auto / manual");
        }
        novelData.updateApprovalMode(novelId, mode);
    }
}
