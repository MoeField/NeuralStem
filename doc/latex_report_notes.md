# 报告写作与 LaTeX 排版笔记（AAAI 模板）

> 用途：本项目除 App 之外还要交课程**报告**（AAAI 2026 格式）。这份文件记录
> 那次做报告时踩到的坑与验证方法，避免下次重来。
> 报告产物：`report/neuralstem.tex` → `Proposal_72515069_NeuralStem.pdf`
> 一键重建：`cd report && pdflatex neuralstem.tex && pdflatex neuralstem.tex`
> （两遍是必须的：第一遍生成 `.aux`，第二遍才解析交叉引用与文献）

---

## 一、环境：本机已装 MacTeX

- `pdflatex` / `latexmk` 在 `/Library/TeX/texbin`（可能不在 `PATH`，需
  `export PATH=/Library/TeX/texbin:$PATH`）。
- 模板三件套（`aaai2026.sty` / `.bst` / `.bib`）放 `report/`，与 `.tex` 同级。

---

## 二、★★ 如何校验生成的 PDF（这次绕了很久）

**问题**：终端里看不到 PDF，需要可编程验证。本机几个常见工具都不可用：

| 工具 | 结果 |
|---|---|
| `pdftotext` | ❌ 不可用 |
| `pdftoppm` | ❌ 不可用 |
| `gs`（Ghostscript） | ❌ 未安装 |
| `pdfinfo` | ❌ 不可用 |
| `sips -s format png x.pdf` | ❌ **输出全黑图**（不可信） |
| **`qlmanage -t -s 1200 -o outdir x.pdf`** | ✅ 可用（只出第 1 页缩略图） |
| **Swift + PDFKit** | ✅ **最可靠**（逐页取文本 / 逐页渲图） |

### 推荐做法：Swift + PDFKit

**① 读每页文本量（判断空白页 / 内容是否到位）**
```swift
import PDFKit
let doc = PDFDocument(url: URL(fileURLWithPath: "neuralstem.pdf"))!
for i in 0..<doc.pageCount {
    let s = doc.page(at: i)?.string ?? ""
    print("page \(i+1): chars=\(s.filter{ !$0.isWhitespace }.count)")
}
```

**② 逐页渲染 PNG（真正的“看一眼”）**
```swift
import PDFKit, AppKit
let doc = PDFDocument(url: URL(fileURLWithPath: "neuralstem.pdf"))!
for i in 0..<doc.pageCount {
    let page = doc.page(at: i)!, r = page.bounds(for: .mediaBox)
    let scale: CGFloat = 2
    let img = NSImage(size: NSSize(width: r.width*scale, height: r.height*scale))
    img.lockFocus()
    NSColor.white.setFill(); NSRect(origin: .zero, size: img.size).fill()
    let ctx = NSGraphicsContext.current!.cgContext
    ctx.scaleBy(x: scale, y: scale)
    page.draw(with: .mediaBox, to: ctx)
    img.unlockFocus()
    let png = NSBitmapImageRep(data: img.tiffRepresentation!)!
        .representation(using: .png, properties: [:])!
    try! png.write(to: URL(fileURLWithPath: "/tmp/page\(i+1).png"))
}
```

**③ 页数**：直接从 `pdflatex` 日志读最省事
```bash
grep "Output written" main.log   # → "Output written on x.pdf (7 pages, …)"
```

> ⚠️ 不要用像素统计去“判断排版好不好”——只能判断“有没有内容”。
> 这次曾用颜色统计误判“波形没画出来”，实际是**颜色对比度低**而非缺失（见 §五）。

---

## 三、★★ 连字符断词：正确做法（踩了 4 轮）

**症状**：正文/表格里单词被 `-` 断开；或反过来，禁连字后出现大量
`Underfull \hbox (badness 10000)`（行内空格被拉得很开，肉眼可见）。

**结论（最终采用）**：

| 区域 | 做法 | 效果 |
|---|---|---|
| **正文** | **保留 LaTeX 默认连字** | 两栏窄栏下间距最均匀 |
| **表格单元格** | 列声明用 `>{\raggedright\arraybackslash}p{…}` + 禁连字 | 不断词、不拉伸 |

```latex
\usepackage{array}
\newcommand{\nohyph}{\hyphenpenalty=10000 \exhyphenpenalty=10000
                     \tolerance=3000 \emergencystretch=2em}
% 列： p{2cm}  →  >{\raggedright\arraybackslash}p{2cm}
```

**为什么表格必须 `raggedright`**：禁连字后，两端对齐的窄列只能靠拉开词间距
填满行，于是出现 badness=10000 的松散行；左对齐则不拉伸。

**为什么正文不要禁连字**：两栏栏宽窄，禁连字会让**每一段**都出现松散行
（本次实测：全禁 → 82 处 Underfull，其中 42 处 badness=10000）。

### 隐蔽坑：`table` 环境不是 TeX 组
在 `\begin{table}` 里改 `\hyphenpenalty` **会泄漏到后面的正文**。
必须用 `\begingroup … \endgroup` 包住，或在列声明里局部设置。

### 检查命令
```bash
grep -c "Overfull .hbox"  main.log     # 目标 0（文字溢出边界）
grep -c "Underfull .hbox" main.log     # 少量且 badness 小即可
grep -oE "Underfull .hbox \(badness [0-9]+\)" main.log | grep -oE "[0-9]+" | sort -n
# 判据：badness < 3000 基本看不出；= 10000 一定难看，必须处理
```

### 其他消除 badness=10000 的手法
- **长内联代码**（如 `\texttt{MediaSessionService}`）不可断行 → 加 `\-` 手动断点，
  或改写句子让它出现在行首/行尾。
- **caption / 短句** 单独成行时最易松散 → 避免用 `\emph{(短语).}` 起句，
  改写成完整句子。

---

## 四、★★ 表格还是列表？判据

| 内容形态 | 用表格 | 用 `description` 列表 |
|---|---|---|
| 多列可横向比较的矩阵（✓/—） | ✅ | |
| 短条目清单（ID + 一句话） | ✅ | |
| 映射关系（From / Event / To） | ✅ | |
| 数值数据（速度 / 内存） | ✅ | |
| **「决策 → 备选 → 完整理由」** | ❌ | ✅ |
| **「风险 → 影响 → 缓解措施」** | ❌ | ✅ |

**原因**：AAAI 模板用 booktabs 风格，**行与行之间没有额外空隙**；把整句论证塞进
2–3 cm 的列里，既挤又断词，读起来非常难受。`description` 列表天然有段间距。

写法：
```latex
\begin{description}
\item[Service-owned state.] \emph{Instead of} keeping state in the ViewModel:
  a ViewModel is destroyed with its host, so playback would be interrupted.
\end{description}
```

---

## 五、★ 截图取素材（Android）

### 1. 切语言（出英文截图）
```bash
adb shell cmd locale set-app-locales pub.miku.neuralstem --locales en
adb shell cmd locale get-app-locales pub.miku.neuralstem     # 校验
```

### 2. 切深浅色
```bash
adb shell cmd uimode night yes   # 深色
adb shell cmd uimode night no    # 浅色
```

### 3. 截图前必须有数据
`install -r` 会**清掉私有目录**，模型要重新 push：
```bash
adb shell "run-as $PKG mkdir -p files/sherpa"
adb push model_assets/sherpa-onnx-spleeter-2stems-fp16/vocals.fp16.onnx /data/local/tmp/v.onnx
adb shell "run-as $PKG cp /data/local/tmp/v.onnx files/sherpa/vocals.fp16.onnx"
# accompaniment 同理；歌曲同法拷进 files/
```
> ⚠️ 不要用 `run-as sh -c 'cat /sdcard/... > files/x'` —— 曾产生 **0 字节文件**
> （权限/重定向问题，见坑 41 同族）。用 `adb push` 到 `/data/local/tmp` 再 `run-as cp`。

### 4. 播放器转盘波形"看不见"？—— 多半是对比度，不是缺失
本次曾以为浅色主题下外环波形没画出来。**实测否定了这个判断**：

| 主题 | 波形像素 | 角向覆盖 |
|---|---|---|
| 深色 | 25,400 | 27/36 段 |
| 浅色 | 24,738 | 27/36 段 |

两色原本 `WaveRemaining = 0xFF37474F`（深灰蓝），在浅底上**存在但细线对比度低**。
⇒ **先做像素统计再下结论**，不要凭肉眼判定"功能缺失"。

```python
# 判断某色像素数与角向分布（判断是否“沿圆完整”）
wave = [(x,y) for y in range(h) for x in range(w) if matches(px[x,y])]
buckets = {int(((math.degrees(math.atan2(y-cy, x-cx))+360)%360)//10) for x,y in wave}
print(len(wave), len(buckets), "/36")
```

### 5. 论文插图用浅色
大块深色底在论文里显得突兀，也不利于黑白打印。**默认用浅色**；
若确实需要深色（产品原设计），在图注里说明。

---

## 六、提交规范

- 文件名严格按作业要求：`Proposal_<StudentNum>_<ProjectName>.pdf`
- 仓库内保留 **`.tex` 源码**（可复现）+ 编译好的 PDF + `figures/`
- `report/.gitignore` 忽略 `.aux/.log/.out/.toc/.bbl/.blg/.synctex.gz`
- 论文必须**全英文**；交付前检查无中日韩字符：
  ```bash
  # 用 PDFKit 读文本后统计 \u4e00-\u9fff 数量，应为 0
  ```

---

## 七、CI 与文档改动

`.github/workflows/android-ci.yml` 已配置 `paths-ignore`：
`**.md`、`doc/**`、`report/**`、`LICENSE`、`NOTICE`、`.gitignore`
⇒ **只改文档/报告不会触发构建**（省 CI 时间，也无需为此开 dev 分支）。

> 因此本项目**维持单 `main` 分支**：文档提交用 `docs:` 前缀即可，
> 不必引入长期 `dev` 分支（单人开发时长期分支只增加合并成本）。
