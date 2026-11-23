# 哔哔啵啵（Android）

> 非官方移植（unofficial port），与 bibibobo 原作者无隶属关系；合成算法按其 MIT 协议使用。

把 [hakihakii/bibibobo](https://github.com/hakihakii/bibibobo) 的合成算法移植成 Java，
包成一个带界面的 **Android 系统 TTS 引擎**：不联网、不需要任何常驻服务，离线合成。

## 界面

- **试听**：输入一句话，用当前预设直接合成播放（本地 AudioTrack，即时出声）。
- **发音预设**：每条预设 = 声线 + 语速 + 音高 + 句间停顿，可新建 / 编辑 / 删除 / 切换。
- 每条预设同时就是引擎的一个**音色**：引擎报给系统的音色名就是预设名。
- **全局朗读引擎**：卡片显示当前系统默认引擎；不是哔哔啵啵时，首次打开会弹一次
  「设为全局朗读引擎？」对话框，确认后跳到系统的文字转语音设置页。
  Android 不允许普通 App 直接改默认引擎（要 `WRITE_SECURE_SETTINGS`），所以只能跳设置。
  Intent 依次尝试 `com.android.settings.TTS_SETTINGS` → 无障碍设置 → 系统设置。
- 桌面图标是自适应图标（深绿底 + 薄荷色锯齿波），APK 约 136 KB。

编辑页参数：

| 参数 | 范围 | 说明 |
|---|---|---|
| 声线 | 大肥鱼 / 路墨 | 两套音阶与共振峰倍率 |
| 语速 | 每字 0.08–0.30 秒 | 越小越快；显示为相对 0.13s 的倍数 |
| 音高 | -6 ~ +6 半音 | 整体移调（问号/感叹号/省略号的语气仍然生效） |
| 句间停顿 | 0.05–0.60 秒 | 标点切句之间的静音 |

> 改名 = 改引擎音色名。别处已经绑定的旧音色，需要重新选一次。

## 缘起

突发奇想，bibibobo拿来当小说背景音会不会蛮有意思的，于是就让AI上了。又不想用termux在后台跑着，所以就有了做成系统TTS的想法。本项目纯AI无人工，且大概也不会再更新了。

## 安装

1. 从 Releases 下载 `bibibobo-tts.apk`，或者按下面的「重新构建」自己编一个。
2. 打开「哔哔啵啵」，点「用当前预设试听」确认出声；需要别的音色就新建/编辑预设。

## 使用

引擎和音色是两层：引擎是 manifest 里的 `TTS_SERVICE`，音色是 `onGetVoices` 报出来的
列表。本 APK 两层都注册了（`BibiTtsService` 的四个回调：`onGetVoices` /
`onGetDefaultVoiceNameFor` / `onLoadVoice` / `onIsValidVoiceName`）。

1. 设成全局首选引擎：设置 → 更多设置 → 无障碍 → 文字转语音输出 → 首选引擎 →「哔哔啵啵」。
   之后所有用系统朗读的 App 都会用它。
2. 支持单独指定朗读引擎的 App，也可以只在它自己的设置里选「哔哔啵啵」，不必改全局。
3. 有些 App 的音色列表要手动刷新/添加一次；音色名就是 App 里的预设名。

**自检**：主界面「检查引擎与音色」会列出系统认到的引擎和本引擎报出的音色数——
应该看到 2 个（或新建的预设数）。如果是 0，才是引擎侧的问题。

改预设名等于改引擎音色名，别处旧的绑定会失效，重选一次即可。

## 映射关系

| bibibobo.js | 这里 |
|---|---|
| `synth(text, {voice, step, gap})` | `BibiSynth.stream(text, voice, step, gap, transpose, sink)` |
| `VOICES.fish / lumo` | 预设的「声线」 |
| `step` | 预设「语速」；朗读时再除以 Android 的 `speechRate/100` |
| 44.1 kHz 单声道 | `callback.start(44100, ENCODING_PCM_16BIT, 1)` |

算法逐行对照移植（锯齿波 + polyBLEP、两级状态变量带通、按字哈希选音高口型、
问号/感叹号/省略号语气、tanh 软限幅）。单句输出与 JS 版**逐采样一致**，
分句拼接处最多相差 1 个采样（约 0.02 ms）。

## 重新构建

环境：JDK、`aapt` / `apksigner` / `zipalign`、`android.jar`（API 23）、R8 的 d8。

```bash
bash build.sh        # 产出 bibibobo-tts.apk
```

源码：

- `src/io/bibibobo/tts/BibiSynth.java` —— 合成算法
- `src/io/bibibobo/tts/BibiTtsService.java` —— 系统 TTS 引擎（预设 = 音色）
- `src/io/bibibobo/tts/MainActivity.java` —— 界面（试听 + 预设管理）
- `src/io/bibibobo/tts/Preset.java` / `PresetStore.java` —— 预设模型与存储

## 许可

合成算法来自 bibibobo（MIT, hakihakii / Lumoswitch），本包装同样 MIT。见 `LICENSE`
（同时保留原作者与本项目的版权行）。
