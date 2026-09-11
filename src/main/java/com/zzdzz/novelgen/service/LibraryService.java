package com.zzdzz.novelgen.service;

import com.zzdzz.novelgen.common.web.BizException;
import com.zzdzz.novelgen.common.web.ErrorCode;
import com.zzdzz.novelgen.dao.CanonDocDAO;
import com.zzdzz.novelgen.dao.DigestDAO;
import com.zzdzz.novelgen.dao.ForeshadowDAO;
import com.zzdzz.novelgen.dao.StylePackDAO;
import com.zzdzz.novelgen.dao.WorldStateDAO;
import com.zzdzz.novelgen.model.entity.CanonDocDO;
import com.zzdzz.novelgen.model.entity.ForeshadowDO;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** 素材库：正典文档（增删改查）、伏笔账本（人工修正）、事实账（查看修正）、风格包（规则正文修订）。 */
@Service
public class LibraryService {

    private static final Set<String> CANON_KINDS = Set.of("world", "character", "misc");
    private static final Set<String> FORESHADOW_STATUSES = Set.of("planned", "planted", "recovered", "dropped");

    private final CanonDocDAO canonDocDAO;
    private final ForeshadowDAO foreshadowDAO;
    private final StylePackDAO stylePackDAO;
    private final DigestDAO digestDAO;
    private final WorldStateDAO worldStateDAO;
    private final com.fasterxml.jackson.databind.ObjectMapper mapper;

    public LibraryService(CanonDocDAO canonDocDAO, ForeshadowDAO foreshadowDAO,
                          StylePackDAO stylePackDAO, DigestDAO digestDAO,
                          WorldStateDAO worldStateDAO,
                          com.fasterxml.jackson.databind.ObjectMapper mapper) {
        this.canonDocDAO = canonDocDAO;
        this.foreshadowDAO = foreshadowDAO;
        this.stylePackDAO = stylePackDAO;
        this.digestDAO = digestDAO;
        this.worldStateDAO = worldStateDAO;
        this.mapper = mapper;
    }

    // ===== 正典文档 =====

    public List<CanonDocDO> listCanon(long novelId) {
        return canonDocDAO.listByNovel(novelId);
    }

    public CanonDocDO canonDoc(long id) {
        CanonDocDO doc = canonDocDAO.findById(id);
        if (doc == null) throw new BizException(ErrorCode.NOT_FOUND, "正典文档不存在: " + id);
        return doc;
    }

    public void updateCanon(long id, String content) {
        requireText(content, "内容不能为空");
        canonDoc(id);
        canonDocDAO.updateContent(id, content);
    }

    public void createCanon(long novelId, String kind, String name, String content) {
        requireText(name, "名称不能为空");
        requireText(content, "内容不能为空");
        if (!CANON_KINDS.contains(kind)) {
            throw new BizException(ErrorCode.PARAM_ERROR, "类型只支持 world / character / misc");
        }
        if (canonDocDAO.exists(novelId, kind, name)) {
            throw new BizException(ErrorCode.PARAM_ERROR, "同类型下已存在同名文档: " + name);
        }
        canonDocDAO.insert(novelId, kind, name, content);
    }

    public void deleteCanon(long id) {
        canonDoc(id);
        canonDocDAO.softDelete(id);
    }

    // ===== 伏笔账本 =====

    public List<ForeshadowDO> listForeshadows(long novelId) {
        return foreshadowDAO.listByNovel(novelId);
    }

    public void updateForeshadow(long id, String content, Integer plantedIn, Integer recoveredIn, String status) {
        requireText(content, "内容不能为空");
        if (!FORESHADOW_STATUSES.contains(status)) {
            throw new BizException(ErrorCode.PARAM_ERROR, "状态只支持 planned/planted/recovered/dropped");
        }
        if (foreshadowDAO.findById(id) == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "伏笔不存在: " + id);
        }
        foreshadowDAO.update(id, content, plantedIn, recoveredIn, status);
    }

    // ===== 事实账 =====

    public List<DigestDAO.DigestItem> listDigests(long novelId) {
        return digestDAO.listByNovel(novelId);
    }

    public void updateDigest(long id, String contentMd, String factsJson) {
        requireText(contentMd, "摘要不能为空");
        digestDAO.updateContent(id, contentMd, factsJson == null || factsJson.isBlank() ? "[]" : factsJson);
    }

    // ===== 世界状态账 =====

    public List<WorldStateDAO.StateRow> listWorldStates(long novelId, int limit) {
        return worldStateDAO.listByNovel(novelId, limit);
    }

    /** 人工纠偏某章快照：必须是可解析 JSON 对象。 */
    public void saveWorldState(long novelId, int chapterNo, String stateJson) {
        requireText(stateJson, "状态不能为空");
        try {
            if (!mapper.readTree(stateJson).isObject()) {
                throw new IllegalArgumentException("不是 JSON 对象");
            }
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException(ErrorCode.PARAM_ERROR, "状态必须是合法 JSON 对象");
        }
        worldStateDAO.updateByChapter(novelId, chapterNo, stateJson);
    }

    // ===== 风格包 =====

    public StylePackVO styleByNovel(long novelId) {
        return new StylePackVO(stylePackDAO.findRulesMdByNovel(novelId),
                stylePackDAO.findFingerprintByNovel(novelId),
                stylePackDAO.findGateConfigByNovel(novelId));
    }

    /** 门禁配置更新（黑名单/章长容差），JSON 由前端组装、后端校验可解析。 */
    public void updateGateConfig(long novelId, String gateConfigJson) {
        requireText(gateConfigJson, "门禁配置不能为空");
        try {
            mapper.readValue(gateConfigJson, Map.class);
        } catch (Exception e) {
            throw new BizException(ErrorCode.PARAM_ERROR, "门禁配置必须是合法 JSON");
        }
        stylePackDAO.updateGateConfigByNovel(novelId, gateConfigJson);
    }

    public void updateStyleRules(long novelId, String rulesMd) {
        requireText(rulesMd, "规则正文不能为空");
        stylePackDAO.updateRulesMdByNovel(novelId, rulesMd);
    }

    private void requireText(String s, String message) {
        if (s == null || s.isBlank()) throw new BizException(ErrorCode.PARAM_ERROR, message);
    }

    /** 风格包视图（内部模型）。 */
    public record StylePackVO(String rulesMd, String fingerprintJson, String gateConfigJson) {
    }
}
