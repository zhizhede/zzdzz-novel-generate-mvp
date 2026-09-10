package com.zzdzz.novelgen.service;

import com.zzdzz.novelgen.common.web.BizException;
import com.zzdzz.novelgen.common.web.ErrorCode;
import com.zzdzz.novelgen.dao.NovelDAO;
import com.zzdzz.novelgen.model.vo.NovelVO;
import org.springframework.stereotype.Service;

import java.util.List;

/** 作品查询与审批模式切换。 */
@Service
public class NovelService {

    private final NovelDAO novelDAO;

    public NovelService(NovelDAO novelDAO) {
        this.novelDAO = novelDAO;
    }

    public List<NovelVO> list() {
        return novelDAO.listAlive().stream()
                .map(n -> new NovelVO(n.id(), n.title(), n.description(), n.approvalMode(),
                        n.status(), novelDAO.chapterCount(n.id())))
                .toList();
    }

    public void setApprovalMode(long novelId, String mode) {
        if (!"auto".equals(mode) && !"manual".equals(mode)) {
            throw new BizException(ErrorCode.PARAM_ERROR, "审批模式只支持 auto / manual");
        }
        novelDAO.updateApprovalMode(novelId, mode);
    }
}
