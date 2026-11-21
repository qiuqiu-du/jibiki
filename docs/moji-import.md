# 从 MOJi辞書 导出收藏

最后更新：2026-11-20

MOJi 官方只有「导出 PDF」（多为会员功能），没有 CSV / Anki 导出。本文记录 2026-11-20 实际用过的办法：在已登录的 MOJi 网页版里，调用网页自己读取收藏用的接口，把收藏逐层读出来，转成本 App 的 CSV 再导入。

> 只读取自己的数据；请求之间间隔约 0.3 秒；账号由用户自己在浏览器里登录，登录凭证只在网页内使用，不写进任何文件。批量调用接口可能不符合 MOJi 的用户协议，接口随时可能改变。

## 步骤

1. 用浏览器打开 `https://www.mojidict.com/collection`，**用户自己登录**。
2. 在这个页面的开发者控制台（或自动化工具）里运行脚本：从 localStorage 取出登录信息，按下面的接口从根目录开始递归读取所有收藏夹。
3. 网页里把结果生成 TSV 文件下载到电脑（`~/Downloads/moji-favorites.tsv`，列：path、top、scope、type、spell、pron、added、moji_id、title）。
4. 电脑上去重、筛选、转成本 App 的 CSV（列：`folder, headword, reading, added, target`，UTF-8 带 BOM）。写法和读音能在 `core.db` 里对上的，直接填 `target = core:<JMdict 序号>`，其余留空交给 App 匹配。
5. `adb push` 到手机，在 App 里「收藏 → ⋮ → 导入收藏 → CSV（UTF-8）」导入。

## 接口

MOJi 后端是 Parse Server。云函数统一为：

```
POST https://api.mojidict.com/parse/functions/<函数名>
Content-Type: text/plain
Body（JSON）：业务参数 + 下面的公共字段
```

| 公共字段 | 来源 / 取值 |
| --- | --- |
| `_SessionToken` | localStorage 中 `Parse/<AppId>/currentUser` 的 `sessionToken`（登录凭证，不要外传） |
| `_InstallationId` | localStorage 中 `Parse/<AppId>/installationId`（**必须带**，缺了返回 `code: 100000002`） |
| `_ApplicationId` | 网页版公开的客户端 ID（从网页实际请求里读取） |
| `_ClientVersion` | `js4.3.1` |
| `g_os` / `g_ver` | `PCWeb` / 网页版本号（如 `4.23.5`） |

### `folder-fetchContentWithRelatives`：读一个收藏夹的一页

参数：`{ fid, count: 30, sortType: 0, pageIndex: 1 }`

- `fid`：收藏夹 id；根目录是 `ROOT#com.mojitec.mojidict#zh-CN_ja`。
- 返回 `result.result` 为条目数组，每条主要字段：
  - `targetType`：`1000` 收藏夹、`102` 单词、`103` 例句（另见少量其他类型）；
  - `targetId`：收藏夹 id（递归时作为下一层的 `fid`）或单词 id；
  - `title`：单词为「写法 | 读音 ③」；
  - `target`：单词的 `spell`（写法）、`pron`（读音）、`accent`、`excerpt`；收藏夹的 `title`、`wordsNum`；
  - `targetUserId`：收藏夹的所有者，和自己的 `objectId` 不同即为「收藏的别人共享的词单」；
  - `createdAt`：收藏时间（UTC）。

**翻页的坑**：返回里的 `size`、`totalPage` 恒为 0，不能用来判断结束；根目录翻到最后一页之后，服务器会一直重复返回最后一页。所以要按「本页不足 30 条」**或**「本页没有新的 id」停止，最后再按（收藏夹, 类型, id）去重。

### 其他看到的接口（导出用不到）

| 接口 | 用途 |
| --- | --- |
| `union-api`（云函数） | 批量调用多个云函数，如 `note-get` |
| `inbox-read`（云函数） | 消息已读 |
| `GET /app/mojidict/api/v1/word/detailInfo?wordId=` | 单词详情（释义、例句） |
| `GET /app/mojidict/api/v1/<wordId>/targets` | 某个单词被收藏在哪些收藏夹 |

## 2026-11-20 实测结果

- 263 次请求读完全部收藏：63 个收藏夹（自建 23 个），条目 10854 行，去重后 5482 条。
- 按用户选择只导入自建收藏夹、子收藏夹并入顶层、根目录的词放进「MOJi 未分类」：21 个收藏夹、2914 个词，词数与 MOJi 一致。
- 2852 个对上 JMdict 词条，11 个对上单本辞书条目，52 个（语法形式、短语、新词）只能按写法查询。
- 例句收藏（`103`）本 App 不支持，未导入。

原始导出文件含个人数据，只留在用户电脑上，不放进仓库。
