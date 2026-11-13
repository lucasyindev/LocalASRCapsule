# 本地语音 (OnePlus 15R 纯离线语音输入)

> 面向 OnePlus 15R 的纯普通 App 级（免 Root）本地离线语音输入工具：单击机身左侧独立键启停录音，端侧 SenseVoice 推理完毕后直接落入当前输入光标，不打断键盘，不遮挡屏幕。

---

## 核心特性

1. **一加左侧独立键物理开关（亮屏护盾）**
   - 仅在屏幕点亮且已解锁时响应左侧独立键 (`KEYCODE_781`) 单击；熄屏、锁屏、音量条或电源键操作一律自动屏蔽。
   - **按一次开录 -> 再按一次停止并识别填字**；若唤醒后未开口再按一次即直接静默关闭。
2. **「侧键小药丸」四态轻提示**
   - 紧贴左侧物理键边缘显示 `26 × 60dp` 半透明深色小药丸（`FLAG_NOT_TOUCHABLE` 触摸 100% 穿透）。
   - 四种极简状态：**录音中**（呼吸红点 + 3 根实时声波）、**识别中**（3 点跳动）、**已填字**（绿勾 ✓）、**未听清/异常**（琥珀点）。
3. **原位光标直填（保留软键盘与光标）**
   - 第一顺位通过系统 `AccessibilityInputConnection.commitText` 直接在微信、笔记等当前输入光标处落字；
   - 辅助以多通道无障碍节点注入及 5 秒焦点延迟补射，绝不主动收起键盘或夺走焦点。
4. **双进程阅后即焚 SenseVoice**
   - 主进程轻量常驻；推理子进程 `:asr_worker` 按需加载 SenseVoice (`INT8` / `FP16` 可一键切换)，推理结束空闲 800ms 自动释放进程归还内存。
5. **极简三卡片工具主页**
   - 打开「本地语音」仅保留：**运行状态**、**语音模型**（支持 `INT8` / `FP16` 切换、应用内直接下载、本地文件导入与 GitHub 页面跳转）、**震动反馈**。

---

## 环境要求

- **JDK 17**（与工程 `jvmTarget` / `sourceCompatibility` 一致）
- **Android SDK**：`compileSdk` / `targetSdk` 35，`minSdk` 26
- 仓库已包含 Gradle Wrapper（`gradlew` 与 `gradle/wrapper`），无需仓库外自定义构建脚本
- 安装到真机还需 **Android SDK Platform-Tools**（`adb` 在 `PATH` 中）

模型权重不在本仓库中。首次使用请在应用内下载，或自行导入 SenseVoice 模型文件与 `tokens.txt`。

---

## 权限说明

应用运行需要：

- **麦克风**：采集 16kHz 单声道语音
- **无障碍服务**：监听左侧独立键，并把识别结果写入当前输入光标
- **网络**：仅用于在应用内下载官方模型与字典；识别过程在端侧离线完成
- **震动**：可选的按键反馈（可在主页关闭）

请在系统设置中开启「本地语音服务」无障碍开关，并授予麦克风权限后再使用侧键录音。

---

## 源码目录结构

```text
app/src/main/
├── AndroidManifest.xml
├── aidl/com/lucas/asrcapsule/aidl/
│   ├── IASRService.aidl
│   └── IASRCallback.aidl
├── res/
│   ├── drawable/                             // 极简卡片、按钮与启动图标矢量资源
│   ├── layout/activity_main.xml              // 三卡片工具主界面
│   ├── values/{colors,strings,themes}.xml
│   └── xml/voice_accessibility_service_config.xml
└── java/com/lucas/asrcapsule/
    ├── MainActivity.kt                       // 主界面（状态检测、模型切换/下载/导入、震动开关）
    ├── audio/
    │   ├── AudioCaptureManager.kt            // 16kHz 单声道极速采集
    │   ├── PcmUtils.kt                       // PCM 16-bit 转 FloatArray 与临时文件写入
    │   └── VadDetector.kt                    // 20ms 帧能量 VAD（含前 120ms 按键杂音屏蔽窗）
    ├── model/
    │   ├── CapsuleState.kt                   // 会话生命周期状态枚举
    │   └── ModelManager.kt                   // SenseVoice INT8 / FP16 模型管理与下载地址
    ├── overlay/
    │   ├── ArtifactOverlayManager.kt         // 侧键小药丸 WindowManager 生命周期控制
    │   ├── AuraTokens.kt                     // 小药丸语义色与会话计时常数
    │   └── LeftKeyCursorView.kt              // 侧键小药丸四态自绘 View
    ├── service/
    │   ├── AsrWorkerService.kt               // :asr_worker 独立推理子进程（阅后即焚）
    │   └── VoiceAccessibilityService.kt      // 主进程无障碍服务（按键监听、亮屏守卫、光标直填）
    ├── session/
    │   └── SessionCoordinator.kt             // 单一会话状态机与标点规整
    └── util/
        ├── PreferenceHelper.kt               // 模型选择、自定义路径与震动开关持久化
        └── VibrationHelper.kt                // 线性马达震感与机械微音反馈
```

---

## 编译与安装

在仓库根目录执行：

```bash
./gradlew assembleDebug
adb install -r -d app/build/outputs/apk/debug/app-debug.apk
```

也可以使用仓库内的安装脚本（自动检测设备；若尚无 APK 则先调用上方同一套 `./gradlew assembleDebug`）：

```bash
./install-to-device.sh
```

安装后打开「本地语音」，授予麦克风权限并开启无障碍服务，再在主页「语音模型」中下载或导入模型即可使用。
