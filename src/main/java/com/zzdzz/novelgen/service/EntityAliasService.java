package com.zzdzz.novelgen.service;

import com.zzdzz.novelgen.model.entity.EntityAliasDO;
import com.zzdzz.novelgen.model.entity.MaterialCardDO;
import com.zzdzz.novelgen.service.data.EntityAliasDataService;
import com.zzdzz.novelgen.service.data.MaterialCardDataService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * 别名索引（V40）：把 material_cards.aliases（jsonb 数组，只能整卡加载后在内存里比）做成**可反查**的索引，
 * 让「正文/人物账里出现的这个名字是谁」一步可查。
 *
 * <p>**派生索引，不是第二个真源**：卡一写就整书重建（{@link MaterialCardService}），
 * 存量书用 {@link #rebuild} 补齐，索引随时可丢弃重建。
 *
 * <p>唯一真正的消费者是**名字归一化**（{@link #canonicalName}）：人物账的名字来自模型输出，
 * 可能是别名（老陆）、也可能带括号注释（孩子（沈砚之子，名沈砚）），与卡名对不上，
 * 任何按名字的比对都会漏——归一到卡名后，「同一个人的账」才真的合并成一行、正文提及才匹配得上。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class EntityAliasService {

    /** 别名过短容易误命中（「灯」「门」这类），与素材卡别名命中口径一致：至少 2 字。 */
    private static final int MIN_ALIAS_LEN = 2;

    private final EntityAliasDataService aliasData;
    private final MaterialCardDataService cardDAO;

    /** 反查一行（无则 null）。 */
    public EntityAliasDO resolve(long novelId, String name) {
        if (name == null || name.isBlank()) return null;
        ensureIndexed(novelId);
        return aliasData.findByAlias(novelId, name.strip());
    }

    public List<EntityAliasDO> list(long novelId) {
        ensureIndexed(novelId);
        return aliasData.listByNovel(novelId);
    }

    /**
     * 名字归一化（人物账与正文比对用）：① 卡名/别名精确命中 → 用卡名；② 去掉尾部括号注释后再试一次
     * （真库有 {@code 孩子（沈砚之子，名沈砚）} 这种把说明写进名字的键）；③ 都不中就用原名字（去括号后）。
     */
    public String canonicalName(long novelId, String raw) {
        if (raw == null || raw.isBlank()) return raw;
        Map<String, String> index = index(novelId);
        String name = raw.strip();
        String hit = index.get(name);
        if (hit != null) return hit;
        String stripped = stripTrailingParen(name);
        if (!stripped.equals(name)) {
            hit = index.get(stripped);
            return hit != null ? hit : stripped;
        }
        return name;
    }

    /** 整书的「名字 → 卡名」索引（一次加载，多次归一化用；人物账投影每章有十几行）。 */
    public Map<String, String> index(long novelId) {
        ensureIndexed(novelId);
        Map<String, String> map = new LinkedHashMap<>();
        for (EntityAliasDO a : aliasData.listByNovel(novelId)) {
            map.putIfAbsent(a.getAlias(), a.getCardName());
        }
        return map;
    }

    /** 去掉尾部完整的全角/半角括号注释：「孩子（沈砚之子）」→「孩子」。没有则原样返回。 */
    static String stripTrailingParen(String name) {
        int cut = -1;
        if (name.endsWith("）")) cut = name.lastIndexOf('（');
        else if (name.endsWith(")")) cut = name.lastIndexOf('(');
        if (cut <= 0) return name;
        String head = name.substring(0, cut).strip();
        return head.isEmpty() ? name : head;
    }

    /** 从素材卡整书重建别名索引（卡写路径与手动端点都走它；幂等）。 */
    public int rebuild(long novelId) {
        List<MaterialCardDO> cards = cardDAO.listByNovel(novelId, null);
        List<EntityAliasDataService.Row> rows = new ArrayList<>();
        for (MaterialCardDO c : cards) {
            if (c.getName() == null || c.getName().isBlank()) continue;
            rows.add(new EntityAliasDataService.Row(c.getId(), c.getName().strip(), c.getName().strip(),
                    c.getKind(), true));
            if (c.getAliases() == null) continue;
            for (String a : new LinkedHashSet<>(c.getAliases())) {
                if (a == null || a.isBlank()) continue;
                String alias = a.strip();
                if (alias.length() < MIN_ALIAS_LEN || alias.equals(c.getName().strip())) continue;
                rows.add(new EntityAliasDataService.Row(c.getId(), alias, c.getName().strip(), c.getKind(), false));
            }
        }
        // 同书内一个别名只许指向一张卡（唯一索引兜着）：DB 冲突会让整次重建失败，故先在这里去重并留痕
        Map<String, EntityAliasDataService.Row> dedup = new LinkedHashMap<>();
        for (EntityAliasDataService.Row r : rows) {
            EntityAliasDataService.Row prev = dedup.putIfAbsent(r.alias(), r);
            if (prev != null && prev.cardId() != r.cardId()) {
                log.warn("别名索引：本书内「{}」同时属于「{}」与「{}」，保留前者（后者请改别名）",
                        r.alias(), prev.cardName(), r.cardName());
            }
        }
        aliasData.rebuild(novelId, new ArrayList<>(dedup.values()));
        log.info("别名索引重建：novelId={} 卡 {} 张 / 可反查名字 {} 个", novelId, cards.size(), dedup.size());
        return dedup.size();
    }

    /** 表为空时自动补一次（存量书的查看与归一化不该要求先手动重建）。 */
    private void ensureIndexed(long novelId) {
        if (!aliasData.listByNovel(novelId).isEmpty()) return;
        if (!cardDAO.hasCards(novelId)) return; // 无卡的书没有索引可建，别每次调用都白重建一遍
        rebuild(novelId);
    }
}
