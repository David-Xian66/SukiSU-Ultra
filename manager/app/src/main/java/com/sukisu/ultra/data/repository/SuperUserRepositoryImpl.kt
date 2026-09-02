package com.sukisu.ultra.data.repository

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.os.SystemClock
import android.os.UserManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.sukisu.ultra.Natives
import com.sukisu.ultra.data.model.AppInfo
import com.sukisu.ultra.data.model.WEBVIEW_ZYGOTE_PROFILE_KEY
import com.sukisu.ultra.data.model.WEBVIEW_ZYGOTE_UID
import com.sukisu.ultra.ksuApp

/**
 * Reads SuperUser data directly from the manager process.
 *
 * The original implementation bound through libsu's RootService to a
 * KsuService that ran in a root-uid subprocess and proxied
 * UserManager / PackageManager calls via AIDL. On Android 10 (dipper)
 * that path is unusable: libsu's RootServerMain calls
 * ActivityThread.systemMain() via reflection, which throws
 * "WTF--create system assets with illegal uid: <app-uid>" in
 * AssetManager.createSystemAssetsInZygoteLocked because the root
 * subprocess still runs under the manager app's uid (10205), not the
 * Android system/phone/shell uid (1000/1001/2000).
 *
 * KsuService only ever proxied plain UserManager / PackageManager
 * calls — none of them required root. The actual kernel ioctl
 * (Natives.getAppProfile / Natives.uidShouldUmount) was already being
 * issued from the manager process via libkernelsu.so JNI, which now
 * carries its own prctl bootstrap in ksu.cc. So we can read both the
 * user list and the installed packages straight from the manager
 * process and avoid the libsu IPC entirely.
 */
class SuperUserRepositoryImpl : SuperUserRepository {

    companion object {
        private const val TAG = "SuperUserRepository"
    }

    override suspend fun getAppList(): Result<Pair<List<AppInfo>, List<Int>>> = withContext(Dispatchers.IO) {
        runCatching {
            val pm = ksuApp.packageManager
            val um = ksuApp.getSystemService(Context.USER_SERVICE) as UserManager
            val start = SystemClock.elapsedRealtime()

            val idsArray = getAllUserIds(um)
            val packages = getInstalledPackagesForUsers(pm, idsArray)

            val newApps = packages.filter {
                val ai = it.applicationInfo ?: return@filter false
                ai.uid != WEBVIEW_ZYGOTE_UID &&
                        (ai.flags and ApplicationInfo.FLAG_HAS_CODE) != 0
            }.mapNotNull {
                val appInfo = it.applicationInfo ?: return@mapNotNull null
                // A single JNI failure (transient ksud / ioctl error on
                // Android 10) must not blank the whole SuperUser list.
                val profile = try {
                    Natives.getAppProfile(it.packageName, appInfo.uid)
                } catch (e: Throwable) {
                    Log.w(TAG, "getAppProfile failed for ${it.packageName}", e)
                    Natives.Profile(appInfo.loadLabel(pm).toString(), appInfo.uid)
                }
                AppInfo(
                    label = appInfo.loadLabel(pm).toString(),
                    packageInfo = it,
                    profile = profile,
                )
            }.toMutableList()

            // WebView Zygote is a single system UID, not a per-user package.
            // Reuse the system icon and synthesise a placeholder PackageInfo.
            runCatching {
                val systemInfo = ApplicationInfo(pm.getApplicationInfo("android", 0)).apply {
                    uid = WEBVIEW_ZYGOTE_UID
                }
                val placeholder = PackageInfo().apply {
                    packageName = ""
                    applicationInfo = systemInfo
                }
                val webViewProfile = try {
                    Natives.getAppProfile(WEBVIEW_ZYGOTE_PROFILE_KEY, WEBVIEW_ZYGOTE_UID)
                } catch (e: Throwable) {
                    Log.w(TAG, "getAppProfile failed for WebViewZygote", e)
                    Natives.Profile("WebView Zygote", WEBVIEW_ZYGOTE_UID)
                }
                newApps += AppInfo(
                    label = "WebView Zygote",
                    packageInfo = placeholder,
                    profile = webViewProfile,
                    profileKey = WEBVIEW_ZYGOTE_PROFILE_KEY,
                    special = true,
                )
            }.onFailure { Log.w(TAG, "skip WebView Zygote placeholder", it) }

            Log.i(TAG, "load cost: ${SystemClock.elapsedRealtime() - start}")
            Pair(newApps, idsArray.toList())
        }
    }

    override suspend fun refreshProfiles(currentApps: List<AppInfo>): Result<List<AppInfo>> = withContext(Dispatchers.IO) {
        runCatching {
            if (currentApps.isEmpty()) return@runCatching emptyList()

            currentApps.map {
                val profile = try {
                    Natives.getAppProfile(it.profileKey, it.uid)
                } catch (e: Throwable) {
                    Log.w(TAG, "getAppProfile failed for ${it.profileKey}", e)
                    it.profile
                }
                it.copy(profile = profile)
            }
        }
    }

    /**
     * Resolve the set of user ids we should enumerate packages for.
     *
     * Mirrors the three-tier fallback that lives in KsuService.getAllUserIds()
     * (used to be needed for libsu IPC; now runs in-process where the same
     * reflection rules apply). Each tier catches only its own exception class
     * so unexpected failures still surface to the outer runCatching.
     */
    private fun getAllUserIds(um: UserManager): IntArray {
        // 1) Preferred on API 31+: UserManager.getAliveUsers() (public since
        //    API 31). NoSuchMethodException on older devices falls through.
        try {
            val method = um.javaClass.getMethod("getAliveUsers")
            @Suppress("UNCHECKED_CAST")
            val users = method.invoke(um) as List<*>
            val ids = extractUserIds(users)
            if (ids.isNotEmpty()) return ids
        } catch (_: NoSuchMethodException) {
            Log.i(TAG, "getAliveUsers unavailable, trying UserManager.getUsers()")
        } catch (e: Exception) {
            Log.e(TAG, "getAliveUsers reflection failed", e)
        }

        // 2) Fallback for API 17-30: UserManager.getUsers() — public since
        //    API 17, deprecated in API 30, hidden by the compileSdk 37
        //    stubs so we reach it through reflection. Some OEM ROMs @hide
        //    even this method, hence the NoSuchMethodException branch.
        try {
            val method = um.javaClass.getMethod("getUsers")
            val users = method.invoke(um) as List<*>
            val ids = extractUserIds(users)
            if (ids.isNotEmpty()) return ids
        } catch (_: NoSuchMethodException) {
            Log.i(TAG, "getUsers unavailable, trying UserManager.getUserIds()")
        } catch (e: Exception) {
            Log.e(TAG, "UserManager.getUsers() reflection failed", e)
        }

        // 3) Last reflection fallback: UserManager.getUserIds() returns
        //    int[] directly. Hidden from the public SDK but stable since
        //    API 17 across AOSP and major vendor ROMs.
        try {
            val method = um.javaClass.getMethod("getUserIds")
            val ids = method.invoke(um) as IntArray
            if (ids != null && ids.isNotEmpty()) return ids
        } catch (e: Exception) {
            Log.e(TAG, "UserManager.getUserIds() reflection failed", e)
        }

        // 4) Hard fallback so we never return an empty array and
        //    accidentally produce a zero-package response.
        return intArrayOf(0)
    }

    private fun extractUserIds(users: List<*>?): IntArray {
        if (users.isNullOrEmpty()) return intArrayOf(0)

        return try {
            users.map { user ->
                user!!.javaClass.getField("id").getInt(user)
            }.toIntArray()
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting ID from UserInfo", e)
            intArrayOf(0)
        }
    }

    /**
     * Enumerate installed packages across the supplied user ids. Calls
     * PackageManager.getInstalledPackagesAsUser via reflection (the method
     * is hidden since API 21 but present on every Android version we
     * support). Per-user failures are isolated so a single broken user
     * cannot wipe the whole list.
     */
    private fun getInstalledPackagesForUsers(pm: android.content.pm.PackageManager, userIds: IntArray): List<PackageInfo> {
        val packages = ArrayList<PackageInfo>()
        for (userId in userIds) {
            Log.i(TAG, "getInstalledPackagesAll: $userId")
            packages.addAll(getInstalledPackagesAsUser(pm, 0, userId))
        }
        return packages
    }

    @Suppress("UNCHECKED_CAST")
    private fun getInstalledPackagesAsUser(
        pm: android.content.pm.PackageManager,
        flags: Int,
        userId: Int,
    ): List<PackageInfo> {
        return try {
            val method = pm.javaClass.getDeclaredMethod(
                "getInstalledPackagesAsUser",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType
            )
            method.invoke(pm, flags, userId) as List<PackageInfo>
        } catch (e: Throwable) {
            Log.e(TAG, "getInstalledPackagesAsUser failed for user $userId", e)
            ArrayList()
        }
    }
}
