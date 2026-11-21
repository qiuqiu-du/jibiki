# 辞書 App 架构与编码规范

> **给 AI 和开发者的指令**：写代码或重构之前，必须先遵守本规范。
> 坚持单向数据流（UDF）和分层隔离，不准跨层调用，不准把逻辑堆进 Activity 或 Composable。
> 本规范和《日语词典 App 设计明细文档》（`docs/` 目录下，唯一维护版本）一起使用：设计文档规定**做什么**，本文件规定**怎么写**。两者冲突时，以设计文档为准，并同时更新本文件。

---

## 0. 项目约束（所有规则的前提）

| 约束 | 含义 |
| --- | --- |
| 只给自己用 | 不发布、不分发；辞书数据包和 APK 都不外传 |
| 离线优先 | 只有「コトバンク / Weblio」网页页签和手写模型的首次下载会联网，没有自己的服务器和网络 API |
| 查询方向 | 只做**日→日**（第一版）和**日→中**（M5）。输入的是日语（假名、汉字、罗马音、手写）；不做中→日反查，也不做日→英 |
| 体积预算 | APK ≤ 30 MB；词库数据包合计 ≤ 90 MB；词库**不打包进 APK** |
| 性能预算 | 候选列表 ≤ 50 ms；词条页离线部分 ≤ 150 ms；冷启动到可输入 ≤ 800 ms |
| 目标设备 | 荣耀 PPG-AN00（Android 17 / API 37）；minSdk 29；targetSdk 跟随最新稳定版 |

---

## 1. 技术栈

| 领域 | 选用 | 备注 |
| --- | --- | --- |
| 语言与并发 | Kotlin + Coroutines + Flow | |
| UI | Jetpack Compose + Material 3 | 不使用 XML 布局 |
| 架构 | MVVM + 精简 Clean Architecture（UI → Domain → Data） | Domain 层只在业务复杂时才写 |
| 依赖注入 | 手写容器（`JishoApp` 里用 `by lazy` 创建，ViewModel 用 `viewModelFactory`） | 只有几个对象，不值得引入 Hilt 和注解处理（构建更快、APK 更小） |
| 本地存储 | 数据包：只读 SQLite，通过 `SqlDb` 接口访问（App 用 Android SQLite，测试用 JDBC）；`user.db`：同样直接用 SQLite，通过可写的 `WritableDb` 接口（`UserStore`）；设置：DataStore | 数据包是电脑上生成好的，用 Room 反而要迁就它的表结构校验；`user.db` 只有三张小表，不用 Room 省掉一个依赖和注解处理，且测试能直接用 JDBC 跑真 SQLite（M4 决定）；查询用查询键的 B 树前缀索引，不用 FTS5 |
| 压缩 | zstd-jni（带训练字典） | 释义正文按词条单独解压；固定 1.5.7-12，更新的版本要求 compileSdk 37 |
| 分词 / 形态还原 | Kuromoji + IPADIC（默认）、活用规则表（可切换） | 统一实现 `Lemmatizer` 接口；Sudachi 后期按需 |
| 手写识别 | Google ML Kit Digital Ink Recognition（日语模型） | 首次使用时下载，之后离线 |
| 网页页签 | Android WebView | 原样显示，不抓取网页内容 |
| 导航 | Navigation Compose（字符串路由，集中定义在 `navigation/AppNavHost.kt`） | 路由很少，暂不引入序列化插件 |
| 序列化 | Kotlinx Serialization（M4 起） | 只用于备份文件；活用规则表是 TSV，几行代码就能解析，不需要它 |
| 设置 | DataStore Preferences（`core/data/SettingsRepository`） | 目前只有还原器选择 |
| 测试 | JUnit 4 + kotlinx-coroutines-test + sqlite-jdbc（JVM）；AndroidX Test（真机） | 见第 8 节 |

**不引入**：Retrofit / OkHttp（没有 API）、Coil（没有网络图片；外字小图直接从数据包读取）、任何统计、广告、崩溃上报 SDK。

> **加依赖的规则**：每新增一个第三方库，都要在 PR / 提交说明里写清楚**用途**和**对 APK 体积的影响**（MB）。能用平台 API 或几十行代码解决的，不加库。

---

## 2. 目录结构

单个 Gradle 模块 `:app`，按包分层（不拆多模块，保持构建快）。电脑端的数据转换脚本放在仓库根目录的 `tools/`，不进 APK。

```text
dict/                                   # 仓库根目录
├── Architecture.md                     # 本文件
├── docs/design-spec.md                 # 设计明细文档（唯一维护版本）
├── dictionaries/                       # 原始辞书文件（.mdx/.mdd、Yomitan .zip、NHK .json），不提交到 git
├── tools/                              # 电脑端 Python 脚本：原始格式 → SQLite 数据包
│   ├── readers/                        # mdx_reader.py / yomitan_reader.py / nhk2016_reader.py，输出统一中间结构
│   ├── dicts/                          # 每部辞书一个适配器：词头解析、标签映射规则
│   ├── lib/                            # textnorm（查询键规范化）、markup（正文标记转换）、packdb（表结构、zstd）
│   ├── build_core.py                   # JMdict + 音调（NHK 2016 / 新明解8 / Kanjium）→ core.db
│   ├── build_dict.py                   # 大辞泉 / 明鏡 / 新明解 → dict_<id>.db
│   └── tests/check_packs.py            # 音调、对齐、标签、链接抽查
├── datapacks/                          # 生成的数据包（不提交到 git，用 adb push 到手机）
└── app/src/main/java/<包名>/
    ├── core/
    │   ├── designsystem/               # 主题、颜色、字体、间距，通用 Compose 组件
    │   ├── database/                   # SqlDb 接口、CoreDb / DictDb（只读数据包）、模型；user.db（M4）
    │   ├── datapack/                   # 数据包导入（选文件夹 → 复制 → 校验版本），实现 PackOpener
    │   ├── data/                       # DictionaryRepository：组合 core 和各辞书，处理输入
    │   ├── lemmatizer/                 # Lemmatizer 接口 + Kuromoji / 规则 两个实现
    │   ├── japanese/                   # 罗马音 ↔ 假名、全角半角、长音等纯函数
    │   └── common/                     # 协程调度器、AppResult、扩展函数
    ├── feature/
    │   ├── importpack/                 # 首次启动的导入页
    │   ├── search/                     # 查词首页、候选列表、手写面板
    │   ├── entry/                      # 词条页、辞书释义渲染、网页页签
    │   ├── favorites/                  # 收藏夹列表与详情、导出
    │   └── settings/                   # 辞书管理、分词器切换、备份恢复、外观
    │       （每个 feature 内部）
    │       ├── ui/                     # Route、Screen、局部组件、UiState、UiEvent
    │       ├── viewmodel/
    │       ├── domain/                 # UseCase 与纯 Kotlin 模型（可选）
    │       └── data/                   # Repository 接口与实现
    └── navigation/                     # NavHost 与路由定义
```

**归属规则**：
- 两个以上 feature 都要用的，放 `core/`；只有一个 feature 用的，放在那个 feature 里。
- `core/` 不准依赖 `feature/`；feature 之间不准互相依赖，跨 feature 跳转只通过 `navigation/`。

---

## 3. 分层与单向数据流

```text
[Compose UI] ──(用户事件)──▶ [ViewModel] ──▶ [UseCase（可选）] ──▶ [Repository] ──▶ [UserStore / 数据包 / Lemmatizer]
[Compose UI] ◀──(StateFlow)── [ViewModel] ◀──(Domain 模型 / Flow)── [Repository] ◀── [Entity]
```

### 3.1 UI 层
- Screen 只观察 `StateFlow<UiState>` 来渲染，用户操作通过 lambda 交给 ViewModel。
- 每个页面拆成两部分：
  - `XxxRoute`：有状态，负责拿到 ViewModel、收集状态、处理导航。
  - `XxxScreen`：无状态纯函数，只接收 `uiState` 和 lambda，并配 `@Preview`。
- Composable 里不准直接注入 Repository，也不准访问数据库、读文件或解压数据。

### 3.2 ViewModel
- 不持有 `Context`、`Activity`、`View`。
- 对外只暴露 `val uiState: StateFlow<UiState>`，内部用 `MutableStateFlow` 更新。
- 一次性事件（Toast、导航）用 `Channel` 加 `receiveAsFlow()` 发出。
- 输入框查词要用 `debounce` + `flatMapLatest`，新输入一到，就取消上一次查询。
- **页面切换要跟手**：导航动画统一 200 ms（`AppNavHost` 的 `TRANSITION_MS`），不用默认的 700 ms 淡入淡出。
- **键盘时机**：只在页面「完全显示」后弹出（`LifecycleResumeEffect`，侧滑返回松手前不会触发），且只在启动和从词条页返回时弹；离开搜索页去词条时立刻收起。输入框自己保存光标（`TextFieldValue`），重新显示时光标在末尾。

### 3.3 Data 层
- 数据库的行（游标）只在 Repository / `UserStore` 内部读取，交给上层的都是纯 Kotlin 数据类（`WordRef`、`Favorite`、`Folder` 等）。
- 所有 I/O（数据库、文件、解压、Kuromoji 加载）都在 `Dispatchers.IO` 或 `Dispatchers.Default` 上执行。调度器通过注入获得，不准直接写死 `Dispatchers.IO`，这样测试时可以替换。
- Repository 对外返回 `AppResult<T>` 或 `Flow<AppResult<T>>`，不准把异常抛到 UI 层。

### 3.4 查词引擎的专门规则
- **还原器**：`Lemmatizer` 接口只有一个方法，返回 `List<LemmaCandidate>`（原形、活用说明、来源）。Kuromoji 和规则表都实现这个接口；用哪个由设置决定，在 `JishoApp` 容器里创建。
- **数据访问**：数据包只通过 `SqlDb` 接口读取，不直接用 `SQLiteDatabase`，这样查询和排序逻辑能在电脑上测试。参数一律按文本绑定（Android 的 `rawQuery` 只支持字符串）：数字直接写进 SQL，不要绑定，否则 `?3 = 1` 这类比较会悄悄失效。
- **候选排序**：分两步。① 完全匹配：（假名输入时）常用且主读音就是输入的「通常写假名」词优先，其余按 `search_key.rank`；② 前缀匹配：只扫 `search_key` 一张表，按预先算好的 `rank`（= `entry.score` × 100 + 写法常用度）和键长排序，每个词条取最好的一个键。不要在前缀查询里联表或 GROUP BY（单个假名会匹配上万行，真机曾因此超过 50 ms）。改排序前后都要跑 `RankingTest`，看整体命中率，不要只看个别词。
- **候选显示**：同一列表里任何两行不能看起来一样（`Candidate.displayKey`）。假名词头附汉字（`kanjiNote`）；仍然相同的，释义一样就合并，不一样就加释义开头作提示（`hint`，由 `DictionaryRepository` 生成）。由 `DictionaryRepositoryTest.homophoneCandidatesAreDistinguishable` 保证。
- **统一校验**：任何还原器给出的候选，都必须经过 `LemmaValidator`，检查 JMdict 里是否存在、词性和活用类型是否一致。校验逻辑只写在这一处。
- **Kuromoji 懒加载**：Kuromoji 在 App 启动后于后台加载，不阻塞首屏；加载完成之前，先用规则表顶上。
- **活用规则是数据**：规则写在 `assets/deinflect_rules.tsv` 里（每行：去掉的词尾、换上的词尾、适用类型、结果类型、活用说明），不写死在代码中。新增一种边界情况，要同时加一条规则和一条 `lemma_cases.tsv` 测试用例；新的活用说明键要在 `core/designsystem/InflectionLabels.kt` 和 `strings.xml` 里加文字（`LemmatizerTest.everyReasonHasALabel` 会检查）。`@prefix` 行声明敬语前缀，`@raw` 行声明输入本身可以是哪些中间形（て形、た形、連用形）及其说明。
- **规则表是唯一的语法来源**：`KuromojiLemmatizer` 只给原形，不给说明；`CombinedLemmatizer` 只保留规则表也能从整个输入拆到的 Kuromoji 原形，并用那条规则路径的说明。不要在 Kuromoji 那边写词尾对照表。敬语框架只在去掉前缀的路径上成立，去掉前缀的读法带 `LemmaCandidate.dropsPrefix`。相关测试：`LemmatizerTest.ruleTableReachesEveryKuromojiReadingInTestSet`、`trailingJunkGivesNoLemma`、`everyLemmaRowHasLabels`、`honorificFrameNeedsPrefix`。
- **句中词语（逐词条）**：只在 A 模式、没有完全匹配也没有还原行时显示（`DictionaryRepository.search`）。`KuromojiLemmatizer.segment` 的切分规则：自立词开始新词；助动词、て／で、接尾词、形式名词并进前一个词；「動詞・非自立」「形容詞・非自立」（补助动词）单独成词；其他助词和符号不显示。每个词查 Kuromoji 标出的原形。完整流程见设计文档 4.1。
- **两种模式**：A（默认）= Kuromoji 结果 + 规则表全部结果，B = 只用规则表；`JishoApp` 根据设置提供当前还原器，`DictionaryRepository` 每次搜索时读取，切换设置立即生效。设置变化或 Kuromoji 加载完成时，`JishoApp.lemmatizerVersion` 变化，搜索页会重新查一次。
- **结果顺序**：有释义的完全匹配 → 还原出的原形（带 `Candidate.inflection`，只保留有释义的）→ 没有释义的完全匹配（只在有还原行时往后排）→ 前缀匹配 → 单本辞书词条。原形之间：不去前缀的在前 → 常用度 → 变形步数 → 并列时 Kuromoji 在前。
- **释义渲染**：数据包里存的是精简后的标记（不是原始 HTML），在 Compose 里渲染成 `AnnotatedString`；只有网页页签使用 WebView。

---

## 4. 状态与错误处理

每个页面都要明确定义自己的状态，**必须覆盖加载中、空状态、出错三种情况**：

```kotlin
sealed interface SearchUiState {
    data object Idle : SearchUiState                                  // 输入框为空，显示历史
    data object Loading : SearchUiState
    data class Results(val items: List<Candidate>) : SearchUiState
    data class Empty(val query: String) : SearchUiState               // 查不到：列表顶部「查「X」」，打开只有网页页签的词条页（EntryUiState.Unknown）
    data class Error(val message: String) : SearchUiState             // 例如数据包缺失或损坏
}
```

```kotlin
sealed interface AppResult<out T> {
    data class Success<T>(val data: T) : AppResult<T>
    data class Failure(val error: AppError) : AppResult<Nothing>
}
```

- `AppError` 用密封类列出所有已知错误：`DataPackMissing`、`DataPackCorrupted`、`DecodeFailed`、`LemmatizerNotReady`、`Io(cause)`。
- 数据包缺失或版本不匹配时，引导用户到设置页的「辞书管理」，不能让 App 崩溃。

---

## 5. 性能与体积规则

- 列表（`LazyColumn`）一律提供稳定的 `key`，`key` 用词条 id。
- 释义正文只在打开词条页时才解压，候选列表里不解压。
- 数据库查询必须走索引：上线前用 `EXPLAIN QUERY PLAN` 检查候选查询，不准出现全表扫描。
- Release 构建开启 R8 代码缩减和资源缩减；不在 APK 里放字体文件（日文用系统自带的 Noto Sans CJK JP，见第 6 节）。
- 每个里程碑结束时，记录一次 APK 大小、数据包大小、冷启动时间，写进设计文档的里程碑表格。

---

## 6. UI 与设计系统规范

### 6.1 原则
- **内容优先**：词典的主角是文字。界面要克制、留白充足，只用一种强调色，不用渐变，不加装饰性插图。
- **搜索永远在手边**：首页就是搜索框，打开 App 就能输入。
- **一屏一件事**：词条页从上往下依次是词头、音调、释义；次要信息默认折叠。

### 6.2 主题
- 使用 Material 3，并把所有颜色、字体、形状都定义在 `core/designsystem` 里。组件里**不准写死颜色值和字号**，只能引用主题里的值。
- 颜色：默认使用一套中性色加一种强调色（建议藍色系）；设置里可以改成**动态取色**（Android 12+ 跟随壁纸，手机已支持）。
- 浅色、深色两套主题都要有，默认跟随系统。
- 形状：卡片和面板统一使用 16dp 圆角，小组件统一使用 8dp 圆角。
- 间距：统一使用 4dp 的倍数，定义成 `Spacing` 常量（4 / 8 / 12 / 16 / 24 / 32）。

### 6.3 日文排版（重要）
- 所有日文文本都要加上 `localeList = LocaleList("ja")`。否则系统会使用 HONOR Sans 的**中文字形**（直、骨、角等字的写法与日文不同）；加上之后，会改用系统自带的 Noto Sans CJK JP。
- 释义段落使用日文按词组断行（Compose `LineBreak` 配合 `WordBreak.Phrase`），避免在词语中间换行。
- 词头字号要明显大于正文；音调数字（⓪①②）放在读音后面，用次要颜色显示。
- 字号跟随系统字体大小设置（使用 `sp`）；App 内不另设字号选项（2026-11-21 精简设置时决定）。

### 6.4 交互与动效
- 全面屏沉浸显示（edge-to-edge），支持预测性返回手势。
- 从候选列表进入词条页时，词头使用共享元素过渡（`SharedTransitionLayout`）。
- 动效时长统一放在设计系统里，不准随意写死毫秒数；系统开启「移除动画」时要跟着关闭。
- 收藏等关键操作配合轻触觉反馈。

### 6.5 无障碍
- 可点击区域不小于 48dp × 48dp。
- 图标按钮必须提供 `contentDescription`。
- 文字与背景的对比度不低于 4.5:1。

---

## 7. 命名与代码风格

| 对象 | 规则 | 例子 |
| --- | --- | --- |
| 页面 | `XxxRoute` / `XxxScreen` | `EntryRoute`、`EntryScreen` |
| 状态 / 事件 | `XxxUiState` / `XxxUiEvent` | `SearchUiState` |
| ViewModel | `XxxViewModel` | `FavoritesViewModel` |
| UseCase | 动词开头 + `UseCase`，只提供 `operator fun invoke` | `LookupWordUseCase` |
| Repository | 接口 `XxxRepository`，实现 `XxxRepositoryImpl`（或按来源命名，如 `UserStore`） | |
| 用户数据 | 纯 Kotlin 数据类，放在 `core/userdata/` | `Favorite`、`Folder` |
| 日语术语 | 代码里用英文：词头 `headword`、读音 `reading`、音调 `pitch`、原形 `lemma`、活用 `inflection`、辞书 `dictionary` | |

- 代码遵循 Kotlin 官方风格，用 ktlint 检查格式。
- **代码一律用英文**：类名、函数名、变量名、注释、日志、异常信息、测试名、提交说明都用英文。
- **文件名和目录名一律用英文**：小写，单词之间用 `-` 连接（例如 `design-spec.md`、`build_core.py` 这类 Python 脚本按 Python 惯例用 `_`）；Kotlin 源文件按类名用 PascalCase。
- **中文只出现在**和用户的对话，以及开发设计相关的文档里（`docs/`、`AGENTS.md`、`Architecture.md`）。
- **日文只作为数据出现**：辞书内容、测试用例里的词、界面上显示的日文文本。
- 界面文字一律放在 `res/values/strings.xml` 里，不写死在代码中。
- 公共函数和类必须写 KDoc，说明**为什么**这样做，不用复述代码在做什么。

---

## 8. 测试

**原则：尽量在界面之前测。** 逻辑在电脑上的 JVM 测试里直接读真实数据包（`datapacks/`），几秒跑完；真机测试只测性能；界面交互由用户在手机上确认。

| 层 | 位置与工具 | 命令 |
| --- | --- | --- |
| JVM 单元测试 | `app/src/test/`，JUnit 4；数据访问用 sqlite-jdbc 读 `datapacks/`（没生成数据包时自动跳过） | `./gradlew :app:testDebugUnitTest`（约 10 秒） |
| 真机测试 | `app/src/androidTest/`，AndroidX Test；需要先在 App 里导入数据包 | `./gradlew :app:connectedDebugAndroidTest` |
| 数据包抽查 | `tools/tests/check_packs.py` | 见 AGENTS.md |

**文件划分：每个组件一个测试文件**（包名和被测代码一致）：

| 测试文件 | 覆盖内容 |
| --- | --- |
| `core/japanese/JapaneseTextTest` | 查询键规范化（含与 Python 的 3000+ 条一致性向量）、罗马音转换 |
| `core/database/CoreDbTest` | 词条、写法、音调、前缀搜索、显示规则、JVM 上的查询耗时 |
| `core/database/DictDbTest` | 三部辞书全部词条解压、标签白名单、内部链接、与 JMdict 的对齐 |
| `core/database/RankingTest` | 排序测试集 `ranking_cases.tsv`（命中率 ≥ 95%）+ 全部常用汉字词按写法能排进前 3 |
| `core/designsystem/DesignSystemTest` | 音调格式、正文渲染（注音、段落、链接、坏标签），并抽样渲染真实词条 |
| `core/data/DictionaryRepositoryTest` | 罗马音和片假名输入、结果合并（完全匹配 → 原形 → 前缀）、歧义词、整句分词、词条组装 |
| `feature/FeatureViewModelTest` | 搜索和词条页的 UiState，链接跳转的解析 |
| `core/lemmatizer/LemmatizerTest` | 形态还原测试集 `lemma_cases.tsv`（565 条），A、B 两种模式都必须全部通过，并打印各类别准确率和第一名命中率；规则表格式、词性校验、敬语前缀、整句分词、每行候选都有活用说明 |
| `androidTest/DeviceRuntimeTest` | 在 Android 运行时上重跑查询键一致性向量、罗马音、正文渲染（抓 ICU 正则等平台差异），以及整个形态还原测试集（Kuromoji 从 APK 里读词典） |
| `androidTest/DevicePerformanceTest` | 真机上候选 p95 ≤ 50 ms（含活用形和整句）、词条 p95 ≤ 150 ms；记录 Kuromoji 加载时间和内存 |
| `androidTest/DeviceUiTest` | 所有 Compose 界面测试，一个文件、每类功能一节，用真实触摸事件、按状态判断：收藏夹拖动排序；设置页（选项写入设置、按钮文字完整不折行不带省略号、词条页按「默认展开」展开辞书、查词页按「自动弹出」决定是否聚焦）。设置写进临时 DataStore（`SettingsRepository` 可传入存储），不碰用户的设置和收藏 |

**规则：**
- 测试共用 `testing/TestPacks.kt`（打开数据包、读测试资源），不要在各文件里重复写。
- 修 bug 先补一个能复现的测试。
- 排序、形态还原这类「好坏程度」问题，用测试集看整体命中率，不靠逐词目测。
- 改 `tools/lib/textnorm.py` 后，要重新运行 `tools/tests/gen_search_key_vectors.py` 生成一致性向量。

---

## 9. 数据包规范

- 每个数据包都要在 `meta` 表里记录 `schema_version`、`kind`（core / dictionary）、`built_at`；辞书包还要记录 `dict_id`、`title`、`revision`、`body_format`。App 启动时检查 `schema_version` 和 `body_format`，不匹配就提示重新导入（`DataPackManager.isReady()` 发现过期的辞书包时回到导入页）。
- 表结构以 `tools/build_core.py`（core.db）和 `tools/lib/packdb.py`（辞书包）里的 SQL 为准，`CoreDb` / `DictDb` 的查询必须和它们一致。改了表结构要同时升 `schema_version` 和 `DataPackManager` 里的期望版本。
- **查询键规范化**（`tools/lib/textnorm.py` 的 `search_key`）：NFKC → 去掉 `・‐-=▽▼×` 等记号和空白 → 片假名转平假名。App 对用户输入必须做完全相同的处理，两边实现要一起改。
- **正文存储**：`dict_entry.body` = zstd（标题 + 换行 + 正文标记），使用同库 `zdict` 表里的字典解压。字典在打开辞书时加载一次。
- **正文标记**（body_format 1，只允许下列标签，`tools/tests/check_packs.py` 会检查）：
  - 块：`<p>`（可嵌套，渲染时开始、结束都当换行）、`<br>`
  - 行内：`<b>` `<i>` `<sup>` `<sub>` `<ruby>…<rt>…</rt></ruby>`
  - `<span class="pos|sense|ex|label|note|mark">`：词性、义项编号、例句、标签框、注释、记号
  - `<span class="box-b|box-w|num-c">标签</span>`：由 App 自己画的编号（`core/designsystem/Badges.kt`）。`box-b` 黑底方框、`box-w` 白底方框（标签是一～二十的汉字），`num-c` 实心圆圈（1～99）。不能画的地方（搜索提示等纯文本）退回成「［一］」「〔一〕」「(21)」。
  - `<a href="#123">` 跳到本辞书第 123 条；`<a href="?key">` 用 key 查词
  - `<img src="名称">`：外字图片，图片本身存在同一辞书包的 `media` 表里（schema 2 起）。App 按名称取出，作为一个字宽、一行高的行内图片显示，并染成正文颜色（深色模式也能看清）；取不到时显示 〓。
  - 明鏡的 `<ruby>` 注音保留在数据里，第一版渲染时隐藏 `<rt>`。
  - 标签（`span.label`：類語、使い方、語源、类语分类等）渲染为灰色〔〕括起，**字号和字重与正文相同**。不要缩小或加粗正文里的汉字：系统字体没有对应字重时会合成，看起来像另一个字形。其他 span 也不改字号。
  - **外字的处理规则**（`tools/lib/gaiji.py`）：能用文字准确表达的（分段编号、圆圈数字、普通符号）在 `tools/dicts/gaiji/<辞书>.tsv` 里逐条写明替换成什么、为什么；其余（生僻字异体、乐谱记号、图标）一律保留原图，不猜字。SVG 在电脑上用 `rsvg-convert` 转成 PNG，手机只解码 PNG 和 GIF。分段编号和圆圈义项编号不用 Unicode 近似字符，也不用原图，统一写成上面的编号标记，由 App 画（主题色；方框里的汉字按字体的设计框居中，数字按笔画重心水平居中，同类编号共用一条基线）。
  - 原辞书只在别处（例如明鏡每条开头的目录）出现的标题，转换时要补回正文：明鏡的类语段落开头补「類語」（`lib/markup.py` 的 `_label`）。
- 数据包由 `tools/` 下的脚本生成，**生成过程必须可重复**：同样的输入，得到同样的输出。
- `dictionaries/`、`sources/`、`datapacks/`、`tools/.venv/` 都要写进 `.gitignore`，辞书原文件和生成的数据包不提交、不外传。

---

## 10. 提交前自检清单

- [ ] 新功能是否放在 `feature/<name>/` 下，而不是堆在根目录？
- [ ] 页面是否拆成了 `Route` 和无状态的 `Screen`，并且 `Screen` 带 `@Preview`？
- [ ] 交给 UI 的是否都是纯 Kotlin 数据类，而不是游标或数据库行？
- [ ] 是否处理了加载中、空状态、出错三种状态？
- [ ] `LazyColumn` 是否提供了稳定的 `key`？
- [ ] 所有 I/O 和解压是否都不在主线程上？
- [ ] 日文文本是否设置了 `ja` 语言环境？颜色和字号是否都来自主题？
- [ ] 改了还原器或规则表后，测试集是否全部通过？
- [ ] 新加的依赖是否写明了用途和体积影响？
- [ ] 查询方向是否仍然只有日→日、日→中，没有引入其他方向？
- [ ] 新文件名和代码（含注释）是否全部是英文？
