# 章节导出 Markdown 使用文档

脚本：`scripts/export_derive_chapters_md.py`
数据源：docker 容器 `pg-vector` 内 `derive_gen` 库 `chapters` 表（读取 `novels` 取书名）。

## 输入

| 输入 | 说明 |
|---|---|
| `novel_id`（必填） | `novels.id`，按此书导出 |
| `-o / --output`（可选） | 输出 md 路径；缺省为 `小说输出/<书名>.md`（书名中的非法文件名字符自动替换为 `_`） |

前置条件：`pg-vector` 容器在运行；本机有 `python`（3.10+，仅标准库）与 `docker`。

## 命令全文

```bash
# 基本用法：导出指定书，写到默认路径 小说输出/<书名>.md
python scripts/export_derive_chapters_md.py 3

# 指定输出路径
python scripts/export_derive_chapters_md.py 3 -o var/无歇之航.md

# 等价完整写法
python scripts/export_derive_chapters_md.py --output "小说输出/无歇之航.md" 3
```

终端输出示例：

```
已导出 8 章 → 小说输出\无歇之航.md
跳过无正文的章（chapter_no）: 7, 8
```

异常情况直接报错退出、不写文件：书号不存在、该书没有章节、所有章节均无正文、psql 执行失败。

## 输出预期格式

- UTF-8 编码、`\n` 换行的 Markdown 文件。
- 按 `chapter_no` 升序，每章一块：标题行（`# 第n章 标题`）下一行即正文首段，段落之间空行分隔，章与章之间空行分隔。

```md
# 第1章 潮闷渐起
正文第一段……。

正文第二段……。

# 第2章 物件就位
正文第一段……。

正文第二段……。
```

排版规则：

- 库内正文以单换行分段，导出时统一转为 Markdown 空行分段；行尾空白、首尾空行清除。
- `title` 为空 → 标题只有 `第n章`；`title` 已是「第N章」格式 → 直接用作标题，不重复拼接。
- 正文首行若本身是冗余章头（「第N章 …」且不超过 30 字，如书 18 第 1 章）会剥掉，避免与标题行重复。
- `content` 为空（NULL/空白）的章跳过，并在终端按章号列出。
