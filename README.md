# KernelSU — Dipper (小米 8) 定制

本仓库为小米 8 / dipper 设备定制的 KernelSU 内核模块,核心改动是为 Android 10 + Linux 4.9 non-GKI 内核增加 `prctl` bootstrap,同时保留原有的 `reboot` fallback。

## 背景

Android 10 的 zygote 子进程受 seccomp 过滤器限制,任何 `reboot(LINUX_REBOOT_MAGIC1, LINUX_REBOOT_MAGIC2, 0, &fd)` 调用(即 syscall 142)会被杀死,导致 SukiSU/KernelSU 的传统 `reboot` hook 无法获取内核 fd。
本项目通过 LSM `task_prctl` 路径新增 `ksu_task_prctl`,使用一组独立的 magic number(`KSU_INSTALL_MAGIC1 = 0xDEADBEEF`、`KSU_INSTALL_MAGIC2 = 0xCAFEBABE`)在不影响其他 prctl 命令的前提下,完成 fd 的下发。

## 改动状态

### 已完成 — 内核端

- `kernel/hook/lsm_hook.c`:实现 `ksu_task_prctl` 并通过 `LSM_HOOK_INIT(task_prctl, ksu_task_prctl)` 注册
- `kernel/supercall/supercall.c` / `dispatch.c`:保留 `ksu_handle_sys_reboot` 作为 fallback
- 内核镜像必须包含符号:`ksu_task_prctl`、`ksu_install_fd`、`ksu_handle_sys_reboot`
- SUSFS、KPM 当前未开启

### 已完成 — 用户端

- `SukiSU-Ultra/userspace/ksud/src/ksucalls.rs`:
  - 新增 `try_prctl_bootstrap()` 优先调用 `prctl`
  - `try_reboot_bootstrap()` 仅在 `reboot_bootstrap_is_safe()`(通过 `prctl(PR_GET_SECCOMP)` 检测 seccomp 未启用)时回退
- 状态:`M userspace/ksud/src/ksucalls.rs`(待提交)

### 待办

- Mac 拉取 SukiSU-Ultra 仓库,构建 APK,`adb install -r` 部署
- 验证 seccomp 崩溃消失、`adb shell su -c id` 成功

## 工作流

### 何时需要重新打包 boot

仅在以下内容变动时:

- `/work/lawrun-13.1` 内核源码
- `KernelSU/kernel/`
- `fs/exec.c`、`fs/open.c`、`kernel/reboot.c` 等内核 hook
- 内核配置

### 仅 app 改动

只需重新构建 APK,无需重打 boot。

## 编译与部署

> 以下命令在 Docker 容器 `sukisu-builder` 内运行。
> Mac 端仅做 `git pull`、Gradle 构建、`adb install`。

### 内核编译

```bash
docker exec -it sukisu-builder bash

cd /work/lawrun-13.1

export ARCH=arm64
export SUBARCH=arm64
export CROSS_COMPILE=/work/gcc-aarch64-4.9/bin/aarch64-linux-android-
export CROSS_COMPILE_ARM32=/work/gcc-arm32-4.9/bin/arm-linux-androideabi-
export LOCALVERSION=

set -o pipefail

make O=out-sukisu -j$(nproc) \
  2>&1 | tee /work/sukisu-build.log

echo "BUILD_RC=$?"
```

### 验证产物

```bash
ls -lh out-sukisu/arch/arm64/boot/Image.gz-dtb

grep -E \
  "ksu_task_prctl|ksu_install_fd|ksu_handle_sys_reboot" \
  out-sukisu/System.map
```

### 打包 boot.img

```bash
cp out-sukisu/arch/arm64/boot/Image.gz-dtb \
  /work/Image-lawrun13-sukisu.gz-dtb

CMDLINE='console=ttyMSM0,115200n8 earlycon=msm_geni_serial,0xA84000 androidboot.hardware=qcom androidboot.console=ttyMSM0 video=vfb:640x400,bpp=32,memsize=3072000 msm_rtb.filter=0x237 ehci-hcd.park=3 lpm_levels.sleep_disabled=1 service_locator.enable=1 swiotlb=2048 androidboot.configfs=true loop.max_part=7 androidboot.usbcontroller=a600000.dwc3 buildvariant=user'

mkbootimg \
  --kernel /work/Image-lawrun13-sukisu.gz-dtb \
  --ramdisk /work/boot-unpacked/ramdisk \
  --cmdline "$CMDLINE" \
  --base 0x00000000 \
  --kernel_offset 0x00008000 \
  --ramdisk_offset 0x01000000 \
  --second_offset 0x00f00000 \
  --tags_offset 0x00000100 \
  --pagesize 4096 \
  --header_version 1 \
  --os_version 10.0.0 \
  --os_patch_level 2020-12-01 \
  --output /work/boot-lawrun13-sukisu.img
```

### Mac 复制并刷入

```bash
docker cp \
  sukisu-builder:/work/boot-lawrun13-sukisu.img \
  ./boot-lawrun13-sukisu.img

adb reboot bootloader
fastboot boot boot-lawrun13-sukisu.img   # 临时启动验证
# 确认可正常开机后再:
adb reboot bootloader
fastboot flash boot boot-lawrun13-sukisu.img
fastboot reboot
```

### 提交并构建 App

```bash
# Docker 内
cd /work/lawrun-13.1/KernelSU/SukiSU-Ultra

git status --short
git add userspace/ksud/src/ksucalls.rs
git commit -m "fix: add Android 10 prctl bootstrap"
git push origin HEAD
```

```bash
# Mac
cd /Users/xyj/Documents/Code/About-SukiSU-Ultra/SukiSU-Ultra
git pull --ff-only

cd manager
./gradlew clean assembleDebug

APK=$(ls -t app/build/outputs/apk/debug/*.apk | head -1)
adb install -r "$APK"
```

## 验证

```bash
adb shell am force-stop com.sukisu.ultra
adb logcat -c

adb shell monkey \
  -p com.sukisu.ultra \
  -c android.intent.category.LAUNCHER \
  1

sleep 5

# 期望:无输出(seccomp 崩溃消失)
adb logcat -b crash -d | grep -Ei \
  'SIGSYS|SYS_SECCOMP|syscall 142|libksud'

# 期望:uid=0(root) gid=0(root)
adb shell su -c id
```

## 关键文件

| 路径 | 作用 |
| --- | --- |
| `kernel/hook/lsm_hook.c` | `ksu_task_prctl` 实现 + LSM hook 注册 |
| `kernel/supercall/supercall.c` | `ksu_install_fd` + `ksu_handle_sys_reboot`(reboot fallback) |
| `kernel/supercall/dispatch.c` | `ksu_handle_sys_reboot` 调用入口 |
| `kernel/include/uapi/supercall.h` | UABI magic constants |
| `SukiSU-Ultra/userspace/ksud/src/ksucalls.rs` | ksud 用户态 fd bootstrap 流程 |
| `SukiSU-Ultra/userspace/ksud/src/ksu_uapi.rs` | UAPI 常量绑定 |
| `SukiSU-Ultra/userspace/ksud/src/susfs/abi/consts.rs` | SUSFS magic(暂未启用) |

## 一句话总结

- 改 kernel:`Docker 编译 → 打 boot → 刷入`
- 改 `userspace/manager`:`Docker commit/push → Mac pull → 构建并安装 APK`