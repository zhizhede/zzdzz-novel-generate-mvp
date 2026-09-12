package com.zzdzz.novelgen.llm;

import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * JSON 类 LLM 节点公共件：容错解析（截取首尾大括号 + 容忍裸控制字符 + 修复字符串内裸引号）
 * + 校验失败带原因喂回重试。新节点一律经此调用；outline/digest/review 的内联副本待顺手迁移。
 */
@Component
public class LlmJson {

    /** 校验不合规：message 即喂回给模型的重试原因。 */
    public static class Bad extends RuntimeException {
        public Bad(String reason) { super(reason); }
    }

    private static final Logger log = LoggerFactory.getLogger(LlmJson.class);

    private final LlmPort llm;
    /** LLM 输出专用：容忍字符串内的裸换行/Tab 等控制字符 */
    private final ObjectMapper lenientMapper = JsonMapper.builder()
            .enable(JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS)
            .build();

    public LlmJson(LlmPort llm) {
        this.llm = llm;
    }

    /**
     * 调 LLM 并解析 JSON：parse 抛 Bad（或解析异常）即把原因拼到末条 user 消息后重试，共 tries 轮。
     * 全部失败抛 IllegalStateException（llm_call_log 已逐轮全量留档，可回放）。
     */
    public <T> T ask(LlmPort.ChatRequest request, Function<JsonNode, T> parse, int tries) {
        String feedback = "";
        Exception last = null;
        for (int i = 1; i <= tries; i++) {
            LlmPort.ChatResult r = llm.chat(withFeedback(request, feedback));
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

    /** 容错解析：截取首个 { 到末个 }，失败再试修复字符串值内未转义英文引号。 */
    public JsonNode read(String content) throws Exception {
        String s = content.strip();
        int start = s.indexOf('{');
        int end = s.lastIndexOf('}');
        if (start < 0 || end <= start) throw new Bad("输出中没有 JSON 对象");
        String json = s.substring(start, end + 1);
        try {
            return lenientMapper.readTree(json);
        } catch (Exception first) {
            return lenientMapper.readTree(repairStraightQuotes(json));
        }
    }

    /** 重试轮把失败原因追加到末条（应为 user）消息之后。 */
    private LlmPort.ChatRequest withFeedback(LlmPort.ChatRequest request, String feedback) {
        if (feedback == null || feedback.isEmpty()) return request;
        List<LlmPort.Message> ms = new ArrayList<>(request.messages());
        LlmPort.Message lastMsg = ms.get(ms.size() - 1);
        ms.set(ms.size() - 1, new LlmPort.Message(lastMsg.role(),
                lastMsg.content() + "\n\n【上一次输出不合规：" + feedback + "。请重新输出，只输出合法 JSON。】"));
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
