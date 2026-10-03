<template>
  <div>
    <PageHeader
      title="规划"
      hint="大纲 / 卷纲 / 章纲 三级管理：大纲进生成上下文，卷纲驱动逐章生成，章纲为 AI 场景拆解">
      <el-select v-model="novelId" style="width: var(--ctrl-w-3xl)" @change="() => { setSelectedNovelId(novelId); loadAll() }">
        <el-option v-for="n in novels" :key="n.id" :value="n.id" :label="n.title" />
      </el-select>
    </PageHeader>

    <el-tabs>
      <!-- 大纲 -->
      <el-tab-pane label="大纲">
        <el-input v-model="story" type="textarea" :rows="22" />
        <div style="margin-top: 8px">
          <el-button type="primary" @click="saveStory">保存大纲</el-button>
          <span class="hint" style="margin-left: 10px">全书脉络 / 主线 / 卷走向；保存后自动进入每章生成的上下文</span>
        </div>
      </el-tab-pane>

      <!-- 卷纲 -->
      <el-tab-pane :label="`卷纲（${planChapters.length} 章规划）`">
        <div v-if="planTask" style="margin-bottom: 10px; padding: 8px 12px; background: var(--tag-attn-bg); border-radius: 6px">
          <div style="display: flex; align-items: center; gap: 10px; margin-bottom: 4px">
            <el-tag size="small" type="warning">卷纲规划中</el-tag>
            <span class="hint">{{ planTask.currentStep || '排队等待中' }} · 第 {{ planTask.fromChapter }} 章起
              <template v-if="planTask.status === 'DONE'"> · 完成</template>
            </span>
          </div>
          <el-progress :percentage="planTaskPercent" :stroke-width="8" :show-text="false" />
          <div v-if="planTask.status === 'DONE'" style="font-size: var(--text-xs); color: var(--success); margin-top: 4px">规划完成并落库 ✓</div>
        </div>
        <div class="toolbar">
          <el-button size="small" type="primary" @click="openAdd">新增章规划</el-button>
          <el-button size="small" type="success" :loading="autoPlanBusy" :disabled="!!planTask && planTask.status !== 'DONE'" @click="openAutoPlan">AI 规划下一卷</el-button>
          <span class="hint" style="display: flex; align-items: center; gap: 6px">
            卷纲人工审核
            <el-switch v-model="planMode" active-value="manual" inactive-value="auto" @change="switchPlanMode" />
            <span>（自动=AI 审校通过直接落库；人工=出草稿，编辑后采纳）</span>
          </span>
        </div>
        <div v-for="v in volumes" :key="v.volNo" style="margin-bottom: 16px">
          <div style="font-weight: bold; margin-bottom: 6px; display: flex; gap: 10px; align-items: center">
            <span v-if="v.volNo === 0">未分卷（{{ v.chapters?.length || 0 }} 章）—— 导入正文之外的散章</span>
            <span v-else>第 {{ v.volNo }} 卷 · {{ v.arc }}（{{ v.chapters?.length || 0 }} 章）</span>
            <el-button size="small" plain :loading="retroBusy === v.volNo" @click="runReview(v)">卷级复盘</el-button>
          </div>
          <div class="hint" v-if="isImportVolume(v)" style="margin: -2px 0 6px 0; line-height: 1.7">
            导入成稿卷：这 {{ v.chapters?.length || 0 }} 章是你导入的原文（正文已成），目标/钩子为空是正常的——
            卷纲/章纲是「写之前」的规划，成稿章不需要再规划；生成管线从第 {{ firstGeneratedChapterNo }} 章接着写。
          </div>
          <DataTable :data="v.chapters" border size="small">
            <el-table-column prop="chapterNo" label="章" width="60" />
            <el-table-column prop="title" label="标题" width="160" />
            <el-table-column prop="goal" label="目标" min-width="220" show-overflow-tooltip />
            <el-table-column prop="hook" label="钩子" min-width="200" show-overflow-tooltip />
            <el-table-column prop="timeNote" label="时间跨度" width="130" show-overflow-tooltip>
              <template #default="{ row }">{{ row.timeNote || '紧接' }}</template>
            </el-table-column>
            <el-table-column label="状态" width="130">
              <template #default="{ row }">
                <el-tag size="small" :type="row.hasText ? 'success' : 'info'">
                  {{ row.hasText ? '正文已成' : '规划就绪·待生成' }}
                </el-tag>
                <span class="hint" v-if="row.sceneCount" > {{ row.sceneCount }}场</span>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="220">
              <template #default="{ row }">
                <el-button size="small" @click="openEdit(row)">编辑</el-button>
                <el-button size="small" type="warning" plain :loading="regenBusy" @click="regen(row)">生成章纲</el-button>
                <el-button size="small" type="danger" plain @click="removePlan(row)">删</el-button>
              </template>
            </el-table-column>
          </DataTable>
        </div>
      </el-tab-pane>

      <!-- 章纲 -->
      <el-tab-pane :label="`章纲（场景拆解${allScenes.length ? ' · ' + allScenes.length + ' 场景' : ''}）`">
        <div class="toolbar">
          <span>批量生成章纲：第</span>
          <el-input-number v-model="outlineFrom" :min="1" size="small" style="width: var(--ctrl-w-sm)" />
          <span>至</span>
          <el-input-number v-model="outlineTo" :min="outlineFrom || 1" size="small" style="width: var(--ctrl-w-sm)" />
          <span>章</span>
          <el-checkbox v-model="outlineIncludeText" size="small">
            含已有正文的章（默认跳过——勾上就为它们补章纲，状态与正文不动）
          </el-checkbox>
          <el-button size="small" type="primary" :disabled="!!outlineTask" @click="submitOutlineBatch">
            {{ outlineTask ? '章纲生成中…' : '生成章纲（入队）' }}
          </el-button>
        </div>
        <div class="hint" v-if="noOutlineChapters.length" style="margin-bottom: 10px; line-height: 1.7">
          暂无章纲 {{ noOutlineChapters.length }} 章：{{ rangeLabel(noOutlineChapters) }}
          <template v-if="importNoOutline.length">。其中 {{ rangeLabel(importNoOutline) }} 是<b>导入成稿章</b>——章纲是写之前拆场景用的，
            正文已成就不再规划（这是正常的，不是漏跑）；确实要补纲就勾上方「含已有正文的章」</template>
          <template v-else>。这些章没有规划行（先跑卷纲）或缺章纲，可点上方批量生成。</template>
        </div>
        <div class="hint" style="margin-bottom: 10px; line-height: 1.7">
          入队后在工作台生成队列看实时进度（约 1-2 分钟/章，可停止）；已有章纲覆盖重建，无规划行的章自动跳过。
          <b>已有正文的章默认跳过</b>（老路径会把该章状态退回「待生成」并删掉它的场景与门禁报告，之后一续跑就会把这一章重写，
          等于毁掉已写完的正文）——导入书自带的成稿章因此默认没有章纲。
          勾上「含已有正文的章」则改为<b>保全状态</b>出纲：只补章纲与场景拆解，章状态、正文、门禁报告都不动（要花 1-2 分钟/章）。
          提前出的章纲缺「前情」（此前章节的摘要与结尾），量产建议交给管线逐章自动出；启动生成时已有章纲直接复用、不再重出。
        </div>
        <div v-if="outlineTask" style="margin-bottom: 10px; padding: 8px 12px; background: var(--tag-attn-bg); border-radius: 6px">
          <div style="display: flex; align-items: center; gap: 10px; margin-bottom: 4px">
            <el-tag size="small" type="warning">章纲生成中</el-tag>
            <span class="hint">
              任务 #{{ outlineTask.id }} · 第 {{ outlineTask.fromChapter }}-{{ outlineTask.toChapter }} 章
              <template v-if="outlineTask.status === 'RUNNING' && outlineTask.currentChapter">
                · 当前第 {{ outlineTask.currentChapter }} 章 · {{ outlineTask.lastMessage || '' }}
              </template>
              <template v-else-if="outlineTask.status === 'QUEUED'"> · 排队等待中</template>
            </span>
          </div>
          <el-progress :percentage="Math.round((outlineTask.doneChapters || 0) / Math.max(1, outlineTask.totalChapters) * 100)"
                       :stroke-width="8" :show-text="false" />
        </div>
        <div class="toolbar">
          <span style="font-size: var(--text-sm); color: var(--fg-2)">筛选：</span>
          <el-select v-model="sceneFilterChapter" clearable placeholder="全部章纲（按章）" size="small" style="width: var(--ctrl-w-2xl)">
            <el-option v-for="c in sceneChapterOptions" :key="c.value" :value="c.value" :label="c.label">
              <span>{{ c.label }}</span>
              <span class="hint" style="float: right">{{ c.count }} 场景</span>
            </el-option>
          </el-select>
          <el-select v-model="sceneFilterMaterial" clearable filterable placeholder="按素材（出场人物/事物）" size="small" style="width: var(--ctrl-w-2xl)">
            <el-option v-for="m in sceneMaterialOptions" :key="m.name" :value="m.name" :label="m.name">
              <span>{{ m.name }}</span>
              <span class="hint" style="float: right">{{ m.count }} 场景</span>
            </el-option>
          </el-select>
          <el-input v-model="sceneFilterText" clearable placeholder="搜内容：目标 / 必揭示 / 禁出现" size="small"
                    style="width: var(--ctrl-w-2xl)" />
          <el-button v-if="sceneFiltersActive" size="small" link type="primary" @click="clearSceneFilters">清空筛选</el-button>
          <span class="hint" style="margin-left: auto">
            {{ filteredScenes.length }} / {{ allScenes.length }} 场景 · 涉及 {{ filteredChapterCount }} 章
          </span>
        </div>
        <el-empty v-if="!allScenes.length" description="还没有任何章纲——用上方批量生成（区间可只填本章），或启动生成时自动出" :image-size="60" />
        <DataTable v-else :data="filteredScenes" border size="small" max-height="560">
          <el-table-column label="所属章纲" width="180" show-overflow-tooltip>
            <template #default="{ row }">
              <el-link type="primary" :underline="false" style="font-size: var(--text-xs)"
                       @click="sceneFilterChapter = row.chapterNo">第 {{ row.chapterNo }} 章 · {{ row.chapterTitle }}</el-link>
            </template>
          </el-table-column>
          <el-table-column prop="sceneNo" label="场景" width="60" />
          <el-table-column prop="goal" label="场景目标" min-width="300" show-overflow-tooltip />
          <el-table-column label="要素" min-width="300">
            <template #default="{ row }">
              <div v-if="(row.present || []).length" style="font-size: var(--text-xs)">出场：{{ row.present.join('、') }}</div>
              <div v-if="(row.mustReveal || []).length" style="font-size: var(--text-xs); color: var(--success)">必揭示：{{ row.mustReveal.join('、') }}</div>
              <div v-if="(row.mustNot || []).length" style="font-size: var(--text-xs); color: var(--danger)">禁出现：{{ row.mustNot.join('、') }}</div>
            </template>
          </el-table-column>
          <el-table-column prop="wordsBudget" label="预算" width="70" />
        </DataTable>
        <el-empty v-if="allScenes.length && !filteredScenes.length" description="没有符合筛选条件的场景——调整或清空筛选" :image-size="60" />
      </el-tab-pane>
    </el-tabs>

    <!-- AI 规划入参 -->
    <el-dialog v-model="autoPlanOpen" title="AI 规划一卷" width="var(--dlg-w-md)">
      <div class="toolbar">
        <span>卷号</span>
        <el-input-number v-model="autoPlanForm.volNo" :min="1" size="small" style="width: var(--ctrl-w-sm)" />
        <span>起始章</span>
        <el-input-number v-model="autoPlanForm.from" :min="1" size="small" style="width: var(--ctrl-w-md)" />
        <span>结束章</span>
        <el-input-number v-model="autoPlanForm.to" :min="autoPlanForm.from || 1" size="small" style="width: var(--ctrl-w-md)" placeholder="AI 自定" />
      </div>
      <el-input v-model="autoPlanForm.seedOutline" type="textarea" :rows="6"
                placeholder="本卷种子大纲（可空——留空则 AI 依据全书大纲与事实账/世界状态/伏笔账自主设计本卷主线，并在卷简报里说明四个关键决策）" />
      <div class="hint" style="margin-top: 8px">
        流程：生成 → 结构校验 → AI 规划审校（BLOCKER 自动重写 ≤3 轮）→ 落库；引用到的 proposed 伏笔自动采纳排期。
      </div>
      <template #footer>
        <el-button @click="autoPlanOpen = false">取消</el-button>
        <el-button type="primary" :loading="autoPlanBusy" @click="runAutoPlan">开始规划（约 2-10 分钟）</el-button>
      </template>
    </el-dialog>

    <!-- manual 模式草稿编辑 -->
    <el-dialog v-model="draftOpen" title="卷纲草稿（人工审核）" width="var(--dlg-w-xl)">
      <div class="toolbar">
        <span>卷名</span>
        <el-input v-model="draft.arc" style="width: var(--ctrl-w-2xl)" size="small" />
        <span class="hint">可直接编辑；采纳后不再过 AI 审校</span>
      </div>
      <el-input v-model="draft.brief" type="textarea" :rows="4" style="margin-bottom: 10px" placeholder="卷简报" />
      <DataTable :data="draft.rows" border size="small" max-height="420">
        <el-table-column prop="no" label="章" width="52" />
        <el-table-column label="标题" width="150">
          <template #default="{ row }"><el-input v-model="row.title" size="small" /></template>
        </el-table-column>
        <el-table-column label="目标" min-width="230">
          <template #default="{ row }"><el-input v-model="row.goal" size="small" type="textarea" :rows="2" autosize /></template>
        </el-table-column>
        <el-table-column label="钩子" min-width="150">
          <template #default="{ row }"><el-input v-model="row.hook" size="small" type="textarea" :rows="2" autosize /></template>
        </el-table-column>
        <el-table-column label="时间" width="110">
          <template #default="{ row }"><el-input v-model="row.timeNote" size="small" /></template>
        </el-table-column>
        <el-table-column label="伏笔" width="90">
          <template #default="{ row }"><el-input v-model="row.foreshadowsText" size="small" placeholder="F 编码" /></template>
        </el-table-column>
        <el-table-column label="预算" width="140">
          <template #default="{ row }">
            <el-input-number v-model="row.budgetMin" size="small" :min="600" :max="10000" controls-position="right" style="width: var(--ctrl-w-xs)" />
            –
            <el-input-number v-model="row.budgetMax" size="small" :min="600" :max="10000" controls-position="right" style="width: var(--ctrl-w-xs)" />
          </template>
        </el-table-column>
      </DataTable>
      <div v-if="(autoPlanResult?.warnings || []).length" style="color: var(--warn); font-size: var(--text-xs); margin-top: 6px">
        {{ autoPlanResult.warnings.join('；') }}
      </div>
      <template #footer>
        <el-button @click="draftOpen = false">放弃草稿</el-button>
        <el-button type="primary" :loading="adoptBusy" @click="adoptPlan">采纳落库</el-button>
      </template>
    </el-dialog>

    <!-- 章规划编辑 -->
    <el-dialog v-model="planEditor" :title="editing && editing.id ? '编辑章规划' : '新增章规划'" width="var(--dlg-w-md)">
      <template v-if="editing">
        <div style="display: flex; gap: 10px; margin-bottom: 10px">
          <el-input-number v-model="editing.chapterNo" :min="1" size="small" :disabled="!!editing.id" />
          <el-input-number v-model="editing.volNo" :min="1" size="small" placeholder="卷" />
          <el-input v-model="editing.arc" placeholder="卷名/弧名" size="small" style="width: var(--ctrl-w-xl)" />
        </div>
        <el-input v-model="editing.title" placeholder="章节标题" style="margin-bottom: 10px" />
        <el-input v-model="editing.goal" type="textarea" :rows="3" placeholder="本章目标（AI 章纲的种子）" style="margin-bottom: 10px" />
        <el-input v-model="editing.hook" type="textarea" :rows="2" placeholder="章末钩子" style="margin-bottom: 10px" />
        <el-input v-model="editing.timeNote" placeholder="时间跨度（距上一章，如：新年祭后第三日；留空=紧接上一章）" style="margin-bottom: 10px" />
        <div style="display: flex; gap: 10px; align-items: center">
          <span>字数预算</span>
          <el-input-number v-model="editing.budgetMin" :min="500" size="small" />
          -
          <el-input-number v-model="editing.budgetMax" :min="500" size="small" />
        </div>
      </template>
      <template #footer>
        <el-button @click="planEditor = false">取消</el-button>
        <el-button type="primary" @click="savePlan">保存</el-button>
      </template>
    </el-dialog>

    <!-- 卷级复盘报告 -->
    <el-dialog v-model="retroOpen" :title="retro ? `第 ${retro.vol_no} 卷复盘报告（第 ${retro.from_no}-${retro.to_no} 章）` : '卷级复盘'" width="var(--dlg-w-xl)">
      <template v-if="retro">
        <div class="toolbar">
          <el-tag :type="retro.review?.overall === 'pass' ? 'success' : retro.review?.overall === 'critical' ? 'danger' : 'warning'">
            {{ retro.review?.overall === 'pass' ? '整体达标' : retro.review?.overall === 'critical' ? '严重漂移' : retro.review?.overall === 'drift' ? '存在漂移' : '仅机械对账' }}
          </el-tag>
          <span class="hint">机械对账为确定性结果；叙事漂移为 LLM 分析（复审可覆盖）</span>
        </div>
        <div style="white-space: pre-wrap; line-height: 1.8; margin-bottom: 12px">{{ retro.review?.summary }}</div>

        <div style="font-weight: bold; margin: 10px 0 6px">机械对账</div>
        <div style="font-size: var(--text-sm); margin-bottom: 4px">
          章节数 {{ retro.mechanical?.chapters }} · 总字数 {{ retro.mechanical?.text_len_total }} ·
          状态分布 {{ JSON.stringify(retro.mechanical?.status_count || {}) }}
        </div>
        <DataTable v-if="(retro.mechanical?.budget_outliers || []).length" :data="retro.mechanical.budget_outliers" border size="small" style="margin-bottom: 8px">
          <el-table-column prop="chapter_no" label="章" width="70" />
          <el-table-column prop="budget" label="预算" width="140" />
          <el-table-column prop="actual" label="实际字数" width="100" />
          <el-table-column prop="verdict" label="判定" />
        </DataTable>
        <DataTable v-if="(retro.mechanical?.foreshadow_audit || []).length" :data="retro.mechanical.foreshadow_audit" border size="small" style="margin-bottom: 12px">
          <el-table-column prop="chapter_no" label="章" width="70" />
          <el-table-column prop="code" label="伏笔" width="90" />
          <el-table-column prop="verdict" label="对账结果" />
        </DataTable>

        <div style="font-weight: bold; margin: 10px 0 6px">漂移分析</div>
        <DataTable v-if="(retro.review?.drifts || []).length" :data="retro.review.drifts" border size="small">
          <el-table-column prop="type" label="类型" width="100" />
          <el-table-column label="严重度" width="90">
            <template #default="{ row }">
              <el-tag size="small" :type="row.severity === 'major' ? 'danger' : 'warning'">{{ row.severity }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="where" label="位置" width="110" />
          <el-table-column prop="issue" label="漂移" min-width="220" />
          <el-table-column prop="suggestion" label="建议" min-width="200" />
        </DataTable>
        <div v-else style="color: var(--muted); font-size: var(--text-sm)">无漂移项。</div>

        <div style="font-weight: bold; margin: 12px 0 6px">建议采纳（流 D）</div>
        <DataTable v-if="proposals.length" :data="proposals" border size="small">
          <el-table-column prop="content" label="建议" min-width="320" />
          <el-table-column label="状态" width="100">
            <template #default="{ row }">
              <el-tag size="small" :type="row.status === 'ADOPTED' ? 'success' : row.status === 'REJECTED' ? 'info' : 'warning'">
                {{ row.status === 'ADOPTED' ? '已采纳' : row.status === 'REJECTED' ? '已忽略' : '待决' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="150">
            <template #default="{ row }">
              <template v-if="row.status === 'PROPOSED'">
                <el-button size="small" type="success" plain @click="decideProposal(row, true)">采纳</el-button>
                <el-button size="small" plain @click="decideProposal(row, false)">忽略</el-button>
              </template>
              <span class="hint" v-else>{{ row.decisionNote || '已决策' }}</span>
            </template>
          </el-table-column>
        </DataTable>
        <div v-else style="color: var(--muted); font-size: var(--text-sm)">本卷暂无提案（复盘生成后自动落入）。</div>

        <div v-if="(retro.review?.highlights || []).length" style="font-weight: bold; margin: 12px 0 6px">亮点</div>
        <ul v-if="(retro.review?.highlights || []).length" style="margin: 0 0 10px 18px; font-size: var(--text-sm)">
          <li v-for="(h, i) in retro.review.highlights" :key="i">{{ h }}</li>
        </ul>
        <div v-if="retro.review?.next_volume" style="font-size: var(--text-sm)">
          <b>下一卷建议：</b>{{ retro.review.next_volume }}
        </div>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api } from '../api'
import { getSelectedNovelId, setSelectedNovelId } from '../novelSelection'
import DataTable from '../components/DataTable.vue'
import PageHeader from '../components/PageHeader.vue'

const novels = ref([])
const novelId = ref(null)
const story = ref('')
const volumes = ref([])
const allScenes = ref([])
const sceneFilterChapter = ref(null)
const sceneFilterMaterial = ref('')
const sceneFilterText = ref('')
const editing = ref(null)
const planEditor = ref(false)
const regenBusy = ref(false)
const planMode = ref('auto')
const autoPlanOpen = ref(false)
const autoPlanBusy = ref(false)
const autoPlanForm = ref({ volNo: 1, from: 1, to: null, seedOutline: '' })
const autoPlanResult = ref(null)
const draftOpen = ref(false)
const draft = ref({ arc: '', brief: '', rows: [] })
const adoptBusy = ref(false)
const retroBusy = ref(null)
const retroOpen = ref(false)
const retro = ref(null)
const proposals = ref([])
async function loadProposals(volNo) {
  try {
    proposals.value = await api.get(`/api/novels/${novelId.value}/planning/volumes/${volNo}/proposals`)
  } catch {
    proposals.value = []
  }
}

async function decideProposal(row, adopt) {
  try {
    await api.post(`/api/novels/${novelId.value}/planning/retro/${row.id}/decision`, { adopt, note: adopt ? '采纳' : '忽略' })
    ElMessage.success(adopt ? '已采纳：将在下卷规划上下文中生效' : '已忽略')
    await loadProposals(row.volNo)
  } catch (e) {
    ElMessage.error(e.message)
  }
}

watch(retro, (r) => { if (r && r.vol_no != null) loadProposals(r.vol_no) })

async function runReview(v) {
  retroBusy.value = v.volNo
  try {
    retro.value = await api.post(`/api/novels/${novelId.value}/planning/volumes/${v.volNo}/review`)
    retroOpen.value = true
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    retroBusy.value = null
  }
}

const planChapters = computed(() => volumes.value.flatMap((v) => v.chapters))

/** 导入成稿卷（导入书落章即写的 volume_no=1 / arc=导入正文）：其章有正文、无卷纲目标与章纲。 */
const importVolume = computed(() => volumes.value.find((v) => v.arc === '导入正文'))
const isImportVolume = (v) => v.arc === '导入正文'
/** 生成管线的续写起点＝已有正文最末章+1。 */
const firstGeneratedChapterNo = computed(() => {
  const withText = planChapters.value.filter((c) => c.hasText).map((c) => c.chapterNo)
  return withText.length ? Math.max(...withText) + 1 : 1
})
const noOutlineChapters = computed(() => planChapters.value.filter((c) => !c.hasOutline))
const importNoOutline = computed(() => {
  const vol = importVolume.value
  if (!vol) return []
  const nos = new Set((vol.chapters || []).map((c) => c.chapterNo))
  return noOutlineChapters.value.filter((c) => nos.has(c.chapterNo))
})

/** 章号列表压成「第 1–17 章、第 20 章」这样的区间文案。 */
function rangeLabel(chs) {
  const nos = chs.map((c) => c.chapterNo).sort((a, b) => a - b)
  if (!nos.length) return ''
  const parts = []
  let s = nos[0]
  let p = nos[0]
  for (let i = 1; i <= nos.length; i++) {
    if (i < nos.length && nos[i] === p + 1) {
      p = nos[i]
      continue
    }
    parts.push(s === p ? `第 ${s} 章` : `第 ${s}–${p} 章`)
    if (i < nos.length) {
      s = nos[i]
      p = nos[i]
    }
  }
  return parts.join('、')
}

async function loadAll() {
  if (!novelId.value) return
  const s = await api.get(`/api/novels/${novelId.value}/planning/story`)
  story.value = s.content
  volumes.value = await api.get(`/api/novels/${novelId.value}/planning/volumes`)
  const m = await api.get(`/api/novels/${novelId.value}/planning/mode`)
  planMode.value = m.planMode
  // 章纲批量的默认范围=全书（1..末章）：原先默认 1..1，点「生成章纲」只会去碰第 1 章，
  // 而第 1 章若是导入的成稿章会被守卫跳过，看着就像「点了没反应」。
  outlineFrom.value = 1
  outlineTo.value = Math.max(1, ...planChapters.value.map((c) => c.chapterNo))
  await loadAllScenes()
}

async function saveStory() {
  try {
    await api.put(`/api/novels/${novelId.value}/planning/story`, { content: story.value })
    ElMessage.success('大纲已保存（后续生成自动携带）')
  } catch (e) {
    ElMessage.error(e.message)
  }
}

function openAdd() {
  const maxNo = Math.max(0, ...planChapters.value.map((c) => c.chapterNo))
  const lastVol = volumes.value[volumes.value.length - 1]
  editing.value = {
    chapterNo: maxNo + 1, volNo: lastVol ? lastVol.volNo : 1, arc: lastVol ? lastVol.arc : '',
    title: '', goal: '', hook: '', timeNote: '', budgetMin: 1800, budgetMax: 2800
  }
  planEditor.value = true
}

function openEdit(row) {
  editing.value = { ...row, volNo: volumes.value.find((v) => v.chapters.includes(row))?.volNo ?? 1 }
  planEditor.value = true
}

async function savePlan() {
  try {
    if (editing.value.id) {
      await api.put(`/api/novels/${novelId.value}/planning/chapters/${editing.value.id}/plan`, editing.value)
    } else {
      await api.post(`/api/novels/${novelId.value}/planning/chapters`, editing.value)
    }
    ElMessage.success('已保存')
    planEditor.value = false
    await loadAll()
  } catch (e) {
    ElMessage.error(e.message)
  }
}

async function removePlan(row) {
  try {
    await ElMessageBox.confirm(`删除第 ${row.chapterNo} 章「${row.title}」的规划？`, '确认', { type: 'warning' })
    await api.delete(`/api/novels/${novelId.value}/planning/chapters/${row.id}/plan`)
    await loadAll()
  } catch (e) {
    if (e !== 'cancel') ElMessage.error(e.message)
  }
}

async function regen(row) {
  try {
    await ElMessageBox.confirm(
      `为第 ${row.chapterNo} 章「${row.title}」生成章纲？（已有章纲将清掉重建，约 1-2 分钟）`, '确认', { type: 'warning' })
  } catch {
    return
  }
  regenBusy.value = true
  try {
    const spec = await api.post(`/api/novels/${novelId.value}/planning/chapters/${row.chapterNo}/outline/regenerate`)
    ElMessage.success(`章纲已生成：${spec.length} 个场景`)
    await loadAll()
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    regenBusy.value = false
  }
}

/** 章纲批量生成入队：秒回任务 id，逐章出场景拆解；进度走工作台队列与本页横幅，单章 from=to 即可。 */
async function submitOutlineBatch() {
  try {
    const r = await api.post(`/api/novels/${novelId.value}/planning/outline/batch`,
        { from: outlineFrom.value, to: outlineTo.value, includeTextChapters: outlineIncludeText.value })
    ElMessage.success(`章纲生成已入队（任务 #${r.taskId}）——下方显示进度，工作台生成队列同步可见`)
    pollPlanTask()
  } catch (e) {
    ElMessage.error(e.message)
  }
}

/** 章纲全量跨章拉取（带所属章号/章题）——进 tab 即全景展示，筛选全在前端即时完成。 */
async function loadAllScenes() {
  try {
    allScenes.value = await api.get(`/api/novels/${novelId.value}/planning/scenes`)
  } catch (e) {
    ElMessage.error(e.message)
  }
}

// ===== 章纲筛选（章节/素材/内容，前端即时计算） =====
const sceneChapterOptions = computed(() => {
  const byChapter = new Map()
  for (const s of allScenes.value) {
    const cur = byChapter.get(s.chapterNo) || { value: s.chapterNo, label: `第 ${s.chapterNo} 章 · ${s.chapterTitle || '未命名'}`, count: 0 }
    cur.count++
    byChapter.set(s.chapterNo, cur)
  }
  return [...byChapter.values()].sort((a, b) => a.value - b.value)
})
const sceneMaterialOptions = computed(() => {
  const count = new Map()
  for (const s of allScenes.value) {
    for (const p of s.present || []) count.set(p, (count.get(p) || 0) + 1)
  }
  return [...count.entries()].map(([name, n]) => ({ name, count: n }))
    .sort((a, b) => b.count - a.count || a.name.localeCompare(b.name, 'zh'))
})
const filteredScenes = computed(() => allScenes.value.filter((s) => {
  if (sceneFilterChapter.value != null && s.chapterNo !== sceneFilterChapter.value) return false
  if (sceneFilterMaterial.value && !(s.present || []).includes(sceneFilterMaterial.value)) return false
  const q = sceneFilterText.value.trim().toLowerCase()
  if (q) {
    const hay = [s.goal, ...(s.mustReveal || []), ...(s.mustNot || []), ...(s.present || [])]
        .filter(Boolean).join('\n').toLowerCase()
    if (!hay.includes(q)) return false
  }
  return true
}))
const filteredChapterCount = computed(() => new Set(filteredScenes.value.map((s) => s.chapterNo)).size)
const sceneFiltersActive = computed(() =>
    sceneFilterChapter.value != null || !!sceneFilterMaterial.value || !!sceneFilterText.value.trim())
function clearSceneFilters() {
  sceneFilterChapter.value = null
  sceneFilterMaterial.value = ''
  sceneFilterText.value = ''
}

// ===== AI 卷纲规划 =====

async function switchPlanMode(v) {
  try {
    await api.put(`/api/novels/${novelId.value}/planning/plan-mode`, { mode: v })
    ElMessage.success(v === 'auto' ? '已切换为自动（AI 审校通过直接落库）' : '已切换为人工审核（出草稿待采纳）')
  } catch (e) {
    planMode.value = v === 'auto' ? 'manual' : 'auto'
    ElMessage.error(e.message)
  }
}

function openAutoPlan() {
  const maxNo = Math.max(0, ...planChapters.value.map((c) => c.chapterNo))
  const maxVol = Math.max(0, ...volumes.value.map((v) => v.volNo))
  autoPlanForm.value = { volNo: maxVol + 1, from: maxNo + 1, to: null, seedOutline: '' }
  autoPlanOpen.value = true
}

async function runAutoPlan() {
  autoPlanOpen.value = false
  // ⑤ 任务化：auto 模式入队秒回（进度见工作台队列）；manual 模式保留同步草稿流
  if (planMode.value !== 'manual') {
    try {
      await api.post(`/api/novels/${novelId.value}/planning/volume/auto-plan-async`, autoPlanForm.value)
      ElMessage.success('规划任务已入队——本页下方实时显示进度，完成后自动刷新')
      if (!planPollTimer) pollPlanTask()
    } catch (e) {
      ElMessage.error(e.message)
    }
    return
  }
  autoPlanBusy.value = true
  ElMessage.info('AI 规划中，约 2-10 分钟，可去工作台看事件流水…')
  try {
    const r = await api.post(`/api/novels/${novelId.value}/planning/volume/auto-plan`, autoPlanForm.value)
    autoPlanResult.value = r
    if (r.adopted) {
      const fs = (r.adoptedForeshadows || []).join('；')
      ElMessage.success(`卷纲已落库：${r.arc}，${r.rows.length} 章${fs ? '；伏笔：' + fs : ''}`)
      await loadAll()
    } else {
      draft.value = {
        arc: r.arc,
        brief: r.brief,
        rows: r.rows.map((row) => ({ ...row, foreshadowsText: (row.foreshadows || []).join(',') }))
      }
      draftOpen.value = true
    }
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    autoPlanBusy.value = false
  }
}

async function adoptPlan() {
  adoptBusy.value = true
  try {
    const r = await api.post(`/api/novels/${novelId.value}/planning/volume/adopt`, {
      volNo: autoPlanForm.value.volNo,
      arc: draft.value.arc,
      brief: draft.value.brief,
      rows: draft.value.rows.map((row) => ({
        no: row.no,
        title: row.title,
        goal: row.goal,
        hook: row.hook,
        timeNote: row.timeNote,
        foreshadows: (row.foreshadowsText || '').split(/[,，]/).map((s) => s.trim()).filter(Boolean),
        budgetMin: row.budgetMin,
        budgetMax: row.budgetMax
      }))
    })
    const fs = (r.adoptedForeshadows || []).join('；')
    ElMessage.success(`已落库 ${r.adoptedChapters} 章${fs ? '；伏笔：' + fs : ''}`)
    draftOpen.value = false
    await loadAll()
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    adoptBusy.value = false
  }
}

// ===== 卷纲规划/章纲批量 异步任务内嵌展示 =====
const planTask = ref(null)
const outlineTask = ref(null)
const outlineFrom = ref(1)
const outlineTo = ref(1)
/** 「含已有正文的章」：默认关（守卫口径）；勾上＝为成稿章补章纲（保全状态出纲）。 */
const outlineIncludeText = ref(false)
let planPollTimer = null

const planTaskPercent = computed(() => (planTask.value?.status === 'RUNNING' ? 50 : 5))

async function pollPlanTask() {
  try {
    const title = (novels.value.find((n) => n.id === novelId.value) || {}).title
    const q = title ? await api.get('/api/pipeline/queue') : []
    planTask.value = q.find((t) => t.novelTitle === title
        && t.kind === 'PLAN' && ['QUEUED', 'RUNNING'].includes(t.status)) || null
    outlineTask.value = q.find((t) => t.novelTitle === title
        && t.kind === 'OUTLINE' && ['QUEUED', 'RUNNING'].includes(t.status)) || null
  } catch { /* 忽略轮询错误 */ }
  if (planTask.value || outlineTask.value) {
    planPollTimer = setTimeout(pollPlanTask, 3000)
  } else if (planPollTimer) {
    // 任务从队列消失=刚完成——刷新一次后停止轮询（loadAll 内含章纲全量刷新）
    planPollTimer = null
    try { await loadAll() } catch { /* 刷新失败不打扰 */ }
  }
}

onMounted(async () => {
  novels.value = await api.get('/api/novels')
  novelId.value = getSelectedNovelId() ?? novels.value[0]?.id
  if (!novels.value.some((n) => n.id === novelId.value)) novelId.value = novels.value[0]?.id
  await loadAll()
  pollPlanTask()
})

onUnmounted(() => clearTimeout(planPollTimer))
</script>
