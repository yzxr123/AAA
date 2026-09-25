# GitHub 发布说明

仓库包含两条自动化流程：

- `build-apk.yml`：每次提交或 Pull Request 自动校验源码、运行测试、执行 Release Lint 并生成 APK 构建附件。
- `release.yml`：推送版本标签后重复完成完整检查，并把 APK、SHA-256、签名报告和第三方说明发布到 GitHub Releases。

## 正常发布

1. 确认 `main` 分支的 `Build 去你的广告 B7.17` 全部通过。
2. 创建与 `app/build.gradle` 中 `versionName` 一致的标签：`v0.7.17-b7.17`。
3. 推送标签，等待 `Publish GitHub Release` 完成。

```bash
git tag v0.7.17-b7.17
git push origin v0.7.17-b7.17
```

## 重新发布

在 Actions 页面手动运行 `Publish GitHub Release`，填写 `v0.7.17-b7.17`，并仅在确认要删除原 Release 附件时启用 `replace_existing`。工作流会删除同名 Release 后重建，但不会删除 Git 标签。

## 发布门禁

测试失败、Lint 失败、报告缺失、APK 签名验证失败或 APK 超过 50 MiB 时不会发布。成功的 Release 包含：

- `Qunideguanggao-B7.17.apk`
- `SHA256SUMS.txt`
- `apksigner-report.txt`
- `THIRD_PARTY_NOTICES.md`
