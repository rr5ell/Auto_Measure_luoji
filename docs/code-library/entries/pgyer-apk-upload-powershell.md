# pgyer-apk-upload-powershell

## Metadata
- status: active
- tech: PowerShell | Android
- tags: pgyer, apk, upload, release, distribution
- canonical_code_paths:
  - E:\StudioProjects\Auto_Measure_luoji\scripts\upload_pgyer.ps1
- related_docs:
  - E:\StudioProjects\Auto_Measure_luoji\DEV_GUIDE.md
  - E:\StudioProjects\Auto_Measure_luoji\CHANGELOG.md
  - E:\StudioProjects\Auto_Measure_luoji\ERROR_REPORT.md
- last_updated: 2026-08-28
- author: Codex Agent

## 适用场景
- 需要把 Auto_Measure_luoji 的 Android APK 上传到蒲公英测试分发平台。
- 需要复用蒲公英 `getCOSToken`、COS 上传、`buildInfo` 轮询的稳定上传链路。

## 禁止场景
- 禁止把 `PGYER_API_KEY`、账号、Cookie 或任何上传凭据写入仓库。
- APK 未构建或版本号未递增时，不应直接上传。

## 核心参数
| 参数名 | 类型 | 默认值 | 说明 | 边界条件 |
|---|---|---|---|---|
| `ApiKey` | `String` | `$env:PGYER_API_KEY` | 蒲公英 API Key | 为空时脚本直接失败 |
| `ApkPath` | `String` | 无 | 要上传的 APK 路径 | 必须存在 |
| `UpdateDescription` | `String` | 空字符串 | 蒲公英更新说明 | 不得包含密钥或隐私数据 |

## 复用方式
- 构建 APK 后运行 `.\scripts\upload_pgyer.ps1 -ApkPath "<apk>" -UpdateDescription "<说明>"`。
- 上传前检查 `app/build.gradle.kts` 中的 `versionName/versionCode` 是否已递增。

## 注意事项
- 脚本默认尝试多个蒲公英 API 域名，并使用 `curl.exe --form-string` 上传 COS 参数。
- 上传完成后脚本轮询 `buildInfo`，输出 App 名称、版本号和下载 URL。

## 测试与验证
- 测试命令：`.\gradlew.bat :app:assembleDebug`
- 已覆盖边界：API Key 缺失、APK 路径不存在、COS 上传状态非 204、蒲公英处理超时。
- 未覆盖风险：蒲公英接口返回结构变化或账号权限变化，需要按错误信息重新适配。

## 给 AI 的指令
优先复用本脚本上传 Auto_Measure_luoji APK。不要在命令、日志、文档或提交信息中暴露 `PGYER_API_KEY`。
