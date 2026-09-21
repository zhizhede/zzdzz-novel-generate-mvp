package com.zzdzz.novelgen.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.zzdzz.novelgen.common.web.BizException;
import com.zzdzz.novelgen.common.web.ErrorCode;
import com.zzdzz.novelgen.service.data.MaterialCardDataService;
import com.zzdzz.novelgen.model.dto.MaterialCardDTO;
import org.springframework.stereotype.Service;

import com.zzdzz.novelgen.model.vo.MaterialCardVO;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 素材卡：设定层实体卡（角色/物品/地点/现象/地标/灾害/组织）的 CRUD 与生成注入。
 * 注入两档：场景=常驻(pinned)全量 + 别名匹配命中卡摘要；规划/审校=全部活跃卡摘要。
 * 作品无卡时返回 null，由 ContextPacker 回退 canon 整文档（向下兼容）。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class MaterialCardService {


    private static final Set<String> KINDS = Set.of(
            MaterialCardDTO.KIND_CHARACTER, MaterialCardDTO.KIND_ITEM, MaterialCardDTO.KIND_LOCATION,
            MaterialCardDTO.KIND_LANDMARK, MaterialCardDTO.KIND_PHENOMENON, MaterialCardDTO.KIND_DISASTER,
            MaterialCardDTO.KIND_ORG, MaterialCardDTO.KIND_MISC);

    private static final Set<String> STATUSES = Set.of("active", "retired", "dead", "merged");

    /** kind → 中文标签（注入文本用）。 */
    private static final Map<String, String> KIND_LABELS = Map.of(
            MaterialCardDTO.KIND_CHARACTER, "角色", MaterialCardDTO.KIND_ITEM, "物品",
            MaterialCardDTO.KIND_LOCATION, "地点", MaterialCardDTO.KIND_LANDMARK, "地标",
            MaterialCardDTO.KIND_PHENOMENON, "现象", MaterialCardDTO.KIND_DISASTER, "灾害",
            MaterialCardDTO.KIND_ORG, "组织", MaterialCardDTO.KIND_MISC, "其他");

    /** 单场景匹配命中卡上限的 tuning 键（常驻卡不占额），防上下文膨胀。 */
    private static final String MAX_MATCHED_KEY = "pack_max_matched_cards";

    private final MaterialCardDataService cardDAO;
    private final TuningService tuning;


    /** 向量化文本（RAG 索引用）：类型标签 + 名 + 别名 + 摘要 + 正文。 */
    public String embeddingText(MaterialCardDTO card) {
        StringBuilder sb = new StringBuilder("素材卡·")
                .append(KIND_LABELS.getOrDefault(card.getKind(), card.getKind()))
                .append("：").append(card.getName());
        if (card.getAliases() != null && !card.getAliases().isEmpty()) {
            sb.append("（别名：").append(String.join("、", card.getAliases())).append('）');
        }
        if (card.getSummary() != null && !card.getSummary().isBlank()) sb.append('\n').append(card.getSummary());
        if (card.getContentMd() != null && !card.getContentMd().isBlank()) sb.append('\n').append(card.getContentMd());
        return sb.toString();
    }

    // ===== CRUD =====

    public List<MaterialCardVO> list(long novelId, String kind) {
        return cardDAO.listByNovel(novelId, kind).stream()
                .map(MaterialCardVO::from).toList();
    }

    /** 详情（API 用）：DO 不出 service 层。 */
    public MaterialCardVO vo(long id) {
        return MaterialCardVO.from(get(id));
    }

    /** 内部沿用 DO（EmbeddingService 向量化等）。 */
    public MaterialCardDTO get(long id) {
        MaterialCardDTO card = cardDAO.findById(id);
        if (card == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "素材卡不存在: " + id);
        }
        return card;
    }

    public void create(long novelId, String kind, String name, List<String> aliases, String summary,
                       String contentMd, Boolean pinned, String status, Integer sourceChapter) {
        validate(kind, name, status);
        if (cardDAO.exists(novelId, kind, name)) {
            throw new BizException(ErrorCode.PARAM_ERROR, "同类型下已存在同名卡: " + name);
        }
        cardDAO.insert(novelId, kind, name, aliases, summary, contentMd,
                pinned != null && pinned, status == null ? "active" : status, sourceChapter);
    }

    public void update(long id, String name, List<String> aliases, String summary, String contentMd,
                       Boolean pinned, String status, Integer sourceChapter) {
        MaterialCardDTO card = get(id);
        validate(card.getKind(), name, status);
        cardDAO.update(id, name, aliases, summary, contentMd, pinned,
                status == null ? card.getStatus() : status, sourceChapter);
    }

    public void delete(long id) {
        get(id);
        cardDAO.softDelete(id);
    }

    private void validate(String kind, String name, String status) {
        if (kind != null && !KINDS.contains(kind)) {
            throw new BizException(ErrorCode.PARAM_ERROR, "未知的素材卡类型: " + kind);
        }
        if (name == null || name.isBlank()) {
            throw new BizException(ErrorCode.PARAM_ERROR, "卡名不能为空");
        }
        if (status != null && !STATUSES.contains(status)) {
            throw new BizException(ErrorCode.PARAM_ERROR, "未知的状态: " + status);
        }
    }

    // ===== 注入 =====

    public boolean hasCards(long novelId) {
        return cardDAO.hasCards(novelId);
    }

    /**
     * 场景注入块：pinned 全量 + 按 matchText 别名命中的活跃卡。
     * 返回 null 表示该作品无卡（调用方回退 canon 整文档）。
     */
    public String sceneBlock(long novelId, String matchText) {
        List<MaterialCardDTO> all = cardDAO.listByNovel(novelId, null);
        if (all.isEmpty()) {
            return null;
        }
        List<MaterialCardDTO> pinned = new ArrayList<>();
        List<MaterialCardDTO> matched = new ArrayList<>();
        String text = matchText == null ? "" : matchText;
        for (MaterialCardDTO card : all) {
            if (!card.active()) {
                continue;
            }
            if (card.pinned()) {
                pinned.add(card);
            } else if (hits(card, text)) {
                matched.add(card);
                if (matched.size() >= tuning.i(MAX_MATCHED_KEY, 12)) {
                    break;
                }
            }
        }
        if (pinned.isEmpty() && matched.isEmpty()) {
            return null; // 无卡或本章无命中：宁缺勿滥，不注空块
        }
        return render(pinned, matched);
    }

    /** 规划/审校注入块：全部活跃卡（pinned 带全文，其余摘要）。返回 null 表示无卡。 */
    public String fullBlock(long novelId) {
        List<MaterialCardDTO> all = cardDAO.listByNovel(novelId, null).stream()
                .filter(MaterialCardDTO::active).toList();
        if (all.isEmpty()) {
            return null;
        }
        List<MaterialCardDTO> pinned = all.stream().filter(MaterialCardDTO::pinned).toList();
        List<MaterialCardDTO> rest = all.stream().filter(c -> !c.pinned()).toList();
        return render(pinned, rest);
    }

    /** 别名命中：卡名或任一别名（≥2 字）作为子串出现在文本中。 */
    private boolean hits(MaterialCardDTO card, String text) {
        if (text.isEmpty()) {
            return false;
        }
        if (text.contains(card.getName())) {
            return true;
        }
        if (card.getAliases() != null) {
            for (String alias : card.getAliases()) {
                if (alias != null && alias.length() >= 2 && text.contains(alias)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 渲染：【设定卡】块，pinned 在前带全文，其余摘要；已按 kind 分组。 */
    private String render(List<MaterialCardDTO> pinned, List<MaterialCardDTO> rest) {
        StringBuilder sb = new StringBuilder();
        sb.append("【设定卡（人物/物品/地点设定，必须遵守，不得发明矛盾设定）】\n");
        Map<String, List<MaterialCardDTO>> byKind = new LinkedHashMap<>();
        for (MaterialCardDTO c : pinned) {
            byKind.computeIfAbsent(c.getKind(), k -> new ArrayList<>()).add(c);
        }
        for (MaterialCardDTO c : rest) {
            byKind.computeIfAbsent(c.getKind(), k -> new ArrayList<>()).add(c);
        }
        for (Map.Entry<String, List<MaterialCardDTO>> e : byKind.entrySet()) {
            for (MaterialCardDTO c : e.getValue()) {
                String label = KIND_LABELS.getOrDefault(c.getKind(), c.getKind());
                sb.append("- ").append(c.getName()).append("（").append(label);
                if (c.pinned()) {
                    sb.append("，常驻");
                }
                if ("dead".equals(c.getStatus())) {
                    sb.append("，已死亡");
                } else if ("retired".equals(c.getStatus())) {
                    sb.append("，已退场");
                }
                sb.append("）：");
                String body = c.pinned() && notBlank(c.getContentMd()) ? c.getContentMd() : firstNonBlank(c.getSummary(), c.getContentMd());
                if (body == null) {
                    body = "";
                }
                sb.append(body.strip().replace('\n', '；'));
                sb.append('\n');
            }
        }
        return sb.toString().strip();
    }

    /** 摘要缺省时全文截断（匹配命中卡的兜底）。 */
    private String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) {
            return a;
        }
        if (b != null && b.length() > 200) {
            return b.substring(0, 200) + "…";
        }
        return b;
    }

    private boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
