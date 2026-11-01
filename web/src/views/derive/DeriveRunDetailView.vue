<template>
  <div v-loading="loading">
    <PageHeader :title="`Run #${route.params.id}${detail ? ' · ' + (detail.novelTitle || '未挂成品书') : ''}`">
      <el-button size="small" @click="router.push('/derive/runs')">← 返回列表</el-button>
      <el-button size="small" :loading="loading" @click="loadAll">刷新</el-button>
      <el-button v-if="detail?.novelId" size="small" type="primary" link
                 @click="router.push(`/derive/books/${detail.novelId}`)">打开成品书 →</el-button>
      <template #hint>
        <el-tag v-if="detail" size="small" :type="statusTag(RUN_STATUS, detail.status).type">
          {{ statusTag(RUN_STATUS, detail.status).label }}
        </el-tag>
      </template>
    </PageHeader>

    <template v-if="detail">
      <el-card shadow="never" class="block action-bar">
        <div class="action-bar__row">
          <el-tag size="large" :type="statusTag(RUN_STATUS, detail.status).type">
            {{ statusTag(RUN_STATUS, detail.status).label }}
          </el-tag>
          <span class="guide">{{ guide }}</span>
          <span class="action-bar__spacer" />

          <template v-if="detail.status === 'PLANNING' && (!detail.novelId || (storyPlan?.planState?.currentUnit && storyPlan.planState.currentUnit !== 'DONE'))">
            <el-button type="primary" :loading="acting === 'plan'"
                       :disabled="!!acting || autoRunning" @click="actPlan">继续 ⑥a 故事规划</el-button>
          </template>
          <template v-else-if="detail.status === 'PLANNING'">
            <el-button type="primary" :loading="acting === 'draft'"
                       :disabled="!!acting || !!serverDraft || autoRunning" @click="actDraft(false)">⑥b 生成具体稿</el-button>
          </template>
          <template v-else-if="detail.status === 'DRAFTING'">
            <el-button v-if="!gatePassed" type="warning" :loading="acting === 'gate'"
                       :disabled="!!acting || autoRunning" @click="actGate">P6 立项闸（大纲评审）</el-button>
            <el-tag v-else size="large" type="success">立项闸 PASS · 第 {{ gateInfo?.round ?? 1 }} 轮</el-tag>
            <el-button :loading="acting === 'write'" :disabled="!!acting || writeBusy || autoRunning || !gatePassed"
                       @click="actWriteOne">⑦ 写下一章</el-button>
            <el-button type="primary" :disabled="!!acting || writeBusy || autoRunning || !gatePassed"
                       @click="actWriteAll">▶ 连续写完（服务端）</el-button>
          </template>
          <template v-else-if="detail.status === 'QC'">
            <el-button type="primary" :loading="acting === 'qc'"
                       :disabled="!!acting || autoRunning" @click="actQc">⑧ 运行质检</el-button>
          </template>
          <template v-else-if="detail.status === 'REWORK'">
            <el-button type="warning" :loading="acting === 'draft'"
                       :disabled="!!acting || !!serverDraft || autoRunning" @click="actDraft(true)">⑥b 回炉重写具体稿</el-button>
          </template>

          <template v-if="serverAuto?.running">
            <el-button type="warning" plain @click="stopAuto">
              ■ 停全流程（当前步完成即停）
            </el-button>
            <el-button type="danger" plain @click="killAuto">
              ⛔ 立即强停（不等返回）
            </el-button>
          </template>
          <template v-else-if="!['PASSED', 'FAILED', 'SAMPLING'].includes(detail.status)">
            <el-button plain :disabled="!!acting || writeBusy || !!serverDraft"
                       @click="actTrial">试跑一步</el-button>
            <el-button plain :disabled="!!acting || writeBusy || !!serverDraft"
                       @click="resumeTask">↻ 续跑任务</el-button>
            <el-button type="success" :disabled="!!acting || writeBusy || !!serverDraft"
                       @click="actAuto(false)">▶ 全流程无人值守</el-button>
            <el-button type="danger" plain :disabled="!!acting || writeBusy || !!serverDraft"
                       @click="actAuto(true)">⏭ 全程直通·初稿</el-button>
          </template>

          <el-button v-if="writeBusy" type="danger" plain @click="stopWriteLoop">
            ■ 停止（当前章写完即停）
          </el-button>
          <el-button :loading="loading" :disabled="writeBusy || autoRunning" @click="loadAll">刷新</el-button>
          <el-button type="danger" plain
                     :disabled="!!acting || writeBusy || autoRunning"
                     @click="clearPlanning">清空⑥+数据</el-button>
        </div>
        <p v-if="draftLoopText" class="loop-text">{{ draftLoopText }}</p>
        <p v-if="serverAutoText" class="loop-text loop-text--live">{{ serverAutoText }}</p>
        <p v-else-if="serverWriteText" class="loop-text loop-text--live">{{ serverWriteText }}</p>
        <p v-else-if="serverDraftText" class="loop-text loop-text--live">{{ serverDraftText }}</p>
      </el-card>

      <el-card shadow="never" class="block">
        <template #header>
          运行过程
          <el-tag v-if="serverDraft" size="small" type="primary" style="margin-left: 8px">进行中</el-tag>
        </template>
        <div v-if="serverAutoText || serverWriteText || serverDraftText" class="process-live">{{ serverAutoText || serverWriteText || serverDraftText }}</div>
        <div v-else class="process-idle">当前没有进行中的 LLM 任务；下表是本 run 最近的调用账本（服务端事实，刷新/换页都在）。</div>
        <DataTable :data="llmTrail" size="small" style="margin-top: 8px">
          <el-table-column label="时间" width="150">
            <template #default="{ row }">{{ fmtTime(row.createTime) }}</template>
          </el-table-column>
          <el-table-column label="环节" width="150">
            <template #default="{ row }">{{ nodeLabel(row.node) }}</template>
          </el-table-column>
          <el-table-column label="耗时" width="90">
            <template #default="{ row }">{{ Math.round((row.latencyMs || 0) / 1000) }}s</template>
          </el-table-column>
          <el-table-column label="tokens（入/出/总）" width="150">
            <template #default="{ row }">{{ row.promptTokens }} / {{ row.completionTokens }} / {{ row.totalTokens }}</template>
          </el-table-column>
          <el-table-column label="结果" width="80">
            <template #default="{ row }">
              <el-tag size="small" :type="row.effectiveStatus === 'ACCEPTED' ? 'success' : (row.effectiveStatus === 'CONTENT_REJECTED' ? 'warning' : 'danger')">
                {{ row.effectiveStatusLabel || (row.status === 'ok' ? '成功' : '失败') }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="错误" min-width="160" show-overflow-tooltip>
            <template #default="{ row }">{{ row.error || '—' }}</template>
          </el-table-column>
          <el-table-column label="输入 / 输出" width="110" fixed="right">
            <template #default="{ row }">
              <el-button link type="primary" size="small" @click="openCall(row)">看详情</el-button>
            </template>
          </el-table-column>
        </DataTable>
      </el-card>

      <el-drawer v-model="callDrawer" :title="callTitle" size="62%">
        <div v-if="callDetail" class="call-body">
          <el-descriptions :column="3" size="small" border>
            <el-descriptions-item label="环节">{{ nodeLabel(callDetail.node) }}</el-descriptions-item>
            <el-descriptions-item label="模型">{{ callDetail.model }}</el-descriptions-item>
            <el-descriptions-item label="耗时">
              {{ Math.round((callDetail.latencyMs || 0) / 1000) }}s
            </el-descriptions-item>
            <el-descriptions-item label="tokens（入/出/总）">
              {{ callDetail.promptTokens }} / {{ callDetail.completionTokens }} / {{ callDetail.totalTokens }}
            </el-descriptions-item>
            <el-descriptions-item label="时间">{{ fmtTime(callDetail.createTime) }}</el-descriptions-item>
            <el-descriptions-item label="归属">
              run {{ callDetail.runId ?? '—' }} · 书 {{ callDetail.novelId ?? '—' }}
            </el-descriptions-item>
          </el-descriptions>

          <el-alert v-if="callDetail.error && callDetail.error !== '—'" class="call-alert"
                    type="warning" :closable="false" show-icon title="本轮不合规原因 / 调用失败">
            <div class="pre">{{ callDetail.error }}</div>
          </el-alert>

          <el-tabs v-model="callTab" class="call-tabs">
            <el-tab-pane label="发出去的提示词" name="req">
              <div v-for="(m, i) in (callDetail.request || [])" :key="i" class="call-msg">
                <div class="call-msg-role">{{ m.role === 'system' ? '系统提示词' : '用户输入' }}</div>
                <pre class="pre call-pre">{{ m.content }}</pre>
              </div>
              <p v-if="!(callDetail.request || []).length" class="process-idle">
                该次调用没有可解析的 messages，原始请求见「原始 JSON」。
              </p>
            </el-tab-pane>
            <el-tab-pane label="模型返回" name="resp">
              <pre class="pre call-pre">{{ callDetail.content || '（空——模型把内容全写进了思考）' }}</pre>
            </el-tab-pane>
            <el-tab-pane label="思考" name="think">
              <pre class="pre call-pre">{{ callDetail.reasoning || '（无）' }}</pre>
            </el-tab-pane>
            <el-tab-pane label="原始 JSON" name="raw">
              <div class="call-msg-role">请求</div>
              <pre class="pre call-pre">{{ prettyRaw(callDetail.requestRaw) }}</pre>
              <div class="call-msg-role">响应</div>
              <pre class="pre call-pre">{{ prettyRaw(callDetail.responseRaw) }}</pre>
            </el-tab-pane>
          </el-tabs>
        </div>
        <p v-else class="process-idle">读取中…</p>
      </el-drawer>

      <el-row :gutter="12" class="stat-row">
        <el-col :span="6">
          <el-card shadow="never">
            <div class="stat-label">章进度（已写 / 总数）</div>
            <div class="stat-value">{{ detail.progress.chapterWritten }} / {{ detail.progress.chapterTotal }}</div>
            <el-progress :percentage="pct(detail.progress.chapterWritten, detail.progress.chapterTotal)"
                         :stroke-width="8" />
            <div class="stat-sub">其中质检通过 {{ detail.progress.chapterQcPass }} 章</div>
          </el-card>
        </el-col>
        <el-col :span="6">
          <el-card shadow="never">
            <div class="stat-label">拍进度（已核销 / 总数）</div>
            <div class="stat-value">{{ detail.progress.beatVerified }} / {{ detail.progress.beatTotal }}</div>
            <el-progress :percentage="pct(detail.progress.beatVerified, detail.progress.beatTotal)"
                         :stroke-width="8" status="success" />
            <div class="stat-sub">已写入 {{ detail.progress.beatWritten }} · 跳过 {{ detail.progress.beatSkipped }}</div>
          </el-card>
        </el-col>
        <el-col :span="6">
          <el-card shadow="never">
            <div class="stat-label">质检</div>
            <div class="stat-value">
              <el-tag size="small" :type="statusTag(VERDICT, detail.qc.latestVerdict).type">
                {{ statusTag(VERDICT, detail.qc.latestVerdict).label }}
              </el-tag>
              <span class="stat-inline">共 {{ detail.qc.reportCount }} 轮</span>
            </div>
            <div class="stat-sub">预测 {{ detail.qc.wordsPred ?? '—' }} / 实测 {{ detail.qc.wordsActual ?? '—' }} 字</div>
          </el-card>
        </el-col>
        <el-col :span="6">
          <el-card shadow="never">
            <div class="stat-label">参数快照</div>
            <div class="stat-sub">seed {{ detail.seed ?? '—' }} · 字数带 {{ detail.wordsMin ?? '—' }}~{{ detail.wordsMax ?? '—' }}</div>
            <div class="stat-sub">{{ detail.form || '—' }} · 结局 {{ detail.outcome || '—' }} · 节奏 {{ detail.pace ?? '—' }}</div>
            <div class="stat-sub">
              防复刻 <el-tag size="small" :type="detail.antiRepro ? 'warning' : 'info'">{{ detail.antiRepro ? '开' : '关' }}</el-tag>
              字数校验 <el-tag size="small" :type="detail.lenCheck ? 'warning' : 'info'">{{ detail.lenCheck ? '开' : '关' }}</el-tag>
            </div>
          </el-card>

      <el-card v-if="volumes.length" shadow="never" class="block">
        <template #header>
          卷结构（{{ volumes.length }} 卷）
        </template>
        <DataTable :data="volumes" size="small">
          <el-table-column prop="volumeNo" label="卷" width="50" />
          <el-table-column label="章范围" width="100">
            <template #default="{ row }">{{ row.fromChapter }}–{{ row.toChapter }}</template>
          </el-table-column>
          <el-table-column prop="arc" label="卷名" min-width="110" show-overflow-tooltip />
          <el-table-column prop="localEvent" label="本卷事件" min-width="180" show-overflow-tooltip />
          <el-table-column prop="localClosure" label="本卷闭合" min-width="180" show-overflow-tooltip />
          <el-table-column prop="nextHook" label="下一卷钩子" min-width="160" show-overflow-tooltip />
          <el-table-column label="角色" width="80">
            <template #default="{ row }">
              <el-tag size="small" :type="row.globalRole === 'FINAL' ? 'success' : 'info'">
                {{ row.globalRole === 'FINAL' ? '终卷' : '普通' }}
              </el-tag>
            </template>
          </el-table-column>
        </DataTable>
      </el-card>
        </el-col>
      </el-row>

      <el-card v-if="detail.intent || detail.style" shadow="never" class="block">
        <template #header>创作思路 / 文风</template>
        <p v-if="detail.intent" class="pre">{{ detail.intent }}</p>
        <p v-if="detail.style" class="pre">文风：{{ detail.style }}</p>
      </el-card>

      <el-card v-if="storyPlan?.planState?.currentUnit" shadow="never" class="block">
        <template #header>
          故事规划
          <el-tag size="small" type="primary" style="margin-left: 8px">
            {{ statusTag(PLAN_UNIT, storyPlan.planState.currentUnit).label }}
          </el-tag>
        </template>
        <el-descriptions v-if="storyPlan.scope" :column="4" size="small" border>
          <el-descriptions-item label="规模判断">{{ storyPlan.scope.decision || '—' }}</el-descriptions-item>
          <el-descriptions-item label="卷数">{{ storyPlan.scope.volumeCount ?? '—' }}</el-descriptions-item>
          <el-descriptions-item label="章数">{{ storyPlan.scope.chapterCount ?? '—' }}</el-descriptions-item>
          <el-descriptions-item label="章均">{{ storyPlan.scope.chapterAvg ?? '—' }}</el-descriptions-item>
        </el-descriptions>

        <template v-if="storyPlan.storySpine?.mainMystery">
          <h4>故事脊柱</h4>
          <p><b>背景大案：</b>{{ storyPlan.storySpine.mainMystery }}</p>
          <p><b>最终真相：</b>{{ storyPlan.storySpine.finalTruth }}</p>
          <p><b>最终选择：</b>{{ storyPlan.storySpine.finalChoice }}</p>
          <DataTable :data="storyPlan.storySpine.truthLadder || []" size="small">
            <el-table-column prop="volumeNo" label="卷" width="50" />
            <el-table-column label="本卷揭晓" min-width="220">
              <template #default="{ row }">{{ (row.reveals || []).join('；') }}</template>
            </el-table-column>
            <el-table-column label="仍禁止" min-width="220">
              <template #default="{ row }">{{ (row.forbidden || []).join('；') || '—' }}</template>
            </el-table-column>
            <el-table-column label="证据线程" min-width="180">
              <template #default="{ row }">{{ (row.evidenceThreads || []).join('、') || '—' }}</template>
            </el-table-column>
          </DataTable>
        </template>

        <template v-if="(storyPlan.volumes || []).length">
          <h4>卷合同</h4>
          <DataTable :data="storyPlan.volumes" size="small">
            <el-table-column prop="volumeNo" label="卷" width="50" />
            <el-table-column label="章范围" width="86">
              <template #default="{ row }">{{ row.fromChapter }}–{{ row.toChapter }}</template>
            </el-table-column>
            <el-table-column prop="arc" label="卷名" min-width="110" show-overflow-tooltip />
            <el-table-column prop="contract.localQuestion" label="本卷问题" min-width="160" show-overflow-tooltip />
            <el-table-column prop="contract.mainMysteryAdvance" label="大案增量" min-width="180" show-overflow-tooltip />
            <el-table-column prop="localClosure" label="局部闭合" min-width="180" show-overflow-tooltip />
            <el-table-column prop="nextHook" label="下一卷钩子" min-width="160" show-overflow-tooltip />
            <el-table-column label="操作" width="84" fixed="right">
              <template #default="{ row }">
                <el-button link type="warning" size="small" :disabled="!!acting" @click="replanVolume(row.volumeNo)">
                  重规划
                </el-button>
              </template>
            </el-table-column>
          </DataTable>
        </template>

        <template v-if="(storyPlan.characters || []).length">
          <h4>角色功能与弧光</h4>
          <DataTable :data="storyPlan.characters" size="small">
            <el-table-column prop="characterKey" label="Key" width="62" />
            <el-table-column prop="displayNameHint" label="名字提示" width="100" />
            <el-table-column prop="functionCode" label="功能" width="110" />
            <el-table-column prop="spotlightVolume" label="高光卷" width="72" />
            <el-table-column prop="conflictVolume" label="冲突卷" width="72" />
            <el-table-column prop="contract.functionalConflict" label="功能冲突" min-width="220" show-overflow-tooltip />
            <el-table-column prop="contract.highlightChoice" label="高光选择" min-width="180" show-overflow-tooltip />
          </DataTable>
        </template>

        <template v-if="(storyPlan.threads || []).length">
          <h4>故事线程</h4>
          <DataTable :data="storyPlan.threads" size="small">
            <el-table-column prop="threadKey" label="线程" width="150" />
            <el-table-column prop="kind" label="类型" width="100" />
            <el-table-column prop="title" label="标题" min-width="180" />
            <el-table-column prop="openedVolume" label="起始卷" width="72" />
            <el-table-column prop="payoffVolume" label="兑现卷" width="72" />
            <el-table-column prop="status" label="状态" width="90" />
          </DataTable>
        </template>

        <el-collapse v-if="(storyPlan.chapterSlots || []).length" style="margin-top: 14px">
          <el-collapse-item :title="`章功能槽（${storyPlan.chapterSlots.length} 章）`">
            <DataTable :data="storyPlan.chapterSlots" size="small">
              <el-table-column prop="chapterNo" label="章" width="50" />
              <el-table-column prop="volumeNo" label="卷" width="50" />
              <el-table-column prop="planContract.chapterFunction" label="章功能" min-width="170" />
              <el-table-column prop="planContract.localCaseGoal" label="本卷案件目标" min-width="220" show-overflow-tooltip />
              <el-table-column label="必需拍功能" min-width="180">
                <template #default="{ row }">
                  {{ (row.planContract?.requiredBeatRoles || []).join('、') }}
                </template>
              </el-table-column>
            </DataTable>
          </el-collapse-item>
        </el-collapse>
      </el-card>

      <el-card v-if="gateInfo && gateInfo.status !== 'PENDING'" shadow="never" class="block">
        <template #header>
          P6 立项闸
          <el-tag size="small" :type="gateTagType(gateInfo.status)" style="margin-left: 8px">
            {{ gateStatusLabel(gateInfo.status) }}
          </el-tag>
          <span class="stat-inline" style="margin-left: 8px">第 {{ gateInfo.round ?? 1 }} 轮评审</span>
        </template>
        <template v-if="gateCriteriaRows.length">
          <div class="stat-label">六判据审稿（终态由系统裁决）</div>
          <DataTable :data="gateCriteriaRows" size="small">
            <el-table-column prop="label" label="判据" width="110" />
            <el-table-column label="结果" width="80">
              <template #default="{ row }">
                <el-tag size="small" :type="row.pass ? 'success' : 'danger'">
                  {{ row.pass ? '通过' : '有问题' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="级别" width="90">
              <template #default="{ row }">{{ gateSeverityLabel(row.severity) }}</template>
            </el-table-column>
            <el-table-column label="回炉层级" width="120">
              <template #default="{ row }">{{ gateRepairTargetLabel(row.repairTarget) }}</template>
            </el-table-column>
            <el-table-column prop="note" label="系统审稿依据" min-width="220" show-overflow-tooltip />
            <el-table-column label="具体问题" min-width="280" show-overflow-tooltip>
              <template #default="{ row }">{{ (row.issues || []).join('；') || '—' }}</template>
            </el-table-column>
          </DataTable>
        </template>
        <template v-if="(gateInfo.issues || []).length">
          <div class="stat-label">问题清单（{{ gateInfo.issues.length }}）</div>
          <ul class="gate-issues">
            <li v-for="(it, i) in gateInfo.issues" :key="i">{{ it }}</li>
          </ul>
        </template>
        <p v-if="gateInfo.directive" class="pre"><b>减法指令（⑥b 回炉按此执行）：</b>{{ gateInfo.directive }}</p>
        <p v-if="gateInfo.status === 'REWORK'" class="pre" style="color: var(--el-color-warning)">
          闸已打回：点上方「⑥b 回炉重写具体稿」，完成后需重新过闸。
        </p>
        <p v-if="gateInfo.status === 'FAILED'" class="pre" style="color: var(--el-color-danger)">
          闸已熔断（REJECT 或回炉超轮次）——run 转 FAILED，需人工处理。
        </p>
      </el-card>

      <el-card shadow="never" class="block">
        <template #header>
          章进度
          <el-button size="small" link type="primary" style="margin-left: 10px" @click="toggleText">
            {{ withText ? '隐藏正文列' : '附带正文加载' }}
          </el-button>
        </template>
        <DataTable :data="chapters" size="small">
          <el-table-column prop="chapterNo" label="章号" width="60" />
          <el-table-column prop="title" label="标题" min-width="140" show-overflow-tooltip />
          <el-table-column label="状态" width="90">
            <template #default="{ row }">
              <el-tag size="small" :type="statusTag(CHAPTER_STATUS, row.status).type">
                {{ statusTag(CHAPTER_STATUS, row.status).label }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="budget" label="预算" width="70" />
          <el-table-column prop="charCount" label="实测" width="70" />
          <el-table-column label="正文" width="80">
            <template #default="{ row }">
              <el-tag size="small" :type="row.hasText ? 'success' : 'info'">{{ row.hasText ? '有' : '无' }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="comment" label="章注" min-width="140" show-overflow-tooltip />
          <el-table-column v-if="withText" label="正文预览" min-width="200" show-overflow-tooltip>
            <template #default="{ row }">{{ (row.content || '').slice(0, 80) || '—' }}</template>
          </el-table-column>
        </DataTable>
      </el-card>

      <el-card shadow="never" class="block">
        <template #header>拍实例（{{ beats.length }}）</template>
        <DataTable :data="beats" size="small">
          <el-table-column prop="seq" label="拍序" width="60" />
          <el-table-column prop="beatType" label="拍型" width="80" />
          <el-table-column prop="chapterNo" label="章号" width="60" />
          <el-table-column label="状态" width="90">
            <template #default="{ row }">
              <el-tag size="small" :type="statusTag(BEAT_STATUS, row.status).type">
                {{ statusTag(BEAT_STATUS, row.status).label }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="pacing" label="节奏" width="60" />
          <el-table-column prop="filledText" label="填实情节点" min-width="260" show-overflow-tooltip />
        </DataTable>
      </el-card>

      <el-card shadow="never" class="block">
        <template #header>质检报告（{{ reports.length }} 轮，最新在前）</template>
        <el-empty v-if="!reports.length" description="还没有质检记录" :image-size="60" />
        <el-collapse v-else>
          <el-collapse-item v-for="r in reports" :key="r.id" :name="r.id">
            <template #title>
              <el-tag size="small" :type="statusTag(VERDICT, r.verdict).type" style="margin-right: 8px">
                {{ statusTag(VERDICT, r.verdict).label }}
              </el-tag>
              <span>第 {{ r.roundNo }} 轮 · {{ fmtTime(r.createTime) }}</span>
            </template>
            <el-tabs>
              <el-tab-pane label="问题清单">
                <pre class="json">{{ pretty(r.issues) }}</pre>
              </el-tab-pane>
              <el-tab-pane label="脚本硬判">
                <pre class="json">{{ pretty(r.scriptResult) }}</pre>
              </el-tab-pane>
              <el-tab-pane label="LLM 快检">
                <pre class="json">{{ pretty(r.llmResult) }}</pre>
              </el-tab-pane>
              <el-tab-pane label="修复记录">
                <pre class="json">{{ pretty(r.repairLog) }}</pre>
              </el-tab-pane>
            </el-tabs>
          </el-collapse-item>
        </el-collapse>
      </el-card>
    </template>
  </div>
</template>

<script setup>
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import PageHeader from '../../components/PageHeader.vue'
import DataTable from '../../components/DataTable.vue'
import { deriveApi } from '../../deriveApi'
import { usePolling } from '../../composables/usePolling'
import { formatAutoProgress, formatWriteProgress } from './taskProgress'
import { BEAT_STATUS, CHAPTER_STATUS, PLAN_UNIT, RUN_STATUS, VERDICT, fmtTime, statusTag } from './deriveLabels'

/** run 详情：进度聚合卡 + 章/拍表 + 质检报告折叠面板（只读监控核心页）。 */
const route = useRoute()
const router = useRouter()

const loading = ref(false)
const detail = ref(null)
const chapters = ref([])
const beats = ref([])
const reports = ref([])
const volumes = ref([])
const storyPlan = ref(null)
const withText = ref(false)
const serverDraft = ref(null)
const llmTrail = ref([])

const NODE_LABELS = {
  derive_intake_review: '思路预审',
  derive_word_band: 'AI 估档',
  derive_tag_norm: '类别归一化',
  derive_weld: '素材焊接',
  derive_plan: '⑥a 章节计划',
  derive_draft: '⑥b 具体稿',
  derive_gate: 'P6 立项闸',
  derive_write: '⑦ 写正文',
  derive_qc: '⑧ 质检',
  derive_extract: '② 抽取',
  derive_deindex: '③ 无指向化',
  derive_source_words: '④a 扫词',
  derive_label: '④b 打标',
  derive_domain: '④c 域格',
  derive_style: '④d 文风'
}
function nodeLabel(node) {
  return NODE_LABELS[node] || node
}

/** 长任务实时活动（⑥b/闸/⑦/⑧ 共用 /activity；刷新/换页也能看到；顺带锁按钮防重复提交）。 */
async function pollServerDraft() {
  try {
    const p = await deriveApi.get(`/api/derive/runs/${route.params.id}/activity`)
    serverDraft.value = p?.active ? p : null
  } catch {
    // 轮询失败静默
  }
}

async function pollTrail() {
  try {
    llmTrail.value = await deriveApi.get(`/api/derive/runs/${route.params.id}/llm-trail`)
  } catch {
    // 轮询失败静默
  }
}

/** 单次调用全文抽屉：发出去的提示词 + 模型返回 + 思考 + 本轮不合规原因。 */
const callDrawer = ref(false)
const callDetail = ref(null)
const callTab = ref('req')
const callTitle = computed(() => callDetail.value
  ? `${nodeLabel(callDetail.value.node)} · #${callDetail.value.id}`
  : 'AI 调用详情')

async function openCall(row) {
  callDetail.value = null
  callTab.value = 'req'
  callDrawer.value = true
  try {
    callDetail.value = await deriveApi.get(`/api/derive/runs/llm-calls/${row.id}`)
  } catch (e) {
    ElMessage.error(e.message || '读取调用详情失败')
    callDrawer.value = false
  }
}

function prettyRaw(raw) {
  if (!raw) return '（无）'
  try {
    return JSON.stringify(JSON.parse(raw), null, 2)
  } catch {
    return raw
  }
}

const serverDraftText = computed(() => {
  const p = serverDraft.value
  if (!p) return ''
  const s = Math.round((p.elapsedMs || 0) / 1000)
  const idle = Math.round((p.idleMs || 0) / 1000)
  const stall = idle > 90 ? ` · 已 ${idle}s 无输出（可能卡流）` : ''
  const nonStream = !p.frames && !p.chars ? ' · 该步非流式，完成前无中间输出' : ''
  return `进行中：${p.phase} · 已耗时 ${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')}`
    + ` · 输出约 ${(p.chars / 10000).toFixed(1)} 万字${stall}${nonStream}`
})

function pct(done, total) {
  if (!total) return 0
  return Math.min(100, Math.round((done / total) * 100))
}

function pretty(node) {
  if (node == null) return '—'
  return JSON.stringify(node, null, 2)
}

async function loadAll() {
  loading.value = true
  const id = route.params.id
  try {
    const [d, c, b, q, v, sp] = await Promise.all([
      deriveApi.get(`/api/derive/runs/${id}`),
      deriveApi.get(`/api/derive/runs/${id}/chapters${withText.value ? '?includeText=true' : ''}`),
      deriveApi.get(`/api/derive/runs/${id}/beats`),
      deriveApi.get(`/api/derive/runs/${id}/qc-reports`),
      deriveApi.get(`/api/derive/runs/${id}/volumes`),
      deriveApi.get(`/api/derive/runs/${id}/story-plan`)
    ])
    detail.value = d
    chapters.value = c
    beats.value = b
    reports.value = q
    volumes.value = v
    storyPlan.value = sp
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    loading.value = false
  }
}

async function toggleText() {
  withText.value = !withText.value
  await loadAll()
}

/* ── 状态机操作条（纯前端走完全流程的驾驶位） ────────────────────── */
const acting = ref('')
const draftLoopText = ref('')
const writePolling = usePolling(
  () => deriveApi.get(`/api/derive/runs/${route.params.id}/write-progress`),
  { intervalMs: 2500 }
)
const serverWrite = writePolling.data
const writePollError = writePolling.error
const writeStarting = ref(false)
let writeDoneHandled = false
let draftPollTimer = null

/** ⑦ 服务端连跑（2026-10-18 根修）：点一次服务器写到完，刷新/换页/关电脑都不影响。 */
const writeBusy = computed(() => writeStarting.value || !!serverWrite.value?.running)

const serverWriteText = computed(() => formatWriteProgress(serverWrite.value, writePollError.value))
const pollWriteProgress = writePolling.poll
const startWritePoll = writePolling.start
const stopWritePoll = writePolling.stop

watch(serverWrite, (w, prev) => {
  if (!w) return
  if (w.allDone && !writeDoneHandled) {
    writeDoneHandled = true
    ElMessage.success('服务端连跑完成：正文全部写完，下一步运行质检 ⑧')
    loadAll()
    return
  }
  if (w.lastError && (!prev || prev.lastError !== w.lastError)) {
    ElMessage.error(`连跑停止：${w.lastError}`)
    loadAll()
    return
  }
  if (!w.running && prev?.running && w.stopRequested) {
    ElMessage.success(`连跑已停止：共写 ${w.chaptersDone} 章，断点保留`)
    loadAll()
  }
})

watch(writePollError, (err, prev) => {
  const message = err?.message || err
  const previous = prev?.message || prev
  if (message && message !== previous) {
    ElMessage.error(`连跑进度轮询失败：${message}`)
  }
})

/* ── 全流程无人值守（服务端主控：⑥a→⑥b→闸→⑦→⑧ 到终态） ─────────── */
const autoPolling = usePolling(
  () => deriveApi.get(`/api/derive/runs/${route.params.id}/auto-progress`),
  { intervalMs: 2500 }
)
const serverAuto = autoPolling.data
const autoPollError = autoPolling.error
const autoRunning = computed(() => !!serverAuto.value?.running)

const serverAutoText = computed(() => formatAutoProgress(serverAuto.value, autoPollError.value))
const pollAuto = autoPolling.poll
const startAutoPoll = autoPolling.start
const stopAutoPoll = autoPolling.stop

watch(serverAuto, (a, prev) => {
  if (!a) return
  if (a.done && (!prev || !prev.done)) {
    ElMessage.success(`全流程完成：${a.stage}`)
    loadAll()
    return
  }
  if (a.lastError && (!prev || prev.lastError !== a.lastError)) {
    ElMessage.error(`全流程停止：${a.lastError}`)
    loadAll()
    return
  }
  if (!a.running && prev?.running && a.stopRequested) {
    ElMessage.success(`全流程已停止：${a.stage || '断点保留，再点按钮续跑'}`)
    loadAll()
  }
})

watch(autoPollError, (err, prev) => {
  const message = err?.message || err
  const previous = prev?.message || prev
  if (message && message !== previous) {
    ElMessage.error(`全流程进度轮询失败：${message}`)
  }
})

async function actAuto(direct) {
  const conf = direct
    ? '全程直通·初稿：跳过 P6 立项闸与 ⑧ 质检的 LLM 审稿；确定性审计、计划、预算和脚本问题降级为报告，不阻断正文产出。'
      + '适合先批量出初稿，人工终稿前再处理报告里的问题。启动？'
    : '全流程无人值守：从当前状态自动推进 ⑥a→⑥b→立项闸→⑦写正文→⑧质检，直到通过或熔断；'
      + '中途打回按各步默认机制自动回炉重试。服务器上跑，关页面不断。启动？'
  try {
    await ElMessageBox.confirm(conf, direct ? '全程直通·初稿' : '全流程无人值守',
      { type: 'warning', confirmButtonText: '启动' })
  } catch {
    return
  }
  try {
    await deriveApi.post(`/api/derive/runs/${route.params.id}/auto?direct=${direct ? 'true' : 'false'}`)
    ElMessage.success(`${direct ? '全程直通（初稿）' : '全流程'}已启动——进度看操作条横幅与「运行过程」，页面可关`)
    await pollAuto()
    startAutoPoll()
  } catch (e) {
    ElMessage.error(e.message)
    await pollAuto()
  }
}

/** 试跑一步：只推进当前状态机的一个单元，便于低成本验证当前配置。 */
async function actTrial() {
  const id = route.params.id
  let action = ''
  if (detail.value.status === 'PLANNING' && !detail.value.novelId) {
    action = '⑥a 定篇幅规模'
  } else if (detail.value.status === 'PLANNING') {
    action = '⑥b 一个具体稿单元'
  } else if (detail.value.status === 'REWORK') {
    action = '⑥b 回炉一个单元'
  } else if (detail.value.status === 'DRAFTING') {
    action = gatePassed.value ? '⑦ 写一章正文' : 'P6 立项闸'
  } else if (detail.value.status === 'QC') {
    action = '⑧ 一轮质检'
  } else {
    ElMessage.warning('当前状态没有可试跑的单元')
    return
  }
  try {
    await ElMessageBox.confirm(`只执行「${action}」，完成后停下；不会自动进入下一步。继续？`,
      '试跑一步', { type: 'info', confirmButtonText: '试跑' })
  } catch {
    return
  }
  acting.value = 'trial'
  try {
    if (detail.value.status === 'PLANNING' && !detail.value.novelId) {
      const d = await deriveApi.post(`/api/derive/runs/${id}/plan`)
      ElMessage.success(d.unit === 'SCALE' ? `试跑完成：定规模 ${d.chapterCount} 章` : '⑥a 单元完成')
    } else if (detail.value.status === 'PLANNING' || detail.value.status === 'REWORK') {
      const d = await deriveApi.post(`/api/derive/runs/${id}/draft`)
      ElMessage.success(`⑥b 单元完成：${d.unit}${d.title ? ` · ${d.title}` : ''}`)
    } else if (detail.value.status === 'DRAFTING' && !gatePassed.value) {
      const g = await deriveApi.post(`/api/derive/runs/${id}/gate`)
      ElMessage.success(`立项闸试跑完成：${g.verdict}`)
    } else if (detail.value.status === 'DRAFTING') {
      const w = await deriveApi.post(`/api/derive/runs/${id}/write`)
      ElMessage.success(w.chapterNo < 0 ? '正文已全部完成' : `试跑完成：第 ${w.chapterNo} 章`)
    } else if (detail.value.status === 'QC') {
      const q = await deriveApi.post(`/api/derive/runs/${id}/qc`)
      ElMessage.success(`质检试跑完成：${q.verdict}`)
    }
    await loadAll()
  } catch (e) {
    ElMessage.error(`试跑失败：${e.message}`)
    await loadAll()
  } finally {
    acting.value = ''
  }
}

/** 从断点续跑全流程：与全流程无人值守同一入口，但使用更明确的续跑提示。 */
async function resumeTask() {
  try {
    await ElMessageBox.confirm(
      '从当前断点继续执行全流程，已完成单元会跳过；不会清空已有规划或正文。继续？',
      '续跑任务', { type: 'warning', confirmButtonText: '续跑' }
    )
  } catch {
    return
  }
  try {
    await deriveApi.post(`/api/derive/runs/${route.params.id}/auto?direct=false`)
    ElMessage.success('已从断点续跑，进度看运行过程')
    await pollAuto()
    startAutoPoll()
  } catch (e) {
    ElMessage.error(e.message)
    await pollAuto()
  }
}

async function stopAuto() {
  try {
    await deriveApi.post(`/api/derive/runs/${route.params.id}/auto/stop`)
    ElMessage.info('已请求停止：当前步完成后收尾，断点保留')
    await pollAuto()
  } catch (e) {
    ElMessage.error(e.message)
  }
}

/** 无条件强停：打断服务端线程与在飞 LLM 调用，不等当前步返回。 */
async function killAuto() {
  try {
    await deriveApi.post(`/api/derive/runs/${route.params.id}/auto/kill`)
    ElMessage.warning('已强停：服务端线程已打断（不等返回）——断点保留，再点可续跑')
    serverAuto.value = null
    setTimeout(pollAuto, 1500)
    await loadAll()
  } catch (e) {
    ElMessage.error(e.message)
  }
}

/** 清空⑥及之后的规划/正文；⑤采样、焊接和 run 参数保留。 */
async function clearPlanning() {
  try {
    await ElMessageBox.confirm(
      '将删除本书的章节、拍实例、具体稿、正文、质检报告和成品书骨架，run 回到 PLANNING；'
      + '⑤采样、焊接、参数和 LLM 调用账本保留。已有完整正文的 run 会被后端拒绝。继续？',
      '清空⑥+数据',
      { type: 'warning', confirmButtonText: '清空' }
    )
  } catch {
    return
  }
  try {
    acting.value = 'clear'
    const r = await deriveApi.post(`/api/derive/runs/${route.params.id}/clear-planning`)
    ElMessage.success(r.message || '已清空规划数据')
    await loadAll()
  } catch (e) {
    ElMessage.error(e.message || '清空失败')
  } finally {
    acting.value = ''
  }
}

function startDraftPoll() {
  stopDraftPoll()
  draftPollTimer = setInterval(async () => {
    try {
      const p = await deriveApi.get(`/api/derive/runs/${route.params.id}/draft-progress`)
      if (!p?.active) return
      const s = Math.round((p.elapsedMs || 0) / 1000)
      const idle = Math.round((p.idleMs || 0) / 1000)
      const stall = idle > 90 ? ` · 已 ${idle}s 无输出（可能卡流）` : ''
      draftLoopText.value =
        `服务端进行中：${p.phase} · 已耗时 ${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')}` +
        ` · 输出约 ${(p.chars / 10000).toFixed(1)} 万字${stall}`
    } catch {
      // 进度接口失败不打断主循环
    }
  }, 2000)
}

function stopDraftPoll() {
  if (draftPollTimer) {
    clearInterval(draftPollTimer)
    draftPollTimer = null
  }
}

const guide = computed(() => {
  const d = detail.value
  if (!d) return ''
  switch (d.status) {
    case 'SAMPLING': return '采样进行中——稍后刷新'
    case 'PLANNING':
      return d.novelId ? '已出章节计划，下一步：生成具体稿（⑥b）' : '下一步：出章节计划（⑥a，建成品书+章行+拍实例）'
    case 'DRAFTING':
      return gatePassed.value
        ? '立项闸已过——点「连续写完（服务端）」由服务器自动逐章写到完，关页面不断'
        : '具体稿就绪，先过 P6 立项闸（六判据评审，GO 后才放行 ⑦ 写正文）'
    case 'QC': return '全部正文已写完，下一步：运行质检（⑧，脚本+AI 快检，可能 10 分钟以上）'
    case 'REWORK':
      return gateInfo.value?.status === 'REWORK'
        ? '立项闸打回：按下方减法指令回炉 ⑥b 整本重做具体稿，完成后重新过闸'
        : '质检打回：先回炉重写具体稿（⑥b），再逐章重写正文（⑦），最后重新质检（⑧）'
    case 'PASSED': return '全链完成，质检通过 ✅'
    case 'FAILED': return '已熔断（qc 三轮未过 / 立项闸 REJECT）——后端暂无重开端点，需人工处理'
    default: return ''
  }
})

/* ── P6 立项闸（run.step_status.steps.GATE，L2 层） ────────────────── */
const gateInfo = computed(() => {
  const raw = detail.value?.stepStatus
  if (!raw) return null
  try {
    const obj = typeof raw === 'string' ? JSON.parse(raw) : raw
    const g = obj?.steps?.GATE
    return g && g.status ? g : null
  } catch {
    return null
  }
})
const gatePassed = computed(() => gateInfo.value?.status === 'PASS')
const GATE_CRITERION_LABELS = {
  single_rule: '规则单轨',
  budget: '体量预算',
  motive_chain: '动机链',
  closure: '闭合',
  function_lock: '职能锁定',
  motif_dedup: '母题查重'
}
const gateCriteriaRows = computed(() => {
  const criteria = gateInfo.value?.criteria
  if (!criteria || typeof criteria !== 'object' || Array.isArray(criteria)) return []
  return Object.entries(GATE_CRITERION_LABELS)
    .filter(([key]) => criteria[key])
    .map(([key, label]) => ({ key, label, ...criteria[key] }))
})

function gateTagType(status) {
  return { PASS: 'success', REWORK: 'warning', FAILED: 'danger', PENDING: 'info' }[status] || 'info'
}
function gateStatusLabel(status) {
  return {
    PASS: '放行 GO',
    REWORK: '打回 REWORK',
    FAILED: '三轮未过',
    PENDING: '待评审'
  }[status] || status
}
function gateSeverityLabel(severity) {
  return { HARD: '硬矛盾', FIXABLE: '可修复', ADVISORY: '建议项' }[severity] || severity || '—'
}
function gateRepairTargetLabel(target) {
  if (!target || target === 'NONE') return '无需回炉'
  if (target.startsWith('PLAN_VOLUME:')) return `第 ${target.split(':')[1]} 卷`
  return { DRAFT: '⑥b 具体稿', SPINE: '故事脊柱', SCALE: '规模/卷数' }[target] || target
}

async function actGate() {
  try {
    await ElMessageBox.confirm(
      '立项闸 = LLM 对全书章纲/拍清单做六判据审稿（规则单轨/体量/动机链/闭合/职能/母题），再由系统按严重度裁决，一次数分钟。无硬伤放行 ⑦；可修复问题前两轮打回对应规划层；三轮仍未过才熔断。继续？',
      'P6 立项闸', { type: 'warning', confirmButtonText: '评审' }
    )
  } catch {
    return
  }
  acting.value = 'gate'
  try {
    const g = await deriveApi.post(`/api/derive/runs/${route.params.id}/gate`)
    await loadAll()
    if (g.verdict === 'GO') ElMessage.success(`立项闸 GO（第 ${g.round} 轮），⑦ 已放行`)
    else if (g.verdict === 'REWORK') ElMessage.warning(`立项闸 REWORK（${g.issues?.length ?? 0} 个问题）——按减法指令回炉 ⑥b`)
    else ElMessage.error('立项闸三轮仍未过——run 已熔断 FAILED')
  } catch (e) {
    ElMessage.error(e.message)
    await loadAll()
  } finally {
    acting.value = ''
  }
}

async function actPlan() {
  if (acting.value) return
  acting.value = 'plan'
  draftLoopText.value = '⑥a 开始：先让 AI 判篇幅…'
  let guard = 0
  try {
    // ⑥a 幂等单元制：SCALE 定规模 → 逐窗分章（每窗 ≤10 章），循环至 allDone
    while (guard < 20) {
      guard += 1
      const d = await deriveApi.post(`/api/derive/runs/${route.params.id}/plan`)
      if (d.allDone) {
        draftLoopText.value = ''
        ElMessage.success(`章节计划完成：${d.chapterCount} 章 / ${d.beatCount} 拍，预算合计 ${d.totalBudget} 字`)
        await loadAll()
        return
      }
      if (d.unit === 'NEED_SCOPE') {
        const suggested = storyPlan.value?.scope?.volumeCount || 1
        const { value } = await ElMessageBox.prompt(
          '当前创作框架无法安全判断单卷还是多卷。请输入最终卷数；输入大于 1 即允许系统扩卷。',
          '确认故事规模',
          {
            inputValue: String(suggested),
            inputPattern: /^[1-9]\d*$/,
            inputErrorMessage: '请输入大于 0 的整数',
            confirmButtonText: '确认并继续'
          }
        )
        const volumeCount = Number(value)
        await deriveApi.post(`/api/derive/runs/${route.params.id}/plan/confirm-scope`, {
          volumeCount,
          allowExpansion: volumeCount > 1
        })
        draftLoopText.value = `已确认 ${volumeCount} 卷——继续生成故事脊柱…`
        await loadAll()
        continue
      }
      if (d.unit === 'SCALE') {
        draftLoopText.value = `AI 定篇幅：${d.chapterCount} 章（分 ${d.windowTotal} 窗，每窗 ≤10 章）——开始逐窗分章…`
      } else if (['SPINE', 'VOLUMES', 'CHARACTERS', 'SLOT_ROUTE', 'VALIDATE'].includes(d.unit)) {
        draftLoopText.value = `⑥a 规划单元 ${d.unit} 完成`
          + (d.volumeNo ? `（第 ${d.volumeNo} 卷，路由 ${d.routedBeats} 拍）` : '')
          + '——继续下一单元…'
      } else {
        draftLoopText.value = `⑥a 分章 第 ${d.windowIndex}/${d.windowTotal} 窗完成`
          + `（已排 ${d.plannedChapters}/${d.chapterCount} 章 · 用拍 ${d.usedBeats}）—— 继续下一窗…`
      }
      await loadAll()
    }
    draftLoopText.value = ''
    ElMessage.warning('已达单次 20 单元保护上限——再点一次会从断点续跑（幂等）')
  } catch (e) {
    draftLoopText.value = ''
    ElMessage.error(`⑥a 中断：${e.message}。已完成的窗已落库，再点一次从断点续跑。`)
    await loadAll()
  } finally {
    acting.value = ''
  }
}

async function replanVolume(volumeNo) {
  try {
    await ElMessageBox.confirm(
      `只重做第 ${volumeNo} 卷的章功能槽和拍路由；已有正文卷会被后端拒绝。继续？`,
      '单卷重规划',
      { type: 'warning', confirmButtonText: '重规划' }
    )
    acting.value = 'plan'
    await deriveApi.post(`/api/derive/runs/${route.params.id}/volumes/${volumeNo}/replan`)
    ElMessage.success(`第 ${volumeNo} 卷拍路由已重排`)
    await loadAll()
  } catch (e) {
    if (e !== 'cancel' && e !== 'close') ElMessage.error(e.message || '单卷重规划失败')
  } finally {
    acting.value = ''
  }
}

async function actDraft(isRework) {
  const conf = isRework
    ? '回炉将清空现有正文（先逐章归档 draft_history）并整本重做具体稿（清稿后从全局头起逐窗重跑），完成后需重新过闸再写正文。继续？'
    : '生成具体稿：全局头 + 逐窗章纲/拍填实（每窗约 3~10 分钟，前端自动循环到全部完成）。继续？'
  try {
    await ElMessageBox.confirm(conf, '确认执行', { type: 'warning', confirmButtonText: '执行' })
  } catch {
    return
  }
  acting.value = 'draft'
  draftLoopText.value = '开始…'
  startDraftPoll()
  let guard = 0
  try {
    // ⑥b 幂等单元制：每次 POST 只推进一个单元（RESET/HEAD/BATCH），循环至 allDone
    while (guard < 40) {
      guard += 1
      const d = await deriveApi.post(`/api/derive/runs/${route.params.id}/draft`)
      if (d.allDone) {
        draftLoopText.value = ''
        ElMessage.success(`具体稿全部完成（《${d.title || ''}》共 ${d.chapterCount} 章），已进入写作阶段`)
        await loadAll()
        return
      }
      if (d.unit === 'RESET') {
        draftLoopText.value = '已清稿（回炉），从全局头重做…'
      } else if (d.unit === 'HEAD') {
        draftLoopText.value = `全局头 ${d.batchIndex}/3 段完成`
          + (d.title ? `：《${d.title}》` : '')
          + (d.protagonist ? ` 主角=${d.protagonist}` : '')
          + ' —— 继续…'
      } else {
        draftLoopText.value = `窗 ${d.batchIndex}/${d.batchTotal} 完成（填实 ${d.filledCount} · 跳过 ${d.skippedCount}）—— 继续下一窗…`
      }
      await loadAll()
    }
    draftLoopText.value = ''
    ElMessage.warning('已达单次 40 单元保护上限——再点一次会从断点续跑（幂等）')
  } catch (e) {
    draftLoopText.value = ''
    ElMessage.error(`⑥b 中断：${e.message}。进度已按单元落库，再点一次从断点续跑，不会重来。`)
    await loadAll()
  } finally {
    stopDraftPoll()
    acting.value = ''
  }
}

function writeFeedback(w) {
  if (!w.gateCleared && w.lastIssue) {
    ElMessage.warning(`第 ${w.chapterNo} 章落库但输出校验未全过（${w.lastIssue}）——交由质检判定`)
  }
}

async function actWriteOne() {
  acting.value = 'write'
  try {
    const w = await deriveApi.post(`/api/derive/runs/${route.params.id}/write`)
    writeFeedback(w)
    if (w.chapterNo < 0 || w.allDone) {
      ElMessage.success('全部章节已写完，run 已转质检')
    } else {
      ElMessage.success(`第 ${w.chapterNo} 章 ${w.charCount} 字（预算 ${w.budget}），剩 ${w.remaining} 章`)
    }
    await loadAll()
  } catch (e) {
    ElMessage.error(e.message)
    await loadAll()
  } finally {
    acting.value = ''
  }
}

async function actWriteAll() {
  try {
    await ElMessageBox.confirm(
      '服务端连续写完：点一次后由服务器自动逐章写到全部完成——关页面、关电脑都不影响。每章约 1~10 分钟。继续？',
      '连续写完（服务端）', { type: 'warning', confirmButtonText: '启动' }
    )
  } catch {
    return
  }
  writeStarting.value = true
  try {
    await deriveApi.post(`/api/derive/runs/${route.params.id}/write-loop`)
    writeDoneHandled = false
    ElMessage.success('服务端连跑已启动——进度看操作条横幅与「运行过程」，页面可关')
    await pollWriteProgress()
    startWritePoll()
  } catch (e) {
    ElMessage.error(e.message)
    await pollWriteProgress()
  } finally {
    writeStarting.value = false
  }
}

async function stopWriteLoop() {
  try {
    await deriveApi.post(`/api/derive/runs/${route.params.id}/write-loop/stop`)
    ElMessage.info('已请求停止：当前章写完后不再继续')
    await pollWriteProgress()
  } catch (e) {
    ElMessage.error(e.message)
  }
}

async function actQc() {
  try {
    await ElMessageBox.confirm(
      '质检 = 脚本 7 项硬判 + AI 快检 5 项；中长篇可能 10 分钟以上。不通过将进入回炉或熔断。继续？',
      '运行质检', { type: 'warning', confirmButtonText: '运行' }
    )
  } catch {
    return
  }
  acting.value = 'qc'
  try {
    await deriveApi.post(`/api/derive/runs/${route.params.id}/qc`)
    await loadAll()
    const v = detail.value?.qc?.latestVerdict
    if (v === 'PASS') ElMessage.success('质检通过（PASS）🎉')
    else if (v === 'REWORK') ElMessage.warning('质检打回（REWORK）——按提示回炉 ⑥b')
    else if (v === 'FAIL' || v === 'FAILED') ElMessage.error('质检熔断（FAIL）——已达轮次上限')
    else ElMessage.success('质检完成')
  } catch (e) {
    ElMessage.error(e.message)
    await loadAll()
  } finally {
    acting.value = ''
  }
}

let serverDraftTimer = null
let trailTimer = null
onMounted(() => {
  loadAll()
  pollServerDraft()
  pollTrail()
  pollWriteProgress()
  startWritePoll()
  pollAuto()
  startAutoPoll()
  serverDraftTimer = setInterval(pollServerDraft, 3000)
  trailTimer = setInterval(pollTrail, 10000)
})
onUnmounted(() => {
  if (serverDraftTimer) clearInterval(serverDraftTimer)
  if (trailTimer) clearInterval(trailTimer)
  stopWritePoll()
  stopDraftPoll()
  stopAutoPoll()
})
</script>

<style scoped>
.stat-row {
  margin-bottom: var(--space-3);
}
.stat-label {
  font-size: var(--text-xs);
  color: var(--muted);
  margin-bottom: 4px;
}
.stat-value {
  font-size: var(--text-lg);
  font-weight: 600;
  margin-bottom: 6px;
  display: flex;
  align-items: center;
  gap: 8px;
}
.stat-inline {
  font-size: var(--text-xs);
  font-weight: 400;
  color: var(--muted);
}
.stat-sub {
  margin-top: 4px;
  font-size: var(--text-xs);
  color: var(--muted);
}
.block {
  margin-bottom: var(--space-3);
}
.action-bar__row {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 10px;
}
.action-bar__spacer {
  flex: 1;
}
.guide {
  font-size: var(--text-xs);
  color: var(--muted);
}
.loop-text {
  margin: 8px 0 0;
  font-size: var(--text-xs);
  color: var(--fg);
}
.loop-text--live {
  color: var(--el-color-primary);
}
.process-live {
  font-size: var(--text-xs);
  color: var(--el-color-primary);
  line-height: var(--leading-body);
}
.process-idle {
  font-size: var(--text-xs);
  color: var(--muted);
}
.pre {
  margin: 0 0 8px;
  white-space: pre-wrap;
  font-size: var(--text-xs);
  line-height: var(--leading-body);
}
.gate-issues {
  margin: 0 0 8px;
  padding-left: 18px;
  font-size: var(--text-xs);
  line-height: var(--leading-body);
}
.gate-issues li {
  margin-bottom: 4px;
}
.json {
  margin: 0;
  max-height: 320px;
  overflow: auto;
  font-size: 12px;
  line-height: 1.5;
  white-space: pre-wrap;
  word-break: break-word;
}
.call-alert {
  margin: 12px 0 4px;
}
.call-tabs {
  margin-top: 8px;
}
.call-msg {
  margin-bottom: 12px;
}
.call-msg-role {
  margin-bottom: 4px;
  font-size: var(--text-xs);
  font-weight: 500;
  color: var(--el-color-primary);
}
.call-pre {
  max-height: 60vh;
  overflow: auto;
  padding: 8px 10px;
  border-radius: 6px;
  background: var(--el-fill-color-lighter);
  word-break: break-word;
}
</style>
