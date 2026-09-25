# 去你的广告

一款面向 Android 11 及以上版本的本地广告识别与自动跳过工具。应用通过系统辅助功能读取当前界面，在满足安全条件时执行明确的关闭或跳过动作；必要时使用本地 OCR 作为补充。项目不申请联网权限，识别、规则匹配和操作记录均在设备本地完成。

当前版本：`0.7.17-b7.17`（versionCode `37`）

## 主要特性

- 基于 Android `AccessibilityService` 的事件驱动识别，不使用 root、Shizuku、VPN 或 Hook。
- 内置 LTT 规则数据、GKD 选择器运行时和经审查的广告规则子集。
- 使用 Tesseract4Android 进行本地简体中文 OCR 兜底。
- 不申请 `INTERNET`、悬浮窗、忽略电池优化、全量应用查询或修改系统安全设置权限。
- 对支付、银行、权限、隐私、确认和奖励领取等敏感控件设置拒绝自动操作边界。
- 保留本机操作记录，并区分“已执行动作”与“已确认关闭”。

## 技术组成

| 组件 | 用途 |
| --- | --- |
| Java / Android SDK | 应用界面、辅助功能服务、规则调度和本地记录 |
| Kotlin/JVM | GKD 选择器解析与匹配模块 |
| LTT 规则数据 | 应用内广告与弹窗识别数据 |
| GKD selector/runtime | 结构化无障碍节点选择器和运行语义 |
| GKD_subscription v593 | 主规则来源；仅打包广告类别的审查子集 |
| nullptr-leo/gkd-rules | 补充的应用内广告规则子集 |
| Tesseract4Android 4.9.0 | 设备端 OCR |
| GitHub Actions | 源码校验、测试、Lint、签名构建和 Release 发布 |

完整来源、固定提交、哈希和许可状态见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。本项目与上述上游项目不存在官方隶属或背书关系。

## 工作方式与安全边界

应用只处理证据明确且唯一的广告关闭目标，不盲点屏幕坐标，不对普通页面通用执行返回操作。每次异步点击、手势和阻塞读取前后都会重新检查前台应用、窗口和用户设置；失败目标在当前应用会话内隔离，避免跨引擎或跨窗口重复触发。

“约 70% 覆盖”是历史规则覆盖估计，不是实测成功率或服务承诺。Android 和厂商的后台策略可能随时终止辅助功能服务；建议将应用保留在最近任务中，若服务失联，请重新打开应用并关闭后再开启辅助功能。

## 安装

1. 从 GitHub Releases 下载 APK 和 `SHA256SUMS.txt`。
2. 校验 APK 的 SHA-256。
3. 安装 APK，并在系统设置中手动启用“去你的广告”辅助功能服务。
4. 返回应用确认服务状态。

当前 B7.17 发布 APK：

- 文件：`Qunideguanggao-B7.17.apk`
- SHA-256：`4AD866BB8E83B527EB453ECBA33C7053E46EC6D3DB6BA213F6252A58B1B47159`
- 签名证书 SHA-256：`0FCAB69E9723D9F9802A10F0E507E8F2E958A1A8A99F2296E07A354EB75815CE`
- APK Signature Scheme：v2

请只从本仓库 Release 页面下载，并在安装前核对校验值。

## 构建

环境要求：JDK 17、Gradle 9.4.1、Android SDK Platform 37、Build Tools 36.0.0 和 Python 3.10+。

```bash
python3 tools/fetch_ocr_data.py
python3 tools/validate_project.py
python3 -m unittest discover -s tests -p 'test_*.py' -v
gradle :gkd-core:verifyGkdRuntime :gkd-core:verifyAndroidHost --no-daemon
gradle :app:lintRelease :app:assembleRelease --no-daemon
```

正式构建与 GitHub Release 的操作步骤见 [.github/RELEASING.md](.github/RELEASING.md)。

## 自动化

- `build-apk.yml`：在 push、pull request 和手动触发时运行源码校验、规则一致性检查、宿主测试、Release 构建和完整 Lint 门禁。
- `release.yml`：按版本标签构建已签名 APK，校验签名与体积，生成 SHA-256 文件，并创建 GitHub Release。

Lint 的 XML、TXT、HTML 和原始日志会先上传，再执行发布门禁；任务失败、报告缺失、签名不匹配或 APK 超过 50 MiB 时均不会发布。

## 项目结构

```text
app/                 Android 应用与内置规则资源
gkd-core/            GKD 选择器 JVM 模块
tests/               Python 与 Android API 宿主回归测试
tools/               规则生成、项目校验、OCR 数据和 Lint 报告工具
third_party/         上游源码、规则快照、许可与来源证明
design/              品牌图标源文件和页面布局说明
docs/                架构与版本说明
.github/workflows/   持续集成和发布流程
```

## 贡献与安全

提交修改前请阅读 [CONTRIBUTING.md](CONTRIBUTING.md)。安全问题请按 [SECURITY.md](SECURITY.md) 中的方式私下报告，不要在公开 Issue 中披露可利用细节。

## 许可

项目主体代码按 [GPL-3.0](LICENSE) 发布。第三方源码和规则数据分别受其自身许可或上游分发条件约束；这些条款不会被项目主许可证覆盖。详见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。
