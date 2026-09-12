package com.zzdzz.novelgen.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzdzz.novelgen.common.web.BizException;
import com.zzdzz.novelgen.common.web.ErrorCode;
import com.zzdzz.novelgen.dao.LlmCallLogDAO;
import com.zzdzz.novelgen.dao.LlmModelPriceDAO;
import com.zzdzz.novelgen.dao.LlmNodeConfigDAO;
import com.zzdzz.novelgen.model.entity.LlmModelPriceDO;
import com.zzdzz.novelgen.model.entity.LlmNodeConfigDO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * LLM 节点路由与计费：平台级 node → 模型/参数覆盖的查询与维护，按价目表（元/百万 tokens，
 * 空闲/高峰两档，输入拆缓存命中与未命中）折算近 7 天节点成本。
 * 任一覆盖字段留空 = 该项走调用方/全局默认；list 合并用量统计与「有用量但未建行」的节点。
 */
@Service
public class LlmNodeConfigService {

    private static final Logger log = LoggerFactory.getLogger(LlmNodeConfigService.class);

    /** 列表行：配置（可为虚拟未建行）+ 近 7 天用量与成本。cost/peakCost 为 null 表示该模型无价目行。 */
    public record NodeVO(Long id, String node, String model, Double temperature, Integer maxTokens,
                         String extraJson, boolean enabled, String remark, boolean configured, LlmNodeStat stat) {
    }

    public record LlmNodeStat(long calls, long totalTokens, long avgLatencyMs, Double cost, Double peakCost) {
    }

    private final LlmNodeConfigDAO configDAO;
    private final LlmCallLogDAO callLogDAO;
    private final LlmModelPriceDAO priceDAO;
    private final ObjectMapper mapper;

    public LlmNodeConfigService(LlmNodeConfigDAO configDAO, LlmCallLogDAO callLogDAO,
                                LlmModelPriceDAO priceDAO, ObjectMapper mapper) {
        this.configDAO = configDAO;
        this.callLogDAO = callLogDAO;
        this.priceDAO = priceDAO;
        this.mapper = mapper;
    }

    // ===== 节点路由 =====

    public List<NodeVO> list() {
        Map<String, LlmModelPriceDO> prices = priceMap();
        Map<String, LlmNodeStat> stats = statsSince(7, prices);
        Map<String, NodeVO> out = new LinkedHashMap<>();
        for (LlmNodeConfigDO c : configDAO.listAll()) {
            out.put(c.node(), toVO(c, stats.get(c.node())));
        }
        // 有用量但没建配置行的节点：虚拟行补进来（前端可一键按此节点建行）
        for (Map.Entry<String, LlmNodeStat> e : stats.entrySet()) {
            if (!out.containsKey(e.getKey())) {
                out.put(e.getKey(), new NodeVO(null, e.getKey(), null, null, null, null, true,
                        null, false, e.getValue()));
            }
        }
        return new ArrayList<>(out.values());
    }

    private NodeVO toVO(LlmNodeConfigDO c, LlmNodeStat stat) {
        return new NodeVO(c.id(), c.node(), c.model(), c.temperature(), c.maxTokens(),
                c.extraJson(), c.enabled(), c.remark(), true, stat);
    }

    public void create(String node, String model, Double temperature, Integer maxTokens,
                       String extraJson, Boolean enabled, String remark) {
        validate(node, temperature, maxTokens, extraJson);
        if (configDAO.exists(node)) {
            throw new BizException(ErrorCode.PARAM_ERROR, "节点已存在配置行: " + node);
        }
        configDAO.insert(node, blankToNull(model), temperature, maxTokens, blankToNull(extraJson),
                enabled == null || enabled, remark);
        log.info("节点路由新增：{} model={}", node, model);
    }

    public void update(long id, String model, Double temperature, Integer maxTokens,
                       String extraJson, Boolean enabled, String remark) {
        LlmNodeConfigDO c = require(id);
        validate(c.node(), temperature, maxTokens, extraJson);
        configDAO.update(id, blankToNull(model), temperature, maxTokens, blankToNull(extraJson),
                enabled == null || enabled, remark);
        log.info("节点路由更新：{} model={} temperature={} maxTokens={} enabled={}",
                c.node(), model, temperature, maxTokens, enabled);
    }

    public void delete(long id) {
        require(id);
        configDAO.softDelete(id);
    }

    private LlmNodeConfigDO require(long id) {
        LlmNodeConfigDO c = configDAO.findById(id);
        if (c == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "节点配置不存在: " + id);
        }
        return c;
    }

    // ===== 价目与成本 =====

    public List<LlmModelPriceDO> prices() {
        return priceDAO.listAll();
    }

    public void updatePrice(long id, BigDecimal idleInputHit, BigDecimal idleInputMiss, BigDecimal idleOutput,
                            BigDecimal peakInputHit, BigDecimal peakInputMiss, BigDecimal peakOutput,
                            int peakStartHour, int peakEndHour, String remark) {
        LlmModelPriceDO p = priceDAO.findById(id);
        if (p == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "价目行不存在: " + id);
        }
        validatePrices(idleInputHit, idleInputMiss, idleOutput, peakInputHit, peakInputMiss, peakOutput);
        if (peakStartHour < 0 || peakStartHour > 23 || peakEndHour < 0 || peakEndHour > 23 || peakStartHour >= peakEndHour) {
            throw new BizException(ErrorCode.PARAM_ERROR, "高峰时段须为同日内 0-23 且起始小于结束");
        }
        priceDAO.update(id, idleInputHit, idleInputMiss, idleOutput, peakInputHit, peakInputMiss,
                peakOutput, peakStartHour, peakEndHour, remark);
        log.info("价目更新：{} 高峰 {}-{} 点", p.model(), peakStartHour, peakEndHour);
    }

    /** 近 N 天按 node 聚合用量与成本（无价目行的模型 cost=null）。 */
    private Map<String, LlmNodeStat> statsSince(int days, Map<String, LlmModelPriceDO> prices) {
        Map<String, long[]> acc = new LinkedHashMap<>();   // node -> {calls, tokens, latencySum}
        Map<String, double[]> cost = new HashMap<>();      // node -> {cost, peakCost}，null 用 NaN 表示无价
        for (LlmCallLogDAO.UsageGroup g : callLogDAO.usageByNodeSince(days)) {
            long[] a = acc.computeIfAbsent(g.node(), k -> new long[3]);
            a[0] += g.calls();
            a[1] += g.promptTokens() + g.completionTokens();
            a[2] += g.latencySum();
            LlmModelPriceDO p = g.model() == null ? null : prices.get(g.model());
            if (p == null) {
                cost.put(g.node(), new double[]{Double.NaN, Double.NaN});
                continue;
            }
            double c = costOf(g, p);
            double[] cc = cost.computeIfAbsent(g.node(), k -> new double[]{0, 0});
            cc[0] += c;
            if (g.peak()) {
                cc[1] += c;
            }
        }
        Map<String, LlmNodeStat> out = new LinkedHashMap<>();
        for (Map.Entry<String, long[]> e : acc.entrySet()) {
            double[] cc = cost.getOrDefault(e.getKey(), new double[]{Double.NaN, Double.NaN});
            out.put(e.getKey(), new LlmNodeStat(e.getValue()[0], e.getValue()[1],
                    e.getValue()[0] == 0 ? 0 : e.getValue()[2] / e.getValue()[0],
                    Double.isNaN(cc[0]) ? null : round(cc[0]),
                    Double.isNaN(cc[1]) ? null : round(cc[1])));
        }
        return out;
    }

    /** 单组成本（元）＝(缓存命中×命中价 + 未命中×未命中价 + 输出×输出价)/百万。 */
    private double costOf(LlmCallLogDAO.UsageGroup g, LlmModelPriceDO p) {
        long hit = Math.min(g.cachedTokens(), g.promptTokens());
        long miss = g.promptTokens() - hit;
        BigDecimal inHit = g.peak() ? p.peakInputHit() : p.idleInputHit();
        BigDecimal inMiss = g.peak() ? p.peakInputMiss() : p.idleInputMiss();
        BigDecimal out = g.peak() ? p.peakOutput() : p.idleOutput();
        return hit * inHit.doubleValue() / 1e6 + miss * inMiss.doubleValue() / 1e6
                + g.completionTokens() * out.doubleValue() / 1e6;
    }

    private Map<String, LlmModelPriceDO> priceMap() {
        Map<String, LlmModelPriceDO> map = new HashMap<>();
        for (LlmModelPriceDO p : priceDAO.listAll()) {
            map.put(p.model(), p);
        }
        return map;
    }

    private void validatePrices(BigDecimal... values) {
        for (BigDecimal v : values) {
            if (v == null || v.signum() < 0) {
                throw new BizException(ErrorCode.PARAM_ERROR, "价格须为非负数");
            }
        }
    }

    private void validate(String node, Double temperature, Integer maxTokens, String extraJson) {
        if (node == null || node.isBlank()) {
            throw new BizException(ErrorCode.PARAM_ERROR, "node 不能为空");
        }
        if (temperature != null && (temperature < 0 || temperature > 2)) {
            throw new BizException(ErrorCode.PARAM_ERROR, "temperature 须在 0-2 之间");
        }
        if (maxTokens != null && maxTokens <= 0) {
            throw new BizException(ErrorCode.PARAM_ERROR, "max_tokens 须为正整数");
        }
        if (extraJson != null && !extraJson.isBlank()) {
            try {
                if (!mapper.readTree(extraJson).isObject()) {
                    throw new IllegalArgumentException("须为 JSON 对象");
                }
            } catch (Exception e) {
                throw new BizException(ErrorCode.PARAM_ERROR, "extra_json 不合法：" + e.getMessage());
            }
        }
    }

    private String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }

    private Double round(double v) {
        return BigDecimal.valueOf(v).setScale(4, RoundingMode.HALF_UP).doubleValue();
    }
}
