package com.zzdzz.novelgen.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.zzdzz.novelgen.llm.LlmTemps;
import com.zzdzz.novelgen.model.enums.ChapterStatus;
import com.zzdzz.novelgen.model.enums.ForeshadowStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.zzdzz.novelgen.common.web.BizException;
import com.zzdzz.novelgen.common.web.ErrorCode;
import com.zzdzz.novelgen.llm.LlmJson;
import com.zzdzz.novelgen.llm.LlmNode;
import com.zzdzz.novelgen.llm.LlmPort;
import com.zzdzz.novelgen.service.data.ChapterDataService;
import com.zzdzz.novelgen.service.data.DigestDataService;
import com.zzdzz.novelgen.service.data.ForeshadowDataService;
import com.zzdzz.novelgen.service.data.WorldStateDataService;
import com.zzdzz.novelgen.model.dto.ChapterDTO;
import com.zzdzz.novelgen.model.dto.ForeshadowDTO;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 章摘要：定稿正文 → 事实账 + 世界状态快照；伏笔状态随章号确定性推进（不走模型）。
 * 本类零提示词文本：所有固定文案走 PromptTemplateService（node+phase 定位，库值优先、目录回退）。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class DigestService {

    /** state 字段规格（digest/state_spec，库值优先可编辑）。 */
    private String stateSpec() {
        return promptTemplates.get("digest", "state_spec");
    }

    private final LlmPort llm;
    private final LlmJson llmJson;
    private final DigestDataService digestData;
    private final ForeshadowDataService foreshadowData;
    private final ChapterDataService chapterData;
    private final WorldStateDataService worldStateData;
    private final PromptTemplateService promptTemplates;
    private final TuningService tuning;


    public void digest(long novelId, long chapterId, int chapterNo, String fullText) {
        if (digestData.existsByChapter(chapterId)) {
            log.info("第 {} 章事实账已存在，跳过", chapterNo);
            chapterData.updateStatus(chapterId, ChapterStatus.DIGESTED.wire());
            return;
        }
        LlmPort.ChatResult r = llm.chat(new LlmPort.ChatRequest(
                LlmNode.DIGEST, novelId, chapterId,
                List.of(LlmPort.Message.system(promptTemplates.format(LlmNode.DIGEST, "system", stateSpec())),
                        LlmPort.Message.user(digestUserPrompt(novelId, chapterId, fullText))), LlmTemps.DIGEST));
        JsonNode node;
        try {
            node = llmJson.read(r.content());
        } catch (Exception e) {
            throw new IllegalStateException("digest JSON 解析失败: " + r.content(), e);
        }
        // 模型偶发无视指令在摘要前加「## 事实账」标题行：入库前剥掉
        String summary = node.path("summary_md").asText("")
                .replaceAll("(?m)^#{1,6}[^\\n]*\\n?", "").strip();
        digestData.insert(chapterId, summary, node.path("facts").toString());
        JsonNode state = node.path("state");
        if (state.isObject() && state.size() > 0) {
            worldStateData.upsert(novelId, chapterNo, state);
            log.info("第 {} 章世界状态快照落库", chapterNo);
        }
        proposeThreads(novelId, chapterNo, node.path("new_threads"));
        foreshadowData.markPlanted(novelId, chapterNo);
        foreshadowData.markRecovered(novelId, chapterNo);
        sweepStaleProposals(novelId, chapterNo);
        chapterData.updateStatus(chapterId, ChapterStatus.DIGESTED.wire());
        log.info("第 {} 章事实账落库（{} tokens）", chapterNo, r.usage().totalTokens());
    }

    /**
     * 伏笔自动园艺第一刀：过期提议归档——digest 每落一章扫一次，proposed 停留超过
     * {@code foreshadow_proposed_max_age} 章（默认 20，0 关闭）说明规划连续多卷未引用，转 dropped 清理
     * 规划上下文的堆积；素材库人工可改回 planned 恢复。逐条 warn 显性化，不静默动账。
     */
    private void sweepStaleProposals(long novelId, int chapterNo) {
        int maxAge = tuning.i("foreshadow_proposed_max_age", TuningDefaults.FORESHADOW_PROPOSED_MAX_AGE);
        if (maxAge <= 0) {
            return;
        }
        foreshadowData.listByNovel(novelId).stream()
                .filter(f -> ForeshadowStatus.PROPOSED.is(f.getStatus())
                        && f.getProposedIn() != null && f.getProposedIn() < chapterNo - maxAge)
                .forEach(f -> {
                    foreshadowData.update(f.getId(), f.getContent(), f.getPlantedIn(), f.getRecoveredIn(),
                            ForeshadowStatus.DROPPED.wire());
                    log.warn("伏笔自动园艺：{} 自第 {} 章提议后 {} 章未被规划引用，已归档 dropped（素材库可恢复）",
                            f.getCode(), f.getProposedIn(), chapterNo - f.getProposedIn());
                });
    }

    /** digest 用户提示：时间锚点段 + 已有伏笔账本段（防同义重复提议）+ 本章全文，三段均落库（digest 命名空间）。 */
    private String digestUserPrompt(long novelId, long chapterId, String fullText) {
        String timeAnchor = chapterData.findById(chapterId)
                .filter(ch -> ch.getTimeNote() != null && !ch.getTimeNote().isBlank())
                .map(ch -> promptTemplates.getSection(LlmNode.DIGEST, "time_anchor",
                        java.util.Map.of("time_note", ch.getTimeNote())))
                .orElse("");
        String ledger = "";
        List<ForeshadowDTO> existing = foreshadowData.listByNovel(novelId);
        if (!existing.isEmpty()) {
            StringBuilder rows = new StringBuilder();
            for (ForeshadowDTO f : existing) {
                rows.append(f.getCode()).append('（').append(f.getStatus()).append('）').append(f.getContent()).append('\n');
            }
            ledger = promptTemplates.getSection(LlmNode.DIGEST, "ledger",
                    java.util.Map.of("rows", rows.toString()));
        }
        return promptTemplates.getSection(LlmNode.DIGEST, "user",
                java.util.Map.of("time_anchor", timeAnchor, "ledger", ledger, "full_text", fullText == null ? "" : fullText));
    }

    /** 自动提议落库：status='proposed'，等素材库人工采纳（→planned）或忽略（→dropped）。 */
    private void proposeThreads(long novelId, int chapterNo, JsonNode threads) {
        if (!threads.isArray() || threads.isEmpty()) {
            return;
        }
        int added = 0;
        int skipped = 0;
        for (JsonNode t : threads) {
            String name = t.path("name").asText("").strip();
            String content = t.path("content").asText("").strip();
            if (name.isBlank() || content.isBlank()) continue;
            String full = name + "：" + content;
            if (foreshadowData.contentExists(novelId, full) || foreshadowData.contentExists(novelId, content)) {
                continue;
            }
            try {
                foreshadowData.insertProposal(novelId, foreshadowData.nextCode(novelId), full, chapterNo);
                added++;
            } catch (Exception dup) {
                // 编码唯一键冲突（并发提议/残留脏行）：跳过该条，不炸整个 digest——但必须显性计数，
                // 静默丢弃曾是伏笔丢失事故的形态（nextCode 已修根因，此处是最后防线）
                skipped++;
                log.warn("伏笔提议 {} 落库冲突，跳过：{}", name, dup.getMessage());
            }
        }
        if (added > 0 || skipped > 0) {
            log.info("第 {} 章伏笔提议：新增 {} 条（PROPOSED 待采纳）{}",
                    chapterNo, added, skipped > 0 ? "，冲突跳过 " + skipped + " 条（编码冲突，需人工核查）" : "");
        }
    }

    /** 存量回填：只产出世界状态快照，不动事实账（轻量调用，逐章触发）。 */
    public void backfillState(long novelId, int chapterNo) {
        ChapterDTO ch = chapterData.find(novelId, chapterNo)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "章不存在: " + chapterNo));
        if (ch.getFullText() == null || ch.getFullText().isBlank()) {
            throw new BizException(ErrorCode.PARAM_ERROR, "该章无正文，无法回填状态");
        }
        LlmPort.ChatResult r = llm.chat(new LlmPort.ChatRequest(
                LlmNode.WORLD_STATE, novelId, ch.getId(),
                List.of(LlmPort.Message.system(promptTemplates.format(LlmNode.WORLD_STATE, "system", stateSpec())),
                        LlmPort.Message.user(promptTemplates.getSection(LlmNode.WORLD_STATE, "user",
                                java.util.Map.of("full_text", ch.getFullText())))), LlmTemps.WORLD_STATE));
        JsonNode node;
        try {
            node = llmJson.read(r.content());
        } catch (Exception e) {
            throw new IllegalStateException("第 " + chapterNo + " 章状态 JSON 解析失败", e);
        }
        // prompt 模板带 "state" 外壳，模型会如实包一层：解包后再校验
        if (node.has("state") && node.path("state").isObject()) {
            node = node.path("state");
        }
        if (!node.isObject() || node.size() == 0) {
            throw new IllegalStateException("第 " + chapterNo + " 章状态输出为空");
        }
        worldStateData.upsert(novelId, chapterNo, node);
        log.info("第 {} 章世界状态回填完成（{} tokens）", chapterNo, r.usage().totalTokens());
    }
}
