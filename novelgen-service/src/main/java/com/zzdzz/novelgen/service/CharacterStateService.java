package com.zzdzz.novelgen.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.zzdzz.novelgen.model.entity.CharacterStateDO;
import com.zzdzz.novelgen.service.data.ChapterDataService;
import com.zzdzz.novelgen.service.data.CharacterStateDataService;
import com.zzdzz.novelgen.service.data.WorldStateDataService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 人物状态账：把 digest 的 world_states（jsonb，键 {@code locations}/{@code possessions} 本来就按人组织）
 * **投影成可查的表** character_states（V39），并据此做出稿后的一致性核对。
 *
 * <p>为什么不新增 LLM 调用：信息全在已有 digest 输出里，缺的只是「按人查、跨章比」的能力。
 * 投影零成本、可重算（{@link #project} 整章先删后插），存量书用 {@link #backfill} 补齐。
 *
 * <p>为什么不做提示词注入：{@code locations}/{@code possessions} 已经随 world_states 整块注入场景与审校提示词
 * （见 ContextPackerService#worldState 与 ReviewService#factBaseline），再注入一遍只是重复占上下文。
 * 本表的价值在**可查（按人查历史）与可核对（出稿后校验）**，不在注入。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class CharacterStateService {

    /** 名字长度上限与列宽一致：超长的「名字」是模型噪声（整句当键），跳过并留痕，截断会造出假身份。 */
    private static final int MAX_NAME_LEN = 128;
    private static final int MAX_LOCATION_LEN = 512;
    /** 回填一次最多扫多少章的世界状态（与素材库查看同一量级口径）。 */
    private static final int BACKFILL_LIMIT = 500;

    /**
     * 死亡/离场标记：出现在 location 里即认为该角色已离场。**必须含殡葬字眼**——
     * 真库实证：书 56 第 10 章把苏眠记成「峡上脉流葬台，横索灰布间」（遗体被移上葬台），
     * 不带「葬」字就会被判成「死后复活」的误报。
     */
    private static final List<String> DEATH_MARKS =
            List.of("已死", "死了", "死亡", "阵亡", "殒命", "咽气", "断气", "身亡",
                    "尸", "葬", "坟", "棺", "墓", "殓", "灵堂");
    private final CharacterStateDataService stateData;
    private final WorldStateDataService worldStateData;
    private final ChapterDataService chapterData;
    private final OutlineService outlineService;
    private final EntityAliasService aliasService;
    private final com.fasterxml.jackson.databind.ObjectMapper mapper;

    /**
     * 核对发现（只报不拦——先可查可核对，再谈拦）。{@code backChapter} 是「矛盾被写进来的那一章」
     * （管线据此只报与本章相关的发现，不重复刷屏）。
     */
    public record Finding(String kind, String name, int deathChapter, int backChapter, String detail) {
    }

    // ===== 投影 =====

    /** digest 落库时调用：把该章快照投影成人物账（整章先删后插，重算不留第二份）。 */
    public void project(long novelId, int chapterNo, JsonNode state) {
        List<CharacterStateDataService.Row> rows = rowsOf(chapterNo, state, aliasService.index(novelId));
        if (rows.isEmpty()) {
            stateData.replaceChapter(novelId, chapterNo, List.of());
            return;
        }
        stateData.replaceChapter(novelId, chapterNo, rows);
        log.info("第 {} 章人物状态账投影：{} 行", chapterNo, rows.size());
    }

    /** 存量书补齐：把 world_states 已有的每一章快照投影一遍（幂等，可重复调用）。 */
    public int backfill(long novelId) {
        var states = worldStateData.listByNovel(novelId, BACKFILL_LIMIT);
        int chapters = 0;
        int rows = 0;
        for (var s : states) {
            try {
                JsonNode state = mapper.readTree(s.stateJson());
                List<CharacterStateDataService.Row> rs = rowsOf(s.chapterNo(), state, aliasService.index(novelId));
                stateData.replaceChapter(novelId, s.chapterNo(), rs);
                chapters++;
                rows += rs.size();
            } catch (Exception e) {
                log.warn("人物状态账回填：第 {} 章快照解析失败，跳过（{}）", s.chapterNo(), e.getMessage());
            }
        }
        log.info("人物状态账回填完成：novelId={} {} 章 / {} 行", novelId, chapters, rows);
        return rows;
    }

    /**
     * 快照 → 账行：locations 的键 ∪ possessions 的键，按名字合并成一行。
     * {@code aliasIndex} 是「别名 → 卡名」表（可为空）：同一角色的别名与卡名归一成一行，
     * 不然「老陆」与「陆朴」会各占一行、正文提及也对不上。
     */
    static List<CharacterStateDataService.Row> rowsOf(int chapterNo, JsonNode state, Map<String, String> aliasIndex) {
        Map<String, JsonNode> possessions = new LinkedHashMap<>();
        JsonNode poss = state == null ? null : state.path("possessions");
        if (poss != null && poss.isObject()) {
            poss.fields().forEachRemaining(e -> possessions.put(e.getKey(), e.getValue()));
        }
        JsonNode loc = state == null ? null : state.path("locations");
        Set<String> names = new LinkedHashSet<>();
        if (loc != null && loc.isObject()) {
            loc.fieldNames().forEachRemaining(names::add);
        }
        names.addAll(possessions.keySet());

        List<CharacterStateDataService.Row> rows = new ArrayList<>();
        Set<String> used = new LinkedHashSet<>();
        for (String name : names) {
            String clean = name == null ? "" : name.strip();
            if (clean.isEmpty()) continue;
            clean = canonical(clean, aliasIndex);
            if (!used.add(clean)) {
                continue; // 两个键归一到同一个人（如「老陆」与「陆朴」）：只写一行，位置取先出现的那条
            }
            if (clean.length() > MAX_NAME_LEN) {
                log.warn("人物状态账投影：第 {} 章出现超长「名字」（{} 字，疑似模型把整句当键），跳过", chapterNo, clean.length());
                continue;
            }
            String location = text(loc == null ? null : loc.path(name));
            if (location != null && location.length() > MAX_LOCATION_LEN) {
                location = location.substring(0, MAX_LOCATION_LEN);
            }
            JsonNode p = possessions.get(name);
            String pj = (p != null && p.isArray() && p.size() > 0) ? p.toString() : null;
            rows.add(new CharacterStateDataService.Row(clean, location, pj));
        }
        return rows;
    }

    /** 名字归一化：别名/带括号注释的键都归到卡名；索引为空（无卡的书）时只去括号。 */
    static String canonical(String name, Map<String, String> aliasIndex) {
        if (aliasIndex == null || aliasIndex.isEmpty()) {
            return EntityAliasService.stripTrailingParen(name);
        }
        String hit = aliasIndex.get(name);
        if (hit != null) return hit;
        String stripped = EntityAliasService.stripTrailingParen(name);
        hit = aliasIndex.get(stripped);
        return hit != null ? hit : stripped;
    }

    private static String text(JsonNode n) {
        if (n == null || n.isMissingNode() || n.isNull()) return null;
        String s = n.asText();
        return s == null || s.isBlank() ? null : s.strip();
    }

    // ===== 查询 =====

    public List<CharacterStateDO> byChapter(long novelId, int chapterNo) {
        ensureLedger(novelId);
        return stateData.listByChapter(novelId, chapterNo);
    }

    public List<CharacterStateDO> byNovel(long novelId) {
        ensureLedger(novelId);
        return stateData.listByNovel(novelId);
    }

    /** 表还是空的（存量书从没跑过 digest 投影）就自动回填一次：核对与查看都不该要求用户先手动建账。 */
    private void ensureLedger(long novelId) {
        if (!stateData.listByNovel(novelId).isEmpty()) return;
        backfill(novelId);
    }

    // ===== 账的一致性核对 =====

    /**
     * 账的一致性核对（书级、确定性、零 LLM）：某角色在第 N 章被记为死亡/离场后，若**更靠后的章**
     * 又给他记了不含死亡标记的位置或随身物品，说明账自己前后矛盾——digest 或生成在「这个人已经死了」这件事上漂了。
     *
     * <p>**为什么不做正文层面的逐句核对**（死人是否还在演戏、位置是否凭空跳转）：试过，regex 判不准。
     * 真库实测（书 56/57 全部死亡标记行 × 其后所有章节正文）两条命中全是误报——
     * 「苏眠说得对」「苏弥看得比…」都是**引用亡者的话**，不是亡者开口。
     * 这类判断要读句子，交给 AI 审校轮：它的提示词已经带 world_state 与事实基准（见 ReviewService#factBaseline）。
     * 本核对只做「账 vs 账」，做得到的部分做扎实，比撒一张满屏误报的网有用。
     */
    public List<Finding> audit(long novelId) {
        ensureLedger(novelId);
        Map<String, List<CharacterStateDO>> byName = stateData.listByNovel(novelId).stream()
                .collect(Collectors.groupingBy(CharacterStateDO::getName, LinkedHashMap::new, Collectors.toList()));
        List<Finding> findings = new ArrayList<>();
        for (var e : byName.entrySet()) {
            List<CharacterStateDO> rows = e.getValue().stream()
                    .sorted(java.util.Comparator.comparingInt(CharacterStateDO::getChapterNo)).toList();
            CharacterStateDO death = null;
            for (CharacterStateDO r : rows) {
                if (death == null) {
                    if (isDeathMarked(r.getLocation())) death = r;
                    continue;
                }
                if (isDeathMarked(r.getLocation()) || (r.getLocation() == null && !hasItems(r))) {
                    continue; // 死亡状态被沿用、或该章什么都没记 → 不算矛盾
                }
                findings.add(new Finding("DEAD_STATE_DRIFT", e.getKey(), death.getChapterNo(), r.getChapterNo(),
                        "第 " + death.getChapterNo() + " 章已记「" + death.getLocation() + "」，第 " + r.getChapterNo()
                                + " 章又记为「" + (r.getLocation() == null ? "（只记了随身物品）" : r.getLocation()) + "」"));
                break; // 一个角色只报首次矛盾，避免同一个漂移刷成一串
            }
        }
        if (!findings.isEmpty()) {
            log.warn("人物账核对：{} 条账内矛盾（只报不拦）：{}", findings.size(),
                    findings.stream().map(f -> f.name() + "（第 " + f.deathChapter() + "→" + f.backChapter() + " 章）")
                            .collect(Collectors.joining("、")));
        }
        return findings;
    }

    private static boolean hasItems(CharacterStateDO r) {
        return r.getPossessions() != null && !r.getPossessions().isNull()
                && r.getPossessions().isArray() && !r.getPossessions().isEmpty();
    }

    static boolean isDeathMarked(String location) {
        if (location == null || location.isBlank()) return false;
        return DEATH_MARKS.stream().anyMatch(location::contains);
    }

}
