# 第三方组件与来源说明

本文件说明“去你的广告”中随源码或 APK 分发的第三方组件。项目主许可证不会替代这些组件各自的许可证、署名要求或上游分发条件。

## LTT 规则数据

- 内容：`app/src/main/assets/ltt/`
- 来源：用户提供的 `LiTiaotiao-main.zip`
- 规模：328 个包、795 条弹窗规则
- 许可：随项目保留的 GNU AGPL v3，见 `third_party/litiaotiao/LICENSE`
- 完整性：三份原始 JSON 使用冻结 SHA-256；`source-meta.json` 记录来源文件哈希和解析计数

这里只包含规则数据，不包含第三方 APK。

## GKD selector/runtime

- 原始选择器源码：`third_party/gkd-selector/`
- JVM 兼容生成源码：`gkd-core/src/main/kotlin/`
- 运行时参考：`third_party/gkd-runtime-reference/`
- 许可：对应目录中的 `LICENSE`
- 完整性：`third_party/gkd-selector/source-manifest.json` 映射每个原始文件与生成文件的 SHA-256

生成过程只移除 JS 专用绑定并降低当前 JVM 工具链不支持的 Kotlin 语法；选择器行为由真实解析语料测试覆盖。

## 主 GKD 订阅

- 项目：[Lin-arm/GKD_subscription](https://github.com/Lin-arm/GKD_subscription)
- 固定提交：`e9cded29ecc8ccc9919683a79a730826c6bb8cb0`
- 订阅版本：v593
- 本项目打包范围：只包含广告类别的审查子集
- 原始文件与说明：`third_party/gkd_subscription/`
- 来源记录：`third_party/gkd_subscription/source-provenance.json`

审查快照中没有发现标准开源许可证。上游 README 包含额外分发条件，其中包括“禁止在国内平台传播”和“仅供学习交流使用”。原文完整保留在 `UPSTREAM_README.md`；使用、修改或再分发前必须自行确认有权遵守并执行这些条件。

## 补充 GKD 订阅

- 项目：[nullptr-leo/gkd-rules](https://github.com/nullptr-leo/gkd-rules)
- 固定提交：`8b93cfe2bda7bfcba6d9847f992a177c35aff7c4`
- 许可：Apache License 2.0
- 本项目打包范围：11 个应用、12 个应用内广告规则组
- 排除项：全局规则、银行/支付目标和冲突组键
- 来源记录：`third_party/gkd_supplement/source-provenance.json`

完整许可证位于 `third_party/gkd_supplement/LICENSE`。

## Tesseract4Android 与语言模型

- 依赖：[Tesseract4Android 4.9.0](https://github.com/adaptech-cz/Tesseract4Android)
- 数据：[tessdata_fast](https://github.com/tesseract-ocr/tessdata_fast) 简体中文模型
- 使用方式：构建时下载并校验固定 SHA-256；运行时完全本地处理

模型不会在应用运行时联网下载，应用也不申请 `INTERNET` 权限。

## 无背书声明

“去你的广告”是独立项目，与 LTT、GKD、各规则订阅、Tesseract 或被适配应用的作者和组织不存在官方隶属、合作或背书关系。所有项目名称仅用于说明兼容性和来源。
