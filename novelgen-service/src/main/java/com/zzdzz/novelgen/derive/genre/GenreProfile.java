package com.zzdzz.novelgen.derive.genre;

import com.fasterxml.jackson.databind.JsonNode;
import com.zzdzz.novelgen.derive.contract.ChapterScheduleEntry;
import com.zzdzz.novelgen.derive.plan.PlanContracts;
import com.zzdzz.novelgen.derive.story.StoryEvidenceIndex;
import com.zzdzz.novelgen.derive.story.StoryKernel;
import com.zzdzz.novelgen.derive.story.StoryObligation;

import java.util.List;

/**
 * 类型插件合同：解析类型合同、校验合同并投影类型专属叙事义务。
 */
public interface GenreProfile<C extends GenreContract> {

    /** 类型代码；新 run 元数据与提示词路由按该代码选择插件。 */
    String genre();

    /** 解析类型合同 JSON；结构不合法时抛出可喂回模型的异常。 */
    C parseContract(JsonNode root, PlanContracts.ScopeContract scope);

    /** 把冻结合同与章级日程投影为类型专属叙事义务。 */
    List<StoryObligation> projectObligations(C contract,
                                             List<ChapterScheduleEntry> schedule);

    /** 校验合同；返回结构化问题，调用方按严重度裁决。 */
    List<PlanContracts.PlanIssue> validateContract(C contract);

    /** 类型 P6 审计；默认不追加类型问题。 */
    default List<PlanContracts.PlanIssue> auditGate(C contract, StoryKernel kernel,
                                                    StoryEvidenceIndex evidence) {
        return List.of();
    }

    /** 类型 QC 审计；默认不追加类型问题。 */
    default List<PlanContracts.PlanIssue> auditQc(C contract, StoryKernel kernel,
                                                  StoryEvidenceIndex evidence,
                                                  GenreQcAuditor.CompiledDraft draft) {
        return List.of();
    }
}
