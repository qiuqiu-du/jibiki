# AGENTS.md

给在本仓库工作的 AI 编程助手看的说明。开始任何任务前先读完本文件，再读 [Architecture.md](Architecture.md)。

## 项目是什么

一款**只给自己用**的 Android 日语词典 App（名称「辞書」）：
- 离线优先，日日释义为主；
- 只保留**查词**和**收藏**两大功能；
- 查询方向只做**日→日**（第一版）和**日→中**（M5），不做中→日反查，也不做日→英。

## 必读文档（按优先级）

1. **设计明细文档**（需求的唯一来源）：[docs/design-spec.md](docs/design-spec.md)
   只维护这一份，没有其他副本。需求变了就直接改这个文件，并更新文件开头的「最后更新」日期。
2. **[Architecture.md](Architecture.md)**：架构、分层、UI 和编码规范。写代码前必须遵守。
3. **[dictionaries/README.md](dictionaries/README.md)**：辞书文件清单和版本说明。里面有几处宣传性描述和实测不符（以设计文档 3.2 节为准），见下文「已知坑」。

设计文档规定**做什么**，Architecture.md 规定**怎么写**。两者冲突时以设计文档为准，并把 Architecture.md 一起改掉。

## 仓库结构

```text
dict/
├── AGENTS.md / Architecture.md
├── docs/
│   ├── design-spec.md   设计明细文档（唯一维护版本）
│   ├── dictionaries.md  每部辞书的支持情况、体积估算和加入顺序
│   ├── moji-import.md   从 MOJi辞書 导出收藏的方法和接口记录
│   └── release.md       打包、签名、分发（scripts/deploy.sh）
├── dictionaries/    原始辞书 .mdx/.mdd、Yomitan .zip、NHK .json（约 3 GB，只读，不提交、不外传）
│   ├── daijisen/        デジタル大辞泉（主辞典）
│   ├── meikyo3/         明鏡国語辞典 第三版（辅助辞典）
│   ├── nhk-accent-2016/ NHK アクセント新辞典（音调来源）
│   ├── shinmeikai8/     新明解国語辞典 第八版（第二辅助辞典，可停用）
│   ├── daijirin4/       大辞林 第四版（M5 ①，第二主辞典）
│   ├── shinmeikai7/     新明解国語辞典 第七版（旧版对照）
│   ├── nhk-accent/      NHK アクセント辞典（1998年旧版对照）
│   ├── daijirin3/       大辞林 第三版（旧版对照）
│   ├── shogakukan-jc/   小学館日中辞典 tyuka 版 / V2 版（不用，被第三版取代）
│   ├── shogakukan-jc3/  小学館日中辞典 第三版（M5 ②，日中主辞典）
│   ├── shinseiki-jc/    外研社 新世纪日汉双解大辞典（M5 ③，可停用）
│   ├── gendai-jc/       现代日汉双解词典 修订版（暂不做）
│   └── kodansha-jc/     讲谈社 日本語大辞典 / 日汉大辞典 双解版（暂不做）
├── sources/         开放数据（不提交）：JMdict_e.gz、Kanjium accents.txt 自动下载；jiten/frequency_list_global.csv 要手动从 jiten.moe/frequency-dictionaries 下载（网站拒绝脚本访问）
├── tools/           电脑端 Python 脚本：.mdx / .zip / .json → SQLite 数据包
├── datapacks/       生成的数据包（不提交，用 adb push 到手机）
└── app/             Android 工程（M1 阶段创建）
```

## 当前进度

| 阶段 | 内容 | 状态 |
| --- | --- | --- |
| 设计 | 需求、辞书选型、分词器方案、架构规范 | ✅ 已完成 |
| M0 数据准备 | 三种格式的读取器，生成 core.db 和大辞泉、明鏡、新明解数据包 | ✅ 2026-11-19（数据包合计 100.1 MB） |
| M1 能查词 | 工程搭建、数据包导入、搜索、词条页、音调 | ✅ 2026-11-20 用户验收通过（候选 p95 9.3 ms、词条 p95 3.4 ms、release APK 3.0 MB） |
| M2 查得准 | Kuromoji 和规则表两种还原器、JMdict 校验、手写、测试集 | ✅ 2026-11-20 用户验收通过（测试集 565 条两种模式全过、候选 p95 9–14.5 ms、冷启动约 510 ms、release APK 25.9 MB） |
| M3 释义完善 | 辞书管理、释义排版和外字、网页页签 | ✅ 2026-11-20 用户验收通过（外字无占位、编号由 App 绘制、词条 p95 1.8 ms、release APK 26.0 MB） |
| M4 收藏完善 | 收藏夹、导出导入、备份恢复、历史 | ✅ 2026-11-21 用户验收通过（release APK 25.0 MB；备份往返、Anki 导入已实测；MOJi 收藏 2914 词已导入；收藏夹拖动排序、左滑删除可撤销） |
| M5 辞书扩充 | 依次加入大辞林第四版、小学館日中辞典第三版、新世纪日汉双解（顺序见 [docs/dictionaries.md](docs/dictionaries.md)） | ✅ 2026-11-21 用户验收通过（大辞林4、小学館日中3、新世纪日汉双解；词库合计 178.8 MB，release APK 25.1 MB） |
| M6 设置与收尾 | 设置页三组、主题、键盘、词条展开、中日英界面、关于；去掉牵强的还原读法 | ✅ 2026-11-21（测试集两种模式 565/565；词库 178.8 MB；release APK 25.2 MB；冷启动约 250 ms，release，未开性能模式） |

完成一个阶段后，更新这张表和设计文档里的里程碑表格（记录 APK 大小、数据包大小、冷启动时间）。

## 已定的关键决策（不要擅自更改）

- **辞书**：デジタル大辞泉（主）、明鏡国語辞典 第三版（辅）、新明解国語辞典 第八版（第二辅助，可停用）、NHK アクセント新辞典 2016（音调）。M5 按顺序加入大辞林第四版（第二主辞典）、小学館日中辞典第三版（日中主辞典）、新世纪日汉双解大辞典（可停用）；讲谈社、现代日汉暂不做，小学館旧版（tyuka、V2）不用（2026-11-21）。旧版（新明解7、大辞林3、NHK 1998）只留作对照。
- **音调来源顺序**：NHK 2016 → 新明解第八版自带的音调数字 → Kanjium。不再解析 NHK 1998 年版的图片编码。
- **转换脚本输入格式**：MDict、Yomitan、NHK JSON；手机端只认生成的 `.db`。
- **只用文字**：不使用 .mdd 多媒体包里的插图和音频；只从中取出外字小图片（三部辞书合计约 140 种、90 KB）。
- **有版权的词库不打包进 APK**（2026-11-22 修改）：APK 里只带开放数据的 `core.db`（JMdict + Kanjium 音调，压缩后约 9 MB），装好就能查词；辞书正文、NHK 和新明解、大辞林的音调、新明解的基本词记号都在各自的数据包里，首次使用时从手机存储导入（可选），导入时并进 App 自己那份 core.db（见 design-spec 3.6）。
- **体积**：APK ≤ 50 MB（2026-11-22 从 30 MB 放宽），数据包合计 ≤ 200 MB（2026-11-21 从 110 MB 放宽）。
- **分词器**：Kuromoji + IPADIC（默认）和活用规则表（可在设置中切换）都实现 `Lemmatizer` 接口；Sudachi 不做（2026-11-21 放弃）。所有候选都必须经过 JMdict 校验。
- **明确不做**：显示注音开关、Sudachi（2026-11-21 放弃）、拍照识别、其他应用划词查询、发音朗读、背词复习、笔记、账号、评论或社区、AI 问答、广告、统计 SDK。

用户提出的新需求如果和这些决策冲突，先指出冲突点，确认后再动手，并同步更新设计文档。

## 开发环境

- macOS（Apple Silicon），已装 Homebrew、Xcode、VS Code。不用 Android Studio，全部命令行构建。
- JDK 17：`/opt/homebrew/opt/openjdk@17`（系统默认的 Java 11 太旧）。运行 Gradle 前设置：
  ```bash
  export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
  ```
- Android SDK：`~/Library/Android/sdk`（platform 36、build-tools 36.0.0，许可已接受），路径写在 `local.properties`（不提交）。
- 构建：Gradle Wrapper 8.14.3、AGP 8.13.2、Kotlin 2.2.20、compileSdk / targetSdk 36、minSdk 29；只打包 arm64-v8a。
- 已装工具：`adb`（android-platform-tools）、`scrcpy`、`gradle`（只用来生成 wrapper，平时用 `./gradlew`）。
- 测试设备：荣耀 **PPG-AN00**，MagicOS 11 / Android 17（API 37），骁龙 SM8750，USB 调试已授权，有 Google Play 服务。
- Python 3.14。`tools/` 的依赖：`mdict-utils`、`python-lzo`、`zstandard`；外字 SVG 转 PNG 还要命令行工具 `rsvg-convert`（`brew install librsvg`）。
  - `python-lzo` 需要先 `brew install lzo`，然后带上编译参数安装：
    ```bash
    CFLAGS="-I$(brew --prefix)/include -I$(brew --prefix)/include/lzo" LDFLAGS="-L$(brew --prefix)/lib" pip install python-lzo
    ```
  - 用项目内的虚拟环境（`tools/.venv`），不要装到系统 Python。

## 常用命令

工程创建后再补充具体的 Gradle 任务名。

数据包（在仓库根目录运行；先 core，再各辞书）：

```bash
tools/.venv/bin/python tools/build_core.py                 # → datapacks/core.db 和 core.db.zst（只用开放数据，打进 APK；约 15 秒）
tools/.venv/bin/python tools/build_pitch.py nhk2016        # → pitch_nhk2016.db（只有音调的数据包）
tools/.venv/bin/python tools/build_dict.py daijisen        # → dict_daijisen.db（约 1 分钟）
tools/.venv/bin/python tools/build_dict.py meikyo3
tools/.venv/bin/python tools/build_dict.py shinmeikai8
tools/.venv/bin/python tools/build_dict.py daijirin4       # 约 1.5 分钟
tools/.venv/bin/python tools/build_dict.py shinseiki_jc    # 约 1 分钟
tools/.venv/bin/python tools/build_dict.py shogakukan_jc3  # 约 40 秒（辞书 id 用下划线：收藏的打开位置 dict:id:序号 不允许连字符）
tools/.venv/bin/python tools/tests/check_packs.py          # 音调、对齐、标签、链接抽查；失败时退出码为 1
tools/.venv/bin/python tools/measure_sources.py daijirin4  # 加新辞书前估算体积和常用词覆盖（不写文件）
tools/.venv/bin/python tools/inspect_source.py shogakukan-jc3/shogakukan-jc3.mdx 手間   # 看标签、class、data-name 的分布和一条原文片段
```

设备与构建：

```bash
adb devices -l                                   # 确认手机已连接
scrcpy --stay-awake --max-size 1400              # 在 Mac 上实时查看手机画面
adb exec-out screencap -p > /tmp/screen.png      # 截图，用来验证界面
adb push datapacks/ /sdcard/Download/jisho/      # 推送数据包到手机（之后在 App 里选文件夹导入）
scripts/deploy.sh                                # 一条龙：数据包 → 检查 → 测试 → release APK（dist/）；install、push 送到手机，见 docs/release.md
scripts/push-datapacks.sh                        # debug 版：直接把数据包放进 App 私有目录，免去手动导入
scripts/test-jvm.sh                              # 只用电脑：数据包检查 + JVM 测试（不需要手机）
scripts/test-device.sh                           # 需要手机：真机测试 + 打印手机上测到的性能数值
./gradlew :app:installDebug                      # 编译并安装到手机
./gradlew :app:connectedDebugAndroidTest         # 真机测试：Android 运行时兼容性 + 性能（需要数据包已在 App 里）
./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=app.jisho.DeviceUiTest
                                                 # 只跑界面测试（约 1 分钟）
./gradlew :app:assembleRelease                   # release 包（约 25 MB）；有 keystore.properties 用发布钥匙签名，否则用本机调试钥匙（docs/release.md）
adb shell am start -S -n app.jisho/.MainActivity --es q 食べる   # debug 版：启动并预填搜索词
adb shell am start -S -n app.jisho/.MainActivity --es tab settings   # debug 版：直接打开设置页（搜索框的键盘会挡住底栏）；favorites 打开收藏页（三个主页面在同一个可左右滑动的 Home 里）
adb shell am start -S -n app.jisho/.MainActivity --es tab badges     # debug 版：编号样张（方框一～二十、圆圈 1～99，浅色和深色）
adb shell am start -S -n app.jisho/.MainActivity --es open meikyo3:51920
                                                 # debug 版：直接打开某辞书词条
```

## 工作方式

- **和用户用中文交流**，解释要通俗，少用术语；一定要用的术语，第一次出现时先解释。
- **语言分工**：
  - **所有文件名和目录名一律用英文**（小写，单词之间用 `-` 连接，例如 `design-spec.md`、`nhk-accent/`），包括文档、脚本、数据包。
  - **代码一律用英文**：标识符、注释、日志、提交说明、测试名都用英文。
  - **中文只出现在两个地方**：和用户的对话，以及开发设计相关的文档（`docs/`、`AGENTS.md`、`Architecture.md`、`dictionaries/` 下的说明文件）。
  - 日文只作为**数据**出现：辞书内容、测试用例里的词（例如 `"食べなかった" to "食べる"`）、界面上显示的日文文本。
  - App 界面文字放在 `strings.xml` 里，不写死在代码中。界面有中文（`values/`）、日文（`values-ja/`）、英文（`values-en/`）三种：新加一条文字要三个文件一起加，`TranslationTest` 会检查条目和占位符是否对齐；辞书术语（词性、活用说明、コトバンク 等）在 `values/` 里标 `translatable="false"`，三种语言下都用日文。
- **修 bug 要用通用修法**：找到问题背后的规则（哪一类词、哪一类输入会出错），按规则修，并用整个测试集（排序测试集、形态还原测试集、全量辞书扫描）验证没有改坏别的。不要写针对某个词的特殊判断；如果确实只能逐条处理（比如数据错误），放进数据表并注明原因，不写进代码。
- **改界面后要在真机上验证**：安装到手机，截图看效果，不能只凭编译通过就说完成。
- **改还原器或规则表后**，两种模式的测试集都要跑，并报告各自的准确率。发现拼出了不存在的读法时，先把它加进 `lemma_rejects.tsv`、把对应的真实说法加进 `lemma_cases.tsv`（guard 类），再在规则表里用类型或 `@never` / `@min-stem` 行修，不在代码里加判断。
- **加依赖前**，说明用途和对体积的影响（MB）。
- **不要读取、复制或改写 `dictionaries/` 里的原始文件**；只有 `tools/` 下的脚本可以读取它们，输出写到 `datapacks/`。
- 抽样查看辞书内容时，只在终端里显示少量片段，不要把大段原文写进仓库里的文件。

## 已知坑（实测发现）

- **说明文件里的不实描述**：`dictionaries/README.md` 和 `VERSION.md` 是外部整理的，实测有出入，以设计文档 3.2 节为准：
  - 明鏡第三版**正文不带音调数字**；
  - NHK 2016 JSON 不是“声调辞典”，而是录音索引，有遗漏和错配（见下）；
  - Yomitan 包并不“零对齐损耗”，同样要和 JMdict 校对。
- **压缩格式**：只有两个日中辞典的 .mdx 用 LZO 压缩，必须装 `python-lzo` 才能读；明鏡第三版、大辞泉不需要。
- **明鏡第三版**：正文 6.8 万条，其余是 `@@@LINK=` 跳转（指向数字 id，如 `@@@LINK=38658`）。正文是完整 XHTML，带 `<head>` 和几乎逐字的 `<ruby>` 注音，转换时要清理。词头写法「た・べる」，中间的点要去掉。
- **Yomitan 包（新明解8、大辞林4）**：每条记录是 `[词头, 读音, 标签, 活用类型, 分数, 释义, 序号, 词条标签]`，释义是嵌套的 structured-content JSON，要转成精简 HTML。压缩包里的外字 SVG 文件名不是 ASCII，解压时注意编码。大辞林4 解压后约 2.7 GB，要流式处理。
- **大辞泉**：大部分记录是 `@@@LINK=` 跳转（约 57.5 万条），要沿着跳转找到正文。正文词头格式是「たべる【食べる】」，可以拆出读音和汉字，用来和 JMdict 对齐。正文里有 `<img class="gaiji">` 外字图片。
- **NHK 2016 JSON**：结构是 `headwords`（检索词 → 录音文件名列表）+ `files`（文件名 → `kana_reading`、`pitch_pattern`、`pitch_number`）。要注意：
  - 一个检索词下混着多个词、活用形和带助词的录音（「端」→ タン、ハシ、ハシオ…），必须按「词头 + 读音」和 JMdict 对齐，只取原形；
  - 有遗漏和错配（「昨日」没有キノウ，「飴」只对应「のど飴」），交给新明解8 和 Kanjium 兜底；
  - `pitch_number` 可能是「1+1」这类复合写法，原样保存；
  - 音频文件不在本地（只有远程链接），我们也不用音频。
- **字形**：手机系统默认字体 HONOR Sans 使用中文字形。所有日文 Text 都必须设置 `LocaleList("ja")`，才会使用系统自带的 Noto Sans CJK JP。
- **JMdict 的「只供检索」写法**：`ke_inf sK` / `re_inf sk`（约 1.6 万个汉字写法、5 千个假名写法）只能用来搜索，不能当词头显示，例如「居らっしゃる」只是「いらっしゃる」的检索写法。`jmdict_reader` 的 `pairs()` 只给可显示的写法，`all_pairs()` 给全部写法（辞书对齐用），`search_only_texts()` 只写进 `search_key`。辞书对齐直接读 JMdict，不读 core.db 的显示写法。
- **JMdict 的常用度很粗**：只有 news1/ichi1 这类标记，很多词并列（基本动词常是 48，新闻名词却有细排名）。已用 `entry.score`（结合新明解 ＊＊/＊ 标记）和「通常写假名」加权解决，见 design-spec 4.1；改动以 `RankingTest` 为准。
- **音调写法**：`pitch.accent` 可能是多个值（`0,2`）或复合词写法（`1+1`），显示时原样处理，不要当成单个整数解析。
- **外字**（M3 已处理，规则见 Architecture.md 第 9 节）：
  - 三部辞书都有外字图片：大辞泉 75 种 GIF（`.mdd` 的 `\gaiji\`），明鏡 116 种 SVG（`.mdd` 的 `\gaiji\`），新明解 142 种 SVG（zip 里的 `smk8/`）。其中 99% 是分段编号这类记号。
  - 原辞书给的替代文字不可靠：明鏡把所有分段编号的 alt 都写成「3分の2」，生僻字的 alt 是「〓」；新明解生僻字的 title 也是「〓」。替换表在 `tools/dicts/gaiji/`，其余保留原图进 `media` 表。
  - SVG 转 PNG 需要 `rsvg-convert`（`brew install librsvg`）。
  - 分段编号和圆圈义项编号由 App 画（`Badges.kt`）。居中不要按字体行高，也不要按笔画外框：汉字按字体设计框居中，数字按笔画重心水平居中（这台手机的字体里数字左侧留白更多，按字位居中会整体偏右 1～3 像素）。
  - 少数大辞泉词条的**词头本身**是外字图片（如「ささてん‐ぼだい【×〓・…】」）：标题是纯文本，只能显示 〓，正文里的图正常。
- **手机输入法**：手机默认是拼音输入法，`adb shell input text` 打出的字母会进入拼音候选，不会直接进搜索框。验证搜索用上面的 `--es q` 启动参数。
- **不要在手机上按返回键或乱点**：用户可能正在用手机；返回键会退出 App 把别的应用切到前台。MagicOS 每次 `adb install` 后会弹出安装安全检查页，只点「完成」，不要点里面的「一键修复」「清理」。
- **界面交互由用户验收**：自动化验证优先用 JVM 测试和真机测试（见 Architecture.md 第 8 节），少用截图点屏。
  - 手势类问题（拖动、滑动、长按）写 Compose 界面测试（`createComposeRule` + `performTouchInput`，**全部放在 `DeviceUiTest.kt` 一个文件里**，每类功能一节）：在单独的测试界面里用真实触摸事件，按列表顺序、滚动位置判断对错，不碰用户数据，几秒一个用例。修之前先让测试在旧代码上失败，确认复现了问题。依赖只进测试包（Espresso 要 3.7.0，Compose 默认带的 3.5 在 API 37 上注入不了触摸）。
  - 需要看真 App 时，用 `adb shell uiautomator dump` 读出屏幕上的文字和坐标（Compose 的文字也在里面），比截图快、能直接算点击位置；只有看颜色、对齐时才截图。
- **拖动排序（`Reorder.kt`）**：LazyColumn 按 key 记住第一行，把行拖进或拖出第一个可见位置时，列表会跟着那一行滚走，所以交换时用 `requestScrollToItem` 按序号保持位置；靠边自动滚动只在手指朝那个方向移动过之后才开始。
- **zstd-jni 版本固定在 1.5.7-12**：1.5.7-14 起要求 compileSdk 37，而 AGP 8.13 最高只支持 36。
- **改了 core.db 表结构**：要同时升 `tools/build_core.py` 的 `CORE_SCHEMA_VERSION` 和 `DataPackManager.CORE_SCHEMA_VERSION`，重打 APK 即可（core.db 随 APK 走）。改了数据包表结构（`tools/lib/packdb.py`、`lib/pitch.py`）要升 `DICT_SCHEMA_VERSION`（两边），手机上重新推送并导入数据包。
- **App 里的 core.db 是合并出来的**：`DataPackManager.prepareCore` 把 APK 里的 `core.db.zst` 解压，再把各数据包的 `pitch`、`entry_score` 表并进去（`CoreComposer`）；App 更新或数据包变化后（`core.stamp` 记录）的第一次启动重做一次，约 1 秒，期间显示「正在准备词库」。JVM 测试的 `TestPacks.coreFile` 用同一段代码合并，`TestPacks.bundledCore` 是没有任何数据包时的样子。
- **JVM 测试通过 ≠ 手机上能跑**：Android 的正则引擎是 ICU，不支持 `(?U)` 这类桌面 Java 的写法，曾导致一输入就闪退。纯 Kotlin 代码里用到正则、`java.text`、字符集的，都要在 `DeviceRuntimeTest` 里在真机上跑一遍。
- **真机测试默认会卸载 App**：`gradle.properties` 里已设置 `android.injected.androidTest.leaveApksInstalledAfterRun=true`，不要删，否则跑完测试导入的数据包会被清空。
- **真机性能测试要在前台、亮屏、固定条件下跑**：
  - MagicOS 会在约一分钟后冻结没有前台界面的进程（cgroup 冻结，`/sys/fs/cgroup/apps/uid_*/pid_*/cgroup.freeze` 为 1；Android 自己的记录却显示「未冻结」），亮屏也一样，测试会无声卡住。所以 `DevicePerformanceTest` 测量时显示一个空白页面（Compose 测试清单里的 `ComponentActivity`）并保持亮屏，开始时熄屏就直接失败。
  - CPU 频率没有 root 改不了，手机也不支持持续性能模式。平时系统会压低最高频率（曾测到大核上限 1.96 GHz，标称 4.47 GHz），同一份代码两轮可以差一倍。对比前后数值时，请用户在 设置 → 电池 打开「性能模式」并接电源，测试前读 `/sys/devices/system/cpu/cpufreq/policy{0,6}/scaling_max_freq` 确认上限一致（只读）；改动前后在同一条件下各跑几轮。
- **vivo 默认让不认识的 App 跑 60 Hz**（2026-11-22，V2183A / Android 12）：系统设置是 120 Hz，但 App 在前台时屏幕一直 60 Hz，滑动时也一样。`MainActivity.preferHighestRefreshRate` 用标准接口（`preferredDisplayModeId`）请求当前分辨率下最高的刷新率后，vivo 变成 120 Hz（静止时也是 120，耗电稍多）；荣耀照常静止 60、滑动 120。用户在系统里设成 60 Hz 时仍以用户设置为准。查刷新率：`adb shell dumpsys SurfaceFlinger | grep -m1 refresh-rate`（荣耀看 `activeFrameRateMode`）。
- **荣耀手机的 logcat 是加密的**（显示为 `(HKS)…(HKE)`）：查闪退用 `adb shell dumpsys dropbox --print data_app_crash`；测试数据写进 App 私有目录的文件，再用 `run-as` 读出来（`test-device.sh` 就是这么做的），不要依赖 `Log`。
- **网页页签**：コトバンク 用 `/word/写法` 直接跳到词条，但它只收录部分写法（「舐める」404，「嘗める」「なめる」才有），所以 404 时依次试 `webSpellings` 给出的其他汉字写法和读音，全部 404 才开搜索页（搜索页带验证参数，必须用户点「検索」，不要用脚本代点；搜索页不隐藏顶栏，否则看不到搜索框）。两站的广告和「看广告才能继续」弹窗由 `AdBlock.kt` 屏蔽（拦截广告域名 + 隐藏广告位和弹窗）；弹窗来自 `fundingchoicesmessages.google.com`。两站和查词无关的部分（顶栏、关联词、排行、页脚等）按站点写在 `EntryTab.clutter`，同一套隐藏样式注入。验证弹窗时先清 App 的网页 Cookie（`adb shell run-as app.jisho rm -rf app_webview`），否则「已看过广告」的 24 小时记录会让弹窗本来就不出现。
- **WebView 第一次启动很慢、深色下会白闪**：每次 App 运行后第一次创建 WebView 要在主线程上花几百毫秒，期间整个界面卡住。`WarmUpWebView` 在词条页显示 0.5 秒后提前启动；`WebPage` 先画背景和进度条，下一帧再创建 WebView。WebView 画出第一帧前是白的，所以先设 `alpha = 0`，`onPageCommitVisible` 后淡入；深色主题下开启 `isAlgorithmicDarkeningAllowed`（API 33+）让网页也变暗。词条页三个页签放在 `HorizontalPager` 里，pager 自带的滑动关掉，改用 `PageSwipe.kt` 统一判断方向（Pager 自带的只看横向位移，看不到 WebView 内部的竖向滚动，网页里稍斜的上下滑就会误切换）；灵敏度是其中的 `SWIPE_RATIO`；底部悬浮页签靠 nested scroll（离线页）和 WebView 的 `setOnScrollChangeListener`（网页）判断滚动方向来收起或显示。
- **用户数据（M4）**：`user.db` 不用 Room，直接用 SQLite（`WritableDb` 接口；App 用 `AndroidWritableDb`，JVM 测试用 `JdbcWritableDb`）。没有内置收藏夹（早期版本无名的「默认」收藏夹启动时改名为普通收藏夹）。收藏按「词头 + 读音」**或同一个 `target`** 识别（导入的词读音常和 JMdict 不一致，如ビビンバ存成「びびんば」、读音后带「⓪」），`target` 记打开位置（`core:序号` / `dict:辞书:词条号` / `lookup:写法`；`lookup:` 词在离线数据里没有，打开的是只有网页页签的词条页）。收藏列表的一句话释义要先去掉开头的标签，标签里可能套括号（「［動ワ五（ハ四）］」），`DictionaryRepositoryTest.noSummaryStartsWithAClosingBracket` 扫全部辞书检查。备份是若干 TSV 组成的 zip，与表结构无关。真机测试 `DeviceUserDataTest` 只用临时文件，不碰用户的 `user.db`。词条页只有一种数据形状 `EntryPage`（JMdict 词、单本辞书条目、离线没有的词都转成它），新增网页页签只改 `EntryTab`。列表里的滑动删除不要用 `rememberSwipeToDismissBoxState`：它按列表 key 保存状态，撤销后词回来时还是「已滑走」，会立刻再删一次。拖动排序是自己写的 `core/designsystem/Reorder.kt`（没加依赖）。在真机上试删除时，撤销要在同一条命令里点，人看截图再点会超过提示的显示时间。
- **大辞林第四版**（`tools/dicts/daijirin4.py`）：和新明解8 同一种 Yomitan 写法，复用 `shinmeikai8.to_html`。注意：
  - 分段编号是图片：`一-fill.svg` 是黑框（词性一级），`一-default.svg` 是白框（其下的义项组），在 `gaiji/daijirin4.tsv` 里换成 App 画的 `box-b` / `box-w`；其余带 title 的图片是标签（文、季、漢、呉、可能…），转成「［文］」；`gaiji/` 里无 title 的（生僻字、变体假名、数学记号）保留图片。
  - 汉字条目（「亜」）的音读、训读列表（`漢字音G`）在 `見出部` 里，整个 `見出部` 删掉前要先取出来放到正文开头。
  - 词头里的链接（「—弁当」指向アート）随 `見出部` 一起去掉，正文链接全部保留。
  - 用户在设置里保存过辞书顺序时，新加的辞书排在最后，需要用户自己调整。
- **日中辞书**（`tools/dicts/shogakukan_jc3.py`）：数据包 meta 里 `lang = ja-zh`，App 据此区分日日和日中（`DictDb.chinese`）。转换脚本必须把中文标出来：译词 `<span class="gloss">`（一行中文释义只认它），其他中文（例句译文、复合词释义）`<span class="zh">`；两者都用简体中文字形显示。一行释义的规则见 design-spec「一行释义」：日文只取日日辞书、中文只取日中辞书，各取第一本启用且有内容的。`check_packs.py` 会统计常用词里有译词行的比例，低于有词条的 95% 就报错。
  - 小学館的插图在 `.mdd` 的 `jc03/` 下（`JGN*.bmp`），不收；外字在 `jc03/gaiji/`、`jc03/entity/`，是 BMP，生成时用 macOS 自带的 `sips` 转 PNG。
  - 新世纪（`tools/dicts/shinseiki_jc.py`）：图片都是内嵌的 base64，几乎全是日文释义和中文之间的分隔图，转换时去掉；中文第一句是译词（gloss），其余 zh。原文有 44 处把 `<sup>` 导出成 `<sub><</sub>sup>`，按规则还原；原文的中间点是拉丁「·」，词头和日文例句里换成「・」（中文里的「·」不动）。
  - 没有读音的条目（复合词）按写法对齐：JMdict 里只有一个词用这个写法时才连上（`build_dict.align`）。
- **JVM 测试用的辞书清单**在 `TestPacks.dictionaryIds`，加新辞书时要一起加，否则「扫描全部辞书」的测试不会覆盖它（大辞林加入时漏过一次）。
- **Anki 导出要用 Anki 实测**：`.apkg` 是 Anki 旧版（schema 11）集合，字段多、容易写错。改了 `AnkiPackage.kt` 后，用单独的虚拟环境装 Anki 官方库（`pip install anki`，不要装进 `tools/.venv`）跑 `python tools/tests/check_anki.py 文件.apkg`。JVM 测试会把样例包写到系统临时目录的 `jisho-test.apkg`。
- **系统文件选择器**：导出、导入、备份、恢复都走系统的保存/打开界面。平时自动化测试不去点它（会在用户手机上留下文件）；逻辑靠 JVM 和真机测试覆盖，用户要求验证时才在手机上实际走一遍。
- **导入 CSV 的编码**：Windows 版 Excel 存的「CSV」是 ANSI（中文系统 GBK、日文系统 Shift-JIS），`TextEncoding` 两种都试、按文字特征打分选一个；改打分规则后跑 `UserDataTest.ansiCsvIsReadInEitherCodePage`。
- **APK 体积**：上限 50 MB。其中 Kuromoji 词典约 13 MB、手写识别原生库约 7 MB、内置 core.db 约 9 MB。再加依赖前先算体积。
- **Kuromoji 打包**：两个 jar 都带 `META-INF/CONTRIBUTORS.md`，已在 `packaging` 里排除；release 包靠 `proguard-rules.pro` 保留 Kuromoji 的类。
- **手写模型**：ML Kit 的日语模型首次使用时从 Google 下载（约 20 MB），已下载后，App 每次启动后第一次打开手写面板，检查模型状态约 0.75 秒（真机实测；纯本地，不联网，时间花在 ML Kit 下载管理组件的一次性初始化；同一进程里再查只要几毫秒），第一次识别约 0.1 秒；`DevicePerformanceTest.handwritingModelLoadTimes` 会测这几个数。面板靠设置里记住的「模型已就绪」直接打开，检查在后台做（`HandwritingViewModel.checkModel`）。App 因此带有联网权限（ML Kit 自动加入），除此之外不联网。下载按钮由用户点，不要替用户点。
- **分词器实测**：对单个词，Kuromoji 和 Sudachi 的还原结果几乎一样。但遇到歧义词，它们只给一个答案，例如「いった」「読めない」，所以需要规则表补充候选。

## 版权与隐私

- 辞书数据有版权，仅供个人使用。
- 不要把用户的个人信息写进代码、提交说明或任何外部请求里。
