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
      <!-- 分组一：素材设定（写书前准备的世界观与人物素材） -->
      <el-tab-pane label="素材设定">
        <el-tabs>
      <!-- 素材卡 -->
      <el-tab-pane :label="`素材卡（${cards.length}）`">
        <div style="display: flex; gap: 12px; align-items: center; margin-bottom: 8px">
          <span style="font-size: 12px; color: #999">
            向量索引（RAG 语义检索）：已建 <b>{{ embIndexed }}</b> 条{{ embEnabled ? '' : '（开关已关）' }}，生成时自动补嵌缺失项
          </span>
          <el-button size="small" :loading="embBackfilling" @click="backfillEmbeddings">手动回填</el-button>
        </div>
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

        </el-tabs>
      </el-tab-pane>

      <!-- 分组二：记忆台账（生成过程中系统自动维护的三本账） -->
      <el-tab-pane label="记忆台账">
        <el-tabs>
      <!-- 伏笔 -->
      <el-tab-pane :label="`伏笔账本（${foreshadows.length}）`">
        <el-alert v-if="health && (health.proposedCount || health.plantOverdue?.length || health.recoverOverdue?.length)"
                  type="warning" :closable="false" style="margin-bottom: 8px"
                  :title="`账本健康度（截至第 ${health.currentChapter} 章）：待采纳 ${health.proposedCount} 条` +
                    (health.oldestProposed ? `（最老 ${health.oldestProposed} 已停 ${health.oldestProposedAge} 章）` : '') +
                    (health.plantOverdue?.length ? `；埋设逾期：${health.plantOverdue.join('、')}` : '') +
                    (health.recoverOverdue?.length ? `；回收逾期：${health.recoverOverdue.join('、')}` : '') +
                    `；已归档 ${health.archivedCount ?? 0} 条；事实账 ${health.digestCount} 条（至第 ${health.digestLatestChapter} 章）、世界状态 ${health.worldStateCount} 份（至第 ${health.worldStateLatestChapter} 章）`" />
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

        </el-tabs>
      </el-tab-pane>

      <!-- 分组三：平台配置（与具体作品无关的全局设置） -->
      <el-tab-pane label="平台配置">
        <el-tabs>
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

      <!-- 提示词注册表 -->
      <el-tab-pane :label="`提示词（${prompts.length}）`">
        <div style="display: flex; gap: 12px; align-items: center; margin-bottom: 8px">
          <span style="color: #999; font-size: 12px">
            所有 LLM 节点提示词与拼装段全量落库：运行时库值优先、代码为回退。%s/%d 为 format 占位（保存时校验序列），{key} 为拼装段占位（代码填参）；停用行即回退代码版。
          </span>
          <el-input v-model="promptFilter" placeholder="按节点/标题筛选" size="small" clearable style="width: 220px" />
          <el-button type="primary" size="small" @click="openPromptCreate">新建提示词</el-button>
        </div>
        <el-table :data="filteredPrompts" border size="small" style="max-width: 1180px" @row-click="(r) => viewPrompt(r.id)">
          <el-table-column prop="node" label="节点" width="150" />
          <el-table-column prop="phase" label="阶段" width="110" />
          <el-table-column prop="title" label="用途" min-width="240" show-overflow-tooltip />
          <el-table-column label="形态" width="90">
            <template #default="{ row }">
              <el-tag :type="row.exact ? 'success' : 'info'" size="small">{{ row.exact ? 'format' : '{key}段' }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="来源" width="70">
            <template #default="{ row }">
              <el-tag v-if="row.custom" type="danger" size="small">已改</el-tag>
              <span v-else style="color: #999; font-size: 12px">代码</span>
            </template>
          </el-table-column>
          <el-table-column label="启用" width="70">
            <template #default="{ row }">
              <span @click.stop>
                <el-switch :model-value="row.enabled" size="small" @change="(v) => togglePromptEnabled(row, v)" />
              </span>
            </template>
          </el-table-column>
          <el-table-column prop="version" label="版" width="50" />
          <el-table-column label="字数" width="70">
            <template #default="{ row }">{{ row.contentLength }}</template>
          </el-table-column>
          <el-table-column label="更新时间" width="150">
            <template #default="{ row }">{{ fmtTime(row.updateTime) }}</template>
          </el-table-column>
          <el-table-column v-if="showPromptDelete" label="删" width="60">
            <template #default="{ row }">
              <el-button v-if="row.custom" size="small" type="danger" link
                         @click.stop="deletePrompt(row)">删</el-button>
            </template>
          </el-table-column>
        </el-table>

        <el-drawer v-model="promptOpen" :title="promptDetail ? promptDetail.node + ' · ' + promptDetail.phase : '提示词'" size="55%">
          <template v-if="promptDetail">
            <div style="display: flex; gap: 10px; align-items: center; margin-bottom: 8px; flex-wrap: wrap">
              <el-tag size="small" :type="promptDetail.exact ? 'success' : 'info'">
                {{ promptDetail.exact ? 'format 模板' : '{key} 拼接段' }}
              </el-tag>
              <el-tag v-if="promptDetail.custom" type="danger" size="small">人工已改</el-tag>
              <el-tag v-if="!promptDetail.enabled" type="warning" size="small">已停用（走代码版）</el-tag>
              <span style="color: #999; font-size: 12px">v{{ promptDetail.version }} · {{ promptDetail.title }}</span>
              <el-button size="small" plain @click="copyPrompt">复制全文</el-button>
              <el-button v-if="!promptEditing" size="small" type="primary" plain @click="promptContent = promptDetail.content; promptEditing = true">编辑</el-button>
              <el-button v-else size="small" type="primary" :loading="promptSaving" @click="savePrompt">保存</el-button>
              <el-button v-if="promptEditing" size="small" @click="promptEditing = false; loadPrompt()">取消</el-button>
              <el-button v-if="!promptDetail.custom" size="small" type="warning" plain @click="resetPrompt">重置回代码版</el-button>
            </div>
            <div style="color: #999; font-size: 12px; margin-bottom: 8px" v-if="promptEditing">
              可直接改文案：format 模板的 %s/%d 占位符数量与顺序必须保持不变（保存时校验）；{key} 拼接段的占位由代码填参，改文案即可。保存后 30 秒内对新生效，格式化失败会自动回退代码模板。
            </div>
            <el-input v-if="promptEditing" v-model="promptContent" type="textarea" :rows="24" />
            <pre v-else style="white-space: pre-wrap; background: #f7f8fa; padding: 12px; border-radius: 6px; font-size: 12px; line-height: 1.7">{{ promptDetail.content }}</pre>
          </template>
        </el-drawer>

        <!-- 新建提示词 -->
        <el-dialog v-model="promptCreateOpen" title="新建提示词" width="640px" append-to-body>
          <div style="display: flex; gap: 10px; margin-bottom: 10px">
            <el-input v-model="promptCreateForm.node" placeholder="节点（如 scene_draft）" />
            <el-input v-model="promptCreateForm.phase" placeholder="阶段（≤32 字符，如 my_rule）" />
          </div>
          <el-input v-model="promptCreateForm.title" placeholder="用途说明（可选）" style="margin-bottom: 10px" />
          <el-input v-model="promptCreateForm.content" type="textarea" :rows="10"
                    placeholder="提示词内容。{key} 为运行时参数占位（由代码填充）；%s/%d 由 String.format 填充。" />
          <div style="font-size: 12px; color: #999; margin-top: 6px">
            自定义行永久保留（目录同步不覆盖）；删除仅限自定义行。内容是否生效取决于消费方是否读取该 node/phase。
          </div>
          <template #footer>
            <el-button @click="promptCreateOpen = false">取消</el-button>
            <el-button type="primary" :loading="promptCreating" @click="createPrompt">创建</el-button>
          </template>
        </el-dialog>
      </el-tab-pane>

        </el-tabs>
      </el-tab-pane>

      <!-- 分组四：质量与风格（指纹基线、门禁与品类预设） -->
      <el-tab-pane label="质量与风格">
        <el-tabs>
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
                <span style="font-size: 13px">每章字数带（期望字数）</span>
                <el-input-number v-model="budgetMin" :min="300" :max="20000" :step="100" size="small" style="width: 110px" />
                <span style="font-size: 13px">至</span>
                <el-input-number v-model="budgetMax" :min="300" :max="20000" :step="100" size="small" style="width: 110px" />
                <span style="font-size: 13px">章长容差（±）</span>
                <el-input-number v-model="lenTol" :min="0" :max="0.5" :step="0.05" size="small" />
                <span style="color: #999; font-size: 12px">卷规划按此带出预算并钳制；容差决定门禁实际允许宽度</span>
              </div>
              <div style="font-size: 13px; margin: 10px 0 6px">评审标准（本书覆盖，未列出的键继承平台调参）</div>
              <div style="display: flex; gap: 10px; align-items: center; flex-wrap: wrap">
                <span style="font-size: 13px">注水软阈值</span>
                <el-input-number v-model="readerStd.reader_fat_ratio_block" :min="0" :max="1" :step="0.01" size="small" style="width: 92px" />
                <span style="font-size: 13px">硬上限</span>
                <el-input-number v-model="readerStd.reader_fat_ratio_hard" :min="0" :max="1" :step="0.01" size="small" style="width: 92px" />
                <span style="font-size: 13px">恢复线比例</span>
                <el-input-number v-model="readerStd.reader_fix_len_min" :min="0.3" :max="1" :step="0.05" size="small" style="width: 92px" />
                <span style="font-size: 13px">扩写护栏</span>
                <el-input-number v-model="readerStd.reader_fix_len_max" :min="1" :max="2" :step="0.05" size="small" style="width: 92px" />
                <span style="font-size: 13px">审校下限</span>
                <el-input-number v-model="readerStd.ai_review_fix_floor" :min="0.3" :max="1" :step="0.05" size="small" style="width: 92px" />
              </div>
              <div style="color: #999; font-size: 12px; margin-top: 4px">软阈值只提示不拦；结构性四问全过且超硬上限才转人工；恢复线 = 预算下限 × 比例</div>
              <el-button type="primary" @click="saveGateConfig">保存门禁配置</el-button>
              <span style="color: #999; font-size: 12px; margin-left: 10px">落库于 style_packs.gate_config，下一次门禁检测即生效</span>
            </div>
          </el-tab-pane>
        </el-tabs>
      </el-tab-pane>

      <!-- 品类预设（阶段三·特征提取管线） -->
      <el-tab-pane label="品类预设">
        <div style="display: flex; gap: 10px; align-items: center; margin-bottom: 8px; flex-wrap: wrap">
          <el-select v-model="presetGenre" filterable allow-create default-first-option placeholder="选择或输入新品类"
                     size="small" style="width: 180px" @change="loadCorpus">
            <el-option v-for="g in presetGenres" :key="g.genre" :value="g.genre"
                       :label="`${g.genre}（${g.chapters} 章 / ${g.words} 字）`" />
          </el-select>
          <el-input v-model="corpusTitle" placeholder="章标题（可选）" size="small" style="width: 200px" />
          <el-button type="primary" size="small" :disabled="!presetGenre || !corpusText" @click="addCorpus">导入语料章</el-button>
          <el-button size="small" :disabled="!presetGenre" @click="extractDraft">提取基线草稿</el-button>
          <span style="color: #999; font-size: 12px">品类随时新增；机械指标零成本；n&lt;10 低置信提示不拒绝</span>
        </div>
        <el-input v-model="corpusText" type="textarea" :rows="5" placeholder="粘贴一章原稿正文后点导入（可反复导入，量级不限）"
                  style="max-width: 860px; margin-bottom: 10px" />
        <el-table v-if="corpusRows.length" :data="corpusRows" border size="small" style="max-width: 860px; margin-bottom: 12px">
          <el-table-column prop="title" label="章标题" min-width="200" />
          <el-table-column prop="wordCount" label="字数" width="90" />
          <el-table-column label="操作" width="80">
            <template #default="{ row }">
              <el-button size="small" type="danger" link @click="delCorpus(row.id)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>

        <el-card v-if="draft" shadow="never" style="max-width: 860px; margin-bottom: 12px">
          <template #header>
            <span style="font-size: 13px">「{{ draft.genre }}」提取草稿（{{ draft.chapters }} 章 · {{ draft.metricCount }} 项指标）</span>
          </template>
          <el-alert v-if="draft.lowConfidence" type="warning" :closable="false" style="margin-bottom: 6px"
                    :title="draft.notes[0]" />
          <div style="font-size: 13px; margin-bottom: 6px">
            章长预算带：<b>{{ draft.budgetMin }}–{{ draft.budgetMax }}</b> 字（容差 {{ draft.chapterLengthTolerance }}）
          </div>
          <div style="font-size: 12px; color: #909399; margin-bottom: 8px; white-space: pre-wrap">{{ draft.notes.join('\n') }}</div>
          <div style="font-size: 12px; color: #909399; max-height: 120px; overflow: auto; margin-bottom: 8px">{{ draft.fingerprintJson.slice(0, 600) }}</div>
          <div style="display: flex; gap: 8px; align-items: center">
            <el-input v-model="presetName" placeholder="预设名（如：漱石猫·日常推理）" size="small" style="width: 240px" />
            <el-button type="primary" size="small" :disabled="!presetName" @click="adoptPreset">保存为预设</el-button>
          </div>
        </el-card>

        <el-table :data="presets" border size="small" style="max-width: 860px">
          <el-table-column prop="id" label="ID" width="60" />
          <el-table-column prop="name" label="预设" min-width="160" />
          <el-table-column prop="description" label="说明" min-width="240" show-overflow-tooltip />
          <el-table-column label="操作" width="110">
            <template #default="{ row }">
              <el-button size="small" type="primary" link @click="applyPreset(row.id)">应用到本书</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <!-- 导入小说（用户定调：输入的小说与全部分析落库可复用，专门分类展示；深度解析出剧情/角色/世界观资产） -->
      <el-tab-pane :label="`导入小说（${samples.length}）`">
        <div style="display: flex; align-items: center; gap: 10px; margin-bottom: 8px">
          <el-button type="primary" size="small" @click="openSampleImport">导入新小说</el-button>
          <span style="font-size: 12px; color: #999">
            每本导入的小说：文风指纹/章长带即时分析留档；「深度解析」由 AI 拆出剧情结构（书/卷/章+场景拆解）、角色/物品/地点/组织资产卡与关系、世界观文档——全部落库，可在下方浏览、纠偏，衍生开书时克隆复用。
            快速档抽样前 40 章出骨架（约几分钟）；完整档全书逐章（长篇 1-3 小时，可断点续跑）。
          </span>
        </div>
        <el-table :data="samples" border size="small" style="max-width: 1080px"
                  :row-class-name="({ row }) => (row.id === highlightSampleId ? 'sample-highlight' : '')">
          <el-table-column type="expand">
            <template #default="{ row }">
              <div v-if="sampleAnalysis(row)" style="padding: 4px 12px; font-size: 13px; line-height: 1.9">
                <div><b>文风分析快照</b>（{{ sampleAnalysis(row).chunks }} 块 · {{ Math.round(sampleAnalysis(row).totalChars / 100) / 100 }} 万字 ·
                  章长带 {{ sampleAnalysis(row).budgetMin }}-{{ sampleAnalysis(row).budgetMax }} ·
                  {{ sampleAnalysis(row).metricCount }} 项指标 ·
                  建议：{{ { match: '复用现有', new: '建新品类', choice: '两可' }[sampleAnalysis(row).recommendation] || sampleAnalysis(row).recommendation }}）</div>
                <div v-for="s in sampleAnalysis(row).similarities || []" :key="s.presetId">
                  相似度 {{ s.name }}：{{ s.comparable ? Math.round(s.score * 100) + '%' : '不可比' }}
                </div>
                <div v-for="(n, i) in sampleAnalysis(row).notes || []" :key="i" style="color: #999">{{ n }}</div>
              </div>
              <div v-else style="padding: 4px 12px; color: #999; font-size: 13px">
                历史导入（早于台账功能，无分析快照；语料在品类库可随时重提）
              </div>
            </template>
          </el-table-column>
          <el-table-column prop="title" label="小说名" min-width="150" show-overflow-tooltip />
          <el-table-column prop="genre" label="入库品类" min-width="100" show-overflow-tooltip />
          <el-table-column label="字数" width="85">
            <template #default="{ row }">{{ (row.totalChars / 10000).toFixed(1) }} 万</template>
          </el-table-column>
          <el-table-column label="采纳预设" width="85">
            <template #default="{ row }">
              <el-tag v-if="row.presetId" size="small" type="success">#{{ row.presetId }}</el-tag>
              <span v-else style="color: #999; font-size: 12px">未采纳</span>
            </template>
          </el-table-column>
          <el-table-column label="深度解析" width="200">
            <template #default="{ row }">
              <template v-if="parseStatuses[row.id] && parseStatuses[row.id].status !== 'NONE'">
                <div v-if="['QUEUED', 'RUNNING'].includes(parseStatuses[row.id].status)" style="width: 100%">
                  <el-progress :percentage="parsePercent(row)" :stroke-width="10"
                               :format="() => `${parseStatuses[row.id].doneUnits}/${parseStatuses[row.id].totalUnits}`" />
                  <span style="font-size: 12px; color: #e6a23c">{{ stageLabel(parseStatuses[row.id].stage) }}</span>
                </div>
                <div v-else-if="parseStatuses[row.id].status === 'DONE'" style="font-size: 12px">
                  <el-tag size="small" type="success">{{ parseStatuses[row.id].mode === 'FAST' ? '快速骨架' : '完整解析' }}</el-tag>
                  <span style="color: #999; margin-left: 4px">
                    {{ parseStatuses[row.id].chapterCount }} 章
                    <template v-if="parseStatuses[row.id].volumeCount">/ {{ parseStatuses[row.id].volumeCount }} 卷</template>
                    / {{ parseStatuses[row.id].cardCount }} 卡
                  </span>
                </div>
                <el-tooltip v-else :content="parseStatuses[row.id].message || parseStatuses[row.id].status" placement="top">
                  <el-tag size="small" type="danger">{{ parseStatuses[row.id].status === 'FAILED' ? '失败' : '已中断' }}</el-tag>
                </el-tooltip>
              </template>
              <span v-else style="color: #999; font-size: 12px">未解析</span>
            </template>
          </el-table-column>
          <el-table-column label="标签" min-width="150">
            <template #default="{ row }">
              <template v-if="(row.tags || []).length">
                <el-tag v-for="t in row.tags.slice(0, 4)" :key="t" size="small" style="margin-right: 4px">{{ t }}</el-tag>
                <span v-if="row.tags.length > 4" style="font-size: 12px; color: #999">+{{ row.tags.length - 4 }}</span>
              </template>
              <span v-else style="color: #999; font-size: 12px">—</span>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="340">
            <template #default="{ row }">
              <el-button v-if="parseStatuses[row.id] && parseStatuses[row.id].chapterCount > 0"
                         size="small" type="primary" link @click="openAssets(row)">资产</el-button>
              <el-button v-if="parseStatuses[row.id] && parseStatuses[row.id].chapterCount > 0"
                         size="small" link :loading="taggingId === row.id" @click="extractTags(row)">提标签</el-button>
              <el-button v-if="!row.presetId" size="small" type="warning" link
                         :loading="adoptingId === row.id" @click="adoptSamplePreset(row)">提预设</el-button>
              <el-button v-if="canParse(row, 'FAST')" size="small" link @click="submitParse(row, 'FAST')">快速解析</el-button>
              <el-button v-if="canParse(row, 'FULL')" size="small" link @click="submitParse(row, 'FULL')">
                {{ parseStatuses[row.id] && parseStatuses[row.id].mode === 'FAST' ? '升级完整' : '完整解析' }}
              </el-button>
              <el-button v-if="parseStatuses[row.id] && ['FAILED', 'INTERRUPTED'].includes(parseStatuses[row.id].status)"
                         size="small" type="warning" link @click="resumeParse(row)">继续解析</el-button>
            </template>
          </el-table-column>
          <el-table-column label="导入时间" width="140">
            <template #default="{ row }">{{ (row.createTime || '').replace('T', ' ').slice(0, 16) }}</template>
          </el-table-column>
        </el-table>
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

    <!-- 导入样本·解析资产浏览 -->
    <el-drawer v-model="assetsOpen" :title="assetsData ? `《${assetsData.title}》解析资产` : ''" size="65%">
      <template v-if="assetsData">
        <el-tabs v-model="assetsTab">
          <el-tab-pane name="plot" label="剧情结构">
            <div v-if="assetsData.book" class="sample-book-summary">{{ assetsData.book.summary }}</div>
            <div v-else style="color: #999; font-size: 13px">尚无全书大纲（解析完成或升级完整解析后生成）</div>
            <div style="display: flex; gap: 12px; margin-top: 10px">
              <el-tree :data="plotTree" node-key="key" highlight-current default-expand-all
                       style="min-width: 260px; max-width: 340px; border: 1px solid #eee; padding: 4px"
                       @node-click="onPlotNodeClick" />
              <div style="flex: 1; min-width: 0">
                <template v-if="plotDetail">
                  <div style="font-weight: 600; margin-bottom: 6px">{{ plotDetail.title }}</div>
                  <div style="font-size: 13px; line-height: 1.8; margin-bottom: 10px">{{ plotDetail.summary }}</div>
                  <el-table v-if="(plotDetail.beats || []).length" :data="plotDetail.beats" border size="small">
                    <el-table-column prop="goal" label="场景目标" min-width="160" show-overflow-tooltip />
                    <el-table-column prop="conflict" label="冲突" min-width="160" show-overflow-tooltip />
                    <el-table-column prop="outcome" label="收束" min-width="160" show-overflow-tooltip />
                  </el-table>
                  <div v-if="plotDetail.meta && plotDetail.meta.pseudo" style="color: #999; font-size: 12px; margin-top: 6px">
                    原文无标准章标题，此段为自动伪章切分
                  </div>
                </template>
                <div v-else style="color: #999; font-size: 13px; padding-top: 6px">点左侧树节点查看章节摘要与场景拆解</div>
              </div>
            </div>
          </el-tab-pane>
          <el-tab-pane name="cards" :label="`资产卡（${sampleCards.length}）`">
            <div style="margin-bottom: 8px">
              <el-button size="small" type="primary" plain @click="openSampleCardCreate">新建卡</el-button>
              <span style="font-size: 12px; color: #999; margin-left: 6px">AI 漏抽的实体在这里手工补录（重新解析会重建全部卡）</span>
            </div>
            <el-table :data="sampleCards" border size="small">
              <el-table-column type="expand">
                <template #default="{ row }">
                  <div style="padding: 4px 12px; font-size: 13px">
                    <div v-if="row.contentMd" style="white-space: pre-wrap; margin-bottom: 8px">{{ row.contentMd }}</div>
                    <div v-for="(r, i) in row.relations || []" :key="i" style="color: #666">
                      关系 · {{ r.target }}（{{ r.kind }}）{{ r.note ? '：' + r.note : '' }}
                    </div>
                  </div>
                </template>
              </el-table-column>
              <el-table-column label="类型" width="70">
                <template #default="{ row }">{{ sampleKindLabel[row.kind] || row.kind }}</template>
              </el-table-column>
              <el-table-column prop="name" label="名称" min-width="120" />
              <el-table-column label="别名" min-width="140" show-overflow-tooltip>
                <template #default="{ row }">{{ (row.aliases || []).join('、') }}</template>
              </el-table-column>
              <el-table-column label="重要度" width="70">
                <template #default="{ row }">{{ '★'.repeat(row.importance || 1) }}</template>
              </el-table-column>
              <el-table-column prop="mentions" label="提及章数" width="80" />
              <el-table-column prop="firstSeq" label="首现章" width="70" />
              <el-table-column prop="summary" label="摘要" min-width="200" show-overflow-tooltip />
              <el-table-column label="操作" width="110">
                <template #default="{ row }">
                  <el-button size="small" link @click="openSampleCard(row)">编辑</el-button>
                  <el-button size="small" type="danger" link @click="delSampleCard(row)">删除</el-button>
                </template>
              </el-table-column>
            </el-table>
          </el-tab-pane>
          <el-tab-pane name="relations" :label="`关系（${sampleRelations.length}）`">
            <el-table :data="sampleRelations" border size="small">
              <el-table-column prop="from" label="主体" min-width="120" />
              <el-table-column prop="kind" label="关系" min-width="110" />
              <el-table-column prop="target" label="对象" min-width="120" />
              <el-table-column prop="note" label="说明" min-width="220" show-overflow-tooltip />
            </el-table>
          </el-tab-pane>
          <el-tab-pane name="world" label="世界观">
            <div v-if="worldCard" style="white-space: pre-wrap; font-size: 13px; line-height: 1.9">{{ worldCard.contentMd }}</div>
            <div v-else style="color: #999; font-size: 13px">尚无世界观文档（解析完成后生成）</div>
          </el-tab-pane>
        </el-tabs>
      </template>
    </el-drawer>

    <!-- 导入样本·资产卡纠偏/新建 -->
    <el-dialog v-model="sampleCardEditor" :title="sampleCardForm.id ? '资产卡纠偏' : '新建资产卡'" width="640px">
      <div style="display: flex; gap: 10px; align-items: center; margin-bottom: 10px">
        <template v-if="sampleCardForm.id">
          <b>{{ sampleCardForm.name }}</b>
          <span style="color: #999; font-size: 12px">{{ sampleKindLabel[sampleCardForm.kind] || sampleCardForm.kind }}（重新解析会重建全部卡）</span>
        </template>
        <template v-else>
          <el-select v-model="sampleCardForm.kind" size="small" style="width: 110px">
            <el-option v-for="(label, k) in sampleKindLabel" :key="k" :value="k" :label="label" />
          </el-select>
          <el-input v-model="sampleCardForm.name" placeholder="名称（必填）" size="small" style="width: 200px" />
          <el-input v-model="sampleCardForm.aliasesText" placeholder="别名（逗号分隔，可选）" size="small" style="width: 220px" />
        </template>
        <span style="font-size: 13px">重要度</span>
        <el-select v-model="sampleCardForm.importance" size="small" style="width: 90px">
          <el-option :value="1" label="★" />
          <el-option :value="2" label="★★" />
          <el-option :value="3" label="★★★" />
        </el-select>
      </div>
      <el-input v-model="sampleCardForm.summary" type="textarea" :rows="3" placeholder="摘要" style="margin-bottom: 10px" />
      <el-input v-model="sampleCardForm.contentMd" type="textarea" :rows="8" placeholder="全文（开书克隆时随卡进新书的素材卡）" />
      <template #footer>
        <el-button @click="sampleCardEditor = false">取消</el-button>
        <el-button type="primary" @click="saveSampleCard">保存</el-button>
      </template>
    </el-dialog>

    <!-- 素材库·导入新小说（不开书也能囤素材：分析落台账，之后随时深度解析/采纳预设/衍生开书） -->
    <el-dialog v-model="sampleImportOpen" title="导入新小说" width="640px">
      <div style="display: flex; gap: 8px; align-items: center; margin-bottom: 8px">
        <el-input v-model="sampleImportForm.name" placeholder="小说名（用于命名品类，可选）" size="small" style="width: 220px" />
        <label style="cursor: pointer; font-size: 13px; color: #409eff">上传 txt / mobi
          <input type="file" accept=".txt,.mobi,.azw3,.azw" style="display: none" @change="onSampleImportFile" />
        </label>
        <span v-if="sampleImportForm.text" style="font-size: 12px; color: #999">
          已载入 {{ (sampleImportForm.text.length / 10000).toFixed(1) }} 万字
        </span>
      </div>
      <el-input v-model="sampleImportForm.text" type="textarea" :rows="8"
                placeholder="或直接粘贴小说正文（整本或长片段，最多 800 万字）。系统自动切块存入语料库并出文风分析，之后可在列表里深度解析。" />
      <div style="font-size: 12px; color: #999; margin-top: 6px">分析为纯机械指标（秒级、零 LLM 成本）；深度解析（LLM）在列表行单独触发。</div>
      <template #footer>
        <el-button @click="sampleImportOpen = false">取消</el-button>
        <el-button type="primary" :loading="sampleImporting" :disabled="!sampleImportForm.text && !sampleImportForm.mobiBase64" @click="importSample">
          分析并入库
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, onUnmounted, ref, watch, reactive } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api } from '../api'
import { getSelectedNovelId, setSelectedNovelId } from '../novelSelection'

const novels = ref([])
const novelId = ref(null)
const health = ref(null)
// 品类预设（阶段三）
const presetGenres = ref([])
const presetGenre = ref('')
const corpusTitle = ref('')
const corpusText = ref('')
const corpusRows = ref([])
const draft = ref(null)
const presetName = ref('')
const presets = ref([])
// 导入小说台账（用户输入即入库，分析快照可回看）
const samples = ref([])
const sampleAnalysisCache = new Map()

async function loadPresets() {
  try {
    presetGenres.value = await api.get('/api/preset/genres')
    presets.value = await api.get('/api/preset/list')
  } catch { /* 预设页签加载失败不拦其他页签 */ }
}

async function loadSamples() {
  try {
    samples.value = await api.get('/api/preset/samples')
    loadParseStatuses()
  } catch { /* 导入小说页签加载失败不拦其他页签 */ }
}

/** 展开行解析分析快照（无快照返回 null 走历史回填文案）。 */
function sampleAnalysis(row) {
  if (!row.analysisJson) return null
  if (!sampleAnalysisCache.has(row.id)) {
    try {
      sampleAnalysisCache.set(row.id, JSON.parse(row.analysisJson))
    } catch {
      sampleAnalysisCache.set(row.id, null)
    }
  }
  return sampleAnalysisCache.get(row.id)
}

// ===== 导入小说·深度解析（样本资产化：剧情结构/资产卡/关系/世界观） =====
const parseStatuses = ref({})
const assetsOpen = ref(false)
const assetsTab = ref('plot')
const assetsData = ref(null)
const plotDetail = ref(null)
const sampleCardEditor = ref(false)
const sampleCardForm = ref({})
const sampleKindLabel = { character: '角色', item: '物品', location: '地点', phenomenon: '现象', landmark: '地标', disaster: '灾害', org: '组织', misc: '其他', world: '世界观' }
let parsePollTimer = null

const sampleCards = computed(() => (assetsData.value?.cards || []).filter((c) => c.kind !== 'world'))
const worldCard = computed(() => (assetsData.value?.cards || []).find((c) => c.kind === 'world'))
const sampleRelations = computed(() => {
  const out = []
  for (const c of sampleCards.value) {
    for (const r of c.relations || []) {
      out.push({ from: c.name, kind: r.kind, target: r.target, note: r.note || '' })
    }
  }
  return out
})

const plotTree = computed(() => {
  const d = assetsData.value
  if (!d) return []
  if ((d.volumes || []).length) {
    return d.volumes.map((v) => ({
      key: `v${v.seq}`,
      label: `${v.title}（${v.meta?.chapters || ''} ${v.meta?.chapters ? '章' : ''}）`.replace('（）', ''),
      children: d.chapters.filter((c) => c.parentSeq === v.seq).map((c) => ({ key: `c${c.seq}`, label: c.title, raw: c }))
    }))
  }
  return [{ key: 'chapters', label: `章节（${d.chapters.length}）`, children: d.chapters.map((c) => ({ key: `c${c.seq}`, label: c.title, raw: c })) }]
})

/** 解析状态轮询：有活跃任务时每 3s 刷新（ QUEUED/RUNNING ），无则停。 */
async function loadParseStatuses() {
  for (const s of samples.value) {
    try {
      parseStatuses.value[s.id] = await api.get(`/api/preset/samples/${s.id}/parse`)
    } catch { /* 单样本状态失败不拦整体 */ }
  }
  scheduleParsePoll()
}

function scheduleParsePoll() {
  clearTimeout(parsePollTimer)
  parsePollTimer = null
  const active = Object.values(parseStatuses.value).some((st) => ['QUEUED', 'RUNNING'].includes(st?.status))
  if (active) parsePollTimer = setTimeout(loadParseStatuses, 3000)
}

function parsePercent(row) {
  const st = parseStatuses.value[row.id]
  if (!st || !st.totalUnits) return 0
  return Math.min(100, Math.round((st.doneUnits / st.totalUnits) * 100))
}

function stageLabel(stage) {
  return { chapter: '逐章解析', merge: '实体归并', volume: '卷级汇总', outline: '大纲合成', world: '世界观', done: '完成' }[stage] || stage || ''
}

/** FAST：尚无章资产才可跑；FULL：无资产可跑，或快速骨架完成后可升级（已析章跳过）。 */
function canParse(row, mode) {
  const st = parseStatuses.value[row.id]
  if (st && ['QUEUED', 'RUNNING'].includes(st.status)) return false
  if (st && ['FAILED', 'INTERRUPTED'].includes(st.status)) return false
  if (!st || !st.chapterCount) return true
  return mode === 'FULL' && st.mode === 'FAST' && st.status === 'DONE'
}

async function submitParse(row, mode) {
  try {
    if (mode === 'FULL') {
      await ElMessageBox.confirm(
        '完整解析全书逐章进行（长篇约 1-3 小时、约 10-30 元），中途可断点续跑；快速骨架已析的章自动跳过。继续？',
        '完整深度解析', { type: 'info' })
    }
    await api.post(`/api/preset/samples/${row.id}/parse`, { mode })
    ElMessage.success('已提交解析')
    await loadParseStatuses()
  } catch (e) {
    if (e !== 'cancel') ElMessage.error(e.message)
  }
}

async function resumeParse(row) {
  try {
    await api.post(`/api/preset/samples/${row.id}/parse/resume`)
    ElMessage.success('已从断点继续')
    await loadParseStatuses()
  } catch (e) {
    ElMessage.error(e.message)
  }
}

async function openAssets(row) {
  try {
    assetsData.value = await api.get(`/api/preset/samples/${row.id}/assets`)
    assetsTab.value = 'plot'
    plotDetail.value = null
    assetsOpen.value = true
  } catch (e) {
    ElMessage.error(e.message)
  }
}

/** 样本类型/特征标签手动提取/重提（解析管线 DONE 前会自动跑一次）。 */
const taggingId = ref(null)
const adoptingId = ref(null)

/** 一键把该样本品类的文风采纳为预设（之后开书下拉即可选）。 */
async function adoptSamplePreset(row) {
  adoptingId.value = row.id
  try {
    const r = await api.post('/api/preset/from-sample', {
      genre: row.genre,
      presetName: row.genre + '·文风v1',
      description: '源品类：' + row.genre + '，' + row.chunks + ' 块语料'
    })
    ElMessage.success(`文风预设已生成（#${r.presetId}），开书向导可直接选`)
    await loadSamples()
    await loadPresets()
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    adoptingId.value = null
  }
}
async function extractTags(row) {
  taggingId.value = row.id
  try {
    const tags = await api.post(`/api/preset/samples/${row.id}/tags`)
    ElMessage.success(`标签已更新：${tags.join('、')}`)
    await loadSamples()
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    taggingId.value = null
  }
}

function onPlotNodeClick(node) {
  if (node.raw) plotDetail.value = node.raw
}

async function reloadAssets() {
  if (!assetsData.value) return
  try {
    assetsData.value = await api.get(`/api/preset/samples/${assetsData.value.sampleId}/assets`)
  } catch (e) {
    ElMessage.error(e.message)
  }
}

async function openSampleCard(row) {
  sampleCardForm.value = { ...row }
  sampleCardEditor.value = true
}

function openSampleCardCreate() {
  sampleCardForm.value = { id: null, kind: 'character', name: '', aliasesText: '', summary: '', contentMd: '', importance: 2 }
  sampleCardEditor.value = true
}

async function saveSampleCard() {
  try {
    if (sampleCardForm.value.id) {
      await api.put(`/api/preset/cards/${sampleCardForm.value.id}`, {
        summary: sampleCardForm.value.summary,
        contentMd: sampleCardForm.value.contentMd,
        importance: sampleCardForm.value.importance
      })
    } else {
      if (!sampleCardForm.value.name.trim()) {
        ElMessage.warning('名称必填')
        return
      }
      await api.post(`/api/preset/samples/${assetsData.value.sampleId}/cards`, {
        kind: sampleCardForm.value.kind,
        name: sampleCardForm.value.name.trim(),
        aliases: sampleCardForm.value.aliasesText || '',
        summary: sampleCardForm.value.summary || '',
        contentMd: sampleCardForm.value.contentMd || '',
        importance: sampleCardForm.value.importance
      })
    }
    ElMessage.success('已保存')
    sampleCardEditor.value = false
    await reloadAssets()
    await loadParseStatuses()
  } catch (e) {
    ElMessage.error(e.message)
  }
}

// ===== 素材库直接导入新小说（不开书囤素材） + 来源定位高亮 =====
const sampleImportOpen = ref(false)
const sampleImportForm = ref({ name: '', text: '' })
const sampleImporting = ref(false)
const highlightSampleId = ref(null)

function openSampleImport() {
  sampleImportForm.value = { name: '', text: '', mobiBase64: '' }
  sampleImportOpen.value = true
}

function onSampleImportFile(ev) {
  const f = ev.target.files && ev.target.files[0]
  if (!f) return
  if (/\.(mobi|azw3|azw)$/i.test(f.name)) {
    const reader = new FileReader()
    reader.onload = () => {
      sampleImportForm.value.mobiBase64 = String(reader.result || '')
      sampleImportForm.value.text = ''
      if (!sampleImportForm.value.name) sampleImportForm.value.name = f.name.replace(/\.(mobi|azw3|azw)$/i, '')
    }
    reader.readAsDataURL(f)
  } else {
    const reader = new FileReader()
    reader.onload = () => {
      sampleImportForm.value.text = String(reader.result || '')
      sampleImportForm.value.mobiBase64 = ''
      if (!sampleImportForm.value.name) sampleImportForm.value.name = f.name.replace(/\.txt$/i, '')
    }
    reader.readAsText(f, 'utf-8')
  }
  ev.target.value = ''
}

async function importSample() {
  sampleImporting.value = true
  try {
    const r = await api.post('/api/preset/analyze', {
      sampleName: sampleImportForm.value.name,
      text: sampleImportForm.value.text,
      mobiBase64: sampleImportForm.value.mobiBase64 || undefined
    })
    sampleImportOpen.value = false
    highlightSampleId.value = r.sampleId
    await loadSamples()
    ElMessage.success(`已入库（${r.chunks} 块语料，品类「${r.genre}」）——点行内「深度解析」拆剧情与资产`)
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    sampleImporting.value = false
  }
}

async function delSampleCard(row) {
  try {
    await ElMessageBox.confirm(`删除资产卡「${row.name}」？（重新解析会重建全部卡）`, '删除', { type: 'warning' })
    await api.delete(`/api/preset/cards/${row.id}`)
    await reloadAssets()
    await loadParseStatuses()
  } catch (e) {
    if (e !== 'cancel') ElMessage.error(e.message)
  }
}

async function loadCorpus() {
  if (!presetGenre.value) return
  corpusRows.value = await api.get(`/api/preset/corpus?genre=${encodeURIComponent(presetGenre.value)}`)
}

async function addCorpus() {
  await api.post('/api/preset/corpus', { genre: presetGenre.value, title: corpusTitle.value, content: corpusText.value })
  corpusTitle.value = ''
  corpusText.value = ''
  ElMessage.success('语料已导入')
  await loadCorpus()
  await loadPresets()
}

async function delCorpus(id) {
  await api.delete(`/api/preset/corpus/${id}`)
  await loadCorpus()
  await loadPresets()
}

async function extractDraft() {
  draft.value = await api.post('/api/preset/extract', { genre: presetGenre.value })
}

async function adoptPreset() {
  const id = await api.post('/api/preset/adopt', { genre: presetGenre.value, name: presetName.value })
  draft.value = null
  presetName.value = ''
  ElMessage.success(`预设已保存（#${id}）`)
  await loadPresets()
}

async function applyPreset(id) {
  await ElMessageBox.confirm('将覆盖本书的指纹基线/门禁配置/风格规则（原有内容不保留），继续？', '应用预设')
  await api.post(`/api/preset/${id}/apply/${novelId.value}`)
  ElMessage.success('已应用：下一次生成即按新指纹门禁')
}
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
const budgetMin = ref(2400)
const budgetMax = ref(3400)
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
const readerStd = reactive({ reader_fat_ratio_block: 0.33, reader_fat_ratio_hard: 0.5, reader_fix_len_min: 0.75, reader_fix_len_max: 1.15, ai_review_fix_floor: 0.6 })
const prompts = ref([])
const promptFilter = ref('')
const promptOpen = ref(false)
const promptDetail = ref(null)
const promptEditing = ref(false)
const promptSaving = ref(false)
const promptContent = ref('')
const embIndexed = ref(0)
const embEnabled = ref(true)

const filteredPrompts = computed(() => {
  const kw = promptFilter.value.trim().toLowerCase()
  if (!kw) return prompts.value
  return prompts.value.filter((p) => p.node.toLowerCase().includes(kw) || p.title.toLowerCase().includes(kw))
})

// ===== 提示词新建/删除（增删改查补齐） =====
const promptCreateOpen = ref(false)
const promptCreateForm = ref({ node: '', phase: '', title: '', content: '' })
const promptCreating = ref(false)
const showPromptDelete = ref(false)

function openPromptCreate() {
  promptCreateForm.value = { node: '', phase: '', title: '', content: '' }
  promptCreateOpen.value = true
}

async function createPrompt() {
  const f = promptCreateForm.value
  if (!f.node.trim() || !f.phase.trim() || !f.content.trim()) {
    ElMessage.warning('节点/阶段/内容必填')
    return
  }
  if (f.phase.trim().length > 32) {
    ElMessage.warning('阶段过长（≤32 字符）')
    return
  }
  promptCreating.value = true
  try {
    await api.post('/api/prompts', {
      node: f.node.trim(),
      phase: f.phase.trim(),
      title: f.title.trim(),
      content: f.content
    })
    ElMessage.success('提示词已创建')
    promptCreateOpen.value = false
    prompts.value = await api.get('/api/prompts')
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    promptCreating.value = false
  }
}

async function deletePrompt(row) {
  try {
    await ElMessageBox.confirm(`删除提示词「${row.node}/${row.phase}」？（软删，可数据库恢复）`, '删除', { type: 'warning' })
    await api.delete(`/api/prompts/${row.id}`)
    ElMessage.success('已删除')
    prompts.value = await api.get('/api/prompts')
  } catch (e) {
    if (e !== 'cancel') ElMessage.error(e.message)
  }
}

async function togglePromptEnabled(row, enabled) {
  try {
    await api.put(`/api/prompts/${row.id}/enabled`, { enabled })
    row.enabled = enabled
    ElMessage.success(enabled ? '已启用（30 秒内生效）' : '已停用（回退代码版）')
  } catch (e) {
    ElMessage.error(e.message)
  }
}

async function viewPrompt(id) {
  promptDetail.value = await api.get(`/api/prompts/${id}`)
  promptEditing.value = false
  promptOpen.value = true
}

function loadPrompt() {
  if (promptDetail.value) viewPrompt(promptDetail.value.id)
}

async function savePrompt() {
  promptSaving.value = true
  try {
    promptDetail.value = await api.put(`/api/prompts/${promptDetail.value.id}`, { content: promptContent.value })
    promptEditing.value = false
    ElMessage.success('已保存，30 秒内对新生效')
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    promptSaving.value = false
  }
}

async function resetPrompt() {
  try {
    await ElMessageBox.confirm('放弃人工修改，恢复为代码内置模板？', '重置确认')
  } catch {
    return
  }
  try {
    promptDetail.value = await api.post(`/api/prompts/${promptDetail.value.id}/reset`)
    ElMessage.success('已重置为代码版')
  } catch (e) {
    ElMessage.error(e.message)
  }
}

async function copyPrompt() {
  try {
    await navigator.clipboard.writeText(promptDetail.value.content)
    ElMessage.success('已复制')
  } catch {
    ElMessage.error('复制失败（浏览器权限）')
  }
}

function fmtTime(iso) {
  if (!iso) return '-'
  return String(iso).replace('T', ' ').slice(0, 19)
}
const embBackfilling = ref(false)

async function backfillEmbeddings() {
  embBackfilling.value = true
  try {
    const r = await api.post(`/api/novels/${novelId.value}/embeddings/backfill`)
    ElMessage.success(`回填完成：新增 ${r.added} 条，共 ${r.indexed} 条`)
    embIndexed.value = r.indexed
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    embBackfilling.value = false
  }
}

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
  health.value = await api.get(`/api/novels/${novelId.value}/ledger-health`).catch(() => null)
  loadPresets()
  loadSamples()
  canon.value = await api.get(`/api/novels/${novelId.value}/canon`)
  cards.value = await api.get(`/api/novels/${novelId.value}/cards`)
  llmNodes.value = await api.get('/api/llm-nodes')
  llmPrices.value = await api.get('/api/llm-prices')
  tunings.value = (await api.get('/api/tuning')).map(t => ({ ...t, editValue: t.value }))
  prompts.value = await api.get('/api/prompts')
  try {
    const es = await api.get(`/api/novels/${novelId.value}/embeddings/status`)
    embIndexed.value = es.indexed
    embEnabled.value = es.enabled !== false
  } catch {
    embIndexed.value = 0
  }
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
    Object.assign(readerStd, await api.get(`/api/novels/${novelId.value}/reader-standards`))
  } catch { /* 回显失败保持默认 */ }
  try {
    const cfg = JSON.parse(s.gateConfigJson || '{}')
    bannedText.value = (cfg.banned_phrases || []).join('\n')
    lenTol.value = typeof cfg.chapter_length_tolerance === 'number' ? cfg.chapter_length_tolerance : 0.15
    budgetMin.value = typeof cfg.budget_min === 'number' && cfg.budget_min > 0 ? cfg.budget_min : 2400
    budgetMax.value = typeof cfg.budget_max === 'number' && cfg.budget_max > 0 ? cfg.budget_max : 3400
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
    const cfg = {
      banned_phrases: phrases,
      chapter_length_tolerance: lenTol.value,
      budget_min: Math.min(budgetMin.value, budgetMax.value),
      budget_max: Math.max(budgetMin.value, budgetMax.value),
      no_straight_quote: true,
      ...JSON.parse(JSON.stringify(readerStd))
    }
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
  // 向导「去深度解析」跳转定位：/?sampleId=N 高亮对应行
  const q = new URLSearchParams((location.hash.split('?')[1] || ''))
  const sid = Number(q.get('sampleId'))
  if (sid) highlightSampleId.value = sid
  await loadAll()
})

onUnmounted(() => clearTimeout(parsePollTimer))

watch(novelId, loadAll)
</script>

<style scoped>
:deep(.sample-highlight td) {
  background: #ecf5ff !important;
}
</style>
