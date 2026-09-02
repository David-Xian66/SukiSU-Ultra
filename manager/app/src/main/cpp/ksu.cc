//
// Created by weishu on 2022/12/9.
//

#include <sys/prctl.h>
#include <cstdint>
#include <cstring>
#include <cstdio>
#include <unistd.h>
#include <utility>
#include <android/log.h>
#include <dirent.h>
#include <cstdlib>

#include <unistd.h>
#include <climits>
#include <sys/syscall.h>
#include <cerrno>
#include "ksu.h"

static int fd = -1;

static inline int scan_driver_fd() {
    const char *kName = "[ksu_driver]";
    DIR *dir = opendir("/proc/self/fd");
    if (!dir) {
        return -1;
    }

    int found = -1;
    struct dirent *de;
    char path[64];
    char target[PATH_MAX];

    while ((de = readdir(dir)) != nullptr) {
        if (de->d_name[0] == '.') {
            continue;
        }

        char *endptr = nullptr;
        long fd_long = strtol(de->d_name, &endptr, 10);
        if (!de->d_name[0] || *endptr != '\0' || fd_long < 0 || fd_long > INT_MAX) {
            continue;
        }

        snprintf(path, sizeof(path), "/proc/self/fd/%s", de->d_name);
        ssize_t n = readlink(path, target, sizeof(target) - 1);
        if (n < 0) {
            continue;
        }
        target[n] = '\0';

        const char *base = strrchr(target, '/');
        base = base ? base + 1 : target;

        if (strstr(base, kName)) {
            found = (int)fd_long;
            break;
        }
    }

    closedir(dir);
    return found;
}

// Mirrors the ksucalls::try_prctl_bootstrap() path on the userspace side:
// ask the kernel's task_prctl LSM hook to install the [ksu_driver]
// anon-inode fd in this process and write it back via copy_to_user.
// prctl(2) is always permitted in app sandboxes, so this survives
// Android 10's zygote seccomp filter that would SIGSYS a sys_reboot
// fallback. Requires the kernel to carry ksu_task_prctl; returns -1
// otherwise (hook absent or ksu_install_fd refused).
static inline int prctl_install_fd() {
    int installed_fd = -1;
    int ret = prctl(
        static_cast<int>(KSU_INSTALL_MAGIC1),
        static_cast<int>(KSU_INSTALL_MAGIC2),
        &installed_fd,
        0,
        0
    );
    if (ret == 0 && installed_fd >= 0) {
        return installed_fd;
    }
    return -1;
}

// Whether the current task is under a seccomp filter. Used to gate the
// sys_reboot fallback so we never trigger SIGSYS / SYS_SECCOMP on
// Android 10 (PR_GET_SECCOMP returns SECCOMP_MODE_DISABLED=0,
// STRICT=1, FILTER=2 — anything >0 means active).
static inline bool seccomp_is_active() {
    return prctl(PR_GET_SECCOMP, 0, 0, 0, 0) > 0;
}

// Legacy fallback: kprobe on sys_reboot. Available on every KSU kernel
// (supercall.c) but unsafe under seccomp, hence the guard above.
static inline int reboot_install_fd() {
    if (seccomp_is_active()) {
        return -1;
    }
    int installed_fd = -1;
    long ret = syscall(
        __NR_reboot,
        static_cast<unsigned long>(KSU_INSTALL_MAGIC1),
        static_cast<unsigned long>(KSU_INSTALL_MAGIC2),
        0,
        &installed_fd
    );
    if (ret == 0 && installed_fd >= 0) {
        return installed_fd;
    }
    return -1;
}

template<typename... Args>
static int ksuctl(unsigned long op, Args &&... args) {

    if (fd < 0) {
        // 1. Inherited fd from a parent process that already ran
        //    ksu_install_fd (e.g. setresuid path).
        fd = scan_driver_fd();
    }
    if (fd < 0) {
        // 2. LSM task_prctl bootstrap (Android 10 friendly, requires
        //    ksu_task_prctl in the kernel module).
        fd = prctl_install_fd();
    }
    if (fd < 0) {
        // 3. Legacy sys_reboot kprobe fallback (only when seccomp is
        //    off — Android 10 zygote filter would SIGSYS otherwise).
        fd = reboot_install_fd();
    }

    static_assert(sizeof...(Args) <= 1, "ioctl expects at most one extra argument");

    return ioctl(fd, op, std::forward<Args>(args)...);
}

static struct ksu_get_info_cmd g_version {};

struct ksu_get_info_cmd get_info() {
    if (!g_version.version) {
        if (ksuctl(KSU_IOCTL_GET_INFO, &g_version) < 0) {
            ksuctl(KSU_IOCTL_GET_INFO_LEGACY, &g_version);
            g_version.uapi_version = 0;
        }
    }
    return g_version;
}

uint32_t get_kernel_uapi_version() {
    auto info = get_info();
    return info.uapi_version;
}

uint32_t get_manager_uapi_version() {
    return KERNEL_SU_UAPI_VERSION;
}

uint32_t get_version() {
    auto info = get_info();
    return info.version;
}

bool get_allow_list(struct ksu_new_get_allow_list_cmd *cmd) {
    return ksuctl(KSU_IOCTL_NEW_GET_ALLOW_LIST, cmd) == 0;
}

bool is_safe_mode() {
    struct ksu_check_safemode_cmd cmd = {};
    ksuctl(KSU_IOCTL_CHECK_SAFEMODE, &cmd);
    return cmd.in_safe_mode;
}

bool is_lkm_mode() {
    auto info = get_info();
    if (info.version > 0) {
        return (info.flags & KSU_GET_INFO_FLAG_LKM) != 0;
    }
    return (legacy_get_info().second & KSU_GET_INFO_FLAG_LKM) != 0;
}

bool is_late_load_mode() {
    auto info = get_info();
    if (info.version > 0) {
        return (info.flags & KSU_GET_INFO_FLAG_LATE_LOAD) != 0;
    }
    return false;
}

bool is_manager() {
    auto info = get_info();
    if (info.version > 0) {
        return (info.flags & KSU_GET_INFO_FLAG_MANAGER) != 0;
    }
    return legacy_get_info().first > 0;
}

bool is_pr_build() {
    auto info = get_info();
    if (info.version > 0) {
        return (info.flags & KSU_GET_INFO_FLAG_PR_BUILD) != 0;
    }
    return false;
}

bool uid_should_umount(int uid) {
    struct ksu_uid_should_umount_cmd cmd = {};
    cmd.uid = uid;
    ksuctl(KSU_IOCTL_UID_SHOULD_UMOUNT, &cmd);
    return cmd.should_umount;
}

bool set_app_profile(const app_profile *profile) {
    struct ksu_set_app_profile_cmd cmd = {};
    cmd.profile = *profile;
    return ksuctl(KSU_IOCTL_SET_APP_PROFILE, &cmd) == 0;
}

int get_app_profile(app_profile *profile) {
    struct ksu_get_app_profile_cmd cmd = {.profile = *profile};
    int ret = ksuctl(KSU_IOCTL_GET_APP_PROFILE, &cmd);
    *profile = cmd.profile;
    return ret;
}

bool set_su_enabled(bool enabled) {
    struct ksu_set_feature_cmd cmd = {};
    cmd.feature_id = KSU_FEATURE_SU_COMPAT;
    cmd.value = enabled ? 1 : 0;
    return ksuctl(KSU_IOCTL_SET_FEATURE, &cmd) == 0;
}

bool is_su_enabled() {
    struct ksu_get_feature_cmd cmd = {};
    cmd.feature_id = KSU_FEATURE_SU_COMPAT;
    if (ksuctl(KSU_IOCTL_GET_FEATURE, &cmd) != 0) {
        return false;
    }
    if (!cmd.supported) {
        return false;
    }
    return cmd.value != 0;
}

static inline bool get_feature(uint32_t feature_id, uint64_t *out_value, bool *out_supported) {
    struct ksu_get_feature_cmd cmd = {};
    cmd.feature_id = feature_id;
    if (ksuctl(KSU_IOCTL_GET_FEATURE, &cmd) != 0) {
        return false;
    }
    if (out_value) *out_value = cmd.value;
    if (out_supported) *out_supported = cmd.supported;
    return true;
}

static inline bool set_feature(uint32_t feature_id, uint64_t value) {
    struct ksu_set_feature_cmd cmd = {};
    cmd.feature_id = feature_id;
    cmd.value = value;
    return ksuctl(KSU_IOCTL_SET_FEATURE, &cmd) == 0;
}

bool set_kernel_umount_enabled(bool enabled) {
    return set_feature(KSU_FEATURE_KERNEL_UMOUNT, enabled ? 1 : 0);
}

bool is_kernel_umount_enabled() {
    uint64_t value = 0;
    bool supported = false;
    if (!get_feature(KSU_FEATURE_KERNEL_UMOUNT, &value, &supported)) {
        return false;
    }
    if (!supported) {
        return false;
    }
    return value != 0;
}

int set_selinux_hide_enabled(bool enabled) {
    if (!set_feature(KSU_FEATURE_SELINUX_HIDE, enabled ? 1 : 0)) {
        return -errno;
    }
    return 0;
}

bool is_selinux_hide_enabled() {
    uint64_t value = 0;
    bool supported = false;
    if (!get_feature(KSU_FEATURE_SELINUX_HIDE, &value, &supported)) {
        return false;
    }
    if (!supported) {
        return false;
    }
    return value != 0;
}

// Custom
DEFINE_CACHED_GETTER(full_version, KSU_IOCTL_GET_FULL_VERSION, ksu_get_full_version_cmd, version_full, 255)
DEFINE_CACHED_GETTER(hook_type, KSU_IOCTL_HOOK_TYPE, ksu_hook_type_cmd, hook_type, 32)
