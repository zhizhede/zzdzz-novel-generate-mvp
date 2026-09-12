package com.zzdzz.novelgen.service;

import com.zzdzz.novelgen.dao.TuningDAO;
import com.zzdzz.novelgen.model.entity.TuningDO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Tuning 开关中心：管线/门禁/提示词行为参数的类型化访问口。
 * 读库带 30s 缓存；行缺失或值非法一律回退调用方给的代码默认（fail-open，调参表故障不拦管线）。
 * 新调参键先在 V15 种子与调用方默认里登记，改值走素材库「调参」页。
 */
@Service
public class TuningService {

    private static final Logger log = LoggerFactory.getLogger(TuningService.class);

    private static final long TTL_MS = 30_000;

    private final TuningDAO dao;
    private final Map<String, String> cache = new ConcurrentHashMap<>();
    private volatile long loadedAt = 0;

    public TuningService(TuningDAO dao) {
        this.dao = dao;
    }

    public double d(String key, double fallback) {
        return parse(key, fallback, Double::parseDouble);
    }

    public int i(String key, int fallback) {
        return parse(key, fallback, Integer::parseInt);
    }

    private <T> T parse(String key, T fallback, Function<String, T> parser) {
        String v = get(key);
        if (v == null) return fallback;
        try {
            return parser.apply(v.trim());
        } catch (NumberFormatException e) {
            log.warn("tuning {}={} 非数值，回退默认 {}", key, v, fallback);
            return fallback;
        }
    }

    private String get(String key) {
        if (System.currentTimeMillis() - loadedAt > TTL_MS) {
            refresh();
        }
        return cache.get(key);
    }

    /** 全量刷新缓存；查询失败保留旧缓存（fail-open）。 */
    public synchronized void refresh() {
        try {
            Map<String, String> next = new ConcurrentHashMap<>();
            for (TuningDO row : dao.findAll()) {
                next.put(row.key(), row.value());
            }
            cache.clear();
            cache.putAll(next);
            loadedAt = System.currentTimeMillis();
        } catch (Exception e) {
            log.warn("tuning 缓存刷新失败，沿用旧值：{}", e.getMessage());
            loadedAt = System.currentTimeMillis();
        }
    }

    public List<TuningDO> list() {
        return dao.findAll();
    }

    public void update(String key, String value) {
        dao.updateValue(key, value);
        loadedAt = 0;
    }
}
