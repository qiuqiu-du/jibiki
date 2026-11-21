# jibiki 字引

一款只给自己用的离线 Android 日语词典：日→日释义为主，日→中为辅；只有**查词**和**收藏**两个功能。App 名称「辞書」。

- 搜索：假名、汉字、罗马音输入，实时候选；活用形还原（Kuromoji 和活用规则表两种方式）；手写输入（ML Kit）
- 词条页：多部辞书的释义、音调数字，以及 コトバンク、Weblio 网页页签
- 收藏：多个收藏夹、拖动排序；导出 CSV / Anki；整库备份和恢复
- 界面：中文、日文、英文；浅色、深色主题

设计见 [docs/design-spec.md](docs/design-spec.md)，架构和编码规范见 [Architecture.md](Architecture.md)，构建和测试命令见 [AGENTS.md](AGENTS.md)。

打包和安装：`scripts/deploy.sh`（数据包 → 测试 → APK → 手机），签名和分发见 [docs/release.md](docs/release.md)。

## 声明 / Disclaimer

- **本仓库不包含任何辞书数据。** 辞书原文件（`dictionaries/`）、下载的开放数据（`sources/`）和生成的数据包（`datapacks/`）都不在仓库里，也不会提供。
- `tools/` 下的脚本只是格式转换工具，只应用于你**合法持有**的辞书副本，转换结果仅限个人使用。各辞书的著作权归原出版社所有；本项目与任何出版社、辞书或网站没有关联，也未获其授权。
- [docs/moji-import.md](docs/moji-import.md) 只记录如何导出**自己账号里**的收藏，需要用户自己登录；它不是 MOJi辞書 的官方接口文档，接口随时可能变化。请遵守该服务的使用条款。
- 网页页签只是在 App 内打开 コトバンク、Weblio 的公开页面，内容版权归各网站所有。
- 开放数据 JMdict（EDRDG，CC BY-SA 4.0）、Kanjium（CC BY-SA 4.0）和 [Jiten](https://jiten.moe) 词频（CC BY-SA 4.0）按各自许可使用，安装包内置的 core.db 由它们生成，同样以 CC BY-SA 4.0 提供；Kuromoji、ML Kit 等依赖按各自许可使用。
- 本项目按现状提供，不作任何保证。如果你是权利人并认为某些内容不妥，请提 issue，我会尽快处理。

This repository contains **no dictionary data**. The scripts in `tools/` only convert copies you lawfully own, for personal use. All dictionaries are copyrighted by their publishers; this project is not affiliated with or endorsed by any publisher, dictionary or website. Provided as is, without warranty. Rights holders: please open an issue and it will be addressed promptly.
