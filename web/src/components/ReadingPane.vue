<template>
  <div class="rp" :class="`rp--${s.theme}`">
    <div class="rp-bar">
      <span class="rp-lab">字号</span>
      <el-radio-group v-model="s.size" size="small">
        <el-radio-button v-for="v in SIZE_STEPS" :key="v" :value="v">{{ v }}</el-radio-button>
      </el-radio-group>

      <span class="rp-lab">行高</span>
      <el-radio-group v-model="s.leading" size="small">
        <el-radio-button v-for="v in LEADING_STEPS" :key="v" :value="v">{{ v }}</el-radio-button>
      </el-radio-group>

      <span class="rp-lab">版心</span>
      <el-radio-group v-model="s.measure" size="small">
        <el-radio-button v-for="v in MEASURE_STEPS" :key="v" :value="v">{{ v }}字</el-radio-button>
      </el-radio-group>

      <span class="rp-lab">字体</span>
      <el-radio-group v-model="s.serif" size="small">
        <el-radio-button :value="true">宋</el-radio-button>
        <el-radio-button :value="false">黑</el-radio-button>
      </el-radio-group>

      <span class="rp-lab">底色</span>
      <el-radio-group v-model="s.theme" size="small">
        <el-radio-button v-for="t in THEMES" :key="t.value" :value="t.value">{{ t.label }}</el-radio-button>
      </el-radio-group>

      <span class="rp-tip">偏好记在本机，下次打开沿用</span>
    </div>

    <div class="rp-scroll">
      <article class="rp-body" :style="bodyStyle">
        <h1 v-if="title" class="rp-title">{{ title }}</h1>
        <p v-for="(p, i) in paragraphs" :key="i" class="rp-p">{{ p }}</p>
        <div v-if="paragraphs.length" class="rp-end">— 完 —</div>
        <p v-else class="rp-empty">（此处无可读正文）</p>
      </article>
    </div>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import {
  readingSettings as s, toParagraphs,
  SIZE_STEPS, LEADING_STEPS, MEASURE_STEPS, THEMES
} from '../readingSettings'

/**
 * 正文阅读面：章节阅读模式的载体，也供会话视图复用排版偏好。
 *
 * 相对旧实现的四件事（旧版是弹窗里一段写死的 `white-space: pre-wrap`）：
 *   1. 可调 —— 字号/行高/版心/字体/底色五维，localStorage 持久化（模块 readingSettings）
 *   2. 版心按「全角字数」表达并渲染为 em —— 1em≈1 全角字，46em≈每行 46 字；
 *      用户调大字号时版心同步变宽，每行字数不变（按比例，不是设 px 上限）
 *   3. 中文排版惯例：段首缩进 2 字符（text-indent: 2em）、段与段之间留空
 *      并独立于行高 —— 中文长文这两项此前完全没有
 *   4. 三种底色（浅/护眼/深），深色不是把浅色反相，是换一套暖色值
 */
const props = defineProps({
  text: { type: String, default: '' },
  title: { type: String, default: '' }
})

const paragraphs = computed(() => toParagraphs(props.text))

const bodyStyle = computed(() => ({
  fontSize: s.size + 'px',
  lineHeight: s.leading,
  maxWidth: s.measure + 'em',
  fontFamily: s.serif ? 'var(--font-display)' : 'var(--font-body)'
}))
</script>

<style scoped>
.rp {
  display: flex;
  flex-direction: column;
  height: 100%;
  background: var(--rp-bg);
  color: var(--rp-fg);
}

/* 三种底色：只换这一层变量，正文排版规则不变 */
.rp--light { --rp-bg: var(--bg); --rp-fg: var(--fg); --rp-border: var(--border); }
.rp--sepia { --rp-bg: #f7f0e2; --rp-fg: #2e2a22; --rp-border: #e3d8c2; }
.rp--dark  { --rp-bg: #141413; --rp-fg: #e8e6dd; --rp-border: #3d3d3a; }

.rp-bar {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  flex-wrap: wrap;
  padding: var(--space-2) var(--space-3);
  border-bottom: 1px solid var(--rp-border);
  background: var(--rp-bg);
  position: sticky;
  top: 0;
  z-index: 1;
}
.rp-lab {
  font-size: var(--text-xs);
  color: var(--rp-fg);
  opacity: 0.65;
  margin-left: var(--space-2);
}
.rp-lab:first-child { margin-left: 0; }
.rp-tip {
  font-size: var(--text-xs);
  color: var(--rp-fg);
  opacity: 0.5;
  margin-left: auto;
}

.rp-scroll {
  flex: 1;
  overflow-y: auto;
  padding: var(--space-6) var(--space-4) 80px;
}
.rp-body {
  margin: 0 auto;
  /* 版心随字号缩放（em）而不是写死 px —— 见组件头注释第 2 条 */
}
.rp-title {
  font-size: 1.5em;
  line-height: 1.4;
  margin: 0 0 1.5em;
  font-weight: 600;
}
.rp-p {
  /* 中文排版惯例：段首缩进两字符 */
  text-indent: 2em;
  /* 段间距独立于行高：行高管行内密度，段间距管段落块感 */
  margin: 0 0 1em;
}
.rp-end {
  text-align: center;
  opacity: 0.55;
  font-size: 0.8em;
  margin-top: 2.5em;
}
.rp-empty {
  text-align: center;
  opacity: 0.6;
  font-size: 0.85em;
}
</style>
