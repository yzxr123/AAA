# 贡献指南

感谢你改进“去你的广告”。项目把误触防护、离线运行和来源可追溯性放在规则数量之前。

## 开发环境

- JDK 17
- Gradle 9.4.1
- Android SDK Platform 37
- Android Build Tools 36.0.0
- Python 3.10+

首次构建前执行：

```bash
python3 tools/fetch_ocr_data.py
```

## 提交前检查

```bash
python3 tools/validate_project.py
python3 -m unittest discover -s tests -p 'test_*.py' -v
gradle :gkd-core:verifyGkdRuntime :gkd-core:verifyAndroidHost --no-daemon --console=plain
gradle :app:lintRelease :app:assembleRelease --no-daemon --console=plain
```

## 规则变更

- 提供目标应用包名、版本、可复现步骤和已脱敏的界面证据。
- 只匹配明确的广告关闭目标，不提交通用坐标、通用返回或模糊角落按钮规则。
- 不自动操作支付、银行、权限、隐私、确认或奖励领取控件。
- 更新规则后运行 `tools/build_gkd_bundle.py --check` 和 `tools/prepare_gkd_selector.py --check`。
- 上游规则必须记录来源提交、许可或分发条件以及内容哈希。

## 代码与文档

- 一个提交只解决一个清晰问题，提交说明使用祈使语气并描述原因。
- 产品行为变化必须同时更新 README、架构说明或 CHANGELOG 中的相关部分。
- 不提交 APK、AAB、构建目录、日志或 OCR 生成文件。
- 新增权限、后台行为或跨应用动作必须在 Pull Request 中单独解释风险和必要性。

## Pull Request

请写明变更目的、验证命令、结果、未覆盖的设备或系统版本，以及任何第三方来源变化。宿主测试不能替代真机验证；没有设备证据时请明确标注。
