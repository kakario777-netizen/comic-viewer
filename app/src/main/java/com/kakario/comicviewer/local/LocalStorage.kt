package com.kakario.comicviewer.local

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.Settings
import com.kakario.comicviewer.model.FileEntry
import com.kakario.comicviewer.model.UserFacingException
import com.kakario.comicviewer.model.sortedForShelf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** 내장 메모리 / SD카드 같은 저장공간의 최상위 폴더. */
data class StorageRoot(val label: String, val path: String)

object LocalStorage {

    /** 폰 안의 모든 파일을 읽을 권한이 있는지. */
    fun hasAccess(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            context.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED
        }

    /** 안드로이드 11 이상: '모든 파일 접근' 설정 화면을 연다. */
    fun openAllFilesSettings(context: Context) {
        val intent = Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.parse("package:${context.packageName}"),
        )
        try {
            context.startActivity(intent)
        } catch (_: Exception) {
            context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
        }
    }

    fun roots(context: Context): List<StorageRoot> {
        val result = mutableListOf<StorageRoot>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val sm = context.getSystemService(StorageManager::class.java)
            sm.storageVolumes.forEach { v ->
                val dir = v.directory ?: return@forEach
                val label = if (v.isPrimary) "내장 메모리" else v.getDescription(context) ?: "SD카드"
                result += StorageRoot(label, dir.absolutePath)
            }
        }
        if (result.isEmpty()) {
            result += StorageRoot("내장 메모리", Environment.getExternalStorageDirectory().absolutePath)
            context.getExternalFilesDirs(null).drop(1).filterNotNull().forEach {
                result += StorageRoot("SD카드", it.absolutePath.substringBefore("/Android/"))
            }
        }
        return result.distinctBy { it.path }
    }

    fun isRoot(context: Context, path: String): Boolean =
        roots(context).any { it.path.trimEnd('/') == path.trimEnd('/') }

    suspend fun list(path: String): List<FileEntry> = withContext(Dispatchers.IO) {
        val dir = File(path)
        if (!dir.exists()) throw UserFacingException("폴더가 없어요: $path")
        val files = dir.listFiles() ?: throw UserFacingException(
            "폴더를 열 수 없어요.\n저장공간 접근 권한이 켜져 있는지 확인해 주세요."
        )
        files.filter { !it.name.startsWith(".") }
            .map { f ->
                FileEntry(
                    name = f.name,
                    path = f.absolutePath,
                    isDirectory = f.isDirectory,
                    size = if (f.isDirectory) 0 else f.length(),
                )
            }
            .sortedForShelf()
    }
}
