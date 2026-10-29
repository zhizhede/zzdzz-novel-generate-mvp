package com.zzdzz.novelgen.derive.contract;

import com.zzdzz.novelgen.common.web.BizException;
import com.zzdzz.novelgen.common.web.ErrorCode;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 合同 → 章级日程的纯函数投影器（零依赖、无 IO、可直接单测）。
 *
 * <p>不变量 I2：日程全部由 Java 推导——模型只写“怎么演”，章号只能来自合同。
 * 任何越界、卷号不一致、线程缺失都抛 {@code CONTRACT_SCHEDULE_INVALID：…}，
 * 由调用方 fail-fast（禁止带着不自洽的合同继续跑下游）。
 *
 * <p>单章 plant/payoff 允许超过 3 条（例如揭示章同时兑现多条），只记 WARN 不拦。
 */
@Slf4j
public final class ChapterScheduleProjector {

    /** 日程投影错误码前缀（日志与测试共用）。 */
    public static final String INVALID = "CONTRACT_SCHEDULE_INVALID";

    private ChapterScheduleProjector() {
    }

    /**
     * 把冻结合同投影成每章一条的日程。
     *
     * @param contract     冻结合同（status 必须是 FROZEN，clues 至少 3 条）
     * @param chapterCount 全书章数（由调用方按 scaffold → scope 顺序解析）
     * @param ranges       卷范围，必须连续覆盖 1..chapterCount（单卷传 [{1,1,N}]）
     * @return 按章号升序的日程（每章恰好一条）
     */
    public static List<ChapterScheduleEntry> project(StoryContract contract, int chapterCount,
                                                     List<ScaffoldContract.VolumeRange> ranges) {
        if (contract == null) {
            throw invalid("缺少故事合同");
        }
        if (!"FROZEN".equals(contract.status())) {
            throw invalid("合同未冻结（status=" + contract.status() + "）");
        }
        if (contract.clues() == null || contract.clues().size() < 3) {
            throw invalid("合同线索少于 3 条（实际 "
                    + (contract.clues() == null ? 0 : contract.clues().size()) + "）");
        }
        if (chapterCount < 1) {
            throw invalid("chapterCount 必须≥1（实际 " + chapterCount + "）");
        }
        List<ScaffoldContract.VolumeRange> volumeRanges = normalizeRanges(ranges, chapterCount);
        StoryContract.Truth truth = contract.truth();
        if (truth == null || truth.revealChapter() < 1 || truth.revealChapter() > chapterCount) {
            throw invalid("revealChapter 越界（"
                    + (truth == null ? "null" : truth.revealChapter()) + " 不在 1.." + chapterCount + "）");
        }
        Map<String, StoryContract.ThreadRef> threadByKey = new LinkedHashMap<>();
        for (StoryContract.ThreadRef thread : contract.threads()) {
            threadByKey.put(thread.threadKey(), thread);
        }
        Map<Integer, List<String>> plants = new HashMap<>();
        Map<Integer, List<String>> payoffs = new HashMap<>();
        Set<String> clueKeys = new LinkedHashSet<>();
        for (StoryContract.Clue clue : contract.clues()) {
            if (clue.clueKey() == null || clue.clueKey().isBlank()) {
                throw invalid("存在没有 clueKey 的线索");
            }
            clueKeys.add(clue.clueKey());
            if (clue.plantedChapter() < 1 || clue.plantedChapter() > chapterCount
                    || clue.payoffChapter() < 1 || clue.payoffChapter() > chapterCount) {
                throw invalid("线索 " + clue.clueKey() + " 章号越界（plant="
                        + clue.plantedChapter() + ", payoff=" + clue.payoffChapter()
                        + ", chapterCount=" + chapterCount + "）");
            }
            if (clue.payoffChapter() <= clue.plantedChapter()) {
                throw invalid("线索 " + clue.clueKey() + " 兑现章必须晚于埋设章（plant="
                        + clue.plantedChapter() + ", payoff=" + clue.payoffChapter() + "）");
            }
            StoryContract.ThreadRef thread = threadByKey.get(clue.clueKey());
            if (thread == null) {
                throw invalid("线索 " + clue.clueKey() + " 在合同 threads 里没有同 key 线程");
            }
            int plantVolume = volumeOf(volumeRanges, clue.plantedChapter());
            int payoffVolume = volumeOf(volumeRanges, clue.payoffChapter());
            if (thread.openedVolume() != plantVolume || thread.payoffVolume() != payoffVolume) {
                throw invalid("线索 " + clue.clueKey() + " 章号与线程卷号不一致（线程 open="
                        + thread.openedVolume() + "/payoff=" + thread.payoffVolume()
                        + "，章号推出 open=" + plantVolume + "/payoff=" + payoffVolume + "）");
            }
            plants.computeIfAbsent(clue.plantedChapter(), key -> new ArrayList<>())
                    .add(clue.clueKey());
            payoffs.computeIfAbsent(clue.payoffChapter(), key -> new ArrayList<>())
                    .add(clue.clueKey());
        }
        List<ChapterScheduleEntry> entries = new ArrayList<>();
        for (int chapterNo = 1; chapterNo <= chapterCount; chapterNo++) {
            List<String> mustPlant = sorted(plants.get(chapterNo));
            List<String> mustPayoff = sorted(payoffs.get(chapterNo));
            if (mustPlant.size() > 3 || mustPayoff.size() > 3) {
                log.warn("章级日程单章线索过多（不拦）：chapter={} plant={} payoff={}",
                        chapterNo, mustPlant.size(), mustPayoff.size());
            }
            entries.add(new ChapterScheduleEntry(chapterNo,
                    volumeOf(volumeRanges, chapterNo),
                    truth.revealChapter() == chapterNo,
                    mustPlant, mustPayoff));
        }
        return List.copyOf(entries);
    }

    /** 某章所属卷号；ranges 必须已覆盖 1..chapterCount。 */
    public static int volumeOf(List<ScaffoldContract.VolumeRange> ranges, int chapterNo) {
        for (ScaffoldContract.VolumeRange range : ranges) {
            if (chapterNo >= range.fromChapter() && chapterNo <= range.toChapter()) {
                return range.volumeNo();
            }
        }
        throw invalid("第 " + chapterNo + " 章不在任何卷范围内");
    }

    /** 把一组日程压成「章号 → 日程」的只读映射。 */
    public static Map<Integer, ChapterScheduleEntry> byChapter(List<ChapterScheduleEntry> entries) {
        Map<Integer, ChapterScheduleEntry> out = new LinkedHashMap<>();
        if (entries != null) {
            entries.forEach(entry -> out.put(entry.chapterNo(), entry));
        }
        return out;
    }

    /**
     * 卷范围必须是 1..chapterCount 的连续切分，卷号从 1 递增——否则合同与 scaffold 自相矛盾。
     */
    static List<ScaffoldContract.VolumeRange> normalizeRanges(
            List<ScaffoldContract.VolumeRange> ranges, int chapterCount) {
        if (ranges == null || ranges.isEmpty()) {
            throw invalid("缺少卷范围（scaffold/volume_plan 均不可用）");
        }
        List<ScaffoldContract.VolumeRange> ordered = new ArrayList<>(ranges);
        ordered.sort(java.util.Comparator.comparingInt(ScaffoldContract.VolumeRange::volumeNo));
        int expectFrom = 1;
        for (int i = 0; i < ordered.size(); i++) {
            ScaffoldContract.VolumeRange range = ordered.get(i);
            if (range.volumeNo() != i + 1) {
                throw invalid("卷号必须从 1 连续（第 " + (i + 1) + " 段实际 volumeNo="
                        + range.volumeNo() + "）");
            }
            if (range.fromChapter() != expectFrom || range.toChapter() < range.fromChapter()) {
                throw invalid("卷范围不连续（第 " + range.volumeNo() + " 卷 "
                        + range.fromChapter() + ".." + range.toChapter()
                        + "，期望从 " + expectFrom + " 起）");
            }
            expectFrom = range.toChapter() + 1;
        }
        if (expectFrom != chapterCount + 1) {
            throw invalid("卷范围未覆盖到第 " + chapterCount + " 章（止于 "
                    + (expectFrom - 1) + "）");
        }
        return List.copyOf(ordered);
    }

    private static List<String> sorted(List<String> keys) {
        if (keys == null || keys.isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>(keys);
        out.sort(String::compareTo);
        return List.copyOf(out);
    }

    private static BizException invalid(String detail) {
        return new BizException(ErrorCode.PARAM_ERROR, INVALID + "：" + detail);
    }
}
