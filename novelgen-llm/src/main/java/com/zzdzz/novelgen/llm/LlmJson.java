package com.zzdzz.novelgen.llm;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.fasterxml.jackson.core.StreamReadFeature;
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
    /** 校验不合规原因回写该次调用的 llm_call_log（否则账本行只有「成功」，失败原因只活在日志里）。 */
    private final com.zzdzz.novelgen.service.data.LlmCallLogDataService callLogDAO;
    /** LLM 输出专用：容忍字符串内的裸换行/Tab 等控制字符 */
    private final ObjectMapper lenientMapper = JsonMapper.builder()
            .enable(JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS)
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
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
            markCall(r.callLogId(), feedback);
            log.warn("LLM JSON 输出不合规（第 {}/{} 轮）node={} 原因: {}", i, tries, request.node(), feedback);
        }
        throw new IllegalStateException("LLM JSON 输出连续 " + tries + " 轮不合规，末次原因：" + feedback, last);
    }

    /** 把本轮不合规原因落到该次调用行：HTTP 成功但内容不合规，status 仍 ok，原因进 error_msg。 */
    private void markCall(long callLogId, String reason) {
        if (callLogId <= 0 || reason == null || reason.isBlank()) {
            return;
        }
        try {
            com.zzdzz.novelgen.model.entity.LlmCallLogDO row = new com.zzdzz.novelgen.model.entity.LlmCallLogDO();
            row.setId(callLogId);
            row.setErrorMsg(reason.length() <= 2000 ? reason : reason.substring(0, 2000));
            callLogDAO.updateById(row);
        } catch (Exception e) {
            log.warn("回写校验原因失败 callLogId={}：{}", callLogId, e.getMessage());
        }
    }

    /**
     * 容错解析：截取首个 { 到末个 }，失败逐级降级修复
     * （字符串内裸引号 → 缺逗号补插 → 补齐漏写闭符，及两两组合）。缺逗号是模型高频语法病
     * （run20/22 实弹「was expecting comma to separate Object entries」），公共件一处修、全节点受益。
     */
    public JsonNode read(String content) throws Exception {
        String s = content.strip();
        int start = s.indexOf('{');
        int end = s.lastIndexOf('}');
        if (start < 0 || end <= start) throw new Bad("输出中没有 JSON 对象");
        String json = s.substring(start, end + 1);
        try {
            return lenientMapper.readTree(json);
        } catch (Exception first) {
            for (String candidate : List.of(
                    repairDuplicateBeatObjects(json),
                    repairStraightQuotes(json),
                    repairMissingCommas(json),
                    closeUnclosed(json),
                    closeUnclosed(repairMissingCommas(json)),
                    closeUnclosed(repairStraightQuotes(json)),
                    closeUnclosed(repairMissingCommas(repairStraightQuotes(json))))) {
                try {
                    return lenientMapper.readTree(candidate);
                } catch (Exception ignored) {
                    // 逐级降级，全败抛首轮原因——它最贴近真实畸形点
                }
            }
            if (isDuplicateField(first)) {
                throw new Bad("JSON 对象存在重复键：" + duplicateFieldName(first)
                        + "；同一对象内每个 seq/filled 必须写成独立对象");
            }
            throw first;
        }
    }

    /**
     * 修复模型把两个拍塞进同一对象的畸形：
     * {@code {"seq":5,"filled":"A","seq":41,"filled":"B"}}。
     * 仅在 beats 数组顶层对象出现第二个 seq 时拆成两个对象，其他重复键保持失败。
     */
    static String repairDuplicateBeatObjects(String json) {
        StringBuilder out = new StringBuilder(json.length() + 32);
        boolean inStr = false;
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (inStr) {
                out.append(c);
                if (c == '\\' && i + 1 < json.length()) {
                    out.append(json.charAt(++i));
                } else if (c == '"') {
                    inStr = false;
                }
                continue;
            }
            if (c != '"') {
                out.append(c);
                continue;
            }
            int endQuote = findStringEnd(json, i + 1);
            if (endQuote < 0) {
                out.append(json.substring(i));
                break;
            }
            String token = json.substring(i + 1, endQuote);
            int next = skipWhitespace(json, endQuote + 1);
            if ("beats".equals(token) && next < json.length() && json.charAt(next) == ':') {
                int arrayStart = skipWhitespace(json, next + 1);
                if (arrayStart < json.length() && json.charAt(arrayStart) == '[') {
                    int arrayEnd = findMatchingArrayEnd(json, arrayStart);
                    if (arrayEnd > arrayStart) {
                        out.append(json, i, arrayStart + 1);
                        out.append(splitDuplicateSeqObjects(
                                json.substring(arrayStart + 1, arrayEnd)));
                        out.append(']');
                        i = arrayEnd;
                        continue;
                    }
                }
            }
            out.append(json, i, endQuote + 1);
            i = endQuote;
        }
        return out.toString();
    }

    private static String splitDuplicateSeqObjects(String arrayBody) {
        StringBuilder out = new StringBuilder(arrayBody.length() + 16);
        boolean inStr = false;
        int objectDepth = 0;
        boolean seenSeq = false;
        for (int i = 0; i < arrayBody.length(); i++) {
            char c = arrayBody.charAt(i);
            if (inStr) {
                out.append(c);
                if (c == '\\' && i + 1 < arrayBody.length()) {
                    out.append(arrayBody.charAt(++i));
                } else if (c == '"') {
                    inStr = false;
                }
                continue;
            }
            if (c == '"') {
                int endQuote = findStringEnd(arrayBody, i + 1);
                if (endQuote < 0) {
                    out.append(arrayBody.substring(i));
                    break;
                }
                String token = arrayBody.substring(i + 1, endQuote);
                int next = skipWhitespace(arrayBody, endQuote + 1);
                boolean key = next < arrayBody.length() && arrayBody.charAt(next) == ':';
                if (objectDepth == 1 && key && "seq".equals(token) && seenSeq) {
                    int comma = lastNonWhitespace(out);
                    if (comma >= 0 && out.charAt(comma) == ',') {
                        out.setCharAt(comma, '}');
                        out.setLength(comma + 1);
                        out.append(",{");
                        seenSeq = false;
                    }
                }
                if (objectDepth == 1 && key && "seq".equals(token)) {
                    seenSeq = true;
                }
                out.append(arrayBody, i, endQuote + 1);
                i = endQuote;
                continue;
            }
            if (c == '{') {
                if (objectDepth == 0) {
                    seenSeq = false;
                }
                objectDepth++;
            } else if (c == '}') {
                objectDepth = Math.max(0, objectDepth - 1);
            }
            out.append(c);
        }
        return out.toString();
    }

    private static int findStringEnd(String json, int from) {
        for (int i = from; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '\\') {
                i++;
            } else if (c == '"') {
                return i;
            }
        }
        return -1;
    }

    private static int findMatchingArrayEnd(String json, int start) {
        int depth = 0;
        boolean inStr = false;
        for (int i = start; i < json.length(); i++) {
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
            } else if (c == '[') {
                depth++;
            } else if (c == ']' && --depth == 0) {
                return i;
            }
        }
        return -1;
    }

    private static int skipWhitespace(String text, int from) {
        int i = from;
        while (i < text.length() && Character.isWhitespace(text.charAt(i))) {
            i++;
        }
        return i;
    }

    private static int lastNonWhitespace(StringBuilder text) {
        for (int i = text.length() - 1; i >= 0; i--) {
            if (!Character.isWhitespace(text.charAt(i))) {
                return i;
            }
        }
        return -1;
    }

    private static boolean isDuplicateField(Exception e) {
        String message = e.getMessage();
        return message != null && (message.contains("Duplicate field")
                || message.contains("重复键"));
    }

    private static String duplicateFieldName(Exception e) {
        String message = e.getMessage() == null ? "" : e.getMessage();
        int start = message.indexOf("Duplicate field '");
        if (start < 0) {
            return "未知";
        }
        int from = start + "Duplicate field '".length();
        int end = message.indexOf('\'', from);
        return end > from ? message.substring(from, end) : "未知";
    }

    /**
     * 修复缺逗号：在「值结束（字符串/数字/true/false/null/}/]）」与「下一值起始（" { [ 数字…）」
     * 之间且缺分隔符时补逗号（含数组元素间）。跳过字符串内部；数字/关键字整 token 消费
     * （防把 1e+3 拆坏）；合法 JSON 输入原样返回。
     */
    static String repairMissingCommas(String json) {
        StringBuilder sb = new StringBuilder(json.length() + 16);
        boolean inStr = false;
        // 0=起点 1=值结束(可补逗号) 2=逗号后 3=冒号后 4=开括号后
        int state = 0;
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (inStr) {
                sb.append(c);
                if (c == '\\' && i + 1 < json.length()) {
                    sb.append(json.charAt(++i));
                } else if (c == '"') {
                    inStr = false;
                    state = 1;
                }
                continue;
            }
            switch (c) {
                case '"' -> {
                    if (state == 1) sb.append(',');
                    sb.append(c);
                    inStr = true;
                }
                case '{', '[' -> {
                    if (state == 1) sb.append(',');
                    sb.append(c);
                    state = 4;
                }
                case ',' -> {
                    sb.append(c);
                    state = 2;
                }
                case ':' -> {
                    sb.append(c);
                    state = 3;
                }
                case '}', ']' -> {
                    sb.append(c);
                    state = 1;
                }
                case '-', '0', '1', '2', '3', '4', '5', '6', '7', '8', '9' -> {
                    if (state == 1) sb.append(',');
                    sb.append(c);
                    while (i + 1 < json.length()) {
                        char n = json.charAt(i + 1);
                        if ((n >= '0' && n <= '9') || n == '.' || n == 'e' || n == 'E'
                                || n == '+' || n == '-') {
                            sb.append(json.charAt(++i));
                        } else {
                            break;
                        }
                    }
                    state = 1;
                }
                case 't', 'f', 'n' -> { // true / false / null
                    if (state == 1) sb.append(',');
                    sb.append(c);
                    while (i + 1 < json.length() && (json.charAt(i + 1) >= 'a' && json.charAt(i + 1) <= 'z')) {
                        sb.append(json.charAt(++i));
                    }
                    state = 1;
                }
                default -> sb.append(c); // 空白等原样
            }
        }
        return sb.toString();
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
                request.runId(), ms, request.temperature());
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
