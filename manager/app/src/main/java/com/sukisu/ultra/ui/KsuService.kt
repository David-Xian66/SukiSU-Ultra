package com.sukisu.ultra.ui

import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.UserManager
import android.util.Log
import com.topjohnwu.superuser.ipc.RootService
import com.sukisu.zako.IKsuInterface
import rikka.parcelablelist.ParcelableListSlice

/**
 * @author weishu
 * @date 2023/4/18.
 */

class KsuService : RootService() {

    companion object {
        private const val TAG = "KsuService"
    }

    override fun onBind(intent: Intent): IBinder {
        return Stub()
    }

    private fun getAllUserIds(): IntArray {
        val um = getSystemService(USER_SERVICE) as UserManager

        // 1) Preferred: getAliveUsers() (API 31+). Falls through on
        //    NoSuchMethodException to keep the API < 31 path live.
        try {
            val method = um.javaClass.getMethod("getAliveUsers")
            val users = method.invoke(um) as List<*>
            val ids = extractUserIds(users)
            if (ids.isNotEmpty()) return ids
        } catch (_: NoSuchMethodException) {
            Log.i(TAG, "getAliveUsers unavailable, using UserManager.getUsers()")
        } catch (e: Exception) {
            Log.e(TAG, "getAliveUsers reflection failed", e)
        }

        // 2) Fallback for Android 10 (API 29) and below:
        //    UserManager.getUsers() has been a public API since API 17
        //    (deprecated in API 30). On Android 10 it returns the same
        //    set as getAliveUsers() does on API 30+. The getter is hidden
        //    from the compileSdk 37 stubs, so go through reflection.
        try {
            val method = um.javaClass.getMethod("getUsers")
            val users = method.invoke(um) as List<*>
            val ids = extractUserIds(users)
            if (ids.isNotEmpty()) return ids
        } catch (e: Exception) {
            Log.e(TAG, "UserManager.getUsers() reflection failed", e)
        }

        // 3) Last-resort fallback so we never return an empty array and
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

    private fun getInstalledPackagesAll(flags: Int): ArrayList<PackageInfo> {
        val packages = ArrayList<PackageInfo>()
        for (userId in getAllUserIds()) {
            Log.i(TAG, "getInstalledPackagesAll: $userId")
            packages.addAll(getInstalledPackagesAsUser(flags, userId))
        }
        return packages
    }

    @Suppress("UNCHECKED_CAST")
    private fun getInstalledPackagesAsUser(flags: Int, userId: Int): List<PackageInfo> {
        return try {
            val pm: PackageManager = packageManager
            val method = pm.javaClass.getDeclaredMethod(
                "getInstalledPackagesAsUser",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType
            )
            method.invoke(pm, flags, userId) as List<PackageInfo>
        } catch (e: Throwable) {
            Log.e(TAG, "err", e)
            ArrayList()
        }
    }

    private inner class Stub : IKsuInterface.Stub() {
        override fun getPackages(flags: Int): ParcelableListSlice<PackageInfo> {
            val list = getInstalledPackagesAll(flags)
            Log.i(TAG, "getPackages: ${list.size}")
            return ParcelableListSlice(list)
        }

        override fun getUserIds(): IntArray {
            val ids = getAllUserIds()
            Log.i(TAG, "getUserIds: ${ids.contentToString()}")
            return ids
        }
    }
}
