<template>
  <div style="max-width: 900px; margin: 0 auto">
    <el-card shadow="never">
      <template #header>
        <div style="display: flex; align-items: baseline; gap: 12px">
          <b style="font-size: 15px">开新书</b>
          <span style="font-size: 12px; color: #999">从品类预设或导入的爆款样本衍生新书：设定衍生参数 → AI 生成大纲 → 创建即入量产链</span>
        </div>
      </template>

      <el-alert v-if="draftBook" type="warning" :closable="false" style="margin-bottom: 14px"
                :title="`有未完成的草稿书《${draftBook.title}》（ID ${draftBook.id}）——离开页面也不会丢`">
        <el-button size="small" type="primary" @click="resumeDraft">继续这份草稿</el-button>
        <span style="font-size: 12px; color: #999; margin-left: 8px">不需要时可在书籍管理页删除</span>
      </el-alert>

      <el-steps :active="wizardStep" finish-status="success" simple style="margin-bottom: 20px">
        <el-step title="基本信息" />
        <el-step title="衍生设定" />
        <el-step title="全书大纲" />
        <el-step title="完成" />
      </el-steps>

      <template v-if="wizardStep === 0">
        <el-form label-width="92px">
          <el-form-item label="书名" required>
            <el-input v-model="wizardForm.title" maxlength="256" placeholder="作品名，全站唯一"
                      :disabled="!!createdNovelId" />
            <div v-if="createdNovelId" style="font-size: 12px; color: #67c23a">已落库为草稿（ID {{ createdNovelId }}），改名请到书籍管理页编辑</div>
          </el-form-item>
          <el-form-item label="简介">
            <el-input v-model="wizardForm.description" type="textarea" :rows="2" placeholder="一句话简介（可选）" />
          </el-form-item>
          <el-form-item label="品类预设" required>
            <el-radio-group v-model="presetMode" size="small" style="margin-bottom: 10px">
              <el-radio-button value="select">选现有预设</el-radio-button>
              <el-radio-button value="analyze">导入我的小说分析</el-radio-button>
            </el-radio-group>

            <template v-if="presetMode === 'select'">
              <template v-if="presets.length">
                <el-select v-model="wizardForm.presetId" placeholder="选择品类预设" style="width: 100%">
                  <el-option v-for="p in presets" :key="p.id" :value="p.id" :label="p.name">
                    <span>{{ p.name }}</span>
                    <span style="float: right; color: #999; font-size: 12px">{{ p.description }}</span>
                  </el-option>
                </el-select>
                <div style="font-size: 12px; color: #999; line-height: 1.7">
                  预设决定文风指纹、门禁阈值与写作规则，创建时克隆为本书私有配置（之后在素材库可单独调整，互不影响）。
                </div>
              </template>
              <el-alert v-else type="warning" :closable="false" title="还没有品类预设"
                description="预设从语料提取（分位带宽指纹+门禁阈值）。可在下方「导入我的小说分析」直接建一个，或到素材库 → 质量与风格 → 品类预设。" />
            </template>

            <template v-else>
              <div style="display: flex; gap: 8px; align-items: center; margin-bottom: 6px; flex-wrap: wrap">
                <el-input v-model="sampleForm.name" placeholder="小说名（用于命名品类，可选）" size="small" style="width: 200px" />
                <label style="cursor: pointer; font-size: 13px; color: #409eff">上传 txt / mobi
                  <input type="file" accept=".txt,.mobi,.azw3,.azw" style="display: none" @change="onSampleFile" />
                </label>
                <span v-if="sampleForm.text" style="font-size: 12px; color: #999">
                  已载入 {{ (sampleForm.text.length / 10000).toFixed(1) }} 万字
                </span>
                <span v-else-if="sampleForm.mobiBase64" style="font-size: 12px; color: #999">已载入电子书文件</span>
                <el-button type="primary" size="small" :loading="analyzing"
                           :disabled="!sampleForm.text && !sampleForm.mobiBase64" @click="analyzeSample">
                  分析文风
                </el-button>
              </div>
              <el-input v-model="sampleForm.text" type="textarea" :rows="6"
                placeholder="或直接粘贴小说正文（整本或长片段）。系统自动切块存入语料库（之后随时可补料/重提/采纳），只分析文风分布（用词/句式/节奏），不看情节。支持 txt 与无 DRM 的 mobi/azw3。" />

              <div v-if="analyzeResult" style="margin-top: 10px; font-size: 13px">
                <div style="margin-bottom: 6px">
                  {{ (analyzeResult.totalChars / 10000).toFixed(1) }} 万字 · {{ analyzeResult.chunks }} 块 ·
                  章长预算 {{ analyzeResult.budgetMin }}-{{ analyzeResult.budgetMax }} 字 ·
                  {{ analyzeResult.metricCount }} 项指标
                  <el-tag v-if="analyzeResult.lowConfidence" size="small" type="warning">样本偏少·低置信</el-tag>
                </div>
                <div v-for="s in analyzeResult.similarities" :key="s.presetId"
                     style="display: flex; align-items: center; gap: 8px; margin-bottom: 3px">
                  <span style="width: 170px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap">{{ s.name }}</span>
                  <div style="flex: 1; height: 8px; background: #f0f2f5; border-radius: 4px; overflow: hidden">
                    <div :style="{ width: Math.max(0, s.score) * 100 + '%', height: '100%', background: s.score >= 0.65 ? '#67c23a' : s.score >= 0.45 ? '#e6a23c' : '#c0c4cc' }" />
                  </div>
                  <span style="width: 48px; text-align: right; color: #606266">{{ s.comparable ? Math.round(s.score * 100) + '%' : '不可比' }}</span>
                </div>

                <el-alert v-if="analyzeResult.recommendation === 'match' && bestSim" type="success" :closable="false"
                  :title="`与「${bestSim.name}」文风相近（${Math.round(bestSim.score * 100)}%），可直接使用`"
                  style="margin-top: 8px">
                  <el-button size="small" type="primary" plain @click="usePreset(bestSim)">用这个预设</el-button>
                </el-alert>
                <el-alert v-else-if="analyzeResult.recommendation === 'new'" type="info" :closable="false"
                  :title="bestSim ? `与现有品类差异大（最相近 ${Math.round(bestSim.score * 100)}%），建议为它建新品类` : '还没有任何品类，建议为它建新品类'"
                  style="margin-top: 8px" />
                <el-alert v-else-if="bestSim" type="warning" :closable="false"
                  :title="`与「${bestSim.name}」有一定相近（${Math.round(bestSim.score * 100)}%）：可直接用，也可以为它建新品类`"
                  style="margin-top: 8px">
                  <el-button size="small" type="primary" plain @click="usePreset(bestSim)">用这个预设</el-button>
                </el-alert>

                <div v-if="analyzeResult.recommendation !== 'match'"
                     style="display: flex; gap: 8px; align-items: center; margin-top: 8px; flex-wrap: wrap">
                  <el-tag size="small" type="info">品类「{{ newGenreForm.genre }}」（语料已存库）</el-tag>
                  <el-input v-model="newGenreForm.presetName" size="small" placeholder="预设名" style="width: 200px" />
                  <el-button size="small" type="primary" :loading="adopting" @click="adoptFromSample">建品类并使用</el-button>
                </div>

                <div v-if="chosenPresetName" style="margin-top: 10px">
                  已选预设：<el-tag size="small" type="success">{{ chosenPresetName }}</el-tag>
                  <el-button v-if="analyzeResult && analyzeResult.sampleId" size="small" type="primary" plain
                             style="margin-left: 10px" @click="goDeepParse(analyzeResult.sampleId)">
                    去深度解析剧情与资产 →
                  </el-button>
                  <div style="font-size: 12px; color: #999; margin-top: 4px">
                    深度解析由 AI 拆出大纲/章纲/角色/世界观资产（素材库可看），衍生开书时即可克隆复用；不解析也可直接继续开书。
                  </div>
                </div>
              </div>
            </template>
          </el-form-item>
        </el-form>
      </template>

      <template v-else-if="wizardStep === 1">
        <el-form label-width="92px">
          <div v-if="presetBand" style="font-size: 12px; color: #999; margin: 0 0 10px 92px">
            本书每章字数带（期望字数，来自预设「{{ chosenPresetName || '所选预设' }}」）：约 <b>{{ presetBand[0] }}–{{ presetBand[1] }}</b> 字/章，
            之后可在素材库·风格包调整
          </div>
          <el-form-item label="参考样本">
            <el-select v-model="wizardForm.sampleId" clearable placeholder="选择导入小说（可不选）" style="width: 100%">
              <el-option v-for="s in wizardSamples" :key="s.id" :value="s.id" :label="s.title">
                <span>{{ s.title }}</span>
                <span style="float: right; color: #999; font-size: 12px">{{ (s.totalChars / 10000).toFixed(0) }} 万字</span>
              </el-option>
            </el-select>
            <div v-if="wizardForm.sampleId" style="display: flex; gap: 12px; font-size: 13px; margin-top: 4px">
              <el-checkbox v-model="wizardForm.cloneAssets.cards">设定卡（地点/物品/组织/现象；不含原书人物——新书写新人物）</el-checkbox>
              <el-checkbox v-model="wizardForm.cloneAssets.world">世界观</el-checkbox>
              <el-checkbox v-model="wizardForm.cloneAssets.plotOutline">剧情骨架预填大纲（慎用：大纲会贴近原书剧情，规划出的卷纲也会像原书；衍生新书建议改用「AI 生成大纲」）</el-checkbox>
            </div>
            <div style="font-size: 12px; color: #999; line-height: 1.7">
              克隆的是样本深度解析出的资产（素材库 → 导入小说 → 深度解析）；未解析的样本克隆不到东西，先去解析。
            </div>
            <el-alert v-if="selectedSample && !selectedSample.presetId" type="warning" :closable="false"
                      :title="`样本「${selectedSample.title}」还没有提取文风预设（开书下拉里选不到它）`"
                      style="margin-top: 6px">
              <el-button size="small" type="primary" :loading="sampleAdopting" @click="adoptSamplePresetFromWizard">
                提取文风预设并使用
              </el-button>
            </el-alert>
          </el-form-item>
          <el-form-item label="类型标签">
            <div style="width: 100%">
              <el-select v-model="wizardForm.derive.tags" multiple filterable allow-create default-first-option
                         placeholder="选样本标签沿用，或直接输入新标签（如：言情、剑与魔法、长篇）" style="width: 100%">
                <el-option v-for="t in sampleTagOptions" :key="t" :value="t" :label="t" />
              </el-select>
              <div style="display: flex; align-items: center; gap: 8px; margin-top: 4px">
                <el-button v-if="sampleTagOptions.length" size="small" link type="primary"
                           @click="wizardForm.derive.tags = [...sampleTagOptions]">沿用样本标签</el-button>
                <span style="font-size: 12px; color: #999">
                  控制衍生书的类型基调与标志性元素（题材/体量节奏/特征元素），进卷规划与正文提示词
                </span>
              </div>
            </div>
          </el-form-item>
          <el-form-item label="掺水量">
            <div style="display: flex; align-items: center; gap: 12px; width: 100%">
              <span style="font-size: 12px; color: #999">干货</span>
              <el-slider v-model="wizardForm.derive.water" :min="0" :max="100" :step="5" style="flex: 1" />
              <span style="font-size: 12px; color: #999">舒缓</span>
              <el-tag size="small" :type="wizardForm.derive.water >= 70 ? 'warning' : wizardForm.derive.water <= 30 ? 'success' : 'info'">
                {{ wizardForm.derive.water >= 70 ? '可注水' : wizardForm.derive.water <= 30 ? '零注水' : '均衡' }}
              </el-tag>
            </div>
            <div style="font-size: 12px; color: #999">决定读者评审的注水拦截线：越干修剪越狠，越水容忍度越高（写入本书门禁）</div>
          </el-form-item>
          <el-form-item label="叙事视角">
            <el-select v-model="wizardForm.derive.pov" style="width: 200px">
              <el-option value="第一人称（主角）" label="第一人称（主角）" />
              <el-option value="第三人称限知" label="第三人称限知" />
              <el-option value="第三人称全知" label="第三人称全知" />
              <el-option value="多视角轮换" label="多视角轮换" />
            </el-select>
            <el-input v-if="wizardForm.derive.pov !== '多视角轮换'" v-model="wizardForm.derive.povCharacter"
                      placeholder="主视角人物名（可选）" style="width: 200px; margin-left: 8px" />
          </el-form-item>
          <el-form-item label="节奏">
            <el-input-number v-model="wizardForm.derive.chaptersPerVolume" :min="3" :max="30" size="small" />
            <span style="margin-left: 6px; font-size: 13px">章/卷</span>
            <el-input-number v-model="wizardForm.derive.targetChapters" :min="10" :max="2000" :step="50" size="small" style="margin-left: 16px" />
            <span style="margin-left: 6px; font-size: 13px">章目标（总）</span>
          </el-form-item>
          <el-form-item label="节奏说明">
            <el-input v-model="wizardForm.derive.pacingNote" type="textarea" :rows="2"
                      placeholder="给卷规划的节奏交代（可选），如：前三卷日常铺垫，之后两卷一个大高潮" />
          </el-form-item>
          <el-form-item label="无人续跑">
            <el-switch v-model="wizardForm.derive.autoContinue" />
            <span style="margin-left: 8px; font-size: 12px; color: #999">
              开=写到总目标为止全自动（卷尽自动规划下卷续批；规划/审批强制 auto，审校硬伤自动重写一轮，仍不过暂停等人）
            </span>
          </el-form-item>
          <el-form-item label="队列优先级">
            <el-radio-group v-model="wizardForm.derive.priority" size="small">
              <el-radio-button :value="0">低</el-radio-button>
              <el-radio-button :value="1">中</el-radio-button>
              <el-radio-button :value="2">高</el-radio-button>
            </el-radio-group>
            <el-button size="small" type="primary" plain :loading="paramsDeciding" :disabled="!wizardForm.sampleId"
                       style="margin-left: 16px" @click="aiDecideParams">AI 帮我定</el-button>
            <span v-if="!wizardForm.sampleId" style="font-size: 12px; color: #999; margin-left: 6px">（选了参考样本才可用）</span>
          </el-form-item>
        </el-form>
      </template>

      <template v-else-if="wizardStep === 2">
        <div style="display: flex; align-items: center; gap: 10px; margin-bottom: 8px">
          <el-button size="small" type="primary" plain :loading="outlineDrafting"
                     :disabled="(!wizardForm.title.trim() || !wizardForm.presetId) && !outlineDrafting"
                     @click="aiDraftOutline">{{ outlineDrafting ? '大纲生成中…' : 'AI 生成大纲草稿' }}</el-button>
          <span style="font-size: 12px; color: #999">
            后台并发生成（约半分钟/本，可连续批量提交），完成后自动填入本框；按书名/简介/文风预设 + 衍生设定（样本骨架、类型标签、POV、节奏与目标章数）生成
          </span>
        </div>
        <el-input v-model="wizardForm.outline" type="textarea" :rows="14"
                  :placeholder="wizardForm.sampleId && wizardForm.cloneAssets.plotOutline
                    ? '可留空——创建时会自动预填样本剧情骨架（标注待改写，之后在「规划」页改写）。也可点上方 AI 生成或直接写全书大纲。'
                    : '全书大纲：主题、主线、分卷走向、主要人物。生成每一章都会携带它作为方向约束。可点上方 AI 生成草稿后修改。'" />
        <div style="font-size: 12px; color: #999; margin-top: 6px">
          可先跳过、之后在「规划」页补写保存；但开跑生成前必须有——没有大纲的章会失去方向约束。
        </div>
      </template>

      <template v-else>
        <el-result v-if="wizardCreated" icon="success" :title="`《${wizardCreated.title}》已创建`"
          :sub-title="`风格包已从预设克隆，当前 ${wizardCreated.chapterCount} 章。接下来三步：`">
          <template #extra>
            <div style="text-align: left; font-size: 13px; line-height: 2">
              <div v-if="wizardForm.derive.autoContinue">
                本书已开「无人续跑」：回工作台点「启动续跑」即可写到总目标章数为止——卷尽自动规划下卷、自动续批；中途出硬伤会暂停等你处理。
              </div>
              <template v-else>
                <div>① 到「<router-link to="/planning">规划</router-link>」页确认/补写大纲，点「AI 规划一卷」生成首卷卷纲（2-10 分钟）</div>
                <div>② 回工作台设好连跑范围（默认从第 1 章起），点「启动生成」</div>
              </template>
              <div>③ 生成中在工作台看实时逐字流；写完的章去「章节」页阅读/审批</div>
            </div>
            <div style="display: flex; gap: 10px; justify-content: center">
              <el-button type="primary" @click="wizardDone">开始规划 →</el-button>
              <el-button @click="router.push('/')">去工作台</el-button>
            </div>
          </template>
        </el-result>
        <div v-else style="text-align: center; padding: 30px; color: #999">
          创建中……（克隆预设、建风格包、落书、存大纲）
        </div>
      </template>

      <div style="margin-top: 20px; display: flex; justify-content: flex-end; gap: 10px">
        <el-button v-if="wizardStep > 0 && wizardStep < 3" @click="wizardStep--">上一步</el-button>
        <el-button v-if="wizardStep < 2" type="primary"
          :disabled="(!wizardForm.title.trim() || !wizardForm.presetId || !presets.length) && !createdNovelId"
          @click="wizardStep++">下一步</el-button>
        <el-button v-else-if="wizardStep === 2 && !createdNovelId" type="primary" :loading="wizardBusy" @click="createNovel">创建作品</el-button>
        <el-button v-else-if="wizardStep === 2 && createdNovelId" type="primary" :loading="wizardBusy" @click="finalizeDraft">完成并激活</el-button>
      </div>
    </el-card>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api } from '../api'

const router = useRouter()

const wizardStep = ref(0)
const wizardBusy = ref(false)
const wizardCreated = ref(null)
const createdNovelId = ref(null)
const draftBook = ref(null)
const wizardForm = ref({
  title: '', description: '', presetId: null, outline: '',
  sampleId: null,
  cloneAssets: { cards: true, world: true, plotOutline: true },
  derive: { water: 50, pov: '第三人称限知', povCharacter: '', pacingNote: '', chaptersPerVolume: 10, targetChapters: 300, autoContinue: false, priority: 1, tags: [] }
})
const wizardSamples = ref([])
const paramsDeciding = ref(false)
const outlineDrafting = ref(false)
const presetBand = computed(() => {
  const p = presets.value.find((x) => x.id === wizardForm.value.presetId)
  return p && p.budgetMin && p.budgetMax ? [p.budgetMin, p.budgetMax] : null
})
const sampleTagOptions = computed(() => {
  const s = wizardSamples.value.find((x) => x.id === wizardForm.value.sampleId)
  return s ? (s.tags || []) : []
})
const selectedSample = computed(() => wizardSamples.value.find((x) => x.id === wizardForm.value.sampleId) || null)
const sampleAdopting = ref(false)
const presets = ref([])
const presetMode = ref('select')
const sampleForm = ref({ name: '', text: '', mobiBase64: '' })
const analyzing = ref(false)
const analyzeResult = ref(null)
const newGenreForm = ref({ genre: '', presetName: '' })
const adopting = ref(false)
const chosenPresetName = ref('')

const bestSim = computed(() => {
  const sims = (analyzeResult.value?.similarities || []).filter((s) => s.comparable)
  return sims.length ? sims[0] : null
})

/** 页面进入即初始化新一轮向导（重新进入页面自动重置）。 */
async function initWizard() {
  wizardStep.value = 0
  wizardCreated.value = null
  createdNovelId.value = null
  wizardForm.value = {
    title: '', description: '', presetId: null, outline: '',
    sampleId: null,
    cloneAssets: { cards: true, world: true, plotOutline: true },
    derive: { water: 50, pov: '第三人称限知', povCharacter: '', pacingNote: '', chaptersPerVolume: 10, targetChapters: 300, autoContinue: false, priority: 1, tags: [] }
  }
  presetMode.value = 'select'
  sampleForm.value = { name: '', text: '', mobiBase64: '' }
  analyzeResult.value = null
  newGenreForm.value = { genre: '', presetName: '' }
  chosenPresetName.value = ''
  outlineDrafting.value = false
  draftBook.value = null
  wizardSamples.value = await api.get('/api/preset/samples').catch(() => [])
  try {
    presets.value = await api.get('/api/preset/list')
    if (presets.value.length) wizardForm.value.presetId = presets.value[0].id
  } catch (e) {
    presets.value = []
    ElMessage.error(e.message)
  }
  // 草稿检测：生大纲时书已落库，离开页面后回来自动接续——大纲已生成好就直接恢复填入
  try {
    const books = await api.get('/api/novels')
    draftBook.value = books.find((b) => b.status === 'draft') || null
    if (draftBook.value) {
      const task = await api.get(`/api/novels/${draftBook.value.id}/outline-draft/latest`).catch(() => null)
      if (task && task.status === 'DONE') {
        await resumeDraft()
      }
    }
  } catch { /* 草稿检测失败不拦初始化 */ }
}

/** 继续草稿：恢复书名/样本/衍生设定到大纲步，并自动取回已生成/生成中的大纲。 */
async function resumeDraft() {
  const b = draftBook.value
  if (!b) return
  try {
    const cfg = await api.get(`/api/novels/${b.id}/derive-config`)
    createdNovelId.value = b.id
    wizardForm.value.title = b.title
    wizardForm.value.description = b.description || ''
    wizardForm.value.sampleId = cfg.sourceSampleId || null
    const d = wizardForm.value.derive
    d.water = cfg.water ?? 50
    d.pov = cfg.pov || '第三人称限知'
    d.povCharacter = cfg.povCharacter || ''
    d.pacingNote = cfg.pacingNote || ''
    d.chaptersPerVolume = cfg.chaptersPerVolume ?? 10
    d.targetChapters = cfg.targetChapters ?? 300
    d.autoContinue = !!cfg.autoContinue
    d.priority = cfg.priority ?? 1
    d.tags = cfg.tags || []
    wizardStep.value = 2
    draftBook.value = null
    const task = await api.get(`/api/novels/${b.id}/outline-draft/latest`).catch(() => null)
    if (task && task.id) {
      if (task.status === 'DONE') {
        wizardForm.value.outline = task.result
        ElMessage.success('已取回生成好的大纲，确认后点「完成并激活」')
      } else if (task.status === 'FAILED') {
        ElMessage.warning('上次大纲生成失败，可重新点「AI 生成大纲草稿」')
      } else {
        outlineDrafting.value = true
        ElMessage.success('大纲正在后台生成，完成后自动填入')
        pollOutlineTask(task.id)
      }
    } else {
      ElMessage.info('已恢复草稿设定，该书还没生成过大纲')
    }
  } catch (e) {
    ElMessage.error(e.message)
  }
}

/** 导入小说分析：切块即落库（品类名唯一化，语料永久可复用），返回与现有品类的相似度与建议。 */
async function analyzeSample() {
  analyzing.value = true
  try {
    analyzeResult.value = await api.post('/api/preset/analyze', {
      sampleName: sampleForm.value.name,
      text: sampleForm.value.text,
      mobiBase64: sampleForm.value.mobiBase64 || undefined
    })
    newGenreForm.value.genre = analyzeResult.value.genre
    newGenreForm.value.presetName = (sampleForm.value.name.trim() || analyzeResult.value.genre) + '·自动提取v1'
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    analyzing.value = false
  }
}

function usePreset(sim) {
  wizardForm.value.presetId = sim.presetId
  chosenPresetName.value = sim.name
}

/** 一键建品类：切块落语料 + 采纳为预设，随后当作普通预设继续向导。 */
async function adoptFromSample() {
  adopting.value = true
  try {
    const r = await api.post('/api/preset/from-sample', {
      genre: newGenreForm.value.genre,
      presetName: newGenreForm.value.presetName,
      text: sampleForm.value.text
    })
    wizardForm.value.presetId = r.presetId
    chosenPresetName.value = r.presetName
    presets.value = await api.get('/api/preset/list')
    ElMessage.success(`品类「${r.genre}」已建（${r.chunks} 块语料），预设已选用`)
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    adopting.value = false
  }
}

function onSampleFile(ev) {
  const f = ev.target.files && ev.target.files[0]
  if (!f) return
  if (/\.(mobi|azw3|azw)$/i.test(f.name)) {
    const reader = new FileReader()
    reader.onload = () => {
      sampleForm.value.mobiBase64 = String(reader.result || '')
      sampleForm.value.text = ''
      if (!sampleForm.value.name) sampleForm.value.name = f.name.replace(/\.(mobi|azw3|azw)$/i, '')
    }
    reader.readAsDataURL(f)
  } else {
    const reader = new FileReader()
    reader.onload = () => {
      sampleForm.value.text = String(reader.result || '')
      sampleForm.value.mobiBase64 = ''
      if (!sampleForm.value.name) sampleForm.value.name = f.name.replace(/\.txt$/i, '')
    }
    reader.readAsText(f, 'utf-8')
  }
  ev.target.value = ''
}

/** 向导内分析完直接跳素材库深度解析（带 sampleId 定位高亮）。 */
function goDeepParse(sampleId) {
  router.push({ path: '/library', query: { sampleId } })
}

/** 样本品类还没采纳预设时的一键提取（完成后自动选中新预设）。 */
async function adoptSamplePresetFromWizard() {
  const s = selectedSample.value
  if (!s) return
  sampleAdopting.value = true
  try {
    const r = await api.post('/api/preset/from-sample', {
      genre: s.genre,
      presetName: s.genre + '·文风v1',
      description: '源品类：' + s.genre + '，' + s.chunks + ' 块语料'
    })
    presets.value = await api.get('/api/preset/list')
    wizardForm.value.presetId = r.presetId
    chosenPresetName.value = r.presetName
    wizardSamples.value = await api.get('/api/preset/samples')
    ElMessage.success(`文风预设「${r.presetName}」已生成并选用`)
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    sampleAdopting.value = false
  }
}

/** 「AI 帮我定」：依据样本结构画像回填衍生参数（用户可继续改）。 */
async function aiDecideParams() {
  paramsDeciding.value = true
  try {
    const r = await api.post(`/api/preset/samples/${wizardForm.value.sampleId}/recommend-params`)
    const d = wizardForm.value.derive
    d.water = r.water ?? d.water
    d.pov = r.pov || d.pov
    d.povCharacter = r.povCharacter || d.povCharacter
    d.chaptersPerVolume = r.chaptersPerVolume || d.chaptersPerVolume
    d.targetChapters = r.targetChapters || d.targetChapters
    d.pacingNote = r.pacingNote || d.pacingNote
    if ((r.tags || []).length) d.tags = r.tags
    ElMessage.success(r.reason ? `已按样本画像回填：${r.reason}` : '已按样本画像回填')
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    paramsDeciding.value = false
  }
}

function buildOutlinePayload() {
  const f = wizardForm.value
  return {
    title: f.title.trim(),
    description: f.description.trim() || undefined,
    presetId: f.presetId,
    sampleId: f.sampleId || undefined,
    novelId: createdNovelId.value || undefined,
    draft: createdNovelId.value ? undefined : true,
    deriveConfig: {
      water: f.derive.water,
      pov: f.derive.pov,
      povCharacter: f.derive.povCharacter.trim() || undefined,
      pacingNote: f.derive.pacingNote.trim() || undefined,
      chaptersPerVolume: f.derive.chaptersPerVolume,
      targetChapters: f.derive.targetChapters,
      priority: f.derive.priority,
      tags: (f.derive.tags || []).length ? f.derive.tags : undefined
    }
  }
}

/** AI 生成大纲草稿：异步提交秒回，后台并发生成，轮询完成后自动填入（页面不阻塞，可连续批量提交）。 */
async function aiDraftOutline() {
  if (outlineDrafting.value) {
    ElMessage.info('上一份大纲还在后台生成中，完成后自动填入')
    return
  }
  if (wizardForm.value.outline.trim()) {
    try {
      await ElMessageBox.confirm('当前已有一份大纲内容，生成完成后将覆盖它。继续？', '覆盖确认', { type: 'warning' })
    } catch (e) {
      if (e !== 'cancel') ElMessage.error(e.message)
      return
    }
  }
  try {
    // 生大纲即落库：首次点击先静默建书（草稿态），页面关闭/浏览器崩溃设定与大纲任务都不丢
    if (!createdNovelId.value) {
      const n = await api.post('/api/novels', { ...buildOutlinePayload(), draft: true })
      createdNovelId.value = n.id
      ElMessage(`书已落库为草稿（ID ${n.id}），书籍管理页可见`)
    }
    const payload = buildOutlinePayload()
    delete payload.draft
    const taskId = await api.post('/api/novels/outline-draft', payload)
    outlineDrafting.value = true
    ElMessage.success('已提交后台生成，完成后自动填入——可先继续填其他内容')
    pollOutlineTask(taskId)
  } catch (e) {
    ElMessage.error(e.message)
  }
}

async function pollOutlineTask(taskId) {
  for (;;) {
    await new Promise((r) => setTimeout(r, 3000))
    let st
    try {
      st = await api.get(`/api/novels/outline-draft/${taskId}`)
    } catch { /* 轮询失败下一轮重试 */ continue }
    if (st.status === 'DONE') {
      outlineDrafting.value = false
      wizardForm.value.outline = st.result
      ElMessage.success('大纲草稿已生成并填入，可自由修改后创建作品')
      return
    }
    if (st.status === 'FAILED') {
      outlineDrafting.value = false
      ElMessage.error(st.message || '大纲生成失败，可重试')
      return
    }
  }
}

async function createNovel() {
  wizardBusy.value = true
  wizardStep.value = 3
  wizardCreated.value = null
  try {
    const n = await api.post('/api/novels', {
      title: payload.title,
      description: payload.description,
      presetId: payload.presetId,
      sampleId: payload.sampleId,
      cloneAssets: wizardForm.value.sampleId ? { ...wizardForm.value.cloneAssets } : undefined,
      deriveConfig: payload.deriveConfig
    })
    if (wizardForm.value.outline.trim()) {
      try {
        await api.put(`/api/novels/${n.id}/planning/story`, { content: wizardForm.value.outline.trim() })
      } catch (e2) {
        ElMessage.warning(`作品已创建，但大纲保存失败（${e2.message}）——请到「规划」页补写`)
      }
    }
    wizardCreated.value = n
  } catch (e) {
    ElMessage.error(e.message)
    wizardStep.value = 0
  } finally {
    wizardBusy.value = false
  }
}

/** 草稿书收尾：保存大纲 + 激活为正式书。 */
async function finalizeDraft() {
  wizardBusy.value = true
  try {
    if (wizardForm.value.outline.trim()) {
      await api.put(`/api/novels/${createdNovelId.value}/planning/story`, { content: wizardForm.value.outline.trim() })
    }
    await api.post(`/api/novels/${createdNovelId.value}/activate`)
    wizardCreated.value = { title: wizardForm.value.title.trim(), chapterCount: 0 }
    wizardStep.value = 3
    ElMessage.success('草稿已激活为正式作品')
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    wizardBusy.value = false
  }
}

/** 向导收尾：落到规划页，接上「AI 规划一卷」那一步。 */
function wizardDone() {
  router.push('/planning')
}

onMounted(initWizard)
</script>
