package com.zzdzz.novelgen.derive.draft;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.regex.Pattern;

/** 正文层纯文本规则：只剥离 markdown fence，机器 JSON/过程话一律拒绝。 */
final class DraftTextRules {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern FENCE = Pattern.compile("```(?:text|plaintext)?\\s*\\n(.*?)\\n```",
            Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern MACHINE_JSON_TAIL = Pattern.compile(
            "(?s)\\n\\s*(\\{.*}|\\[.*])\\s*$");
    private static final Pattern META_LEAK = Pattern.compile(
            "估算字数|目标字数带|正文字数|本章\\d+字|挂拍|请提供第[零一二三四五六七八九十百千两\\d]+章"
                    + "|我需要先确认第[零一二三四五六七八九十百千两\\d]+章"
                    + "|我先核对状态包|我先确认第[零一二三四五六七八九十百千两\\d]+章"
                    + "|上一稿|状态包|拍实例|evidence 从正文|章纲明确记载");
    private static final Pattern FORBIDDEN = Pattern.compile(
            "O-P\\d|S-P\\d|W-P\\d|O-F\\d|素材来源|抽取稿|标签层");

    private DraftTextRules() {
    }

    static String stripFence(String raw) {
        if (raw == null) {
            return "";
        }
        String text = raw.trim();
        var matcher = FENCE.matcher(text);
        if (matcher.matches()) {
            text = matcher.group(1).trim();
        }
        return text;
    }

    static String validateScene(String text) {
        if (text == null || text.isBlank()) {
            return "DRAFT_SCENE_EMPTY";
        }
        if (text.contains("```") || hasMachineJsonTail(text)) {
            return "DRAFT_SCENE_INVALID";
        }
        if (text.length() < 20 || META_LEAK.matcher(text).find()
                || FORBIDDEN.matcher(text).find()) {
            return "DRAFT_SCENE_INVALID";
        }
        return null;
    }

    static boolean hasMetaLeak(String text) {
        return text != null && (META_LEAK.matcher(text).find()
                || FORBIDDEN.matcher(text).find());
    }

    static boolean hasMachineJsonTail(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String trimmed = text.trim();
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            try {
                MAPPER.readTree(trimmed);
                return true;
            } catch (Exception ignored) {
                return false;
            }
        }
        var matcher = MACHINE_JSON_TAIL.matcher(trimmed);
        if (!matcher.find()) {
            return false;
        }
        try {
            MAPPER.readTree(matcher.group(1));
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    static int length(String text) {
        return text == null ? 0 : text.replaceAll("\\s", "").length();
    }
}

