<template>
  <div>
    <div style="display: flex; align-items: center; gap: 14px; margin-bottom: 6px">
      <h3 style="margin: 0">素材库</h3>
      <el-select v-model="novelId" style="width: 260px" @change="() => { setSelectedNovelId(novelId); loadAll() }">
        <el-option v-for="n in novels" :key="n.id" :value="n.id" :label="n.title" />
      </el-select>
      <span style="color: #999; font-size: 12px">所有素材按作品隔离</span>
    </div>

    <el-tabs>
      <!-- 素材卡 -->
      <el-tab-pane :label="`素材卡（${cards.length}）`">
        <div style="display: flex; gap: 8px; margin-bottom: 10px; align-items: center">
          <el-button type="primary" size="small" @click="openCard(null)">新增素材卡</el-button>
          <span style="color: #999; font-size: 12px">
            设定层实体（角色/物品/地点/现象/地标/灾害/组织）；生成时按「常驻 + 本场景别名命中」自动取值注入
          </span>
        </div>
        <el-table :data="cards" border size="small" style="max-width: 980px">
          <el-table-column label="类型" width="80">
            <template #default="{ row }">{{ kindLabel[row.kind] || row.kind }}</template>
          </el-table-column>
          <el-table-column prop="name" label="名称" width="120" />
          <el-table-column label="别名" min-width="130">
            <template #default="{ row }">{{ (row.aliases || []).join('、') || '-' }}</template>
          </el-table-column>
          <el-table-column prop="summary" label="摘要" min-width="240" show-overflow-tooltip />
          <el-table-column label="常驻" width="70">
            <template #default="{ row }">
              <el-tag v-if="row.pinned" size="small" type="success">常驻</el-tag>
              <span v-else style="color: #bbb">-</span>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="80">
            <template #default="{ row }">
              <el-tag size="small" :type="{ active: 'info', retired: 'warning', dead: 'danger', merged: 'info' }[row.status]">
                {{ { active: '在场', retired: '退场', dead: '死亡', merged: '合并' }[row.status] }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="150">
            <template #default="{ row }">
              <el-button size="small" @click="openCard(row)">编辑</el-button>
              <el-button size="small" type="danger" plain @click="removeCard(row)">删</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <!-- 正典 -->
      <el-tab-pane :label="`正典文档（${canon.length}）`">
        <div style="display: flex; gap: 8px; margin-bottom: 10px">
          <el-select v-model="newCanon.kind" style="width: 130px" size="small">
            <el-option value="world" label="world 世界观" />
            <el-option value="character" label="character 人物" />
            <el-option value="misc" label="misc 其他" />
          </el-select>
          <el-input v-model="newCanon.name" placeholder="文档名称" style="width: 200px" size="small" />
          <el-button type="primary" size="small" @click="createCanon">新增文档</el-button>
        </div>
        <el-table :data="canon" border size="small" style="max-width: 680px">
          <el-table-column prop="kind" label="类型" width="110" />
          <el-table-column prop="name" label="名称" width="180" />
          <el-table-column label="操作" width="150">
            <template #default="{ row }">
              <el-button size="small" @click="openCanon(row)">编辑</el-button>
              <el-button size="small" type="danger" plain @click="removeCanon(row)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <!-- 伏笔 -->
      <el-tab-pane :label="`伏笔账本（${foreshadows.length}）`">
        <div v-if="foreshadows.some((f) => f.status === 'proposed')" style="margin-bottom: 8px; font-size: 12px; color: #e6a23c">
          有 AI 自动提议的新伏笔待处理——采纳后进入埋设编排，忽略则弃用
        </div>
        <el-table :data="foreshadows" border size="small" style="max-width: 900px">
          <el-table-column prop="code" label="编号" width="70" />
          <el-table-column prop="content" label="内容" min-width="280" show-overflow-tooltip />
          <el-table-column prop="plantedIn" label="埋设章" width="80" />
          <el-table-column prop="recoveredIn" label="回收章" width="80" />
          <el-table-column prop="proposedIn" label="提议章" width="80">
            <template #default="{ row }">{{ row.proposedIn || '-' }}</template>
          </el-table-column>
          <el-table-column label="状态" width="90">
            <template #default="{ row }">
              <el-tag size="small" :type="{ proposed: 'warning', planned: 'info', planted: 'warning', recovered: 'success', dropped: 'danger' }[row.status]">
                {{ { proposed: '待采纳', planned: '计划', planted: '已埋', recovered: '已收', dropped: '弃用' }[row.status] }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="200">
            <template #default="{ row }">
              <template v-if="row.status === 'proposed'">
                <el-button size="small" type="success" plain @click="setForeshadowStatus(row, 'planned')">采纳</el-button>
                <el-button size="small" type="info" plain @click="setForeshadowStatus(row, 'dropped')">忽略</el-button>
              </template>
              <el-button v-else size="small" @click="openForeshadow(row)">修正</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <!-- 事实账 -->
      <el-tab-pane :label="`事实账（${digests.length}）`">
        <div style="color: #999; font-size: 12px; margin-bottom: 8px">续写前情链的唯一来源——发现摘要与正文不符时在此人工修正</div>
        <el-table :data="digests" border size="small" style="max-width: 860px">
          <el-table-column prop="chapterNo" label="章" width="70" />
          <el-table-column prop="contentMd" label="摘要" min-width="480" show-overflow-tooltip />
          <el-table-column label="操作" width="80">
            <template #default="{ row }">
              <el-button size="small" @click="openDigest(row)">修正</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <!-- 世界状态账 -->
      <el-tab-pane :label="`世界状态（${worldStates.length}）`">
        <div style="color: #999; font-size: 12px; margin-bottom: 8px">
          每章一份结构化快照（时间/位置/随身物/新承诺/未解），随事实账自动产出并注入后续生成上下文——写错时在此人工纠偏
        </div>
        <div style="display: flex; gap: 10px; align-items: center; margin-bottom: 10px">
          <el-select v-model="wsChapter" placeholder="选择章号" size="small" style="width: 160px" @change="loadWsForEdit">
            <el-option v-for="w in worldStates" :key="w.chapterNo" :value="w.chapterNo" :label="`第${w.chapterNo}章`" />
          </el-select>
          <el-button size="small" @click="backfillWs" :loading="wsBackfilling">对本章重新抽取</el-button>
          <span style="color: #999; font-size: 12px">回填 = 轻量 LLM 调用重做快照，不动事实账</span>
        </div>
        <div style="max-width: 860px">
          <el-input v-model="wsText" type="textarea" :rows="18" placeholder="选择章号后加载快照 JSON" />
          <div style="margin-top: 8px">
            <el-button type="primary" @click="saveWs">保存纠偏</el-button>
            <span style="color: #999; font-size: 12px; margin-left: 10px">必须是合法 JSON 对象；下一次场景生成即注入</span>
          </div>
        </div>
      </el-tab-pane>

      <!-- 模型路由 -->
      <el-tab-pane :label="`模型路由（${llmNodes.length}）`">
        <div style="display: flex; gap: 14px; align-items: center; margin-bottom: 8px; flex-wrap: wrap">
          <el-tag :type="inPeak ? 'danger' : 'success'" size="small">
            当前{{ inPeak ? '高峰时段（计费 ×2）' : '空闲时段' }}
          </el-tag>
          <span style="color: #999; font-size: 12px">
            平台级配置，所有作品共用；覆盖项留空 = 走全局默认，改完下次调用即生效。近 7 天成本按价目表折算（元）。
          </span>
        </div>

        <el-table :data="llmPrices" border size="small" style="max-width: 1020px; margin-bottom: 6px">
          <el-table-column prop="model" label="模型" width="130" />
          <el-table-column label="空闲价（命中/未命中/输出）" min-width="200">
            <template #default="{ row }">{{ row.idleInputHit }} / {{ row.idleInputMiss }} / {{ row.idleOutput }} {{ row.currency }}/百万</template>
          </el-table-column>
          <el-table-column label="高峰价（命中/未命中/输出）" min-width="200">
            <template #default="{ row }">{{ row.peakInputHit }} / {{ row.peakInputMiss }} / {{ row.peakOutput }} {{ row.currency }}/百万</template>
          </el-table-column>
          <el-table-column label="高峰时段" width="110">
            <template #default="{ row }">{{ row.peakStartHour }}:00 – {{ row.peakEndHour }}:00</template>
          </el-table-column>
          <el-table-column label="操作" width="90">
            <template #default="{ row }">
              <el-button size="small" @click="openPrice(row)">改价</el-button>
            </template>
          </el-table-column>
        </el-table>

        <el-table :data="llmNodes" border size="small" style="max-width: 1020px">
          <el-table-column prop="node" label="节点" width="160" />
          <el-table-column prop="remark" label="说明" min-width="200" show-overflow-tooltip />
          <el-table-column label="模型（留空=默认）" width="140">
            <template #default="{ row }">
              <span v-if="row.configured">{{ row.model || '（默认）' }}</span>
              <el-tag v-else size="small" type="info">未建行</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="近7天" width="180">
            <template #default="{ row }">
              <span v-if="row.stat">{{ row.stat.calls }} 次 / {{ (row.stat.totalTokens / 10000).toFixed(1) }}万 tok / 均 {{ Math.round(row.stat.avgLatencyMs / 1000) }}s</span>
              <span v-else style="color: #bbb">无调用</span>
            </template>
          </el-table-column>
          <el-table-column label="近7天成本" width="150">
            <template #default="{ row }">
              <span v-if="row.stat && row.stat.cost != null">
                ¥{{ row.stat.cost.toFixed(2) }}<span v-if="row.stat.peakCost" style="color: #f56c6c">（高峰 ¥{{ row.stat.peakCost.toFixed(2) }}）</span>
              </span>
              <span v-else-if="row.stat" style="color: #bbb">无价目</span>
              <span v-else style="color: #bbb">-</span>
            </template>
          </el-table-column>
          <el-table-column label="启用" width="70">
            <template #default="{ row }">
              <el-tag v-if="row.configured" size="small" :type="row.enabled ? 'success' : 'info'">
                {{ row.enabled ? '是' : '否' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="130">
            <template #default="{ row }">
              <el-button v-if="row.configured" size="small" @click="openNode(row)">编辑</el-button>
              <el-button v-else size="small" type="primary" plain @click="openNode(row)">建行</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <!-- 调参（平台级行为参数） -->
      <el-tab-pane :label="`调参（${tunings.length}）`">
        <div style="color: #999; font-size: 12px; margin-bottom: 8px">
          管线/门禁/提示词的行为参数，平台级生效（30 秒内）；删掉库内行即回退代码默认。改错会让门禁或自愈行为变形，改前看清说明。
        </div>
        <el-table :data="tunings" border size="small" style="max-width: 900px">
          <el-table-column prop="key" label="键" width="220" />
          <el-table-column prop="description" label="说明" min-width="300" show-overflow-tooltip />
          <el-table-column label="值" width="160">
            <template #default="{ row }">
              <el-input v-model="row.editValue" size="small" />
            </template>
          </el-table-column>
          <el-table-column label="操作" width="90">
            <template #default="{ row }">
              <el-button size="small" type="primary" plain :disabled="row.editValue === row.value"
                @click="saveTuning(row)">保存</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <!-- 风格包 -->
      <el-tab-pane label="风格包">
        <el-tabs v-model="styleTab">
          <el-tab-pane label="规则正文（可编辑）" name="rules">
            <el-input v-model="styleRules" type="textarea" :rows="20" />
            <div style="margin-top: 8px">
              <el-button type="primary" @click="saveStyle">保存规则正文</el-button>
              <span style="color: #999; font-size: 12px; margin-left: 10px">直改生成时的文风指令；指纹阈值不在此改</span>
            </div>
          </el-tab-pane>
          <el-tab-pane label="指纹基线（只读）" name="fingerprint">
            <el-table :data="fingerprintRows" border size="small" style="max-width: 720px">
              <el-table-column prop="metric" label="指标" width="220" />
              <el-table-column prop="value" label="基线值" width="100" />
              <el-table-column prop="tolerance" label="容差" width="100" />
              <el-table-column prop="abs_max" label="天花板" width="100" />
            </el-table>
          </el-tab-pane>
          <el-tab-pane label="门禁配置（可编辑）" name="gate">
            <div style="max-width: 720px">
              <div style="font-size: 13px; margin-bottom: 6px">AI 腔黑名单（每行一个，正文中出现即判未过）</div>
              <el-input v-model="bannedText" type="textarea" :rows="8" placeholder="心中暗想" />
              <div style="display: flex; gap: 12px; align-items: center; margin: 10px 0">
                <span style="font-size: 13px">章长容差（±）</span>
                <el-input-number v-model="lenTol" :min="0" :max="0.5" :step="0.05" size="small" />
                <span style="color: #999; font-size: 12px">预算 2400-3200、容差 0.15 → 实际允许 2040-3680 字</span>
              </div>
              <el-button type="primary" @click="saveGateConfig">保存门禁配置</el-button>
              <span style="color: #999; font-size: 12px; margin-left: 10px">落库于 style_packs.gate_config，下一次门禁检测即生效</span>
            </div>
          </el-tab-pane>
        </el-tabs>
      </el-tab-pane>
    </el-tabs>

    <!-- 价目编辑 -->
    <el-dialog v-model="priceEditor" :title="`改价：${priceForm.model}（元/百万 tokens）`" width="560px">
      <div style="font-size: 13px; margin-bottom: 6px">空闲时段</div>
      <div style="display: flex; gap: 10px; align-items: center; margin-bottom: 10px">
        <span style="font-size: 13px">输入命中</span>
        <el-input-number v-model="priceForm.idleInputHit" :min="0" :step="0.01" size="small" style="width: 110px" />
        <span style="font-size: 13px">未命中</span>
        <el-input-number v-model="priceForm.idleInputMiss" :min="0" :step="0.1" size="small" style="width: 110px" />
        <span style="font-size: 13px">输出</span>
        <el-input-number v-model="priceForm.idleOutput" :min="0" :step="0.5" size="small" style="width: 110px" />
      </div>
      <div style="font-size: 13px; margin-bottom: 6px">高峰时段</div>
      <div style="display: flex; gap: 10px; align-items: center; margin-bottom: 10px">
        <span style="font-size: 13px">输入命中</span>
        <el-input-number v-model="priceForm.peakInputHit" :min="0" :step="0.01" size="small" style="width: 110px" />
        <span style="font-size: 13px">未命中</span>
        <el-input-number v-model="priceForm.peakInputMiss" :min="0" :step="0.1" size="small" style="width: 110px" />
        <span style="font-size: 13px">输出</span>
        <el-input-number v-model="priceForm.peakOutput" :min="0" :step="0.5" size="small" style="width: 110px" />
      </div>
      <div style="display: flex; gap: 10px; align-items: center">
        <span style="font-size: 13px">高峰时段</span>
        <el-input-number v-model="priceForm.peakStartHour" :min="0" :max="23" size="small" style="width: 90px" />
        <span>:00 –</span>
        <el-input-number v-model="priceForm.peakEndHour" :min="0" :max="23" size="small" style="width: 90px" />
        <span>:00</span>
      </div>
      <template #footer>
        <el-button @click="priceEditor = false">取消</el-button>
        <el-button type="primary" @click="savePrice">保存</el-button>
      </template>
    </el-dialog>

    <!-- 模型路由编辑 -->
    <el-dialog v-model="nodeEditor" :title="nodeForm.id ? `编辑节点：${nodeForm.node}` : `新建节点路由：${nodeForm.node}`" width="560px">
      <el-input v-model="nodeForm.model" placeholder="模型名（留空 = 全局默认 MiniMax-M3）" style="margin-bottom: 10px" />
      <div style="display: flex; gap: 14px; align-items: center; margin-bottom: 10px">
        <span style="font-size: 13px">温度（留空=调用方默认）</span>
        <el-input-number v-model="nodeForm.temperature" :min="0" :max="2" :step="0.1" size="small" style="width: 110px" />
        <span style="font-size: 13px">max_tokens</span>
        <el-input-number v-model="nodeForm.maxTokens" :min="0" :step="1000" size="small" style="width: 130px" />
      </div>
      <el-input v-model="nodeForm.extraJson" type="textarea" :rows="3" placeholder='extra 请求参数（JSON 对象，如 {"thinking":{"type":"disabled"}}；留空不传）' style="margin-bottom: 10px" />
      <div style="display: flex; gap: 14px; align-items: center">
        <el-switch v-model="nodeForm.enabled" active-text="启用" />
        <el-input v-model="nodeForm.remark" placeholder="备注（节点用途）" size="small" style="flex: 1" />
      </div>
      <template #footer>
        <el-button @click="nodeEditor = false">取消</el-button>
        <el-button type="primary" @click="saveNode">保存</el-button>
      </template>
    </el-dialog>

    <!-- 素材卡编辑 -->
    <el-dialog v-model="cardEditor" :title="cardForm.id ? '编辑素材卡' : '新增素材卡'" width="640px">
      <div style="display: flex; gap: 10px; margin-bottom: 10px; align-items: center">
        <el-select v-model="cardForm.kind" style="width: 110px" size="small">
          <el-option v-for="(label, k) in kindLabel" :key="k" :value="k" :label="label" />
        </el-select>
        <el-input v-model="cardForm.name" placeholder="名称" style="width: 170px" size="small" />
        <el-input v-model="cardForm.aliasesText" placeholder="别名（逗号分隔，场景匹配用）" size="small" style="flex: 1" />
      </div>
      <div style="display: flex; gap: 14px; align-items: center; margin-bottom: 10px">
        <el-switch v-model="cardForm.pinned" active-text="常驻（每场景必注入全文）" />
        <el-select v-model="cardForm.status" size="small" style="width: 100px">
          <el-option value="active" label="在场" />
          <el-option value="retired" label="退场" />
          <el-option value="dead" label="死亡" />
          <el-option value="merged" label="合并" />
        </el-select>
        <span style="font-size: 13px">首现章</span>
        <el-input-number v-model="cardForm.sourceChapter" :min="1" size="small" style="width: 100px" />
      </div>
      <el-input v-model="cardForm.summary" type="textarea" :rows="2" placeholder="摘要（2-3 句；匹配命中时注入的就是它）" style="margin-bottom: 10px" />
      <el-input v-model="cardForm.contentMd" type="textarea" :rows="8" placeholder="全文（常驻卡注入全文；其余卡只注入摘要）" />
      <template #footer>
        <el-button @click="cardEditor = false">取消</el-button>
        <el-button type="primary" @click="saveCard">保存</el-button>
      </template>
    </el-dialog>

    <!-- 正典编辑 -->
    <el-drawer v-model="canonEditor" :title="editing ? `${editing.kind} / ${editing.name}` : ''" size="50%">
      <el-input v-if="editing" v-model="editing.content" type="textarea" :rows="24" />
      <div style="margin-top: 10px">
        <el-button type="primary" @click="saveCanon">保存</el-button>
      </div>
    </el-drawer>

    <!-- 伏笔修正 -->
    <el-dialog v-model="foreshadowEditor" title="修正伏笔" width="560px">
      <template v-if="editing">
        <el-input v-model="editing.content" type="textarea" :rows="3" placeholder="伏笔内容" />
        <div style="display: flex; gap: 10px; margin-top: 10px; align-items: center">
          <span>埋设章 <el-input-number v-model="editing.plantedIn" :min="1" size="small" /></span>
          <span>回收章 <el-input-number v-model="editing.recoveredIn" :min="1" size="small" /></span>
          <el-select v-model="editing.status" size="small" style="width: 110px">
            <el-option value="planned" label="计划" />
            <el-option value="planted" label="已埋" />
            <el-option value="recovered" label="已收" />
            <el-option value="dropped" label="弃用" />
          </el-select>
        </div>
      </template>
      <template #footer>
        <el-button @click="foreshadowEditor = false">取消</el-button>
        <el-button type="primary" @click="saveForeshadow">保存</el-button>
      </template>
    </el-dialog>

    <!-- 事实账修正 -->
    <el-drawer v-model="digestEditor" :title="editing ? `第 ${editing.chapterNo} 章事实账` : ''" size="55%">
      <template v-if="editing">
        <div style="font-size: 12px; color: #999; margin-bottom: 6px">摘要（进入后续章节前情窗口的内容）</div>
        <el-input v-model="editing.contentMd" type="textarea" :rows="8" />
        <div style="font-size: 12px; color: #999; margin: 10px 0 6px">硬事实（JSON 数组，一条一句）</div>
        <el-input v-model="editing.facts" type="textarea" :rows="8" />
        <div style="margin-top: 10px">
          <el-button type="primary" @click="saveDigest">保存</el-button>
        </div>
      </template>
    </el-drawer>
  </div>
</template>

<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api } from '../api'
import { getSelectedNovelId, setSelectedNovelId } from '../novelSelection'

const novels = ref([])
const novelId = ref(null)
const canon = ref([])
const foreshadows = ref([])
const digests = ref([])
const worldStates = ref([])
const wsChapter = ref(null)
const wsText = ref('')
const wsBackfilling = ref(false)
const styleRules = ref('')
const styleFingerprint = ref('')
const bannedText = ref('')
const lenTol = ref(0.15)
const styleTab = ref('rules')
const editing = ref(null)
const canonEditor = ref(false)
const foreshadowEditor = ref(false)
const digestEditor = ref(false)
const newCanon = ref({ kind: 'character', name: '', content: '（待填写）' })
const cards = ref([])
const cardEditor = ref(false)
const cardForm = ref({})
const kindLabel = { character: '角色', item: '物品', location: '地点', phenomenon: '现象', landmark: '地标', disaster: '灾害', org: '组织', misc: '其他' }
const llmNodes = ref([])
const nodeEditor = ref(false)
const nodeForm = ref({})
const llmPrices = ref([])
const priceEditor = ref(false)
const priceForm = ref({})
const tunings = ref([])

const inPeak = computed(() => {
  const p = llmPrices.value[0]
  if (!p) return false
  const h = new Date().getHours()
  return h >= p.peakStartHour && h < p.peakEndHour
})

function openPrice(row) {
  priceForm.value = { ...row }
  priceEditor.value = true
}

async function savePrice() {
  try {
    await api.put(`/api/llm-prices/${priceForm.value.id}`, priceForm.value)
    ElMessage.success('价目已更新')
    priceEditor.value = false
    llmPrices.value = await api.get('/api/llm-prices')
    llmNodes.value = await api.get('/api/llm-nodes')
  } catch (e) {
    ElMessage.error(e.message)
  }
}

function openNode(row) {
  nodeForm.value = {
    id: row.id, node: row.node, model: row.model || '',
    temperature: row.temperature ?? null, maxTokens: row.maxTokens ?? null,
    extraJson: row.extraJson || '', enabled: row.enabled !== false, remark: row.remark || ''
  }
  nodeEditor.value = true
}

async function saveNode() {
  if (nodeForm.value.extraJson) {
    try { JSON.parse(nodeForm.value.extraJson) } catch {
      ElMessage.error('extra 参数不是合法 JSON')
      return
    }
  }
  try {
    const body = {
      model: nodeForm.value.model || null,
      temperature: nodeForm.value.temperature,
      maxTokens: nodeForm.value.maxTokens || null,
      extraJson: nodeForm.value.extraJson || null,
      enabled: nodeForm.value.enabled,
      remark: nodeForm.value.remark
    }
    if (nodeForm.value.id) await api.put(`/api/llm-nodes/${nodeForm.value.id}`, body)
    else await api.post('/api/llm-nodes', { node: nodeForm.value.node, ...body })
    ElMessage.success('已保存，下一次调用即生效')
    nodeEditor.value = false
    llmNodes.value = await api.get('/api/llm-nodes')
  } catch (e) {
    ElMessage.error(e.message)
  }
}

function openCard(row) {
  cardForm.value = row
    ? { ...row, aliasesText: (row.aliases || []).join(',') }
    : { kind: 'character', name: '', aliasesText: '', summary: '', contentMd: '', pinned: false, status: 'active', sourceChapter: null }
  cardEditor.value = true
}

async function saveCard() {
  const f = cardForm.value
  const body = {
    kind: f.kind,
    name: f.name,
    aliases: (f.aliasesText || '').split(/[,，]/).map((s) => s.trim()).filter(Boolean),
    summary: f.summary,
    contentMd: f.contentMd,
    pinned: !!f.pinned,
    status: f.status,
    sourceChapter: f.sourceChapter || null
  }
  try {
    if (f.id) await api.put(`/api/cards/${f.id}`, body)
    else await api.post(`/api/novels/${novelId.value}/cards`, body)
    ElMessage.success('已保存')
    cardEditor.value = false
    await loadAll()
  } catch (e) {
    ElMessage.error(e.message)
  }
}

async function removeCard(row) {
  try {
    await ElMessageBox.confirm(`删除素材卡「${row.name}」？`, '确认', { type: 'warning' })
    await api.delete(`/api/cards/${row.id}`)
    await loadAll()
  } catch (e) {
    if (e !== 'cancel') ElMessage.error(e.message)
  }
}

function loadWsForEdit() {
  const row = worldStates.value.find((w) => w.chapterNo === wsChapter.value)
  if (!row) { wsText.value = ''; return }
  try { wsText.value = JSON.stringify(JSON.parse(row.stateJson), null, 2) } catch { wsText.value = row.stateJson }
}

async function saveWs() {
  try {
    JSON.parse(wsText.value)
  } catch {
    ElMessage.error('不是合法 JSON')
    return
  }
  try {
    await api.put(`/api/novels/${novelId.value}/world-states/${wsChapter.value}`, { state: wsText.value })
    ElMessage.success('已保存，下次场景生成即注入')
    worldStates.value = await api.get(`/api/novels/${novelId.value}/world-states`)
  } catch (e) {
    ElMessage.error(e.message)
  }
}

async function backfillWs() {
  if (wsChapter.value == null) return
  wsBackfilling.value = true
  try {
    await api.post(`/api/novels/${novelId.value}/world-states/backfill/${wsChapter.value}`)
    ElMessage.success('已重新抽取')
    worldStates.value = await api.get(`/api/novels/${novelId.value}/world-states`)
    loadWsForEdit()
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    wsBackfilling.value = false
  }
}

const fingerprintRows = computed(() => {  try {
    const baseline = JSON.parse(styleFingerprint.value || '{}').baseline || {}
    return Object.entries(baseline).map(([metric, r]) => ({ metric, ...r }))
  } catch {
    return []
  }
})

async function saveTuning(row) {
  try {
    await api.put(`/api/tuning/${row.key}`, { value: row.editValue })
    ElMessage.success('已保存，30 秒内生效')
    row.value = row.editValue
  } catch (e) {
    ElMessage.error(e.message)
  }
}

async function loadAll() {
  if (!novelId.value) return
  canon.value = await api.get(`/api/novels/${novelId.value}/canon`)
  cards.value = await api.get(`/api/novels/${novelId.value}/cards`)
  llmNodes.value = await api.get('/api/llm-nodes')
  llmPrices.value = await api.get('/api/llm-prices')
  tunings.value = (await api.get('/api/tuning')).map(t => ({ ...t, editValue: t.value }))
  foreshadows.value = await api.get(`/api/novels/${novelId.value}/foreshadows`)
  digests.value = await api.get(`/api/novels/${novelId.value}/digests`)
  worldStates.value = await api.get(`/api/novels/${novelId.value}/world-states`)
  if (wsChapter.value == null && worldStates.value.length) {
    wsChapter.value = worldStates.value[0].chapterNo
    loadWsForEdit()
  }
  const s = await api.get(`/api/novels/${novelId.value}/style`)
  styleRules.value = s.rulesMd
  styleFingerprint.value = s.fingerprintJson
  try {
    const cfg = JSON.parse(s.gateConfigJson || '{}')
    bannedText.value = (cfg.banned_phrases || []).join('\n')
    lenTol.value = typeof cfg.chapter_length_tolerance === 'number' ? cfg.chapter_length_tolerance : 0.15
  } catch {
    bannedText.value = ''
  }
}

async function openCanon(row) {
  editing.value = await api.get(`/api/canon/${row.id}`)
  canonEditor.value = true
}

async function saveCanon() {
  try {
    await api.put(`/api/canon/${editing.value.id}`, { content: editing.value.content })
    ElMessage.success('已保存')
    canonEditor.value = false
  } catch (e) {
    ElMessage.error(e.message)
  }
}

async function createCanon() {
  try {
    await api.post(`/api/novels/${novelId.value}/canon`, { ...newCanon.value })
    ElMessage.success('已新增')
    newCanon.value = { kind: newCanon.value.kind, name: '', content: '（待填写）' }
    await loadAll()
  } catch (e) {
    ElMessage.error(e.message)
  }
}

async function removeCanon(row) {
  try {
    await ElMessageBox.confirm(`删除「${row.name}」？（软删除，可恢复）`, '确认', { type: 'warning' })
    await api.delete(`/api/canon/${row.id}`)
    await loadAll()
  } catch (e) {
    if (e !== 'cancel') ElMessage.error(e.message)
  }
}

function openForeshadow(row) {
  editing.value = { ...row }
  foreshadowEditor.value = true
}

async function setForeshadowStatus(row, status) {
  try {
    await api.put(`/api/foreshadows/${row.id}`, {
      content: row.content,
      plantedIn: row.plantedIn,
      recoveredIn: row.recoveredIn,
      status
    })
    ElMessage.success(status === 'planned' ? '已采纳，进入埋设编排' : '已忽略')
    await loadAll()
  } catch (e) {
    ElMessage.error(e.message)
  }
}

async function saveForeshadow() {
  try {
    await api.put(`/api/foreshadows/${editing.value.id}`, {
      content: editing.value.content,
      plantedIn: editing.value.plantedIn,
      recoveredIn: editing.value.recoveredIn,
      status: editing.value.status
    })
    ElMessage.success('已修正')
    foreshadowEditor.value = false
    await loadAll()
  } catch (e) {
    ElMessage.error(e.message)
  }
}

function openDigest(row) {
  editing.value = { ...row }
  digestEditor.value = true
}

async function saveDigest() {
  try {
    await api.put(`/api/digests/${editing.value.id}`, {
      contentMd: editing.value.contentMd,
      facts: editing.value.facts
    })
    ElMessage.success('已修正（影响后续章节前情窗口）')
    digestEditor.value = false
    await loadAll()
  } catch (e) {
    ElMessage.error(e.message)
  }
}

async function saveGateConfig() {
  try {
    const phrases = bannedText.value.split('\n').map((s) => s.trim()).filter(Boolean)
    const cfg = { banned_phrases: phrases, chapter_length_tolerance: lenTol.value, no_straight_quote: true }
    await api.put(`/api/novels/${novelId.value}/gate-config`, { gateConfig: JSON.stringify(cfg) })
    ElMessage.success('门禁配置已落库（下一次门禁检测即生效）')
  } catch (e) {
    ElMessage.error(e.message)
  }
}

async function saveStyle() {
  try {
    await api.put(`/api/novels/${novelId.value}/style`, { rulesMd: styleRules.value })
    ElMessage.success('风格规则已更新（立即影响后续生成）')
  } catch (e) {
    ElMessage.error(e.message)
  }
}

onMounted(async () => {
  novels.value = await api.get('/api/novels')
  novelId.value = getSelectedNovelId() ?? novels.value[0]?.id
  if (!novels.value.some((n) => n.id === novelId.value)) novelId.value = novels.value[0]?.id
  await loadAll()
})

watch(novelId, loadAll)
</script>
