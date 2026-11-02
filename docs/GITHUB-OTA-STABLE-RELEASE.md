# MotoNav App 内一键 OTA 发布

Android App 请求 GitHub Release 列表。普通“检查稳定版更新”只选择非草稿、非预发布条目中的 `motonav-ota-stable.json`，并排除诊断资产；用户显式点击“检查测试版更新”后才会读取 `motonav-ota-diagnostic.json`。索引精确列出 1.75C manifest 和 `*_app_0x10000.bin`，圆屏写入前再次校验 RSA 与 SHA-256。

## 构建并固定发布提交

先提交本次源码，记录**实际构建所用的完整 40 位提交 SHA**，随后完成构建、APK 校验、固件签名与校验。发布时必须用 `--target <精确构建SHA>`，不能省略后依赖 GitHub 默认分支，也不能仅凭 Release 页面显示的发布时间判断 App 能否发现它。

```powershell
$releaseTag = 'motonav-v8.3.65'
$releaseBuildSha = (git rev-parse HEAD).Trim() # 在干净工作区、执行本轮构建前记录
$releaseAssets = '.\.tools\release-v8.3.65'

gh release create $releaseTag --repo shuiyeshanchuan/MotoNav --target $releaseBuildSha --title 'MotoNav v8.3.65 测试版' --notes-file '.\docs\RELEASE-8.3.65-175C-update-discovery.md'
gh release upload $releaseTag --repo shuiyeshanchuan/MotoNav "$releaseAssets\*"
```

以上为本轮示例，每轮必须替换 tag、构建 SHA、资产目录与发布说明；v8.3.65 必须使用本轮修复提交，不能沿用 v8.3.64 的 fa7d2de。若 tag 已存在，`--target` 不会自动纠正旧 tag；创建前确认 tag 最终解引用的 commit 就是构建 SHA，有偏差时先停止发布并修正引用。

## 稳定固件索引

1. 使用本机私钥构建并验签 1.75C 产物：

   ```powershell
   ./firmware/build.ps1 -Board 175c -CliPath 'D:\Configrition\arduino-cli_1.5.2-rc.1_Windows_64bit\arduino-cli.exe' -ConfigFile '.\.tools\arduino-cli.yaml' -OtaPrivateKey 'D:\Configrition\MotoNav-ota-private.pem'
   ```

2. 使用稳定 manifest 创建索引，`stable` 通道拒绝 `diagnostic=true`：

   ```powershell
   ./firmware/make-github-ota-index.ps1 -ManifestPath '.\firmware\dist\175c\panel0\MotoNav_universal_1.75C_v<版本>_panel0_manifest.json' -Channel stable
   ```

3. 创建非草稿、非预发布 Release，明确指定精确构建 SHA；上传 `motonav-ota-stable.json`、该索引列出的 manifest 和应用镜像。有 App 更新时一并上传 `app-release.apk`。

## 诊断测试固件索引

诊断固件继续保留 `diagnostic=true` 和未实测时的 `deviceTested=false`。使用独立索引，不得将测试 manifest 填入 stable 索引：

```powershell
./firmware/make-github-ota-index.ps1 -ManifestPath '.\firmware\dist\175c\panel0\MotoNav_universal_1.75C_v7.12.9-nav.12-mount-reference-imu-test_panel0-imu-diagnostic_manifest.json' -Channel diagnostic
```

上传 `motonav-ota-diagnostic.json`、对应 manifest、`*_app_0x10000.bin`；完整 `*_full_0x0.bin` 可作为 USB 救援附件上传，App 不会下载它。用户必须在 App 显式选择“检查测试版更新”，默认稳定检查仍不推荐该固件。

当前 App 自更新只发现非草稿、非预发布 Release，因此同时提供 APK 的测试版条目使用普通 Release，并在标题和说明中明确“测试版”；诊断固件由独立 diagnostic 索引隔离。不能靠关闭预发布标记把诊断包放入稳定通道。

## 发布后必做验证

必须使用与已安装 App 相同的**匿名 API 前 30 条列表**验证可见性，并核对 tag 指向构建提交、资产上传完成、大小和 SHA-256。使用仓库脚本：

```powershell
./scripts/verify-github-release.ps1 -Tag $releaseTag -ExpectedCommit $releaseBuildSha -RequireApp -AssetDirectory $releaseAssets
```

`ExpectedCommit` 必须是完整 40 位构建 SHA；包含 APK 时使用 `-RequireApp`，仅固件发布时省略它。`AssetDirectory` 只放本次上传的发布资产，脚本逐个比对远端大小与 SHA-256。脚本未通过前不能报告“App 已能发现更新”；修复发布引用后也要重新运行。

2026-11-02 的 v8.3.64 曾错误指向 `main` 旧提交 `6a18c8d`，匿名列表位于第 31 条，超过 App 查询范围。纠正为构建提交 `fa7d2de39ed4ca6769b50ed9a3ffed2a75b4da15`、发布目标 `175c-ota-one-click` 后，匿名列表首条恢复为 v8.3.64；所有 APK/固件资产不变，已安装者无需重下。

v8.3.65 还修复了客户端候选选择：跨系列比较返回 `0` 代表不可比较，不能用于认定同版本；只有版本字符串精确相同时才适用“稳定包优先”。发布验收除 API 可见性外，还需实际检查候选是否为预期的 nav.12，不能仅验证索引存在。

## 安全边界

- `MotoNav-ota-private.pem` 绝不能上传到 GitHub、Release 或源码压缩包。
- 只发布由 `sign-ota.ps1` 签过且 `verify-ota.ps1` 通过的 manifest。
- App 检查下载文件、板型、分区、镜像大小和 SHA-256；圆屏再次检查 RSA、SHA-256、版本和 OTA 电源条件。
- App 不选择或安装比圆屏数字版本更低的固件；测试检查不改变默认稳定通道。
