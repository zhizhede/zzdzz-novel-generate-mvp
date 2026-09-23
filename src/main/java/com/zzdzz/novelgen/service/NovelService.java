package com.zzdzz.novelgen.service;

import lombok.RequiredArgsConstructor;
import com.zzdzz.novelgen.model.dto.NovelDTO;
import com.zzdzz.novelgen.model.dto.StylePackDTO;
import com.zzdzz.novelgen.model.enums.PlanMode;
import com.zzdzz.novelgen.common.web.BizException;
import com.zzdzz.novelgen.common.web.ErrorCode;
import com.zzdzz.novelgen.service.data.NovelDataService;
import com.zzdzz.novelgen.service.data.StylePackDataService;
import com.zzdzz.novelgen.model.vo.NovelVO;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** 作品查询、开书与审批模式切换。 */
@Service
@RequiredArgsConstructor
public class NovelService {

    private final NovelDataService novelData;
    private final StylePackDataService stylePackData;


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

    /**
     * 开书：克隆所选品类预设（指纹/门禁/规则）为本书私有风格包并挂书。
     * 预设必选——没有风格包的书在生成期才报错，那正是要消灭的死路。
     */
    @Transactional
    public NovelVO create(String title, String description, Long presetId, long userId) {
        if (title == null || title.isBlank()) {
            throw new BizException(ErrorCode.PARAM_ERROR, "书名必填");
        }
        String t = title.strip();
        if (t.length() > 256) {
            throw new BizException(ErrorCode.PARAM_ERROR, "书名过长（≤256 字）");
        }
        if (novelData.findIdByTitle(t) != null) {
            throw new BizException(ErrorCode.STATE_CONFLICT, "已有同名作品：" + t);
        }
        if (presetId == null) {
            throw new BizException(ErrorCode.PARAM_ERROR, "请选择品类预设（没有可用预设时，先到素材库·质量与风格·品类预设提取一个）");
        }
        StylePackDTO preset = stylePackData.getById(presetId);
        if (preset == null || !preset.isPreset()) {
            throw new BizException(ErrorCode.PARAM_ERROR, "所选预设不存在: " + presetId);
        }
        long packId = stylePackData.insertPack(t + "·风格", "开书克隆自预设：" + preset.getName(),
                preset.getRulesMd() == null ? "" : preset.getRulesMd(),
                preset.getFingerprint(), stylePackData.findGateConfigById(presetId));
        long novelId = novelData.insert(userId, t, description == null ? "" : description, packId, "auto");
        NovelDTO n = novelData.getById(novelId);
        return new NovelVO(n.getId(), n.getTitle(), n.getDescription(), n.getApprovalMode(), n.getStatus(), 0);
    }
}
