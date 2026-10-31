package com.zzdzz.novelgen.derive.service;

import com.zzdzz.novelgen.common.web.BizException;
import com.zzdzz.novelgen.common.web.ErrorCode;
import com.zzdzz.novelgen.derive.mapper.RunMapper;
import com.zzdzz.novelgen.derive.orchestration.DeriveRunOrchestrator;
import com.zzdzz.novelgen.model.entity.derive.RunDO;
import com.zzdzz.novelgen.model.vo.derive.AutoVO;
import com.zzdzz.novelgen.model.vo.derive.DraftVO;
import com.zzdzz.novelgen.model.vo.derive.PlanVO;
import com.zzdzz.novelgen.model.vo.derive.WriteVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 全流程无人值守主控（2026-10-18 用户定调：一个按钮从当前状态跑到终态）。
 *
 * <p>每步只推进一个单元（与各步幂等语义对齐），循环读状态机决定下一步：
 * PLANNING 无书→⑥a；PLANNING 有书→⑥b 单元；REWORK→回炉 RESET；DRAFTING 未过闸→P6 闸；
 * DRAFTING 已过闸→⑦ 逐章；QC→⑧ 质检。打回（闸 REWORK / 质检 REWORK）由状态机自然回到
 * 回炉分支——重试口径全部沿用各步默认（闸轮次熔断、质检三轮熔断），主控不加自己的重试。
 * 终态 PASSED/FAILED 即收束；异常记录 lastError 即停（断点续跑，再点按钮接上）。
 * 内存态：进程重启即停，状态无损，再点续跑。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DeriveAutoFlowService {

    private static final int STEP_FUSE = 500;

    private static final ConcurrentHashMap<Long, Auto> AUTOS = new ConcurrentHashMap<>();

    private final RunMapper runMapper;
    private final DerivePlanService planService;
    private final DeriveDraftService draftService;
    private final DeriveGateService gateService;
    private final DeriveWriteService writeService;
    private final DeriveResetService resetService;
    @Autowired(required = false)
    private DeriveBeatRouteService beatRouteService;
    private final DeriveQcService qcService;
    @Autowired(required = false)
    private DeriveRunOrchestrator runOrchestrator;

    static final class Auto {
        final long runId;
        final long startedMs = System.currentTimeMillis();
        final boolean direct;
        volatile Thread thread;
        volatile boolean stop;
        volatile boolean running = true;
        volatile boolean done;
        volatile String stage = "准备中";
        volatile int steps;
        volatile String lastError = "";

        Auto(long runId, boolean direct) {
            this.runId = runId;
            this.direct = direct;
        }
    }

    /** 同一 run 同时只许一个主控；与 ⑦ 连跑/进行中活动互斥。direct=直通免审（跳过闸与质检的 AI 检测）。 */
    public synchronized AutoVO start(long runId, boolean direct) {
        Auto cur = AUTOS.get(runId);
        if (cur != null && cur.running) {
            throw new BizException(ErrorCode.PARAM_ERROR,
                    "全流程已在跑（" + cur.stage + "，已 " + cur.steps + " 步）——看进度或先停止");
        }
        if (DeriveWriteService.loopRunning(runId)) {
            throw new BizException(ErrorCode.PARAM_ERROR,
                    "该 run 的 ⑦ 连跑正在运行——先停它，或等它写完再启全流程");
        }
        if (DeriveActivity.snapshot(runId).active()) {
            throw new BizException(ErrorCode.PARAM_ERROR, "该 run 有进行中的生成任务，稍后再启动");
        }
        RunDO run = runMapper.selectById(runId);
        if (run == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "run 不存在: " + runId);
        }
        if ("PASSED".equals(run.getStatus()) || "FAILED".equals(run.getStatus())) {
            throw new BizException(ErrorCode.PARAM_ERROR, "run 已是终态 " + run.getStatus());
        }
        if ("SAMPLING".equals(run.getStatus())) {
            throw new BizException(ErrorCode.PARAM_ERROR, "run 采样尚未完成，稍后再启动");
        }
        Auto auto = new Auto(runId, direct);
        AUTOS.put(runId, auto);
        Thread t = new Thread(() -> run(auto), "derive-auto-flow-" + runId);
        t.setDaemon(true);
        auto.thread = t;
        t.start();
        log.info("全流程无人值守启动：runId={} 起点状态={} direct={}", runId, run.getStatus(), direct);
        return vo(auto);
    }

    public AutoVO stop(long runId) {
        Auto a = AUTOS.get(runId);
        if (a == null || !a.running) {
            throw new BizException(ErrorCode.PARAM_ERROR, "该 run 没有正在运行的全流程");
        }
        a.stop = true;
        log.info("全流程无人值守收到停止请求：runId={}（当前步完成后收尾）", runId);
        return vo(a);
    }

    /**
     * 无条件强停（2026-10-18 用户定调：不等返回）：置停止标志 + interrupt 主控线程。
     * 在飞的 LLM 调用被打断（CompletableFuture.get 可中断；极个别阻塞读最迟随 600s 响应超时退出），
     * 线程随即走 finally 收束。HTTP 立即返回，不等线程死透。
     */
    public AutoVO kill(long runId) {
        Auto a = AUTOS.get(runId);
        if (a == null || !a.running) {
            throw new BizException(ErrorCode.PARAM_ERROR, "该 run 没有正在运行的全流程");
        }
        a.stop = true;
        a.stage = "强停信号已发出（不等当前步）";
        Thread t = a.thread;
        if (t != null) {
            t.interrupt();
        }
        log.warn("全流程无条件强停：runId={} interrupt={}", runId, t != null);
        return vo(a);
    }

    public AutoVO status(long runId) {
        Auto a = AUTOS.get(runId);
        return a == null
                ? new AutoVO(runId, false, false, false, "", 0, "", 0, false)
                : vo(a);
    }

    static boolean autoRunning(long runId) {
        Auto a = AUTOS.get(runId);
        return a != null && a.running;
    }

    void run(Auto auto) {
        int transportRetries = 0;
        if (auto.direct) {
            // 直通上下文：本线程内的评审类规则（快热/张力沉淀/反转留尾）降级留痕不拦
            DerivePlanService.DIRECT_PASS.set(Boolean.TRUE);
        }
        try {
            for (int i = 0; i < STEP_FUSE && !auto.stop; i++) {
                try {
                    RunDO run = runMapper.selectById(auto.runId);
                    if (run == null) {
                        auto.lastError = "run 不存在（被删除？）";
                        return;
                    }
                    String st = run.getStatus();
                    switch (st) {
                        case "PASSED", "FAILED" -> {
                            auto.stage = "终态 " + st + ("PASSED".equals(st) ? " ✅" : "（熔断）");
                            auto.done = true;
                            log.info("全流程收束：runId={} 终态={} 共 {} 步", auto.runId, st, auto.steps);
                            return;
                        }
                        case "SAMPLING" -> {
                            auto.lastError = "run 处于 SAMPLING（采样未完成）——停止";
                            return;
                        }
                        case "PLANNING" -> {
                            // ⑥a 单元制：SCALE 段就会建书（novel_id 非空），所以不能用 novel_id 判 ⑥a 完成，
                            // 必须看 plan() 的 allDone；未完成时本轮只推进一步，下一轮继续。
                            PlanVO plan = planService.plan(auto.runId);
                            if (!plan.allDone()) {
                                auto.stage = "⑥a 规划单元 " + plan.unit() + "（窗 " + plan.windowIndex() + "/"
                                        + plan.windowTotal() + "，已排 " + plan.plannedChapters() + "/"
                                        + plan.chapterCount() + " 章）";
                            } else {
                                auto.stage = "⑥b 具体稿（逐单元推进）";
                                DraftVO d = draftService.draft(auto.runId);
                                if ("RESET".equals(d.unit())) {
                                    auto.stage = "回炉清稿完成，从全局头重做";
                                } else if ("HEAD".equals(d.unit())) {
                                    auto.stage = "⑥b 全局头 " + d.batchIndex() + "/" + d.batchTotal()
                                            + "（《" + d.title() + "》）";
                                } else if ("BATCH".equals(d.unit())) {
                                    auto.stage = "⑥b 窗 " + d.batchIndex() + "/" + d.batchTotal() + " 完成";
                                }
                            }
                        }
                        case "REWORK" -> {
                            String target = DeriveGateService.repairTarget(run.getStepStatus());
                            if ("NONE".equals(target) || target.isBlank()) {
                                target = DeriveQcService.repairTarget(run.getStepStatus());
                            }
                            if (target.startsWith("PLAN_VOLUME:")) {
                                int volumeNo = Integer.parseInt(target.substring("PLAN_VOLUME:".length()));
                                if (beatRouteService == null) {
                                    auto.lastError = "结构返工需要 BeatRouteService，当前未装配";
                                    return;
                                }
                                auto.stage = "结构回炉：重排第" + volumeNo + "卷拍路由";
                                beatRouteService.replanVolume(auto.runId, volumeNo);
                                // 局部修复只碰这一卷：清该卷章纲后回 PLANNING，让 ⑥b 增量重做该卷；
                                // 绝不调用整本 draft()——那会走 REWORK 整本清稿，把局部问题放大成全书重生成。
                                draftService.resetVolumeDraft(auto.runId, volumeNo);
                                auto.stage = "结构回炉：只重做第" + volumeNo + "卷具体稿（其余卷保留）";
                                auto.steps++;
                                continue;
                            } else if ("SPINE".equals(target) || "SCALE".equals(target)) {
                                auto.stage = "根因回炉：从 " + target + " 重建故事规划";
                                resetService.replanFrom(auto.runId, target);
                                auto.steps++;
                                continue;
                            }
                            auto.stage = "回炉：只清问题章的章纲与拍，其它章保留";
                            draftService.resetForRepair(auto.runId);
                            auto.steps++;
                            continue;
                        }
                        case "DRAFTING" -> {
                            if (!DeriveGateService.gatePassed(run.getStepStatus())) {
                                if (auto.direct) {
                                    String next = DeriveGateService.withGate(run.getStepStatus(), "PASS",
                                            DeriveGateService.gateRound(run.getStepStatus()),
                                            java.util.List.of("直通模式：免审放行（跳过 P6 立项闸）"), "");
                                    runMapper.updateStepStatus(auto.runId, next);
                                    auto.stage = "直通：跳过 P6 立项闸（免审放行）";
                                } else {
                                    auto.stage = "P6 立项闸评审（六判据，约 2 分钟）";
                                    var g = gateService.gate(auto.runId);
                                    auto.stage = "立项闸 " + g.verdict() + "（第 " + g.round() + " 轮，"
                                            + g.issues().size() + " 个问题）";
                                }
                            } else {
                                if (run.getActivePlanRevision() != null && runOrchestrator != null) {
                                    AutoVO pipeline = runOrchestrator.advance(auto.runId);
                                    auto.stage = "compiler-v1 正文闭环：" + pipeline.stage();
                                    auto.steps++;
                                    if (pipeline.lastError() != null
                                            && !pipeline.lastError().isBlank()) {
                                        auto.lastError = pipeline.lastError();
                                        log.warn("compiler-v1 正文闭环失败：runId={} stage={} error={}",
                                                auto.runId, pipeline.stage(), pipeline.lastError());
                                        return;
                                    }
                                    if ("COMPLETE:DONE".equals(pipeline.stage())) {
                                        runMapper.updateStatus(auto.runId, "PASSED", null);
                                        auto.stage = "compiler-v1 正文闭环完成";
                                        auto.done = true;
                                        log.info("compiler-v1 正文闭环完成：runId={}", auto.runId);
                                        return;
                                    }
                                    continue;
                                }
                                auto.stage = "⑦ 逐章写正文（legacy）";
                                WriteVO w = writeService.writeNext(auto.runId);
                                if (w.allDone() || w.chapterNo() < 0) {
                                    auto.stage = "⑦ 全部正文完成 → 转质检";
                                } else {
                                    auto.stage = "⑦ 第 " + w.chapterNo() + " 章完成（" + w.charCount() + " 字，剩 "
                                            + w.remaining() + " 章）";
                                }
                            }
                        }
                        case "QC" -> {
                            if (auto.direct) {
                                // 直通=只跳 AI 快检五项；脚本硬判照跑、qc_report 落档、章升 QC_PASS
                                // （2026-10-19 修：原整段跳过致无档 PASSED，run29 审稿硬伤全漏）
                                auto.stage = "⑧ 直通质检（脚本硬判，跳过 AI 快检）";
                                var qc = qcService.qc(auto.runId, true);
                                auto.stage = "直通质检 " + qc.verdict()
                                        + "（script " + qc.scriptPassed() + "/" + qc.scriptTotal() + "）";
                            } else {
                                auto.stage = "⑧ 质检（脚本硬判 + AI 快检，10 分钟级）";
                                qcService.qc(auto.runId);
                            }
                        }
                        default -> {
                            auto.lastError = "未知 run 状态：" + st;
                            return;
                        }
                    }
                    transportRetries = 0;
                    auto.steps++;
                } catch (Exception e) {
                    String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                    // 传输类错误（RST/超时/连接被掐）自动重试 1 次——与 ⑦ 连跑同口径
                    if (DeriveWriteService.isTransportError(msg) && transportRetries < 1 && !auto.stop) {
                        transportRetries++;
                        log.warn("全流程传输类错误，自动重试 {}/1：runId={} 原因={}",
                                transportRetries, auto.runId, msg);
                        continue;
                    }
                    auto.lastError = msg;
                    log.warn("全流程无人值守中断：runId={} 步数={} 原因={}", auto.runId, auto.steps, msg);
                    return;
                }
            }
            if (!auto.done && !auto.stop && auto.lastError.isEmpty()) {
                auto.lastError = "达到 " + STEP_FUSE + " 步保险丝上限（异常循环）——已停，请人工检查";
            }
            if (auto.stop) {
                auto.stage = "已人工停止（断点保留，再点可续）";
                log.info("全流程无人值守已停止：runId={} 共 {} 步", auto.runId, auto.steps);
            }
        } finally {
            auto.running = false;
            DerivePlanService.DIRECT_PASS.remove();
        }
    }

    AutoVO vo(Auto a) {
        return new AutoVO(a.runId, a.running, a.stop, a.done, a.stage, a.steps, a.lastError,
                System.currentTimeMillis() - a.startedMs, a.direct);
    }
}
