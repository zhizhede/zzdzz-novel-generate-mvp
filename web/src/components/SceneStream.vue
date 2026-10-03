<template>
  <div>
    <div class="ss-head" :style="{ color: headingColor }">
      <b>{{ heading }}</b>
      <span v-if="subheading" class="ss-sub">{{ subheading }}</span>
      <el-tag v-if="item.streaming" type="warning" size="small" effect="plain">{{ streamingLabel }}</el-tag>
      <el-tag v-else-if="variant === 'scene'" size="small" effect="plain" type="info">
        {{ item.phase === 'reused' ? '复用缓存' : '已完成' }}
      </el-tag>
      <el-link v-if="item.think" type="info" :underline="false" class="ss-toggle"
               @click="item.thinkOpen = !item.thinkOpen">
        {{ item.thinkOpen ? '收起思考' : `思考（${item.think.length}字）` }}
      </el-link>
    </div>

    <div v-if="showPlaceholder" class="ss-placeholder">
      ⏳ 检索与打包上下文<template v-if="variant === 'scene'">（世界状态/事实账/RAG 召回，约 10-20 秒后开始逐字输出）</template>…
    </div>

    <div v-if="showThink" class="ss-think">{{ item.think }}</div>

    <div v-if="variant === 'scene'" class="ss-text">{{ item.text }}<span
      v-if="item.streaming && item.text" class="ss-cursor">▍</span></div>
  </div>
</template>

<script setup>
import { computed } from 'vue'

/**
 * 场景流式块：工作台「生成输出（实时流式）」与会话转录视图共用。
 *
 * 抽它的原因：这段此前在工作台里写了三遍——`scenes` 循环一处、会话转录的
 * `scene` 分支一处、`pstream` 分支又抄了「收起思考」一处——流式三态标签、
 * 复用/完成标签、思考折叠链接、`⏳ 检索与打包上下文…` 占位、think 块全部
 * 逐字重复，只差变量名。
 *
 * 顺带统一了三处原本不一致的表述（属刻意的收敛，不是等价搬运）：
 *   · 折叠链接统一为「思考（N字）」（原先一处叫「思考过程（N字）」）
 *   · 正文块统一带 `--text-base` 与 1.9 行高（原先 scenes 那处不设字号）
 *   · think 块 max-height 统一 260px（原先 scenes 那处是 220px）
 *   · 标题一律加粗（原先 scenes 那处不加粗，与另两处不一致）
 */
const props = defineProps({
  /** 流式对象：需含 think / text / streaming / phase / thinkOpen 字段（thinkOpen 会被就地切换） */
  item: { type: Object, required: true },
  /** 'scene'＝逐场景正文流；'plan'＝卷规划/审校，只流思考、不逐字出正文 */
  variant: { type: String, default: 'scene' },
  /** 标题文字 */
  heading: { type: String, default: '' },
  /** 标题右侧的次要说明（如场景目标） */
  subheading: { type: String, default: '' },
  /** 标题颜色；场景列表用 muted、会话视图按类型用 success/warn */
  headingColor: { type: String, default: 'var(--muted)' }
})

const streamingLabel = computed(() => {
  if (props.variant === 'plan') return props.item.think ? '思考中…' : '打包上下文中…'
  return props.item.text ? '正文流式生成中' : (props.item.think ? '思考中…' : '检索上下文中…')
})

const showPlaceholder = computed(() =>
  props.variant === 'scene' && props.item.streaming && !props.item.think && !props.item.text)

const showThink = computed(() => {
  if (!props.item.think) return false
  return props.variant === 'scene'
    ? (props.item.thinkOpen || (props.item.streaming && !props.item.text))
    : (props.item.thinkOpen || props.item.streaming)
})
</script>

<style scoped>
.ss-head {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  margin-bottom: 4px;
  font-size: var(--text-sm);
}
.ss-sub {
  color: var(--muted);
  font-size: var(--text-xs);
}
.ss-toggle {
  font-size: var(--text-xs);
}
.ss-placeholder {
  color: var(--meta);
  font-size: var(--text-xs);
  border-left: 3px solid var(--border-soft);
  padding-left: 10px;
}
.ss-think {
  white-space: pre-wrap;
  color: var(--meta);
  font-size: var(--text-xs);
  border-left: 3px solid var(--border-soft);
  padding-left: 10px;
  margin-bottom: 6px;
  max-height: 260px;
  overflow-y: auto;
}
.ss-text {
  white-space: pre-wrap;
  border-left: 3px solid var(--accent);
  padding-left: 10px;
  font-size: var(--text-base);
  line-height: 1.9;
}
.ss-cursor {
  color: var(--accent);
}
</style>
