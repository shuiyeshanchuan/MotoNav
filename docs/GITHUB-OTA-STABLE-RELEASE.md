# MotoNav App 内一键 OTA 发布

Android App 会请求 GitHub API 的 MotoNav Release 列表，找到最新一个**非草稿、非预发布**且包含 `motonav-ota-stable.json` 的 Release。它只下载该 Release 内、索引精确列出的 1.75C manifest 与 `*_app_0x10000.bin`。圆屏最终仍会在写入前校验 RSA 签名和 SHA-256。

## 每次发布稳定版

1. 在安全的本机使用私钥构建并验签 1.75C 产物：

   ```powershell
   ./firmware/build.ps1 -Board 175c -CliPath 'D:\Configrition\arduino-cli_1.5.2-rc.1_Windows_64bit\arduino-cli.exe' -ConfigFile '.\.tools\arduino-cli.yaml' -OtaPrivateKey 'D:\Configrition\MotoNav-ota-private.pem'
   ```

2. 为刚生成的 manifest 创建发布索引：

   ```powershell
   ./firmware/make-github-ota-index.ps1 -ManifestPath '.\firmware\dist\175c\panel0\MotoNav_universal_1.75C_v<版本>_panel0_manifest.json'
   ```

3. 在 GitHub 创建一个新的正式 Release，例如 tag `firmware-v<版本>`。不能标记为 **pre-release**，并填写用户可读的更新说明。
4. 向该 Release 上传以下三个文件：

   - `motonav-ota-stable.json`
   - `MotoNav_universal_1.75C_v<版本>_panel0_manifest.json`
   - `MotoNav_universal_1.75C_v<版本>_panel0_app_0x10000.bin`

`*_full_0x0.bin` 可作为 USB 救援附件一并上传，但 App 不会下载它。

## 安全边界

- `MotoNav-ota-private.pem` 绝不能上传到 GitHub、Release 或源码压缩包。
- 只发布由 `sign-ota.ps1` 签过且 `verify-ota.ps1` 通过的 manifest。
- App 会检查下载文件、板型、分区、镜像大小和 SHA-256；圆屏会再次检查 RSA 签名、SHA-256、版本和 OTA 电源条件。
- App 不会选择或安装比圆屏数字版本更低的固件。
