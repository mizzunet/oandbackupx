/*
 * OAndBackupX: open-source apps backup and restore app.
 * Copyright (C) 2020  Antonios Hazim
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.machiav3lli.backup.utils

import android.Manifest
import android.app.Activity
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.provider.DocumentsContract
import androidx.biometric.BiometricManager
import androidx.core.app.ActivityCompat
import androidx.fragment.app.Fragment
import androidx.preference.PreferenceManager
import com.machiav3lli.backup.Constants
import com.machiav3lli.backup.Constants.classTag
import com.machiav3lli.backup.handler.Crypto
import com.machiav3lli.backup.handler.StorageFile
import java.nio.charset.StandardCharsets

object PrefUtils {
    private val TAG = classTag(".PrefUtils")
    const val READ_PERMISSION = 2
    const val WRITE_PERMISSION = 3
    const val BACKUP_DIR = 5

    @JvmStatic
    fun getCryptoSalt(context: Context?): ByteArray {
        val userSalt = getDefaultSharedPreferences(context).getString(Constants.PREFS_SALT, "")
        return if (userSalt!!.isNotEmpty()) {
            userSalt.toByteArray(StandardCharsets.UTF_8)
        } else Crypto.FALLBACK_SALT
    }

    @JvmStatic
    fun isEncryptionEnabled(context: Context?): Boolean {
        return !getDefaultSharedPreferences(context).getString(Constants.PREFS_PASSWORD, "")!!.isEmpty()
    }

    @JvmStatic
    fun isLockEnabled(context: Context?): Boolean {
        return getDefaultSharedPreferences(context).getBoolean(Constants.PREFS_BIOMETRICLOCK, false)
    }

    @JvmStatic
    fun isBiometricLockAvailable(context: Context?): Boolean {
        return BiometricManager.from(context!!).canAuthenticate() == BiometricManager.BIOMETRIC_SUCCESS
    }

    /**
     * Returns the user selected location. Go for `FileUtil.getBackupDir` to get the actual
     * backup dir's path
     *
     * @param context application context
     * @return user configured location
     * @throws StorageLocationNotConfiguredException if the value is not set
     */
    @JvmStatic
    @Throws(StorageLocationNotConfiguredException::class)
    fun getStorageRootDir(context: Context): String? {
        val location = getPrivateSharedPrefs(context).getString(Constants.PREFS_PATH_BACKUP_DIRECTORY, "")
        if (location!!.isEmpty()) {
            throw StorageLocationNotConfiguredException()
        }
        return location
    }

    @JvmStatic
    fun setStorageRootDir(context: Context, value: Uri?) {
        val fullUri = DocumentsContract.buildDocumentUriUsingTree(value, DocumentsContract.getTreeDocumentId(value))
        getPrivateSharedPrefs(context)
                .edit()
                .putString(Constants.PREFS_PATH_BACKUP_DIRECTORY, fullUri.toString())
                .apply()
        FileUtils.invalidateBackupLocation()
    }

    @JvmStatic
    fun isStorageDirSetAndOk(context: Context): Boolean {
        return try {
            val storageDirPath = getStorageRootDir(context)
            if (storageDirPath!!.isEmpty()) {
                return false
            }
            val storageDir = StorageFile.fromUri(context, Uri.parse(storageDirPath))
            storageDir.exists()
        } catch (e: StorageLocationNotConfiguredException) {
            false
        }
    }

    @JvmStatic
    fun getDefaultSharedPreferences(context: Context?): SharedPreferences {
        return PreferenceManager.getDefaultSharedPreferences(context)
    }

    @JvmStatic
    fun getPrivateSharedPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(Constants.PREFS_SHARED_PRIVATE, Context.MODE_PRIVATE)
    }

    @JvmStatic
    fun requireStorageLocation(fragment: Fragment) {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                .addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        fragment.startActivityForResult(intent, BACKUP_DIR)
    }

    @JvmStatic
    fun checkStoragePermissions(context: Context): Boolean {
        /* if (PrefUtils.STORAGE_MODE_R) {
            return Environment.isExternalStorageManager();
        } else {
            return (context.checkSelfPermission(READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED);
        }
         */
        return context.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    }

    @JvmStatic
    fun getStoragePermission(activity: Activity) {
        requireWriteStoragePermission(activity)
        requireReadStoragePermission(activity)
    }

    fun requireReadStoragePermission(activity: Activity) {
        if (activity.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) ActivityCompat.requestPermissions(activity, arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), READ_PERMISSION)
    }

    fun requireWriteStoragePermission(activity: Activity) {
        if (activity.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) ActivityCompat.requestPermissions(activity, arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), WRITE_PERMISSION)
    }

    @JvmStatic
    fun canAccessExternalStorage(context: Context): Boolean {
        val externalStorage = FileUtils.getExternalStorageDirectory(context)
        return externalStorage.canRead() && externalStorage.canWrite()
    }

    @JvmStatic
    fun checkUsageStatsPermission(context: Context): Boolean {
        val appOps = (context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager)
        val mode = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
            else -> appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        }
        return if (mode == AppOpsManager.MODE_DEFAULT) {
            context.checkCallingOrSelfPermission(Manifest.permission.PACKAGE_USAGE_STATS) == PackageManager.PERMISSION_GRANTED
        } else {
            mode == AppOpsManager.MODE_ALLOWED
        }
    }

    @JvmStatic
    fun checkBatteryOptimization(context: Context, prefs: SharedPreferences, powerManager: PowerManager): Boolean {
        return prefs.getBoolean(Constants.PREFS_IGNORE_BATTERY_OPTIMIZATION, false) || powerManager.isIgnoringBatteryOptimizations(context.packageName)
    }

    @JvmStatic
    fun isKillBeforeActionEnabled(context: Context?): Boolean {
        return getDefaultSharedPreferences(context).getBoolean(Constants.PREFS_KILLBEFOREACTION, false)
    }

    @JvmStatic
    fun isDisableVerification(context: Context?): Boolean {
        return getDefaultSharedPreferences(context).getBoolean(Constants.PREFS_DISABLEVERIFICATION, false)
    }

    class StorageLocationNotConfiguredException : Exception("Storage Location has not been configured")
}