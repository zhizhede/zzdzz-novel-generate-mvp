# USAGE —— 本设计系统的读取顺序

给 agent 的用法路由。为本平台生成或改造界面时，按此顺序读。

## 读序

1. **`DESIGN.md`** —— 规范散文，先读。气质、颜色、排版、宽度口径、Do/Don't 都在里面。
2. **`tokens.css`** —— 唯一令牌源。所有颜色/字号/间距/圆角/阴影都必须走这里的 `var(--*)`。
3. **`element-plus.css`** —— 本项目专用映射层：把品牌令牌接到 Element Plus 的 `--el-*` 变量上。**改 Element Plus 组件外观时改这里，不要去覆盖组件内部样式。**

## 三条硬约束

1. **不写裸值。** 任何 `#999` / `12px` / `border-radius: 6px` 这类硬编码都是回归。
2. **数据表满宽、无 px 上限。** 见 `DESIGN.md` §5；这也是本项目的一个历史缺陷根因。
3. **状态不靠颜色单独表达。** 标签必须带文字。

## 挂载方式（本项目）

`web/src/main.js` 中，在 Element Plus 自带样式**之后**依次引入：

```js
import 'element-plus/dist/index.css'
import './styles/tokens.css'        // → @import 本包的 tokens.css
import './styles/element-plus.css'  // → @import 本包的 element-plus.css
```

顺序不能反：Element Plus 默认值必须先落，否则会盖回品牌值。

## 上游

包结构遵循 OpenDesign `design-systems/_schema/AGENTS.md`（A1 / A2 / B-slot / C-extension 四层，A2 与 B-slot 必须显式声明，不可依赖兜底）。上游基座与目录安装于 `D:\Program\open-design`。
