<template>
  <div>
    <h3>工作台</h3>

    <el-card shadow="never" style="margin-bottom: 12px">
      <div style="display: flex; gap: 16px; align-items: center; flex-wrap: wrap">
        <el-select v-model="novelId" style="width: 240px" @change="onNovelChange">
          <el-option v-for="n in novels" :key="n.id" :value="n.id" :label="n.title" />
        </el-select>
        <el-button size="small" plain @click="router.push('/wizard')">＋ 开新书</el-button>
        <span>共 {{ novel?.chapterCount || 0 }} 章</span>
        <el-tooltip placement="bottom" content="卷纲由谁定稿。自动：AI 规划完整卷纲后直接落库，立即可开跑；人工：规划只出草稿，需到「规划」页采纳后才生效，不采纳不生成。">
          <span>规划模式：
            <el-switch v-model="planManual" active-text="人工" inactive-text="自动" @change="switchPlanMode" />
          </span>
        </el-tooltip>
        <el-tooltip placement="bottom" content="每章写完后由谁放行。自动：AI 审校无硬伤即放行、直接续写下一章；人工：每章停在「待审批」，去「章节」页逐章放行后才继续。">
          <span>审批模式：
            <el-switch v-model="manual" active-text="人工" inactive-text="自动" @change="switchMode" />
          </span>
        </el-tooltip>
        <el-divider direction="vertical" />
        <el-tooltip placement="bottom" content="掺水量/叙事视角/每卷章数/目标章数/无人续跑/优先级——开书后随时可改；老书可由此启用无人续跑。">
          <el-button size="small" plain @click="openDeriveEditor">衍生参数</el-button>
        </el-tooltip>
        <span>连跑范围：第 <el-input-number v-model="from" :min="1" size="small" /> 至
          <el-input-number v-model="to" :min="from" size="small" /> 章</span>
        <span>优先级：
          <el-select v-model="runPriority" size="small" style="width: 72px">
            <el-option :value="0" label="低" />
            <el-option :value="1" label="中" />
            <el-option :value="2" label="高" />
          </el-select>
        </span>
        <el-button type="primary" size="small" :loading="running" @click="run">启动生成</el-button>
        <el-button size="small" type="danger" plain @click="stopAllTasks">全部停止</el-button>
        <el-tag :type="running ? 'warning' : 'info'" size="small">{{ running ? '运行中' : '空闲' }}</el-tag>
        <span style="color:#999;font-size:12px">{{ lastMessage }}</span>
        <el-badge :value="pendingCount" :hidden="!pendingCount" style="margin-left: auto">
          <el-button size="small" plain @click="openPending">待审批{{ pendingCount ? ` ${pendingCount} 章` : '' }}</el-button>
        </el-badge>
        <router-link to="/chapters" style="font-size: 12px; margin-left: 12px">去章节页阅读 →</router-link>
      </div>
    </el-card>

    <!-- 待审批明细（此前只有计数，章列表接口一直有数据没展示） -->
    <el-dialog v-model="pendingOpen" title="待审批章节" width="480px">
      <el-table v-if="pendingList.length" :data="pendingList" border size="small" @row-click="goPending">
        <el-table-column prop="chapterNo" label="章号" width="70" />
        <el-table-column prop="title" label="标题" min-width="160" />
        <el-table-column label="预算" width="120">
          <template #default="{ row }">{{ row.budgetMin }}-{{ row.budgetMax }} 字</template>
        </el-table-column>
      </el-table>
      <el-empty v-else description="当前无待审批章节" :image-size="50" />
      <div style="font-size: 12px; color: #999; margin-top: 8px">点行跳转章节页审批</div>
    </el-dialog>

    <el-card shadow="never" style="margin-bottom: 12px">
      <div style="display: flex; gap: 8px; align-items: center; flex-wrap: wrap">
        <b style="font-size: 13px">评审标准（本书）</b>
        <span style="font-size: 12px; color: #606266">注水软阈值</span>
        <el-input-number v-model="readerStd.reader_fat_ratio_block" :min="0" :max="1" :step="0.01" size="small" style="width: 92px" />
        <span style="font-size: 12px; color: #606266">硬上限</span>
        <el-input-number v-model="readerStd.reader_fat_ratio_hard" :min="0" :max="1" :step="0.01" size="small" style="width: 92px" />
        <span style="font-size: 12px; color: #606266">恢复线比例</span>
        <el-input-number v-model="readerStd.reader_fix_len_min" :min="0.3" :max="1" :step="0.05" size="small" style="width: 92px" />
        <span style="font-size: 12px; color: #606266">扩写护栏</span>
        <el-input-number v-model="readerStd.reader_fix_len_max" :min="1" :max="2" :step="0.05" size="small" style="width: 92px" />
        <span style="font-size: 12px; color: #606266">审校下限</span>
        <el-input-number v-model="readerStd.ai_review_fix_floor" :min="0.3" :max="1" :step="0.05" size="small" style="width: 92px" />
        <el-button size="small" :loading="stdSaving" @click="saveStd">保存到本书</el-button>
        <span style="color:#999;font-size:12px">连贯性优先：四问全过时仅超硬上限才转人工；字数可让路剧情。保存写入本书门禁配置，立即生效</span>
      </div>
    </el-card>


    <!-- 无人续跑链状态（开了无人续跑的书才有；目标进度/暂停原因/恢复入口） -->
    <el-card v-if="autoChain && autoChain.enabled" shadow="never" style="margin-bottom: 12px">
      <template #header>
        <div style="display: flex; align-items: center; gap: 10px">
          <b style="font-size: 13px">无人续跑</b>
          <el-tag size="small" :type="autoChainTagType">{{ autoChainText }}</el-tag>
          <span style="font-size: 12px; color: #999">
            {{ autoChain.currentChapters }}/{{ autoChain.targetChapters ?? '∞' }} 章 · 已规划 {{ autoChain.volumes }} 卷
          </span>
          <el-button v-if="autoChain.state !== 'REACHED' && autoChain.state !== 'RUNNING'"
                     size="small" type="primary" plain @click="resumeAutoChain">
            {{ autoChain.state === 'PAUSED' ? '恢复续跑' : '启动续跑' }}
          </el-button>
          <el-button size="small" plain @click="openDeriveEditor">参数设置</el-button>
          <span v-if="autoChain.message" style="font-size: 12px; color: #e6a23c; flex: 1; text-align: right">
            {{ autoChain.message }}
          </span>
        </div>
      </template>
      <el-progress v-if="autoChain.targetChapters"
                   :percentage="Math.min(100, Math.round(autoChain.currentChapters / autoChain.targetChapters * 100))" />
      <div v-else style="font-size: 12px; color: #999">未设目标章数：续跑按卷推进，达到保险丝或人工停止为止</div>
    </el-card>

    <!-- 衍生参数编辑（开书后随时改；老书由此启用无人续跑） -->
    <el-dialog v-model="deriveEditorOpen" title="衍生参数（本书）" width="600px">
      <el-form label-width="92px" v-if="deriveEdit">
        <el-form-item label="掺水量">
          <div style="display: flex; align-items: center; gap: 12px; width: 100%">
            <span style="font-size: 12px; color: #999">干货</span>
            <el-slider v-model="deriveEdit.water" :min="0" :max="100" :step="5" style="flex: 1" />
            <span style="font-size: 12px; color: #999">舒缓</span>
            <el-tag size="small" :type="deriveEdit.water >= 70 ? 'warning' : deriveEdit.water <= 30 ? 'success' : 'info'">
              {{ deriveEdit.water >= 70 ? '可注水' : deriveEdit.water <= 30 ? '零注水' : '均衡' }}
            </el-tag>
          </div>
        </el-form-item>
        <el-form-item label="叙事视角">
          <el-select v-model="deriveEdit.pov" style="width: 200px">
            <el-option value="第一人称（主角）" label="第一人称（主角）" />
            <el-option value="第三人称限知" label="第三人称限知" />
            <el-option value="第三人称全知" label="第三人称全知" />
            <el-option value="多视角轮换" label="多视角轮换" />
          </el-select>
          <el-input v-if="deriveEdit.pov !== '多视角轮换'" v-model="deriveEdit.povCharacter"
                    placeholder="主视角人物名（可选）" style="width: 180px; margin-left: 8px" />
        </el-form-item>
        <el-form-item label="节奏">
          <el-input-number v-model="deriveEdit.chaptersPerVolume" :min="3" :max="30" size="small" />
          <span style="margin-left: 6px; font-size: 13px">章/卷</span>
          <el-input-number v-model="deriveEdit.targetChapters" :min="10" :max="2000" :step="50" size="small" style="margin-left: 16px" />
          <span style="margin-left: 6px; font-size: 13px">章目标（总）</span>
        </el-form-item>
        <el-form-item label="节奏说明">
          <el-input v-model="deriveEdit.pacingNote" type="textarea" :rows="2" placeholder="给卷规划的节奏交代（可选）" />
        </el-form-item>
        <el-form-item label="类型标签">
          <div style="width: 100%">
            <el-select v-model="deriveEdit.tags" multiple filterable allow-create default-first-option
                       placeholder="选标签沿用，或输入新标签" style="width: 100%">
              <el-option v-for="t in deriveTagOptions" :key="t" :value="t" :label="t" />
            </el-select>
            <el-button v-if="deriveTagOptions.length" size="small" link type="primary"
                       @click="deriveEdit.tags = [...deriveTagOptions]">沿用样本标签</el-button>
          </div>
        </el-form-item>
        <el-form-item label="无人续跑">
          <el-switch v-model="deriveEdit.autoContinue" />
          <span style="margin-left: 8px; font-size: 12px; color: #999">开=写到总目标为止全自动（规划/审批强制自动）</span>
        </el-form-item>
        <el-form-item label="队列优先级">
          <el-radio-group v-model="deriveEdit.priority" size="small">
            <el-radio-button :value="0">低</el-radio-button>
            <el-radio-button :value="1">中</el-radio-button>
            <el-radio-button :value="2">高</el-radio-button>
          </el-radio-group>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="deriveEditorOpen = false">取消</el-button>
        <el-button type="primary" :loading="deriveSaving" @click="saveDeriveEditor">保存</el-button>
      </template>
    </el-dialog>

    <el-card shadow="never" style="margin-bottom: 12px" header="生成队列（异步执行，逐章回写进度）">
      <el-table v-if="queue.length" :data="queue" border size="small">
        <el-table-column prop="id" label="#" width="50" />
        <el-table-column prop="novelTitle" label="作品" width="150" show-overflow-tooltip />
        <el-table-column label="范围" width="80">
          <template #default="{ row }">
            <span v-if="row.kind === 'PLAN'">卷纲规划</span>
            <span v-else>{{ row.fromChapter }}-{{ row.toChapter }}</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag size="small" :type="TASK_COLOR[row.status] || 'info'">{{ TASK_TEXT[row.status] || row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="进度" min-width="170">
          <template #default="{ row }">
            <el-progress :percentage="Math.round(row.doneChapters / row.totalChapters * 100)"
              :stroke-width="10" :format="() => `${row.doneChapters}/${row.totalChapters}`" />
          </template>
        </el-table-column>
        <el-table-column label="当前章" width="70">
          <template #default="{ row }">{{ row.status === 'RUNNING' ? (row.currentChapter ?? '-') : '-' }}</template>
        </el-table-column>
        <el-table-column label="当前阶段" min-width="140">
          <template #default="{ row }">
            <el-link v-if="row.status === 'RUNNING'" type="primary" :underline="false" style="font-size:12px;color:#e6a23c" @click="openSession(row)">{{ row.currentStep || '准备中' }} ⤢</el-link>
            <span v-else style="color:#bbb">-</span>
          </template>
        </el-table-column>
        <el-table-column label="本章tokens" width="95">
          <template #default="{ row }">
            <span v-if="row.status === 'RUNNING' && row.chapterTokens != null" style="font-size:12px;color:#606266">{{ row.chapterTokens.toLocaleString() }}</span>
            <span v-else style="color:#bbb">-</span>
          </template>
        </el-table-column>
        <el-table-column prop="lastMessage" label="消息" min-width="150" show-overflow-tooltip />
        <el-table-column prop="createTime" label="提交时间" width="110" />
        <el-table-column label="操作" width="130">
          <template #default="{ row }">
            <el-button v-if="row.status !== 'QUEUED'" size="small" type="primary" plain @click="openSession(row)">会话</el-button>
            <el-button v-if="row.status === 'QUEUED'" size="small" type="danger" plain @click="cancelTask(row)">取消</el-button>
            <el-button v-else-if="row.status === 'RUNNING'" size="small" type="danger" @click="stopTask(row)">停止</el-button>
            <el-button v-else-if="row.status === 'PAUSED'" size="small" type="success" @click="resumeTask(row)">继续</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-empty v-else description="队列为空：点「启动生成」加入队列" :image-size="50" />
    </el-card>

    <el-row :gutter="12">
      <el-col :span="10">
        <el-card shadow="never" header="生成进程（实时）">
          <div ref="logBox" style="height: 560px; overflow-y: auto; font-family: monospace; font-size: 12px; line-height: 1.9">
            <div v-for="(l, i) in logs" :key="i" :style="{ color: l.color }">{{ l.text }}</div>
          </div>
        </el-card>
      </el-col>
      <el-col :span="14">
        <el-card shadow="never" header="生成输出（实时流式）">
          <div ref="outBox" style="height: 560px; overflow-y: auto">
            <div v-for="s in scenes" :key="s.key" style="margin-bottom: 16px">
              <div style="color:#999;font-size:12px;margin-bottom:4px;display:flex;align-items:center;gap:8px">
                <span>{{ s.title }}</span>
                <el-tag v-if="s.streaming" type="warning" size="small" effect="plain">
                  {{ s.text ? '正文流式生成中' : (s.think ? '思考中…' : '检索上下文中…') }}
                </el-tag>
                <el-tag v-else size="small" effect="plain" type="info">{{ s.phase === 'reused' ? '复用缓存' : '已完成' }}</el-tag>
                <el-link v-if="s.think" type="info" :underline="false" style="font-size:12px" @click="s.thinkOpen = !s.thinkOpen">
                  {{ s.thinkOpen ? '收起思考' : `思考过程（${s.think.length}字）` }}
                </el-link>
              </div>
              <div v-if="s.streaming && !s.think && !s.text" style="color:#b0b3ba;font-size:12px;border-left:3px solid #d9dee5;padding-left:10px">
                ⏳ 检索与打包上下文（世界状态/事实账/RAG 召回，约 10-20 秒后开始逐字输出）…
              </div>
              <div v-if="s.think && (s.thinkOpen || (s.streaming && !s.text))"
                   style="white-space:pre-wrap;color:#8a8f99;font-size:12px;border-left:3px solid #d9dee5;padding-left:10px;margin-bottom:6px;max-height:220px;overflow-y:auto">{{ s.think }}</div>
              <div style="white-space: pre-wrap; border-left: 3px solid #409eff; padding-left: 10px">{{ s.text }}<span v-if="s.streaming && s.text" style="color:#409eff">▍</span></div>
            </div>
            <el-empty v-if="!scenes.length" description="启动生成后在此实时看到 AI 的思考与正文逐字流出" :image-size="60" />
          </div>
        </el-card>
      </el-col>
    </el-row>

    <!-- 会话视图：agent-IDE 式实时转录（RUNNING 任务点 [会话] 进入；断线/错过的历史见章节抽屉·档案） -->
    <el-dialog v-model="sessionOpen" fullscreen top="0" :show-close="true"
      :title="sessionTask ? `会话 · 任务 #${sessionTask.id} ${sessionTask.novelTitle}${sessionTask.kind === 'PLAN' ? ' · 卷纲规划' : ` · 第 ${sessionTask.fromChapter}-${sessionTask.toChapter} 章`}` : '会话'">
      <template #header>
        <div style="display:flex; align-items:center; gap:12px; padding-right: 32px">
          <b>{{ sessionTask ? `任务 #${sessionTask.id} · ${sessionTask.novelTitle}${sessionTask.kind === 'PLAN' ? ' · 卷纲规划' : ` · 第 ${sessionTask.fromChapter}-${sessionTask.toChapter} 章`}` : '' }}</b>
          <el-tag v-if="sessionTask" size="small" :type="TASK_COLOR[sessionTask.status] || 'info'">{{ TASK_TEXT[sessionTask.status] || sessionTask.status }}</el-tag>
          <span v-if="sessionTask?.status === 'RUNNING'" style="color:#e6a23c;font-size:13px">{{ sessionTask.currentStep || '准备中' }}</span>
          <span v-if="sessionTask?.status === 'RUNNING' && sessionTask.chapterTokens != null" style="font-size:13px;color:#606266">本章 {{ sessionTask.chapterTokens.toLocaleString() }} tokens</span>
          <span style="flex:1"></span>
          <el-button v-if="sessionTask?.status === 'RUNNING'" size="small" type="danger" @click="stopTask(sessionTask)">停止</el-button>
        </div>
      </template>
      <div ref="sessBox" style="height: calc(100vh - 110px); overflow-y: auto; max-width: 880px; margin: 0 auto; padding: 0 8px"
        @scroll="onSessionScroll">
        <div v-for="(t, i) in transcript" :key="i" style="margin-bottom: 14px">
          <!-- 章节分节头 -->
          <div v-if="t.type === 'header'" style="border-bottom: 1px solid #e4e7ed; padding-bottom: 6px; margin: 18px 0 10px">
            <b style="font-size: 15px">第{{ t.chapterNo }}章 {{ t.title }}</b>
            <el-tag v-if="t.state" size="small" style="margin-left:8px" :type="t.state === '完成' ? 'success' : 'danger'">{{ t.state }}</el-tag>
          </div>
          <!-- 场景：流式思考/正文块 -->
          <div v-else-if="t.type === 'scene'">
            <div style="color:#67c23a;font-size:13px;margin-bottom:4px;display:flex;align-items:center;gap:8px">
              <b>场景 {{ t.sceneNo }}</b>
              <span style="color:#999;font-size:12px">{{ t.goal }}</span>
              <el-tag v-if="t.streaming" type="warning" size="small" effect="plain">{{ t.text ? '正文流式生成中' : (t.think ? '思考中…' : '检索上下文中…') }}</el-tag>
              <el-tag v-else size="small" effect="plain" type="info">{{ t.phase === 'reused' ? '复用缓存' : '完成' }}</el-tag>
              <el-link v-if="t.think" type="info" :underline="false" style="font-size:12px" @click="t.thinkOpen = !t.thinkOpen">
                {{ t.thinkOpen ? '收起思考' : `思考（${t.think.length}字）` }}
              </el-link>
            </div>
            <div v-if="t.streaming && !t.think && !t.text" style="color:#b0b3ba;font-size:12px;border-left:3px solid #d9dee5;padding-left:10px">
              ⏳ 检索与打包上下文（约 10-20 秒后开始逐字输出）…
            </div>
            <div v-if="t.think && (t.thinkOpen || (t.streaming && !t.text))"
                 style="white-space:pre-wrap;color:#8a8f99;font-size:12px;border-left:3px solid #d9dee5;padding-left:10px;margin-bottom:6px;max-height:260px;overflow-y:auto">{{ t.think }}</div>
            <div style="white-space:pre-wrap; border-left: 3px solid #409eff; padding-left: 10px; font-size: 14px; line-height: 1.9">{{ t.text }}<span v-if="t.streaming && t.text" style="color:#409eff">▍</span></div>
          </div>
          <!-- 通用步骤/判定行 -->
          <div v-else style="font-size: 13px; display: flex; align-items: baseline; gap: 8px">
            <span :style="{ color: t.color || '#606266' }">▸ {{ t.title }}</span>
            <span v-if="t.note" style="color:#999;font-size:12px">{{ t.note }}</span>
            <el-link v-if="t.reason" type="danger" :underline="false" style="font-size:12px" @click="t.open = !t.open">{{ t.open ? '收起原因' : '原因' }}</el-link>
            <div v-if="t.reason && t.open" style="width:100%; white-space:pre-wrap; color:#c45656; font-size:12px; background:#fef0f0; padding:6px 10px; border-radius:4px; margin-top:4px">{{ t.reason }}</div>
          </div>
        </div>
        <el-empty v-if="!transcript.length" description="暂无转录事件（打开后自动读取本章已完成部分，新事件实时追加）" :image-size="60" />
      </div>
    </el-dialog>
  </div>
</template>

<script setup>
import { onMounted, onUnmounted, ref, nextTick, reactive, computed } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api } from '../api'
import { getSelectedNovelId, setSelectedNovelId } from '../novelSelection'
import { NODE_LABEL, GATE_LABEL, STEP_LABEL, TASK_TEXT, TASK_COLOR } from '../labels'

const router = useRouter()

const novel = ref(null)
const novels = ref([])
const novelId = ref(null)
const manual = ref(false)
const planManual = ref(false)
const from = ref(2)
const to = ref(2)
const runPriority = ref(1)
const running = ref(false)
const lastMessage = ref('')
const queue = ref([])
const pendingCount = ref(0)
const pendingList = ref([])
const logs = ref([])
const scenes = ref([])
const logBox = ref(null)
const outBox = ref(null)

// ===== 会话视图（agent-IDE 式转录）=====
const sessionOpen = ref(false)
const sessionTask = ref(null)
const transcript = ref([])
const sessBox = ref(null)
let followSession = true

function onSessionScroll() {
  const el = sessBox.value
  if (!el) return
  // 滚到底部附近才跟随；用户上翻即暂停，翻回底部恢复
  followSession = el.scrollHeight - el.scrollTop - el.clientHeight < 60
}

function scrollSession() {
  if (!followSession) return
  nextTick(() => { if (sessBox.value) sessBox.value.scrollTop = sessBox.value.scrollHeight })
}

function openSession(row) {
  sessionTask.value = row
  sessionOpen.value = true
  followSession = true
  scrollSession()
  seedSession(row)
}

/** 会话打底：中途打开/刷新后打开也不是空的——把当前章已完成的部分（步骤/调用/判定）从 trace 读回来。
 *  每章只打一次底；之后的增量继续走 SSE。 */
let sessionSeededKey = ''

function planEventPayload(e) {
  try { return JSON.parse(e.payloadJson || '{}') } catch { return {} }
}

function planEventTitle(e) {
  const p = planEventPayload(e)
  if (e.stage === 'volume_plan') {
    if (e.phase === 'start') return `卷纲规划开始（第 ${p.volNo} 卷，从第 ${p.from} 章${p.to ? ' 到第 ' + p.to : ''} 章）`
    if (e.phase === 'retry') return `第 ${p.round} 轮重写（${p.check}）`
    if (e.phase === 'failed') return '卷纲规划放弃（轮次用尽）'
    if (e.phase === 'adopted') return '卷纲落库'
    if (e.phase === 'draft') return '卷纲草稿完成（待采纳）'
  }
  if (e.stage === 'volume_plan_review') return `卷纲审校 · ${e.phase || '判定'}`
  return `${e.stage} ${e.phase || ''}`
}

function planEventReason(e) {
  const p = planEventPayload(e)
  return p.reason || (p.issues && p.issues.join('；')) || ''
}

async function seedSession(row) {
  // PLAN（卷纲规划）任务：从事件流水打底，卷纲过程对用户可见
  if (row && row.kind === 'PLAN') {
    const seedKey = `plan-${row.id}-${row.novelTitle}`
    if (seedKey === sessionSeededKey) return
    sessionSeededKey = seedKey
    try {
      const novel = novels.value.find((n) => n.title === row.novelTitle)
      if (!novel) return
      const events = await api.get(`/api/llm-logs/events?novelId=${novel.id}&limit=40`)
      const seed = events
        .filter((e) => ['volume_plan', 'volume_plan_review'].includes(e.stage))
        .slice(-12)
        .map((e) => ({ type: 'line', title: planEventTitle(e), reason: planEventReason(e),
          color: e.phase === 'failed' ? '#c45656' : e.phase === 'retry' ? '#e6a23c' : '#67c23a' }))
      transcript.value.push({ type: 'header', chapterNo: 0, title: `卷纲规划（任务 #${row.id}）——已发生的过程回放` })
      transcript.value.push(...seed)
      if (!seed.length) transcript.value.push({ type: 'line', title: '暂无卷纲事件（首轮生成中，完成后此处实时出现轮次与判定）' })
      scrollSession()
    } catch { /* 打底失败留空，SSE 增量照常 */ }
    return
  }
  if (!row || !row.currentChapter || !novelId.value) return
  const seedKey = `${row.id}-${row.currentChapter}`
  if (seedKey === sessionSeededKey) return
  try {
    const chapters = await api.get(`/api/novels/${novelId.value}/chapters`)
    const ch = chapters.find((c) => c.chapterNo === row.currentChapter)
    if (!ch) return
    const t = await api.get(`/api/chapters/${ch.id}/trace`)
    const seed = []
    seed.push({ type: 'header', chapterNo: t.chapterNo, title: t.title,
      state: t.status === 'PENDING_APPROVAL' ? '待审批' : null })
    const entries = []
    for (const s of t.steps || []) entries.push({ time: s.updateTime || s.createTime,
      line: { type: 'line', title: `${STEP_LABEL[s.step] || s.step}${s.subKey ? ' · 场景 ' + s.subKey : ''} · ${s.status}`,
        note: s.attempt > 1 ? `第 ${s.attempt} 次尝试` : '', color: s.status === 'DONE' ? '#67c23a' : s.status === 'RUNNING' ? '#e6a23c' : '#c45656' } })
    for (const c of t.calls || []) entries.push({ time: c.createTime,
      line: { type: 'line', title: `${NODE_LABEL[c.node] || c.node}${c.status === 'error' ? '（失败）' : ''}`,
        note: `${(c.totalTokens || 0).toLocaleString()} tok · ${(c.latencyMs / 1000).toFixed(0)}s${c.cost != null ? ' · ¥' + c.cost.toFixed(4) : ''}`,
        color: c.status === 'error' ? '#c45656' : '#606266' } })
    for (const k of t.checks || []) entries.push({ time: k.createTime,
      line: { type: 'line', title: `${GATE_LABEL[k.gateType] || k.gateType}${k.sceneId ? ' · 场景级' : ''} · 第 ${k.round || 1} 轮 · ${k.passed ? '通过' : '未过'}`,
        color: k.passed ? '#67c23a' : '#e6a23c' } })
    entries.sort((a, b) => String(a.time || '').localeCompare(String(b.time || '')))
    for (const e of entries) seed.push(e.line)
    // 历史打底放在已有实时条目之前（本页若已积累 live 事件）
    transcript.value = [...seed, ...transcript.value]
    sessionSeededKey = seedKey
    scrollSession()
  } catch { /* 打底失败不拦实时视图 */ }
}

function findTranscriptScene(d) {
  return [...transcript.value].reverse().find((t) => t.type === 'scene' && t.chapterNo === d.chapterNo && t.sceneNo === d.sceneNo)
}

/** SSE 事件 → 转录条目（与日志并行累积；本页会话期间有效，历史回溯走章节抽屉·档案）。 */
function pushTranscript(event, d) {
  if (event === 'run') {
    transcript.value.push({ type: 'line', title: `任务 ${d.phase}`, note: d.message, color: '#909399' })
  } else if (event === 'chapter') {
    if (d.phase === 'start') {
      transcript.value.push({ type: 'header', chapterNo: d.chapterNo, title: d.title, state: null })
    } else {
      const h = [...transcript.value].reverse().find((t) => t.type === 'header' && t.chapterNo === d.chapterNo)
      if (h) h.state = d.phase === 'done' ? `完成（${d.chars} 字）` : d.phase === 'stopped' ? '终止' : '失败'
      if (d.phase !== 'done') {
        transcript.value.push({ type: 'line', title: d.phase === 'stopped' ? '用户终止' : '章失败', reason: d.reason, color: '#c45656' })
      }
    }
  } else if (event === 'scene') {
    if (d.reason === 'rag_degraded') {
      transcript.value.push({ type: 'line', title: 'RAG 召回失败，本场景降级不注入', reason: d.message, color: '#e6a23c' })
    } else if (d.phase === 'start') {
      transcript.value.push({ type: 'scene', chapterNo: d.chapterNo, sceneNo: d.sceneNo, goal: d.goal,
        text: '', think: '', thinkOpen: false, streaming: true, phase: d.phase })
    } else if (d.phase === 'draft' || d.phase === 'reused') {
      const s = findTranscriptScene(d)
      if (s) { s.text = d.text; s.streaming = false; s.phase = d.phase }
      else transcript.value.push({ type: 'scene', chapterNo: d.chapterNo, sceneNo: d.sceneNo, goal: '',
        text: d.text, think: '', thinkOpen: false, streaming: false, phase: d.phase })
    }
  } else if (event === 'gate' || event === 'chapter_gate') {
    transcript.value.push({
      type: 'line', open: false,
      title: `${event === 'gate' ? '场景 ' + d.sceneNo + ' 门禁' : '章级门禁'}${d.passed ? '通过' : '未过'}${d.rewrite ? '，重写' : ''}`,
      reason: d.reason || undefined, color: d.passed ? '#67c23a' : '#e6a23c' })
  } else if (event === 'revise') {
    transcript.value.push({ type: 'line', title: `修订 ${d.phase}`, note: d.chars ? d.chars + ' 字' : '', color: '#e6a23c' })
  } else if (event === 'reader') {
    if (d.phase === 'verdict') {
      transcript.value.push({ type: 'line', title: `读者评审第 ${d.round} 轮：${d.verdict}`, note: (d.issues || []).join('，'), color: d.verdict === 'pass' ? '#67c23a' : '#e6a23c' })
    } else {
      transcript.value.push({ type: 'line', title: `读者评审 ${d.phase}`, note: d.verdict ? `verdict=${d.verdict}` : '', color: '#e6a23c' })
    }
  } else if (event === 'review') {
    if (d.phase === 'verdict') {
      transcript.value.push({ type: 'line', open: false, title: `AI 审校第 ${d.round} 轮：${d.verdict}（${(d.issues || []).length} 条意见）`, reason: (d.issues || []).join('\n') || undefined, color: d.verdict === 'pass' ? '#67c23a' : '#e6a23c' })
    } else {
      const t = d.phase === 'start' ? 'AI 审校中'
        : d.phase === 'done' ? `AI 审校：${d.verdict}${d.blocked ? '（转人工）' : ''}` : 'AI 审校异常（fail-open）'
      transcript.value.push({ type: 'line', title: t, color: '#e6a23c' })
    }
  } else if (event === 'digest') {
    transcript.value.push({ type: 'line', title: '事实账落库（digest）', color: '#67c23a' })
  } else if (event === 'approve') {
    transcript.value.push({ type: 'line', title: `待人工审批${d.reason === 'review_blocker' ? '（审校硬伤未清）' : ''}`, color: '#e6a23c' })
  } else if (event === 'heal') {
    transcript.value.push({ type: 'line', title: '自愈', note: d.message, color: '#e6a23c' })
  } else if (event === 'outline') {
    transcript.value.push({ type: 'line', title: `章纲 ${d.phase}`, note: d.sceneCount ? d.sceneCount + ' 个场景' : '', color: '#67c23a' })
  } else if (event === 'assemble') {
    transcript.value.push({ type: 'line', title: `拼章完成（${d.chars} 字），章级门禁检测中`, color: '#909399' })
  } else if (event === 'volume_plan') {
    if (d.phase === 'retry') transcript.value.push({ type: 'line', title: `卷纲规划第 ${d.round} 轮重写（${d.check}）`, reason: d.reason, color: '#e6a23c' })
    else if (d.phase === 'failed') transcript.value.push({ type: 'line', title: '卷纲规划放弃（轮次用尽）', reason: d.reason, color: '#c45656' })
    else if (d.phase === 'start') transcript.value.push({ type: 'line', title: `卷纲规划开始（第 ${d.volNo} 卷，从第 ${d.from} 章）`, color: '#67c23a' })
    else if (d.phase === 'adopted') transcript.value.push({ type: 'line', title: `卷纲落库（${d.chapters} 章）`, color: '#67c23a' })
    else if (d.phase === 'draft') transcript.value.push({ type: 'line', title: '卷纲草稿完成（manual 待采纳）', color: '#67c23a' })
  } else if (event === 'volume_retro') {
    transcript.value.push({ type: 'line', title: `卷级复盘 ${d.phase}`, color: '#909399' })
  }
  scrollSession()
}
let es = null
let poll = null

const readerStd = reactive({ reader_fat_ratio_block: 0.33, reader_fat_ratio_hard: 0.5, reader_fix_len_min: 0.75, reader_fix_len_max: 1.15, ai_review_fix_floor: 0.6 })
const stdSaving = ref(false)

async function loadStd() {
  if (!novelId.value) return
  try {
    Object.assign(readerStd, await api.get(`/api/novels/${novelId.value}/reader-standards`))
  } catch { /* 回显失败保持默认 */ }
}

async function saveStd() {
  stdSaving.value = true
  try {
    const raw = await api.get(`/api/novels/${novelId.value}/gate-config`)
    const cfg = JSON.parse(raw || '{}')
    Object.assign(cfg, JSON.parse(JSON.stringify(readerStd)))
    await api.put(`/api/novels/${novelId.value}/gate-config`, { gateConfig: JSON.stringify(cfg) })
    ElMessage.success('评审标准已写入本书门禁配置，立即生效')
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    stdSaving.value = false
  }
}

const COLORS = {
  run: '#909399', chapter: '#409eff', outline: '#67c23a', scene: '#303133',
  gate: '#e6a23c', assemble: '#909399', chapter_gate: '#67c23a',
  revise: '#e6a23c', review: '#e6a23c', digest: '#67c23a', approve: '#e6a23c'
}

function log(event, data) {
  const d = typeof data === 'string' ? JSON.parse(data) : data
  // 流式增量不进日志列表（高频），直接喂给实时输出区与会话转录
  if (event === 'scene' && d.phase === 'chunk') { applyChunk(d); return }
  if ((event === 'review' || event === 'reader') && d.phase === 'chunk') { applyReviewChunk(event, d); return }
  try { pushTranscript(event, d) } catch { /* 转录渲染不牢靠时不影响日志主链路 */ }
  let text = `[${event}] `
  if (d.chapterNo !== undefined) text += `第${d.chapterNo}章 `
  if (event === 'run') {
    if (d.phase === 'queued') text += `任务 #${d.taskId} 入队：${d.novel} 第 ${d.from}-${d.to} 章`
    else if (d.phase === 'start') text += `任务 #${d.taskId} 开始执行`
    else if (d.phase === 'done') text += `任务 #${d.taskId} 全部完成`
    else if (d.phase === 'stopped') text += `任务 #${d.taskId} 停止：${d.message || ''}`
    else if (d.phase === 'canceled') text += `任务 #${d.taskId} 已取消`
    else text += `任务异常：${d.message || ''}`
  }
  else if (event === 'chapter') text += d.phase === 'start' ? `《${d.title}》开始` : d.phase === 'done' ? `完成（${d.chars} 字符）` : `失败：${d.reason}`
  else if (event === 'outline') text += `章纲 ${d.phase}${d.sceneCount ? '，' + d.sceneCount + ' 个场景' : ''}`
  else if (event === 'scene') text += d.reason === 'rag_degraded' ? `RAG 召回失败降级（${d.message || ''}）` : `场景 ${d.sceneNo} ${d.phase}`
  else if (event === 'gate') text += `场景 ${d.sceneNo} 门禁${d.passed ? '通过' : '未过' + (d.rewrite ? '，重写中' : '')}`
  else if (event === 'assemble') text += `拼章完成（${d.chars} 字符），章级门禁检测中`
  else if (event === 'chapter_gate') text += `章级门禁${d.passed ? '通过' : '未过'}`
  else if (event === 'revise') text += `修订轮 ${d.phase}${d.chars ? '（' + d.chars + ' 字符）' : ''}`
  else if (event === 'review') {
    if (d.phase === 'start') text += 'AI 语义审校中'
    else if (d.phase === 'verdict') text += `第 ${d.round} 轮判定：${d.verdict}${d.issues?.length ? '（' + d.issues.length + ' 条意见）' : ''}`
    else if (d.phase === 'done') text += `审校完成：${d.verdict}${d.blocked ? '，转人工审批' : ''}${d.issues?.length ? '，' + d.issues.length + ' 条意见' : ''}`
    else text += `审校异常，跳过（${d.message || 'fail-open'}）`
  }
  else if (event === 'digest') text += `事实账落库`
  else if (event === 'approve') text += `待人工审批${d.reason === 'review_blocker' ? '（审校硬伤未清）' : ''}`
  else if (event === 'reader') {
    if (d.phase === 'verdict') text += `第 ${d.round} 轮判定：${d.verdict}${d.issues?.length ? '（' + d.issues.slice(0, 4).join('，') + '）' : ''}`
    else text += `读者评审 ${d.phase}${d.verdict ? '：' + d.verdict : ''}`
  }
  else if (event === 'heal') text += `自愈：${d.message || d.phase}`
  else if (event === 'volume_plan') {
    if (d.phase === 'retry') text += `卷纲第 ${d.round} 轮重写（${d.check}）：${(d.reason || '').slice(0, 60)}`
    else if (d.phase === 'failed') text += `卷纲规划放弃：${d.reason || ''}`
    else if (d.phase === 'adopted') text += `卷纲落库（${d.chapters} 章）`
    else text += `卷纲规划 ${d.phase}（第 ${d.volNo} 卷）`
  }
  else if (event === 'volume_plan_review') text += `卷纲审校 ${d.phase}`
  else if (event === 'volume_retro') text += `卷级复盘 ${d.phase}`
  logs.value.push({ text, color: COLORS[event] || '#303133' })
  scrollLog()
  if (event === 'run') running.value = d.phase === 'start'
  if (event === 'scene' && d.phase === 'start') previewScene(d)
  if (event === 'scene' && (d.phase === 'draft' || d.phase === 'reused')) pushScene(d)
}

/** 场景开始即建条目：检索/打包上下文阶段就有可见占位（此前要等第一个 chunk 才有内容）。 */
function previewScene(d) {
  const key = `${d.chapterNo}-${d.sceneNo}`
  if (!scenes.value.find((x) => x.key === key)) {
    scenes.value.push({ key, title: `第${d.chapterNo}章 场景${d.sceneNo}`, phase: 'start',
      text: '', think: '', thinkOpen: false, streaming: true })
  }
  scrollOut()
}

function pushScene(d) {
  const key = `${d.chapterNo}-${d.sceneNo}`
  const found = scenes.value.find((s) => s.key === key)
  const item = { key, title: `第${d.chapterNo}章 场景${d.sceneNo}`, phase: d.phase, text: d.text,
    think: found?.think || '', thinkOpen: false, streaming: false }
  if (found) Object.assign(found, item)
  else scenes.value.push(item)
  scrollOut()
}

/** 流式块标题：sceneNo=0 是章级修订专用通道。 */
function chunkTitle(d) {
  return d.sceneNo === 0 ? `第${d.chapterNo}章 章级修订` : `第${d.chapterNo}章 场景${d.sceneNo}`
}

/** 流式增量：思考/正文逐字追加到对应场景块（entry 不存在则新建）；reset 清旧稿（修订是替换不是拼接）。 */
function applyChunk(d) {
  const key = `${d.chapterNo}-${d.sceneNo}`
  let s = scenes.value.find((x) => x.key === key)
  if (!s) {
    s = { key, title: chunkTitle(d), phase: 'chunk', text: '', think: '', thinkOpen: false, streaming: true }
    scenes.value.push(s)
  }
  s.streaming = true
  if (d.type === 'reset') {
    s.text = ''
    s.think = ''
    const tr = findTranscriptScene(d)
    if (tr) { tr.text = ''; tr.think = '' }
    return
  }
  if (d.type === 'think') s.think += d.delta || ''
  else s.text += d.delta || ''
  const t = findTranscriptScene(d)
  if (t) {
    t.streaming = true
    if (d.type === 'think') t.think += d.delta || ''
    else t.text += d.delta || ''
  }
  scrollOut()
  scrollSession()
}

/** 评审思考流：只收 think 增量（审校正文输出是 JSON 判定，不逐字流），块可折叠展开「审校在想什么」。 */
function applyReviewChunk(event, d) {
  const key = `${d.chapterNo}-${event}`
  let s = scenes.value.find((x) => x.key === key)
  if (!s) {
    s = { key, title: `第${d.chapterNo}章 ${event === 'reader' ? '读者评审' : 'AI 审校'}（思考）`,
      phase: 'chunk', text: '', think: '', thinkOpen: false, streaming: true }
    scenes.value.push(s)
  }
  s.streaming = true
  s.think += d.delta || ''
  scrollOut()
}

function scrollLog() {
  nextTick(() => { if (logBox.value) logBox.value.scrollTop = logBox.value.scrollHeight })
}

function scrollOut() {
  nextTick(() => { if (outBox.value) outBox.value.scrollTop = outBox.value.scrollHeight })
}

let esEverConnected = false

function connect() {
  es = new EventSource('/api/pipeline/stream')
  for (const ev of ['run', 'chapter', 'outline', 'scene', 'gate', 'assemble', 'chapter_gate', 'revise',
    'reader', 'review', 'digest', 'approve', 'heal', 'volume_plan', 'volume_plan_review', 'volume_retro']) {
    es.addEventListener(ev, (e) => log(ev, e.data))
  }
  es.onopen = () => {
    // 断线期间的事件不可回放：重连后按 trace 重建转录、用章详情愈合输出区的半截流式块
    if (esEverConnected) rebuildAfterReconnect()
    esEverConnected = true
  }
  es.onerror = () => { /* 断线后 EventSource 自动重连，重连善后在 onopen */ }
}

/** 断线重连善后：错过的 SCENE/DRAFT 事件导致输出区停在半截、转录缺段——从 trace 与章详情重建。 */
async function rebuildAfterReconnect() {
  try {
    const row = sessionTask.value || queue.value.find((r) => r.status === 'RUNNING')
    if (!row || row.kind === 'PLAN' || !row.currentChapter || !novelId.value) return
    const chapters = await api.get(`/api/novels/${novelId.value}/chapters`)
    const ch = chapters.find((c) => c.chapterNo === row.currentChapter)
    if (!ch) return
    // 转录重建：清空后按 trace 重新打底（seedSession 去重键一并复位）
    transcript.value = []
    sessionSeededKey = ''
    if (sessionOpen.value) await seedSession(row)
    // 输出区愈合：断线期间完成的场景用库里的最终稿替换半截流式块
    const detail = await api.get(`/api/chapters/${ch.id}`)
    for (const sc of detail.scenes || []) {
      pushScene({ chapterNo: detail.chapterNo, sceneNo: sc.sceneNo, text: sc.draftText || '', phase: 'draft' })
    }
  } catch { /* 重建失败保持现状，不拦实时链路 */ }
}

async function switchMode() {
  try {
    await api.put(`/api/novels/${novel.value.id}/approval-mode`, { mode: manual.value ? 'manual' : 'auto' })
  } catch (e) {
    ElMessage.error(e.message)
    manual.value = !manual.value
  }
}

/** 规划模式与审批模式成对显性化：用户在工作台就能看到并切换当前书的两条“隐藏规则”。 */
async function loadPlanMode() {
  try {
    const m = await api.get(`/api/novels/${novel.value.id}/planning/mode`)
    planManual.value = m.planMode === 'manual'
  } catch { /* 规划模式读取失败不打扰工作台 */ }
}

async function switchPlanMode() {
  try {
    await api.put(`/api/novels/${novel.value.id}/planning/plan-mode`, { mode: planManual.value ? 'manual' : 'auto' })
  } catch (e) {
    ElMessage.error(e.message)
    planManual.value = !planManual.value
  }
}


async function run() {
  try {
    await api.post('/api/pipeline/run', { novel: novel.value.title, from: from.value, to: to.value, priority: runPriority.value })
    scenes.value = []
    transcript.value = []
    sessionSeededKey = ''
    ElMessage.success('已加入生成队列，进度见上方队列面板')
    await loadQueue()
  } catch (e) {
    ElMessage.error(e.message)
  }
}

async function loadQueue() {
  try {
    queue.value = await api.get('/api/pipeline/queue')
    // 会话视图开着时同步头部状态（阶段/tokens/状态随轮询刷新）
    if (sessionOpen.value && sessionTask.value) {
      sessionTask.value = queue.value.find((r) => r.id === sessionTask.value.id) || sessionTask.value
    }
  } catch { /* 忽略轮询错误 */ }
}

async function cancelTask(row) {
  try {
    await api.post(`/api/pipeline/queue/${row.id}/cancel`)
    ElMessage.success('已取消排队任务')
    await loadQueue()
  } catch (e) {
    ElMessage.error(e.message)
  }
}

/** 流 0 v2：硬停止——落库取消标记并中断在飞 LLM 调用（毫秒级生效），已完成内容保留。 */
async function stopTask(row) {
  try {
    await api.post(`/api/pipeline/queue/${row.id}/stop`)
    ElMessage.success('停止请求已受理，正在中断在飞调用（秒级生效）；已完成内容保留')
    await loadQueue()
  } catch (e) {
    ElMessage.error(e.message)
  }
}

/** 全局急停：终止所有 RUNNING 任务（失控最后闸门）。 */
async function stopAllTasks() {
  try {
    await ElMessageBox.confirm('终止当前所有运行中的生成任务？在飞调用将被中断，已完成内容保留（章节标记为已终止）。', '全部停止', {
      type: 'warning', confirmButtonText: '全部停止', cancelButtonText: '再想想'
    })
  } catch { return }
  try {
    const n = await api.post('/api/pipeline/queue/stop-all')
    ElMessage.success(n > 0 ? `已受理 ${n} 个任务的停止请求` : '当前没有运行中的任务')
    await loadQueue()
  } catch (e) {
    ElMessage.error(e.message)
  }
}

async function resumeTask(row) {
  try {
    await api.post(`/api/pipeline/queue/${row.id}/resume`)
    ElMessage.success('已继续')
    await loadQueue()
  } catch (e) {
    ElMessage.error(e.message)
  }
}

async function loadPending() {
  if (!novelId.value) return
  try {
    pendingList.value = await api.get(`/api/novels/${novelId.value}/pending-approvals`)
    pendingCount.value = pendingList.value.length
  } catch { /* 忽略轮询错误 */ }
}

const pendingOpen = ref(false)

function openPending() {
  pendingOpen.value = true
}

function goPending() {
  pendingOpen.value = false
  setSelectedNovelId(novelId.value)
  window.location.hash = '#/chapters'
}

async function pollStatus() {
  try {
    const s = await api.get('/api/pipeline/status')
    running.value = s.running
    lastMessage.value = s.lastMessage
    await loadQueue()
    await loadPending()
    await loadAutoChain()
  } catch { /* 忽略轮询错误 */ }
}

// ===== 无人续跑链（P3）：状态条 + 恢复/启动 =====
const autoChain = ref(null)
const autoChainText = { OFF: '未启用', IDLE: '待启动', RUNNING: '续跑中', PAUSED: '已暂停', REACHED: '目标达成' }
const autoChainTagType = computed(() => ({
  RUNNING: 'success', REACHED: 'info', PAUSED: 'warning'
}[autoChain.value?.state] || 'info'))

async function loadAutoChain() {
  try {
    autoChain.value = novelId.value
      ? await api.get(`/api/pipeline/novels/${novelId.value}/auto-continue`)
      : null
  } catch { /* 未启用/查询失败不展示 */ }
}

async function resumeAutoChain() {
  try {
    autoChain.value = await api.post(`/api/pipeline/novels/${novelId.value}/auto-continue/resume`)
    ElMessage.success(autoChain.state === 'RUNNING' ? '续跑已恢复，任务已入队' : '续跑已启动，任务已入队')
    await loadQueue()
  } catch (e) {
    ElMessage.error(e.message)
  }
}

// ===== 衍生参数编辑（开书后随时改；老书由此启用无人续跑） =====
const deriveEditorOpen = ref(false)
const deriveSaving = ref(false)
const deriveEdit = ref(null)

const deriveTagOptions = ref([])
async function openDeriveEditor() {
  try {
    const c = await api.get(`/api/novels/${novelId.value}/derive-config`)
    deriveEdit.value = {
      water: c.water ?? 50,
      pov: c.pov || '第三人称限知',
      povCharacter: c.povCharacter || '',
      pacingNote: c.pacingNote || '',
      chaptersPerVolume: c.chaptersPerVolume ?? 10,
      targetChapters: c.targetChapters ?? 300,
      autoContinue: !!c.autoContinue,
      priority: c.priority ?? 1,
      tags: c.tags || []
    }
    // 源样本的 AI 标签作沿用建议
    deriveTagOptions.value = []
    if (c.sourceSampleId) {
      const s = (await api.get('/api/preset/samples').catch(() => [])).find((x) => x.id === c.sourceSampleId)
      if (s) deriveTagOptions.value = s.tags || []
    }
    deriveEditorOpen.value = true
  } catch (e) {
    ElMessage.error(e.message)
  }
}

async function saveDeriveEditor() {
  deriveSaving.value = true
  try {
    const d = deriveEdit.value
    await api.put(`/api/novels/${novelId.value}/derive-config`, {
      deriveConfig: {
        water: d.water,
        pov: d.pov,
        povCharacter: d.povCharacter.trim() || undefined,
        pacingNote: d.pacingNote.trim() || undefined,
        chaptersPerVolume: d.chaptersPerVolume,
        targetChapters: d.targetChapters,
        autoContinue: d.autoContinue,
        priority: d.priority,
        tags: (d.tags || []).length ? d.tags : []
      }
    })
    ElMessage.success(d.autoContinue ? '已保存：无人续跑已启用，可点「启动续跑」开始' : '已保存')
    deriveEditorOpen.value = false
    await loadAutoChain()
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    deriveSaving.value = false
  }
}

function onNovelChange() {
  novel.value = novels.value.find((n) => n.id === novelId.value) || null
  setSelectedNovelId(novelId.value)
  manual.value = novel.value?.approvalMode === 'manual'
  loadPlanMode()
  loadStd()
}

onMounted(async () => {
  novels.value = await api.get('/api/novels')
  novelId.value = getSelectedNovelId() ?? novels.value[0]?.id
  if (!novels.value.some((n) => n.id === novelId.value)) novelId.value = novels.value[0]?.id
  onNovelChange()
  connect()
  pollStatus()
  poll = setInterval(pollStatus, 3000)
})

onUnmounted(() => {
  if (es) es.close()
  if (poll) clearInterval(poll)
})
</script>
