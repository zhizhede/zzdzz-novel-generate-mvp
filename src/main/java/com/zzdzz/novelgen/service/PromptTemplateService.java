package com.zzdzz.novelgen.service;

import com.zzdzz.novelgen.common.web.BizException;
import com.zzdzz.novelgen.common.web.ErrorCode;
import com.zzdzz.novelgen.dao.PromptTemplateDAO;
import com.zzdzz.novelgen.llm.PromptCatalog;
import com.zzdzz.novelgen.model.entity.PromptTemplateDO;
import com.zzdzz.novelgen.model.vo.PromptDetailVO;
import com.zzdzz.novelgen.model.vo.PromptTemplateVO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.IllegalFormatException;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 提示词注册表服务：启动时把 PromptCatalog 同步落库（缺失才插入；代码模板变更且未被人工定制才更新），
 * 提供带 30s 缓存的读取与 fail-open 格式化——库内模板格式化异常时自动回退代码模板，生成管线永不因坏模板中断。
 */
@Service
public class PromptTemplateService {

    private static final Logger log = LoggerFactory.getLogger(PromptTemplateService.class);
    private static final Pattern FORMAT_SPEC = Pattern.compile("%[a-zA-Z%]");
    private static final long CACHE_TTL_MS = 30_000;

    private final PromptTemplateDAO dao;
    private final Map<String, String> cache = new HashMap<>();
    private volatile long cacheLoadedAt = 0;

    public PromptTemplateService(PromptTemplateDAO dao) {
        this.dao = dao;
    }

    /** 应用就绪后同步目录（幂等；custom 行永不覆盖）。 */
    @EventListener(ApplicationReadyEvent.class)
    public void syncOnStartup() {
        for (PromptCatalog.TemplateDef t : PromptCatalog.ALL) {
            dao.sync(t.node(), t.phase(), t.title(), t.content(), t.exact(), md5(t.content()));
        }
        cacheLoadedAt = 0;
        log.info("提示词注册表同步完成（{} 条）", PromptCatalog.ALL.size());
    }

    /** 取模板：库内 enabled 行优先，否则回退代码模板（fail-open）。 */
    public String get(String node, String phase, String fallback) {
        Map<String, String> c = cache();
        return c.getOrDefault(node + "|" + phase, fallback);
    }

    /** 取模板并格式化：库内模板占位符损坏时回退代码模板，抛错只记日志。 */
    public String format(String node, String phase, String fallback, Object... args) {
        return formatSafe(get(node, phase, fallback), fallback, node + "/" + phase, args);
    }

    /** fail-open 格式化：模板占位符与参数不匹配时回退 fallback（记 warn）。 */
    static String formatSafe(String tpl, String fallback, String tag, Object... args) {
        try {
            return String.format(tpl, args);
        } catch (IllegalFormatException e) {
            log.warn("提示词 {} 库内模板格式化失败（{}），回退代码模板", tag, e.getMessage());
            return String.format(fallback, args);
        }
    }

    /** 人工编辑：仅 exact 行可编辑，占位符序列必须与代码目录一致（防坏模板；运行时另有 fail-open 兜底）。 */
    public PromptDetailVO updateContent(long id, String content) {
        PromptTemplateDO t = require(id);
        PromptCatalog.TemplateDef def = catalogDef(t.node(), t.phase())
                .orElseThrow(() -> new IllegalArgumentException("该条为运行时拼接骨架，未接入库读取，不可编辑"));
        if (content == null || content.isBlank()) {
            throw new BizException(ErrorCode.PARAM_ERROR, "模板内容不能为空");
        }
        if (!specs(content).equals(specs(def.content()))) {
            throw new BizException(ErrorCode.PARAM_ERROR, "占位符序列与代码模板不一致（须保持 %s/%d 的数量与顺序）");
        }
        if (dao.updateContent(id, content) == 0) {
            throw new BizException(ErrorCode.STATE_CONFLICT, "保存失败：模板已被其他操作变更");
        }
        cacheLoadedAt = 0;
        return detail(id);
    }

    /** 重置：清 custom、内容对齐代码目录。 */
    public PromptDetailVO reset(long id) {
        PromptTemplateDO t = require(id);
        PromptCatalog.TemplateDef def = catalogDef(t.node(), t.phase())
                .orElseThrow(() -> new IllegalArgumentException("目录中不存在该节点，无法重置"));
        dao.reset(id, def.content(), md5(def.content()));
        cacheLoadedAt = 0;
        return detail(id);
    }

    public List<PromptTemplateVO> list() {
        return dao.findAll().stream()
                .map(t -> new PromptTemplateVO(t.id(), t.node(), t.phase(), t.title(),
                        t.exact(), t.version(), t.custom(), t.enabled(), t.content().length(), t.updateTime()))
                .toList();
    }

    public PromptDetailVO detail(long id) {
        PromptTemplateDO t = require(id);
        return new PromptDetailVO(t.id(), t.node(), t.phase(), t.title(),
                t.content(), t.exact(), t.version(), t.custom(), t.enabled());
    }

    private PromptTemplateDO require(long id) {
        return dao.findById(id).orElseThrow(() -> new NoSuchElementException("提示词不存在: " + id));
    }

    private java.util.Optional<PromptCatalog.TemplateDef> catalogDef(String node, String phase) {
        return PromptCatalog.ALL.stream()
                .filter(d -> d.node().equals(node) && d.phase().equals(phase))
                .findFirst();
    }

    private Map<String, String> cache() {
        Map<String, String> c = cache;
        if (c.isEmpty() || System.currentTimeMillis() - cacheLoadedAt > CACHE_TTL_MS) {
            synchronized (this) {
                if (cache.isEmpty() || System.currentTimeMillis() - cacheLoadedAt > CACHE_TTL_MS) {
                    Map<String, String> fresh = new HashMap<>();
                    for (PromptTemplateDO t : dao.findAll()) {
                        if (t.enabled()) fresh.put(t.node() + "|" + t.phase(), t.content());
                    }
                    cache.clear();
                    cache.putAll(fresh);
                    cacheLoadedAt = System.currentTimeMillis();
                }
            }
        }
        return cache;
    }

    /** 提取格式化占位符序列（%s/%d/%%…），用于编辑校验。 */
    static List<String> specs(String tpl) {
        Matcher m = FORMAT_SPEC.matcher(tpl);
        List<String> out = new java.util.ArrayList<>();
        while (m.find()) out.add(m.group());
        return out;
    }

    static String md5(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest(s.getBytes(StandardCharsets.UTF_8))) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
