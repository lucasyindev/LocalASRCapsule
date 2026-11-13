#!/usr/bin/env bash
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$PROJECT_DIR"

GRADLEW="$PROJECT_DIR/gradlew"
APK_PATH="$PROJECT_DIR/app/build/outputs/apk/debug/app-debug.apk"

echo "=========================================="
echo " 本地离线语音胶囊 (Local ASR Capsule) 快速部署"
echo "=========================================="

if [ ! -f "$GRADLEW" ]; then
    echo "未找到仓库内的 Gradle Wrapper: $GRADLEW" >&2
    exit 1
fi
if [ ! -x "$GRADLEW" ]; then
    chmod +x "$GRADLEW"
fi

if ! command -v adb >/dev/null 2>&1; then
    echo "未在 PATH 中找到 adb。请安装 Android SDK Platform-Tools，并确保 adb 可用。" >&2
    exit 1
fi

# 1. 检查 ADB 设备连接
DEVICE_COUNT=$(adb devices | grep -v "List" | (grep "device$" || true) | wc -l | tr -d ' ')

if [ "$DEVICE_COUNT" -eq 0 ]; then
    echo "⚠️ 未检测到已授权的 Android 设备！请检查："
    echo "  1. 手机是否已用 Type-C 线连接到电脑；"
    echo "  2. 手机是否开启【设置 -> 开发者选项 -> USB 调试】；"
    echo "  3. 手机屏幕上若弹出【允许 USB 调试吗？】，请勾选【始终允许】并点击【允许】。"
    echo ""
    echo "当前 ADB 状态："
    adb devices -l
    exit 1
fi

echo "✓ 检测到已连接设备："
adb devices -l | grep "device " || true

if [ ! -f "$APK_PATH" ]; then
    echo "⚠️ 未找到已编译的 APK，正在使用仓库内 Gradle Wrapper 编译..."
    "$GRADLEW" assembleDebug
fi

echo ""
echo "正在通过 adb 安装 APK 到手机..."
adb install -r -d "$APK_PATH"

echo "✓ 安装成功！"
echo ""
echo "【下一步快捷指引】"
echo "1. 打开手机上的【本地语音】应用；"
echo "2. 授予麦克风权限，并开启【无障碍服务】（本地语音服务）；"
echo "3. 在主页「语音模型」卡片中下载 INT8/FP16 模型，或导入本地模型与 tokens.txt；"
echo "4. 屏幕点亮且已解锁时，单击一加机身左侧独立键即可启停录音并填字。"
