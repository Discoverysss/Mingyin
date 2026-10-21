# 明音（Mingyin）Android 音准应用

通过手机麦克风实时检测声音音高，展示音名和走势，并支持录音回放及曲线定位。当前是 Android 原生预览版。

## 功能

- **音高聚焦**：展示音名、频率、音分偏差及近期音高走势。
- **音高轨迹**：实时绘制音高曲线，并查看已记录的轨迹。
- **录音回放**：录制并管理音频，播放时通过曲线定位到对应时间。
- **明暗主题**：深海蓝暗色、薰衣草雾亮色；偏好会保存。
- 桌面应用名称为“明音”。

## 技术与数据

- Java 原生 Android 单 Activity，Canvas 自绘界面。
- `AudioRecord` 以 44.1 kHz 单声道 PCM 采集，YIN 算法估计基频。
- 监听轨迹与录音索引保存在应用本地；录音 WAV 保存在应用私有存储，不上传服务器。
- `applicationId` 保持 `com.example.voicepitch`，以便对已安装的旧版应用进行原位更新。

## 构建

需要 JDK 17 和 Android SDK Platform 35，最低 Android API 26。

```powershell
.\gradlew.bat assembleDebug --no-daemon --console plain
```

输出：`app/build/outputs/apk/debug/app-debug.apk`。安装时需先在设备上授权麦克风权限。

## 状态

项目历程和交接摘要见资料库项目目录中的 `PROJECT_HANDOFF.md`。代码以 GitHub `Discoverysss/Mingyin` 的 `main` 分支为远端版本源；只有核验远端提交号与本地一致后，才标记为已备份。
