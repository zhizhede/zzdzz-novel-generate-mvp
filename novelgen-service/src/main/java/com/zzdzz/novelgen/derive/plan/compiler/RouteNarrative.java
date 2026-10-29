package com.zzdzz.novelgen.derive.plan.compiler;

import com.zzdzz.novelgen.derive.plan.PlanContracts;
import java.util.List;

/**
 * ROUTE v2 的线 wire 结构：模型只描述拍的叙事效果与跳过原因。
 *
 * @param beatNarratives 需要进入结构计划的拍叙事效果
 * @param skipped 被模型明确跳过的拍及原因
 */
public record RouteNarrative(
        List<BeatNarrative> beatNarratives,
        List<SkippedBeat> skipped) {

    /**
     * 单个拍的叙事效果。
     *
     * @param beatKey 结构计划给出的稳定拍键
     * @param goal 本拍要完成的叙事目标
     * @param knowledgeEffects 本拍写入的角色知识
     * @param stateEffects 本拍写入的角色状态变化
     * @param threadEffects 本拍对故事线的作用
     */
    public record BeatNarrative(
            String beatKey,
            String goal,
            List<KnowledgeEffect> knowledgeEffects,
            List<StateEffect> stateEffects,
            List<ThreadEffect> threadEffects) {
    }

    /**
     * 角色获得或确认的一条知识。
     *
     * @param characterKeys 获得知识的稳定角色键
     * @param fact 被写入的知识事实
     */
    public record KnowledgeEffect(List<String> characterKeys, String fact) {
    }

    /**
     * 角色状态变化。
     *
     * @param characterKey 稳定角色键
     * @param kind 状态变化类别
     * @param detail 状态变化说明
     */
    public record StateEffect(
            String characterKey,
            PlanContracts.StateChangeKind kind,
            String detail) {
    }

    /**
     * 故事线效果。
     *
     * @param threadKey 稳定线程键
     * @param effect 线程效果类别
     * @param detail 线程效果说明
     */
    public record ThreadEffect(
            String threadKey,
            PlanContracts.ThreadEffectKind effect,
            String detail) {
    }

    /**
     * 被跳过的拍。
     *
     * @param beatKey 结构计划给出的稳定拍键
     * @param reason 跳过原因
     */
    public record SkippedBeat(String beatKey, String reason) {
    }
}