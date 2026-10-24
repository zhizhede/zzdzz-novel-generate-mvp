package com.zzdzz.novelgen.derive.web;

import com.zzdzz.novelgen.common.web.Result;
import com.zzdzz.novelgen.llm.LlmPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Phase1 验收端点：一次真实 LLM 调用并落 llm_call_log（L1 层台账）。
 * GET /api/derive/ping（登录后）→ MiniMaxClient.chat → 台账返回 callLogId，可与 llm_call_log 行对账。
 */
@RestController
@RequestMapping("/api/derive")
@RequiredArgsConstructor
@Slf4j
public class DerivePingController {

    private final LlmPort llm;

    @GetMapping("/ping")
    public Result<PingVO> ping() {
        LlmPort.ChatResult r = llm.chat(new LlmPort.ChatRequest(
                "derive_ping", null, null,
                List.of(LlmPort.Message.user("回复一个字：pong")),
                0.0));
        log.info("derive ping 完成 callLogId={} tokens={}", r.callLogId(), r.usage().totalTokens());
        return Result.success(new PingVO(r.callLogId(), r.content(), r.usage().totalTokens()));
    }

    public record PingVO(long callLogId, String content, int totalTokens) {}
}
