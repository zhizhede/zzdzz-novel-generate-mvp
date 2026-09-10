package com.zzdzz.novelgen.service;

import com.zzdzz.novelgen.common.web.BizException;
import com.zzdzz.novelgen.common.web.ErrorCode;
import com.zzdzz.novelgen.dao.CanonDocDAO;
import com.zzdzz.novelgen.dao.ForeshadowDAO;
import com.zzdzz.novelgen.dao.StylePackDAO;
import com.zzdzz.novelgen.model.entity.CanonDocDO;
import com.zzdzz.novelgen.model.entity.ForeshadowDO;
import org.springframework.stereotype.Service;

import java.util.List;

/** 素材库：正典文档（查看/编辑）、伏笔账本、风格包。 */
@Service
public class LibraryService {

    private final CanonDocDAO canonDocDAO;
    private final ForeshadowDAO foreshadowDAO;
    private final StylePackDAO stylePackDAO;

    public LibraryService(CanonDocDAO canonDocDAO, ForeshadowDAO foreshadowDAO,
                          StylePackDAO stylePackDAO) {
        this.canonDocDAO = canonDocDAO;
        this.foreshadowDAO = foreshadowDAO;
        this.stylePackDAO = stylePackDAO;
    }

    public List<CanonDocDO> listCanon(long novelId) {
        return canonDocDAO.listByNovel(novelId);
    }

    public CanonDocDO canonDoc(long id) {
        CanonDocDO doc = canonDocDAO.findById(id);
        if (doc == null) throw new BizException(ErrorCode.NOT_FOUND, "正典文档不存在: " + id);
        return doc;
    }

    public void updateCanon(long id, String content) {
        if (content == null || content.isBlank()) {
            throw new BizException(ErrorCode.PARAM_ERROR, "内容不能为空");
        }
        canonDoc(id);
        canonDocDAO.updateContent(id, content);
    }

    public List<ForeshadowDO> listForeshadows(long novelId) {
        return foreshadowDAO.listByNovel(novelId);
    }

    /** 风格包（按作品取）：rulesMd + 指纹原文（含 metrics/baseline）。 */
    public StylePackVO styleByNovel(long novelId) {
        return new StylePackVO(stylePackDAO.findRulesMdByNovel(novelId),
                stylePackDAO.findFingerprintByNovel(novelId));
    }

    /** 风格包视图（内部模型）。 */
    public record StylePackVO(String rulesMd, String fingerprintJson) {
    }
}
