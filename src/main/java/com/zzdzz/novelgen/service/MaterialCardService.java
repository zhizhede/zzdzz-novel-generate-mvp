package com.zzdzz.novelgen.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.zzdzz.novelgen.common.web.BizException;
import com.zzdzz.novelgen.common.web.ErrorCode;
import com.zzdzz.novelgen.service.data.MaterialCardDataService;
import com.zzdzz.novelgen.model.entity.MaterialCardDO;
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
            MaterialCardDO.KIND_CHARACTER, MaterialCardDO.KIND_ITEM, MaterialCardDO.KIND_LOCATION,
            MaterialCardDO.KIND_LANDMARK, MaterialCardDO.KIND_PHENOMENON, MaterialCardDO.KIND_DISASTER,
            MaterialCardDO.KIND_ORG, MaterialCardDO.KIND_MISC);

    private static final Set<String> STATUSES = Set.of("active", "retired", "dead", "merged");

    /** kind → 中文标签（注入文本用）。 */
    private static final Map<String, String> KIND_LABELS = Map.of(
            MaterialCardDO.KIND_CHARACTER, "角色", MaterialCardDO.KIND_ITEM, "物品",
            MaterialCardDO.KIND_LOCATION, "地点", MaterialCardDO.KIND_LANDMARK, "地标",
            MaterialCardDO.KIND_PHENOMENON, "现象", MaterialCardDO.KIND_DISASTER, "灾害",
            MaterialCardDO.KIND_ORG, "组织", MaterialCardDO.KIND_MISC, "其他");

    /** 单场景匹配命中卡上限的 tuning 键（常驻卡不占额），防上下文膨胀。 */
    private static final String MAX_MATCHED_KEY = "pack_max_matched_cards";

    private final MaterialCardDataService cardDAO;
    private final EntityAliasService aliasService;
    private final TuningService tuning;
    private final PromptTemplateService promptTemplates;


    /** 向量化文本（RAG 索引用）：类型标签 + 名 + 别名 + 摘要 + 正文。 */
    public String embeddingText(MaterialCardDO card) {
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
    public MaterialCardDO get(long id) {
        MaterialCardDO card = cardDAO.findById(id);
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
        rebuildAliases(novelId); // 别名索引是派生索引：卡一变就整书重建（一本书几十张卡，代价可忽略）
    }

    public void update(long id, String name, List<String> aliases, String summary, String contentMd,
                       Boolean pinned, String status, Integer sourceChapter) {
        MaterialCardDO card = get(id);
        validate(card.getKind(), name, status);
        // create 一直查重、update 原先没查：把卡名改成同类型下另一张活卡的名字，会直接撞
        // uq_material_cards_novel_kind_name（界面只看到数据库键冲突）。这里补齐，自己除外。
        if (cardDAO.existsOther(card.getNovelId(), card.getKind(), name, id)) {
            throw new BizException(ErrorCode.STATE_CONFLICT, "同类型下已存在同名卡: " + name);
        }
        cardDAO.update(id, name, aliases, summary, contentMd, pinned,
                status == null ? card.getStatus() : status, sourceChapter);
        rebuildAliases(card.getNovelId());
    }

    public void delete(long id) {
        MaterialCardDO card = get(id);
        cardDAO.delete(id);
        rebuildAliases(card.getNovelId());
    }

    /** 别名索引重建：派生索引不该让卡的写路径失败（索引坏了下次写卡或手动端点会再建）。 */
    private void rebuildAliases(long novelId) {
        try {
            aliasService.rebuild(novelId);
        } catch (Exception e) {
            log.warn("别名索引重建失败（不影响卡本身，反查会用到旧索引）：novelId={} {}", novelId, e.getMessage());
        }
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
        List<MaterialCardDO> all = cardDAO.listByNovel(novelId, null);
        if (all.isEmpty()) {
            return null;
        }
        List<MaterialCardDO> pinned = new ArrayList<>();
        List<MaterialCardDO> matched = new ArrayList<>();
        String text = matchText == null ? "" : matchText;
        for (MaterialCardDO card : all) {
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
        List<MaterialCardDO> all = cardDAO.listByNovel(novelId, null).stream()
                .filter(MaterialCardDO::active).toList();
        if (all.isEmpty()) {
            return null;
        }
        List<MaterialCardDO> pinned = all.stream().filter(MaterialCardDO::pinned).toList();
        List<MaterialCardDO> rest = all.stream().filter(c -> !c.pinned()).toList();
        return render(pinned, rest);
    }

    /** 别名命中：卡名或任一别名（≥2 字）作为子串出现在文本中。 */
    private boolean hits(MaterialCardDO card, String text) {
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

    /** 渲染：【设定卡】块，pinned 在前带全文，其余摘要；已按 kind 分组。头段文案走 material/card_block_header（落库可编辑）。 */
    private String render(List<MaterialCardDO> pinned, List<MaterialCardDO> rest) {
        StringBuilder rows = new StringBuilder();
        Map<String, List<MaterialCardDO>> byKind = new LinkedHashMap<>();
        for (MaterialCardDO c : pinned) {
            byKind.computeIfAbsent(c.getKind(), k -> new ArrayList<>()).add(c);
        }
        for (MaterialCardDO c : rest) {
            byKind.computeIfAbsent(c.getKind(), k -> new ArrayList<>()).add(c);
        }
        for (Map.Entry<String, List<MaterialCardDO>> e : byKind.entrySet()) {
            for (MaterialCardDO c : e.getValue()) {
                String label = KIND_LABELS.getOrDefault(c.getKind(), c.getKind());
                rows.append("- ").append(c.getName()).append("（").append(label);
                if (c.pinned()) {
                    rows.append("，常驻");
                }
                if ("dead".equals(c.getStatus())) {
                    rows.append("，已死亡");
                } else if ("retired".equals(c.getStatus())) {
                    rows.append("，已退场");
                }
                rows.append("）：");
                String body = c.pinned() && notBlank(c.getContentMd()) ? c.getContentMd() : firstNonBlank(c.getSummary(), c.getContentMd());
                if (body == null) {
                    body = "";
                }
                rows.append(body.strip().replace('\n', '；'));
                rows.append('\n');
            }
        }
        return promptTemplates.getSection("material", "card_block_header",
                Map.of("cards", rows.toString())).strip();
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
