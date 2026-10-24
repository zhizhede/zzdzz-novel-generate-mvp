# ebook2md 操作手册

txt / mobi 小说转 Markdown 工具。纯 Python 脚本，无第三方电子书库依赖（mobi 解析自研；HTML 步骤需要 beautifulsoup4，随 Anaconda 已带）。

## 输入

| 项目 | 说明 |
|---|---|
| 格式 | `.txt` `.text` `.md`；`.mobi` `.azw` `.azw3` `.prc`（按 BOOKMOBI 容器解析） |
| 形式 | 单个文件、多个文件、或一个目录（目录会递归收集上述扩展名） |
| txt 编码 | 自动探测：UTF-8(含BOM) → GB18030 → Big5 → charset-normalizer 兜底 |
| txt 结构约定 | 默认「一行即一段」；首行视为书名；`第N章` / `第N回` / `第N歌` / `一、标题` / 裸数字节号（如 `1`）/ `序章·楔子·尾声…` 识别为标题行 |
| mobi 限制 | 不支持 DRM 加密；不支持 HUFF/CDIC 压缩（报错提示先用 Calibre 转换）；封面图不抽取 |

## 输出

- 默认写到**输入文件同目录**、同名 `.md`；UTF-8 编码，LF 换行。
- 结构约定：
  - `# 一级标题`：txt 取首行；mobi 取 EXTH 书名（扉页重复行自动去重）
  - `##`：卷/篇级（地狱篇、第一部…）与特殊块（译本序、尾声…）；无卷篇时章节也用 `##`
  - `###`：有卷篇结构时的章/歌
  - 作者、译者、扉页信息 → *斜体*
  - 正文段首全角缩进（`　　`）去掉
  - mobi 诗行：连续短行用行末 `\` 硬换行合并成一段
  - mobi 目录：连续 3 条以上纯链接行整块丢弃（连同「目录」标题）
- 转换结束打印字数与标题数；失败的文件报到 stderr，退出码非 0。

## 命令样例

```bash
# 单文件 → 同目录同名 .md
python tools/ebook2md.py 书.txt
python tools/ebook2md.py 书.mobi

# 指定输出路径（仅单个输入时可用）
python tools/ebook2md.py 书.mobi -o D:\md\神曲.md

# 批量：多个文件 / 整个目录（递归），输出到指定目录
python tools/ebook2md.py 书1.txt 书2.mobi --out-dir D:\md
python tools/ebook2md.py "D:\书库\江户川乱步" --out-dir D:\md

# 覆盖文首一级标题
python tools/ebook2md.py 无名稿.txt --title 我的小说

# 查看全部参数
python tools/ebook2md.py --help
```

> Windows 下若 `python` 不可用，换成你的 Python 解释器绝对路径即可。
