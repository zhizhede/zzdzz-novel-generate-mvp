package com.zzdzz.novelgen.service;

import lombok.RequiredArgsConstructor;
import com.zzdzz.novelgen.model.enums.ForeshadowStatus;
import com.zzdzz.novelgen.common.web.BizException;
import com.zzdzz.novelgen.common.web.ErrorCode;
import com.zzdzz.novelgen.service.data.CanonDocDataService;
import com.zzdzz.novelgen.service.data.DigestDataService;
import com.zzdzz.novelgen.service.data.ForeshadowDataService;
import com.zzdzz.novelgen.service.data.StylePackDataService;
import com.zzdzz.novelgen.service.data.WorldStateDataService;
import com.zzdzz.novelgen.model.dto.CanonDocDTO;
import com.zzdzz.novelgen.model.dto.ForeshadowDTO;
import org.springframework.stereotype.Service;

import java.util.List;
import com.zzdzz.novelgen.model.vo.CanonDocVO;
import com.zzdzz.novelgen.model.vo.ForeshadowVO;

import java.util.Map;
import java.util.Set;

/** 素材库：正典文档（增删改查）、伏笔账本（人工修正）、事实账（查看修正）、风格包（规则正文修订）。 */
@Service
@RequiredArgsConstructor
public class LibraryService {

    private static final Set<String> CANON_KINDS = Set.of("world", "character", "misc");
    private static final Set<String> FORESHADOW_STATUSES = Set.of(ForeshadowStatus.PROPOSED.wire(), ForeshadowStatus.PLANNED.wire(), ForeshadowStatus.PLANTED.wire(), ForeshadowStatus.RECOVERED.wire(), ForeshadowStatus.DROPPED.wire());

    private final CanonDocDataService canonData;
    private final ForeshadowDataService foreshadowData;
    private final StylePackDataService stylePackData;
    private final DigestDataService digestData;
    private final WorldStateDataService worldStateData;
    private final com.fasterxml.jackson.databind.ObjectMapper mapper;


    // ===== 正典文档 =====

    public List<CanonDocVO> listCanon(long novelId) {
        return canonData.listByNovel(novelId).stream()
                .map(CanonDocVO::from).toList();
    }

    /** 详情（API 用）：DO 不出 service 层。 */
    public CanonDocVO canonDocVO(long id) {
        return CanonDocVO.from(canonDoc(id));
    }

    /** 内部校验沿用 DO。 */
    public CanonDocDTO canonDoc(long id) {
        CanonDocDTO doc = canonData.findById(id);
        if (doc == null) throw new BizException(ErrorCode.NOT_FOUND, "正典文档不存在: " + id);
        return doc;
    }

    public void updateCanon(long id, String content) {
        requireText(content, "内容不能为空");
        canonDoc(id);
        canonData.updateContent(id, content);
    }

    public void createCanon(long novelId, String kind, String name, String content) {
        requireText(name, "名称不能为空");
        requireText(content, "内容不能为空");
        if (!CANON_KINDS.contains(kind)) {
            throw new BizException(ErrorCode.PARAM_ERROR, "类型只支持 world / character / misc");
        }
        if (canonData.exists(novelId, kind, name)) {
            throw new BizException(ErrorCode.PARAM_ERROR, "同类型下已存在同名文档: " + name);
        }
        canonData.insert(novelId, kind, name, content);
    }

    public void deleteCanon(long id) {
        canonDoc(id);
        canonData.softDelete(id);
    }

    // ===== 伏笔账本 =====

    public List<ForeshadowVO> listForeshadows(long novelId) {
        return foreshadowData.listByNovel(novelId).stream()
                .map(ForeshadowVO::from).toList();
    }

    public void updateForeshadow(long id, String content, Integer plantedIn, Integer recoveredIn, String status) {
        requireText(content, "内容不能为空");
        if (!FORESHADOW_STATUSES.contains(status)) {
            throw new BizException(ErrorCode.PARAM_ERROR, "状态只支持 planned/planted/recovered/dropped");
        }
        if (foreshadowData.findById(id) == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "伏笔不存在: " + id);
        }
        foreshadowData.update(id, content, plantedIn, recoveredIn, status);
    }

    // ===== 事实账 =====

    public List<DigestDataService.DigestItem> listDigests(long novelId) {
        return digestData.listByNovel(novelId);
    }

    public void updateDigest(long id, String contentMd, String factsJson) {
        requireText(contentMd, "摘要不能为空");
        digestData.updateContent(id, contentMd, factsJson == null || factsJson.isBlank() ? "[]" : factsJson);
    }

    // ===== 世界状态账 =====

    public List<WorldStateDataService.StateRow> listWorldStates(long novelId, int limit) {
        return worldStateData.listByNovel(novelId, limit);
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
        worldStateData.updateByChapter(novelId, chapterNo, stateJson);
    }

    // ===== 风格包 =====

    public StylePackVO styleByNovel(long novelId) {
        return new StylePackVO(stylePackData.findRulesMdByNovel(novelId),
                stylePackData.findFingerprintByNovel(novelId),
                stylePackData.findGateConfigByNovel(novelId));
    }

    /** 门禁配置更新（黑名单/章长容差），JSON 由前端组装、后端校验可解析。 */
    public void updateGateConfig(long novelId, String gateConfigJson) {
        requireText(gateConfigJson, "门禁配置不能为空");
        try {
            mapper.readValue(gateConfigJson, Map.class);
        } catch (Exception e) {
            throw new BizException(ErrorCode.PARAM_ERROR, "门禁配置必须是合法 JSON");
        }
        stylePackData.updateGateConfigByNovel(novelId, gateConfigJson);
    }

    /** 原始门禁配置 JSON（工作台/风格包调参面板读取合并保存用）。 */
    public String gateConfigJson(long novelId) {
        String json = stylePackData.findGateConfigByNovel(novelId);
        return json == null || json.isBlank() ? "{}" : json;
    }

    public void updateStyleRules(long novelId, String rulesMd) {
        requireText(rulesMd, "规则正文不能为空");
        stylePackData.updateRulesMdByNovel(novelId, rulesMd);
    }

    private void requireText(String s, String message) {
        if (s == null || s.isBlank()) throw new BizException(ErrorCode.PARAM_ERROR, message);
    }

    /** 风格包视图（内部模型）。 */
    public record StylePackVO(String rulesMd, String fingerprintJson, String gateConfigJson) {
    }
}
