# 酸败自助补丁工具（直装版 · 纯本地离线）

给手机游戏 **《300大作战》**（官服 / 渠道服）用的直装 + 补丁一体工具，Java 实现。

> **纯本地离线版**：不申请 `INTERNET` / `ACCESS_NETWORK_STATE` 权限，不含在线补丁站点、
> WebView 页面、网址下载和任何用户统计。全部功能断网可用，不上传任何信息。

## 它解决什么

1. **直装**：把已安装的客户端补上共享 UID `lvshu.direct300`、用内置私钥重签，做成一份「直装包」。
   装上后本工具与游戏同 UID，可用普通 `File` API 直接读写游戏数据目录——**无需 root、无需 Shizuku**。
2. **补丁**：把本地补丁（zip / 单文件）铺进游戏热更目录，按文件名或 CAB 精确定位，装前自动备份，可回滚。

## 界面

底部四个页签：

| 页签 | 功能 |
|---|---|
| 直装 | 制作 / 安装直装包，授予文件权限，管理输出目录 |
| 补丁 | 导入 / 安装本地补丁，回滚，清理备份，启动游戏 |
| 文件 | 浏览游戏工作目录，手动放单个文件 |
| 设置 | 切换客户端、配置目录、看日志、直装状态诊断、关于 |

## 下载

- 现成 APK：[`dist/酸败自助补丁工具.apk`](dist/酸败自助补丁工具.apk)
- 详细使用步骤见 [使用说明.md](使用说明.md)

签名指纹：`5ABCF5F7 6AB90645 FB7F85FD 162FB73A C2200B31 17472475 DD1D8280 4A06DF0F`（SHA-256）

## 关键实现

- **共享 UID 直装**：`sharedUserId=lvshu.direct300`，且写入 manifest **第一个属性位**（位置靠后真机不认）。
  中和 `targetSandboxVersion` / `sharedUserMaxSdkVersion` 等阻断项见 `src/com/lvshu/build/Axml.java`、`AxmlInject.java`。
- **普通 File 直读**：与游戏同 UID 后直接 `listFiles()` 访问 `Android/data/<包名>/files`，不依赖游戏是否运行。
- **CAB 定位**：复用酸败补丁助手的共享资源索引 `缓存/资源索引/<包名>/index.tsv`，增量更新；扫描 `files/data` 与
  `files/flies`，只读资源头 8K 做 CAB 匹配，见 `ZipPatch.java`。
- **日志分流**：运行日志 `run.log`、安装日志 `diag.log`、崩溃日志 `crash.txt`，见 `DiagLog.java` / `Paths.java`。

## 私钥说明（重要）

`keys/` 与 `assets/` 下的 `sign_key.der` / `sign_cert.der` 是**直装重签名的核心私钥**，已随仓库公开。
`build.sh` 中签名口令为 `lvshu300`。

> 这把钥匙必须与游戏侧直装链保持一致。更换或丢失密钥会导致做出的直装包签名不匹配、共享 UID 失效。
> 因此本仓库**故意内置私钥**，以便任何人都能重建出签名一致的包——请勿修改密钥或口令。

包信息：`package=com.downm.fixing.direct`，`versionName=2.7`，`versionCode=15`。

## 自己编译

需要 Android SDK（platform 34、build-tools 34.0.0）与 JDK。脚本会寻找 `/opt/android-sdk` 与 `~/.gradle` 下的 apksig：

```bash
bash build.sh
```

产物为当前目录下的 `酸败自助补丁工具.apk`，签名指纹应与上面一致。

## 免责声明

本项目仅供学习与个人使用。补丁资源请自行准备，作者不提供、也不托管任何游戏补丁。
