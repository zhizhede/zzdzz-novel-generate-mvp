<template>
  <div class="page-head">
    <h3 class="page-head__title"><slot name="title">{{ title }}</slot></h3>
    <slot />
    <p v-if="hint || $slots.hint" class="page-head__hint">
      <slot name="hint">{{ hint }}</slot>
    </p>
  </div>
</template>

<script setup>
/**
 * 页面头部：标题 + 页级动作（默认插槽）+ 右侧说明文字。
 *
 * 抽它的原因：原先每个页面各写一遍
 * `<div style="display:flex;align-items:center;gap:14px;margin-bottom:6px"><h3 style="margin:0">…`
 * 加一串 `color:#999;font-size:12px` 的说明 span，改一次要动五处。
 * 说明文字改用 --muted（7.5:1），替掉原先不过 AA 的 #999（2.7:1）。
 */
defineProps({
  /** 页标题；也可用 #title 插槽放更复杂的内容 */
  title: { type: String, default: '' },
  /** 右侧说明文字；也可用 #hint 插槽 */
  hint: { type: String, default: '' }
})
</script>

<style scoped>
.page-head {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  margin-bottom: var(--space-2);
  /* 窗口窄时标题/动作/说明各自换行，而不是横向溢出 */
  flex-wrap: wrap;
}
.page-head__title {
  margin: 0;
  flex: none;
  font-size: var(--text-lg);
  font-weight: 600;
  line-height: var(--leading-tight);
  color: var(--fg);
}
.page-head__hint {
  /* 占据剩余宽度；给个较小基准宽，窄屏时整段换到下一行而不是被挤成细条 */
  flex: 1 1 320px;
  min-width: 0;
  margin: 0;
  font-size: var(--text-xs);
  line-height: var(--leading-body);
  color: var(--muted);
}
</style>
