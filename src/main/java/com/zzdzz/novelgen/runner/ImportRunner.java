package com.zzdzz.novelgen.runner;

import com.zzdzz.novelgen.model.entity.ChapterDO;
import com.zzdzz.novelgen.dao.CanonDocDAO;
import com.zzdzz.novelgen.dao.ChapterDAO;
import com.zzdzz.novelgen.dao.ForeshadowDAO;
import com.zzdzz.novelgen.dao.NovelDAO;
import com.zzdzz.novelgen.dao.StylePackDAO;
import com.zzdzz.novelgen.dao.UserDAO;
import com.zzdzz.novelgen.service.DigestService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 一次性导入器：canon 文件包 + 风格包 → 入库。幂等，按唯一键跳过；指纹基线可重复刷新。
 * 运行：--import.enabled=true
 * 两种模式：
 * - 书目模式：novelDir/book.yaml 存在时，按其配置导入（title/style_pack/rules_md_file/existing_dir/digest_recent），
 *   并把 existing/ 下「第N章.md」作为现成正文入库（APPROVED），为最近 digest_recent 章补事实账。
 * - 遗留模式：无 book.yaml 时保持夜班守则/手搓风的原导入行为。
 */
@Component
@ConditionalOnProperty(name = "import.enabled", havingValue = "true")
public class ImportRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ImportRunner.class);
    private static final Pattern CHAPTER_FILE = Pattern.compile("第(\\d+)章");

    private final UserDAO userDAO;
    private final StylePackDAO stylePackDAO;
    private final NovelDAO novelDAO;
    private final CanonDocDAO canonDocDAO;
    private final ForeshadowDAO foreshadowDAO;
    private final ChapterDAO chapterDAO;
    private final DigestService digestService;
    private final ObjectMapper mapper;
    private final String novelDir;
    private final String styleDir;

    public ImportRunner(UserDAO userDAO, StylePackDAO stylePackDAO,
                        NovelDAO novelDAO, CanonDocDAO canonDocDAO,
                        ForeshadowDAO foreshadowDAO, ChapterDAO chapterDAO,
                        DigestService digestService, ObjectMapper mapper,
                        @Value("${novelgen.novel-dir:novel/夜班守则}") String novelDir,
                        @Value("${novelgen.style-dir:docs/style}") String styleDir) {
        this.userDAO = userDAO;
        this.stylePackDAO = stylePackDAO;
        this.novelDAO = novelDAO;
        this.canonDocDAO = canonDocDAO;
        this.foreshadowDAO = foreshadowDAO;
        this.chapterDAO = chapterDAO;
        this.digestService = digestService;
        this.mapper = mapper;
        this.novelDir = novelDir;
        this.styleDir = styleDir;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        long userId = seedAdmin();
        Path bookCfg = Path.of(novelDir, "book.yaml");
        if (Files.exists(bookCfg)) {
            importBook(userId, bookCfg);
        } else {
            importLegacy(userId);
        }
    }

    // ===== 书目模式（book.yaml 驱动，支持多书与现成正文续写） =====

    @SuppressWarnings("unchecked")
    private void importBook(long userId, Path bookCfg) throws Exception {
        Map<String, Object> cfg = new Yaml().load(Files.readString(bookCfg));
        String title = (String) cfg.get("title");
        long packId = importBookStylePack(cfg);
        Long exist = novelDAO.findIdByTitle(title);
        long novelId = exist != null ? exist
                : novelDAO.insert(userId, title, (String) cfg.get("description"), packId, "auto");
        if (exist == null) {
            log.info("书目导入：新作品 {} (novelId={})", title, novelId);
        }
        importCanonDocs(novelId);
        Path storyOutline = Path.of(novelDir, "canon/大纲.md");
        if (Files.exists(storyOutline)) {
            importDoc(novelId, "misc", "大纲", Files.readString(storyOutline));
        }
        importForeshadows(novelId);
        importOutline(novelId);
        importExisting(novelId, cfg);
        log.info("导入完成 novelId={} stylePackId={}", novelId, packId);
    }

    @SuppressWarnings("unchecked")
    private long importBookStylePack(Map<String, Object> cfg) throws Exception {
        String packName = (String) cfg.get("style_pack_name");
        String fingerprint = Files.readString(Path.of(novelDir, "style-metrics.json"));
        Long exist = stylePackDAO.findIdByName(packName);
        if (exist != null) {
            stylePackDAO.updateFingerprint(exist, fingerprint);
            return exist;
        }
        String rules = Files.readString(Path.of(novelDir, (String) cfg.get("rules_md_file")));
        return stylePackDAO.insert(packName, (String) cfg.get("style_pack_desc"), rules, fingerprint);
    }

    /** existing/ 下的现成正文：入章（APPROVED）并为最近 digest_recent 章补事实账（续写的前情来源）。 */
    @SuppressWarnings("unchecked")
    private void importExisting(long novelId, Map<String, Object> cfg) throws Exception {
        String dir = (String) cfg.get("existing_dir");
        if (dir == null || dir.isBlank()) return;
        Path existing = Path.of(novelDir, dir);
        if (!Files.isDirectory(existing)) return;
        try (Stream<Path> files = Files.list(existing)) {
            files.filter(f -> CHAPTER_FILE.matcher(f.getFileName().toString()).find())
                    .forEach(f -> importExistingChapter(novelId, f));
        }
        int recent = cfg.get("digest_recent") instanceof Number n ? n.intValue() : 3;
        List<ChapterDO> all = chapterDAO.listSummariesByNovel(novelId);
        // 从最新章往回数，只为有正文的 recent 章补事实账（卷纲规划行没有正文，跳过）
        int done = 0;
        for (int i = all.size() - 1; i >= 0 && done < recent; i--) {
            ChapterDO full = chapterDAO.find(novelId, all.get(i).chapterNo()).orElse(null);
            if (full == null || full.fullText() == null) continue;
            digestService.digest(novelId, full.id(), full.chapterNo(), full.fullText());
            done++;
        }
    }

    private void importExistingChapter(long novelId, Path file) {
        Matcher m = CHAPTER_FILE.matcher(file.getFileName().toString());
        if (!m.find()) return;
        int no = Integer.parseInt(m.group(1));
        if (chapterDAO.exists(novelId, no)) return;
        try {
            String content = Files.readString(file);
            String title = "第" + no + "章";
            for (String l : Files.readAllLines(file)) {
                String s = l.strip();
                if (s.startsWith("## ") && s.length() > 3) { title = s.substring(3).strip(); break; }
                if (s.matches("第\\d章\\s*\\S+.*")) { title = s.replaceFirst("第\\d章\\s*", ""); break; }
            }
            chapterDAO.insertPlan(novelId, no, null, null, title, null, null, "[]", "[]", 0, 0);
            ChapterDO ch = chapterDAO.find(novelId, no).orElseThrow();
            chapterDAO.saveFullText(ch.id(), content);
            chapterDAO.updateStatus(ch.id(), "FINAL");
            log.info("现成正文入库：第 {} 章 {}", no, title);
        } catch (Exception e) {
            throw new IllegalStateException("现成正文导入失败: " + file, e);
        }
    }

    // ===== 遗留模式（夜班守则 / 手搓风） =====

    private void importLegacy(long userId) throws Exception {
        long packId = importStylePack();
        long novelId = importNovel(userId, packId);
        importCanonDocs(novelId);
        importForeshadows(novelId);
        importOutline(novelId);
        log.info("导入完成 novelId={} stylePackId={}", novelId, packId);
    }

    private long seedAdmin() {
        Long id = userDAO.findIdByUsername("admin");
        if (id != null) return id;
        return userDAO.insert("admin", "(pending-m1-web)", "admin");
    }

    /** 风格包：rules_md = 蒸馏报告硬规则块 + E01 范例；fingerprint = 基线指标整体（可重复刷新） */
    private long importStylePack() throws Exception {
        String fingerprint = Files.readString(Path.of(novelDir, "style-metrics.json"));
        Long exist = stylePackDAO.findIdByName("手搓风");
        if (exist != null) {
            stylePackDAO.updateFingerprint(exist, fingerprint);
            return exist;
        }
        String report = Files.readString(Path.of(styleDir, "写手风格蒸馏.md"));
        int marker = report.indexOf("【文风硬规则】");
        String rules = report.substring(report.lastIndexOf("```", marker) + 3, report.indexOf("```", marker)).strip();
        String exemplars = Files.readString(Path.of(styleDir, "exemplars.md"));
        String exemplar = exemplars.substring(exemplars.indexOf("### E01"), exemplars.indexOf("### E02")).strip();
        return stylePackDAO.insert("手搓风", "冷面碎片体：一行一拍/自由间接引语/克制黑暗底色",
                rules + "\n\n【风格范例（逐字原文，严格模仿其分行节奏与口吻）】\n" + exemplar, fingerprint);
    }

    private long importNovel(long userId, long packId) {
        Long exist = novelDAO.findIdByTitle("夜班守则");
        if (exist != null) return exist;
        return novelDAO.insert(userId, "夜班守则",
                "规则怪谈：便利店夜班与不对劲的守则（管线测试作）", packId, "auto");
    }

    private void importCanonDocs(long novelId) throws Exception {
        importDoc(novelId, "world", "世界观", Files.readString(Path.of(novelDir, "canon/world.md")));
        importDoc(novelId, "character", "全员", Files.readString(Path.of(novelDir, "canon/characters.md")));
    }

    private void importDoc(long novelId, String kind, String name, String content) {
        if (canonDocDAO.exists(novelId, kind, name)) return;
        canonDocDAO.insert(novelId, kind, name, content);
    }

    @SuppressWarnings("unchecked")
    private void importForeshadows(long novelId) throws Exception {
        List<Map<String, Object>> items = new Yaml().load(Files.readString(Path.of(novelDir, "canon/foreshadows.yaml")));
        for (Map<String, Object> f : items) {
            String code = (String) f.get("id");
            if (foreshadowDAO.exists(novelId, code)) continue;
            foreshadowDAO.insert(novelId, code, (String) f.get("content"),
                    ((Number) f.get("planted_in")).intValue(), ((Number) f.get("recovered_in")).intValue());
        }
    }

    @SuppressWarnings("unchecked")
    private void importOutline(long novelId) throws Exception {
        Map<String, Object> outline = new Yaml().load(Files.readString(Path.of(novelDir, "canon/outline.yaml")));
        Map<String, Object> def = (Map<String, Object>) outline.get("chapter_default");
        int bMin = ((Number) def.get("budget_min")).intValue();
        int bMax = ((Number) def.get("budget_max")).intValue();
        int added = 0;
        for (Map<String, Object> vol : (List<Map<String, Object>>) outline.get("volumes")) {
            int volNo = ((Number) vol.get("num")).intValue();
            String arc = (String) vol.get("title");
            for (Map<String, Object> ch : (List<Map<String, Object>>) vol.get("chapters")) {
                int no = ((Number) ch.get("num")).intValue();
                if (chapterDAO.exists(novelId, no)) continue;
                chapterDAO.insertPlan(novelId, no, volNo, arc, (String) ch.get("title"),
                        (String) ch.get("goal"), (String) ch.get("hook"),
                        mapper.writeValueAsString(ch.get("rule_refs")),
                        mapper.writeValueAsString(ch.get("foreshadow_refs")), bMin, bMax);
                added++;
            }
        }
        log.info("卷纲导入：新增 {} 章", added);
    }
}
