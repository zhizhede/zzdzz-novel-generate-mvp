package com.zzdzz.novelgen.llm;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.Function;

/**
 * JSON 类 LLM 节点公共件：容错解析（截取首尾大括号 + 容忍裸控制字符 + 修复字符串内裸引号
 * + 补齐漏写的收口括号）+ 校验失败带原因喂回重试。新节点一律经此调用；outline/digest/review 的内联副本待顺手迁移。
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class LlmJson {

    /** 校验不合规：message 即喂回给模型的重试原因。 */
    public static class Bad extends RuntimeException {
        public Bad(String reason) { super(reason); }
    }


    private final LlmPort llm;
    /** JSON 重试喂回句走提示词注册表（common/json_retry_feedback，库值优先可编辑）。 */
    private final com.zzdzz.novelgen.service.PromptTemplateService promptTemplates;
    /** LLM 输出专用：容忍字符串内的裸换行/Tab 等控制字符 */
    private final ObjectMapper lenientMapper = JsonMapper.builder()
            .enable(JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS)
            .build();


    /**
     * 调 LLM 并解析 JSON：parse 抛 Bad（或解析异常）即把原因拼到末条 user 消息后重试，共 tries 轮。
     * 全部失败抛 IllegalStateException（llm_call_log 已逐轮全量留档，可回放）。
     */
    public <T> T ask(LlmPort.ChatRequest request, Function<JsonNode, T> parse, int tries) {
        return ask(request, parse, tries, null);
    }

    /** onDelta 非空走流式（评审思考实时可见；重试轮会重推增量，展示侧只追加不做账）。 */
    public <T> T ask(LlmPort.ChatRequest request, Function<JsonNode, T> parse, int tries,
                     LlmPort.StreamDelta onDelta) {
        String feedback = "";
        Exception last = null;
        for (int i = 1; i <= tries; i++) {
            LlmPort.ChatRequest req = withFeedback(request, feedback);
            LlmPort.ChatResult r;
            try {
                r = onDelta == null ? llm.chat(req) : llm.chatStream(req, onDelta);
            } catch (LlmException e) {
                // 空 content（模型把整段写进思考、正文一个字没吐）＝可重试：喂回原因再来，别直接炸穿调用方
                if (e.getMessage() != null && e.getMessage().contains("响应缺少 content")) {
                    feedback = "上一轮响应 content 为空（整段输出进了思考）——不要只输出思考，"
                            + "必须直接输出 JSON 对象正文";
                    last = e;
                    log.warn("LLM 空 content（第 {}/{} 轮）node={}，喂回重试", i, tries, request.node());
                    continue;
                }
                throw e;
            }
            try {
                return parse.apply(read(r.content()));
            } catch (Bad e) {
                feedback = e.getMessage();
                last = e;
            } catch (Exception e) {
                feedback = "JSON 解析失败：" + e.getMessage();
                last = e;
            }
            log.warn("LLM JSON 输出不合规（第 {}/{} 轮）node={} 原因: {}", i, tries, request.node(), feedback);
        }
        throw new IllegalStateException("LLM JSON 输出连续 " + tries + " 轮不合规，末次原因：" + feedback, last);
    }

    /** 容错解析：截取首个 { 到末个 }，失败逐级降级修复（字符串内裸引号 → 补齐漏写闭符）。 */
    public JsonNode read(String content) throws Exception {
        String s = content.strip();
        int start = s.indexOf('{');
        int end = s.lastIndexOf('}');
        if (start < 0 || end <= start) throw new Bad("输出中没有 JSON 对象");
        String json = s.substring(start, end + 1);
        try {
            return lenientMapper.readTree(json);
        } catch (Exception first) {
            for (String candidate : List.of(repairStraightQuotes(json), closeUnclosed(json),
                    closeUnclosed(repairStraightQuotes(json)))) {
                try {
                    return lenientMapper.readTree(candidate);
                } catch (Exception ignored) {
                    // 逐级降级，全败抛首轮原因——它最贴近真实畸形点
                }
            }
            throw first;
        }
    }

    /**
     * 修复容器收口：模型偶发漏写末尾若干个 } / ]，或把闭符种类写错（ } 写成 ]，run20 实弹
     * 「Unexpected close marker ']': expected '}'」）。前者在末尾补齐；后者按栈顶应有闭符就地纠正，
     * 栈空时的多余闭符直接丢弃——正文本身完好时都能解析。字符串未收口一律不动——那多半是被
     * max_tokens 截断，半截事实账必须显性失败，不许悄悄放行。
     */
    static String closeUnclosed(String json) {
        Deque<Character> open = new ArrayDeque<>();
        boolean inStr = false;
        StringBuilder fixed = null;
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (inStr) {
                if (c == '\\') {
                    i++;
                } else if (c == '"') {
                    inStr = false;
                }
                continue;
            }
            if (c == '"') {
                inStr = true;
            } else if (c == '{' || c == '[') {
                open.push(c);
            } else if (c == '}' || c == ']') {
                if (open.isEmpty()) {
                    // 栈空还来闭符＝多写的：丢弃
                    if (fixed == null) fixed = new StringBuilder(json).deleteCharAt(i);
                    else fixed.deleteCharAt(i--);
                } else {
                    char want = open.peek() == '{' ? '}' : ']';
                    if (c != want) {
                        if (fixed == null) fixed = new StringBuilder(json);
                        fixed.setCharAt(i, want);
                    }
                    open.pop();
                }
            }
        }
        if (inStr) {
            return json;
        }
        if (fixed == null && open.isEmpty()) {
            return json;
        }
        StringBuilder sb = fixed != null ? fixed : new StringBuilder(json);
        while (!open.isEmpty()) {
            sb.append(open.pop() == '{' ? '}' : ']');
        }
        return sb.toString();
    }

    /** 重试轮把失败原因追加到末条（应为 user）消息之后；喂回句模板落库（common/json_retry_feedback）。 */
    private LlmPort.ChatRequest withFeedback(LlmPort.ChatRequest request, String feedback) {
        if (feedback == null || feedback.isEmpty()) return request;
        List<LlmPort.Message> ms = new ArrayList<>(request.messages());
        LlmPort.Message lastMsg = ms.get(ms.size() - 1);
        String tail = promptTemplates.getSection("common", "json_retry_feedback",
                java.util.Map.of("reason", feedback));
        ms.set(ms.size() - 1, new LlmPort.Message(lastMsg.role(), lastMsg.content() + "\n\n" + tail));
        return new LlmPort.ChatRequest(request.node(), request.novelId(), request.chapterId(),
                ms, request.temperature());
    }

    /**
     * 修复字符串值内部的未转义英文双引号（模型高频毛病，症状是「expecting comma to separate Array
     * entries」处撞上中文）：处于字符串内时，若一个引号的后继非空字符是 , } ] : 则视为收口引号，否则替换为「。
     */
    static String repairStraightQuotes(String json) {
        StringBuilder sb = new StringBuilder(json.length() + 16);
        boolean inStr = false;
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (!inStr) {
                if (c == '"') inStr = true;
                sb.append(c);
                continue;
            }
            if (c == '"') {
                int j = i + 1;
                while (j < json.length() && Character.isWhitespace(json.charAt(j))) j++;
                char next = j < json.length() ? json.charAt(j) : '\0';
                if (next == ',' || next == '}' || next == ']' || next == ':') {
                    inStr = false;
                    sb.append('"');
                } else {
                    sb.append('「');
                }
            } else if (c == '\\' && i + 1 < json.length()) {
                sb.append(c).append(json.charAt(++i));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
