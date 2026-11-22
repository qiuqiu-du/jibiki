# 打包、签名与分发

最后更新：2026-11-22

## 一条龙脚本

`scripts/deploy.sh` 把部署相关的步骤串在一起，默认（不带参数）执行 `data check test apk`，不碰手机：

| 步骤 | 做什么 | 需要 |
| --- | --- | --- |
| `data` | 从 `dictionaries/` 生成 `datapacks/`（core.db + 各辞书，约 6 分钟）；缺原文件的辞书跳过 | `tools/.venv` |
| `check` | 数据包抽查（`tools/tests/check_packs.py`） | 数据包 |
| `test` | 电脑上的全部测试；没有数据包时，需要数据包的测试自动跳过 | JDK 17 |
| `apk` | 打 release 包，输出 `dist/jisho-<版本>.apk` 和 `.sha256`，并显示签名证书 | JDK 17、Android SDK |
| `install` | 把 `dist/` 里最新的包装到 USB 连着的手机，保留 App 数据 | adb |
| `push` | 把数据包复制到手机的 `Download/jisho/`，再在 App 里选这个文件夹导入 | adb |

```bash
scripts/deploy.sh                  # 数据包 + 检查 + 测试 + APK
scripts/deploy.sh apk install      # 只重打 APK 并装到手机
scripts/deploy.sh push             # 只把数据包送到手机
```

版本号：`versionCode` 取 git 提交数，每次提交后打的包都能覆盖安装旧包；`versionName` 默认是 `0.1.<提交数>`，也可以用 `-PversionName=0.2.0` 指定。

## 签名

### 现在的限制

安卓只允许**同一把钥匙签名**的新版本覆盖旧版本。之前 release 包借用的是这台 Mac 的调试钥匙（`~/.android/debug.keystore`）：

- 换一台电脑或以后在 CI 上打包，钥匙就不同，朋友手机上装不了更新，只能卸载重装，收藏会丢（除非先备份）；
- 调试钥匙谁都能生成同名的，证书是「Android Debug」，不适合给别人装；
- 这台 Mac 重装系统、调试钥匙丢了，以后的包就再也覆盖不了已经装出去的 App。

### 现在的做法：用调试钥匙（2026-11-22 决定）

项目只给自己和朋友用，release 包继续用这台 Mac 的调试钥匙签名（`~/.android/debug.keystore`，密码是公开的默认值 `android`）。好处是已经装出去的 App 都能直接覆盖更新，不用卸载重装。代价：

- **钥匙文件就是唯一的秘密**：谁拿到它，谁就能签出能覆盖这个 App 的「更新」。只放在这台 Mac 和 GitHub Secrets（`DEBUG_KEYSTORE_BASE64`）里，不要发给别人。
- **一定要另外备份这个文件**（密码管理器或加密网盘）。GitHub Secrets 只能写不能读，不算备份；Mac 重装后文件丢了，以后的包就再也覆盖不了已装的 App。
- 证书名是「Android Debug」，不能上架 Google Play（本项目也不上架）。

证书指纹（SHA-256）是 `57:F4:A0:91:…:32:ED:87`，写在 `.github/workflows/release.yml` 里，自动发布时核对。

### 以后想换成自己的发布钥匙

生成一把只属于这个项目的钥匙，本机和以后的 CI 用同一把。`app/build.gradle.kts` 已经支持：有钥匙就用它签，没有就退回调试钥匙（自己手机上照常能用）；加 `-PrequireReleaseSigning=true` 时必须有钥匙，否则直接失败（给以后的 CI 用）。

1. **生成钥匙**（只做一次，密码自己定，不要告诉任何人，包括 AI 助手）：
   ```bash
   keytool -genkeypair -v -keystore ~/jisho-release.jks -alias jisho -keyalg RSA -keysize 4096 -validity 36500
   ```
   `keytool` 在 JDK 里：`/opt/homebrew/opt/openjdk@17/bin/keytool`。有效期填 100 年，免得过期。
2. **备份**：把 `jisho-release.jks` 和密码一起存进密码管理器或离线位置。钥匙一丢，已装出去的 App 就再也收不到更新。
3. **本机配置**：在仓库根目录新建 `keystore.properties`（已在 `.gitignore` 里，不会提交）：
   ```properties
   storeFile=/Users/你的用户名/jisho-release.jks
   storePassword=你的密码
   keyAlias=jisho
   keyPassword=你的密码
   ```
   之后 `scripts/deploy.sh apk` 打出的包，签名证书显示的就是你自己的名字。
4. **一次性迁移**：手机上现在装的是调试钥匙签的版本。换成发布钥匙后，先在 App 里 设置 → 备份 导出收藏，卸载，装新包，再恢复备份。之后的更新都能直接覆盖。

### 其他方案（没有采用）

- **Google Play 内部测试**：最多 100 人，Play 自动推送更新，签名由 Google 托管。要 25 美元开发者账号，应用要过 Play 的审核和隐私表单，对只给朋友用的项目太重。
- **Firebase App Distribution**：免费，邮件邀请测试者，有更新提醒。签名还是要自己的发布钥匙，多一个 Google 服务账号要管。

## 分发给朋友

**APK**：打版本标签后自动发布到 GitHub 仓库的 [Releases](https://github.com/qiuqiu-du/jibiki/releases) 页面（见下文「GitHub 自动发布」），把链接发给朋友即可。朋友有两种方式：
- 直接下载安装（要允许「安装未知来源应用」）；
- 如果放在 GitHub Releases，可以装 [Obtainium](https://github.com/ImranR98/Obtainium) 填仓库地址，以后有新 Release 会提醒更新。

APK 里没有任何辞书内容，放在公开仓库的 Release 里没有问题。

**数据包要另外考虑**：数据包是从有版权的辞书转换出来的（见 README 的声明），只能自己合法持有、自己使用。

- 不要把数据包放进 GitHub（仓库、Release 都不行），也不要公开分享链接。
- 给朋友用时，最稳妥的做法是：朋友自己有辞书文件，用 `scripts/deploy.sh data` 自己生成。
- 你直接把自己生成的数据包发给朋友，属于把辞书内容转给别人，是否允许取决于你和他们的授权情况，需要你自己判断，项目和脚本不提供这条路。

## GitHub 自动发布（`.github/workflows/release.yml`）

**发布一个版本**：确认改动都已提交并推送，然后打版本标签并推送：

```bash
git tag v0.2.0
git push origin v0.2.0
```

GitHub 的服务器（约 10 分钟）依次：取完整提交历史 → 装 JDK 17、Android SDK、Python → 还原调试钥匙 → 下载开放数据 → 生成 core.db → 跑电脑端测试 → 打包（显示版本取标签名，`v0.2.0` → `0.2.0`；内部版本号仍是提交数）→ 核对签名证书指纹 → 在 Releases 页面发布 APK 和 `.sha256`，发布说明列出上一个版本标签以来的提交。任何一步失败都不会发布，GitHub 会发邮件提醒。

**只试打包不发布**：GitHub 网页 Actions → Release → Run workflow。打出的 APK 在那次运行的 Artifacts 里（保留 90 天，要登录 GitHub 下载）。

**开放数据**：JMdict、Kanjium、Jiten 词频放在仓库的 [data-sources](https://github.com/qiuqiu-du/jibiki/releases/tag/data-sources) Release 里（不是 App 版本，已标为预发布），打包时从这里下载，保证和本机测试时用的是同一份。更新 `sources/` 里的文件后，替换附件：

```bash
gh release upload data-sources sources/jmdict/JMdict_e.gz sources/kanjium/accents.txt sources/jiten/frequency_list_global.csv --clobber
```

服务器上没有辞书原文件，所以不生成数据包，需要数据包的测试自动跳过。
