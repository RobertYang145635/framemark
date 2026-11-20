# 更新分发渠道（2026-11-20 调研 + 实测）

这份文档回答两件事：**国内下载为什么慢**、以及**我们用什么办法把它变快**。
所有结论都带本机实测数据（2026-11-20，国内网络，同一份 `updates/latest.json` 与同一个
4 MB 资产分片），测得的数据会随时间/地区变化，但量级关系是稳定的。

## 1. 慢在哪

| 路径 | 清单（1.5 KB） | 资产（4 MB 分片） |
|---|---|---|
| 直连 `github.com` Releases | 1.7 s | **~18 KB/s**（302 到 `objects.githubusercontent.com` 后再慢） |
| 直连 `raw.githubusercontent.com` | 1.7 s | — |
| `ghfast.top/` | 2.4 s | **~249 KB/s** |
| `gh-proxy.org/` | 1.5 s | ~155 KB/s |
| `gh-proxy.com/` | 2.2 s | ~80 KB/s |
| `ghproxy.net/` | 7.3 s | — |
| `mirror.ghproxy.com/` | **12 s 超时（已失效）** | — |

结论：**清单文件怎么都能拉到，慢的是 Release 资产**——它走的是 GitHub 的对象存储域名，
在国内容易被限速甚至阻断。所以「检查更新」本身不慢，「下载安装包」才是瓶颈。

## 2. 别人怎么发更新（调研摘要）

- **前缀代理**（`gh-proxy.org` / `ghfast.top` / `gh-proxy.com`、`ghproxy.net`）：把原始
  GitHub 链接前面拼一个域名即可加速 Releases / Raw / Archive / API。免配置、免费，但
  稳定性不由我们控制（有站点已经失效），所以**必须做候选列表 + 失败换源**。
- **jsDelivr**：`cdn.jsdelivr.net/gh/<user>/<repo>@<ref>/<path>` 能加速仓库里的**文件**
  （适合 `latest.json`），但**不服务 Release 附件**，所以只能解决清单，解决不了安装包。
- **Gitee 镜像 + GitHub Action**：有现成 Action 把 GitHub Release（含附件）同步到 Gitee；
  国内直连快、可长期维护，但需要维护第二个仓库与 token，且 Gitee 对单个附件大小/流量有额度。
- **对象存储 + CDN**（阿里云 OSS / 腾讯 COS / 七牛 / Cloudflare R2）：最可控、最快，代价是
  费用与发布步骤；Sparkle/WinSparkle 这套 appcast 方案默认就是这么做的（清单里写死直链）。
- **应用内更新的通用做法**（Sparkle / WinSparkle / 各种 Electron 更新器）：启动时**静默检查
  一次**、有新版本弹一次窗、清单里带**校验和**（我们已经有 sha256）、支持「跳过这个版本」。

## 3. 我们的做法

1. **两段式**：先读 `updates/latest.json`（带平台资产直链 + sha256 + 大小），读不到再退回
   GitHub Releases API（只有 tag/说明，不下载）。
2. **清单里可以带镜像**：`"mirrors": ["https://cdn.example.cn/framemark"]`。以后挂到国内
   对象存储上，**只改这份 JSON**就能让所有已发布版本用上新地址，不用等用户升级。
3. **下载候选顺序**：清单镜像 → 内置加速前缀（按实测速度排：`ghfast.top` → `gh-proxy.org`
   → `gh-proxy.com` → `ghproxy.net`）→ 直连兜底。每个候选都校验 SHA-256，不符就换下一个。
4. **启动自动检查**：每次打开静默检查一次；比当前版本新就弹一次窗（标题 + 一句更新说明 +
   「下载并安装」/「跳过这个版本」）。跳过记录写在 `dataDir/updates/skipped.txt`，同一个版本
   不再打扰；下一个版本照常提示。
5. **版本比较**用清单里的整数 `versionCode` 与 `versionName`（`UpdateVersion`），与关于页
   共用同一份逻辑，两处不会给出不同答案。

## 4. 还可以做的（按性价比）

1. **把安装包放到国内对象存储**（推荐下一步）：OSS/COS + CDN，在 `latest.json` 的
   `mirrors` 里加一条即可。按 133 MB 的 APK 算，OSS 流量费用很低，但下载速度能到几 MB/s。
2. **Gitee 镜像 + Action**：适合不想付费用、又能接受额外维护的情况；注意附件大小限制。
3. **差分更新**（APK 用 `bsdiff`/`archive-patcher`，MSI 用增量补丁）：体积小一个数量级，
   但要维护版本矩阵，收益不如先换 CDN。
4. **CDN 预热 + 多地域探测**：发布后主动预热 CDN，客户端首次下载即可命中边缘节点。
