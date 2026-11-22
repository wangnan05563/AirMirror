package io.github.wangnan05563.airmirror.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import java.io.File

/** 下载包与本机已装版本的签名关系：系统只允许同证书的包覆盖安装。 */
enum class ApkSignatureState {
    /** 同证书，可以直接拉起安装器 */
    Match,

    /** 证书不同（常见于从调试包换成正式包），只能先卸载旧版本 */
    Mismatch,

    /** 解析不了或读不到证书（未签名/半截包/只有 v2 签名），不能冒险交给安装器 */
    Unknown,
}

/**
 * 拉起系统安装器。Android 8.0 起必须先由用户为「未知来源」单独授权，
 * 因此把「能否安装」与「去授权的设置页」都收在这里，界面只负责按结论说话。
 */
object ApkInstaller {

    fun needsInstallPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()

    fun permissionSettings(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + context.packageName))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /**
     * Android 只允许 versionCode 不降低的覆盖安装。更新包比本机旧时，
     * 系统安装器只会抛 INSTALL_FAILED_VERSION_DOWNGRADE；提前拦下并说明原因。
     */
    @Suppress("DEPRECATION")
    fun isDowngrade(context: Context, apk: File): Boolean {
        val archive = runCatching {
            context.packageManager.getPackageArchiveInfo(apk.absolutePath, 0)
        }.getOrNull() ?: return false
        val installed = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0)
        }.getOrNull() ?: return false
        val incomingCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            archive.longVersionCode
        } else {
            archive.versionCode.toLong()
        }
        val installedCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            installed.longVersionCode
        } else {
            installed.versionCode.toLong()
        }
        return incomingCode < installedCode
    }

    /**
     * 装之前先自检：系统安装器遇到「未签名/证书不同」只会给一句「软件包似乎无效」或
     * 「与现有应用签名不一致」，用户根本不知道该干什么。这里提前判出来，换成能执行的提示。
     */
    @Suppress("DEPRECATION")
    fun signatureState(context: Context, apk: File): ApkSignatureState {
        val archive = runCatching {
            context.packageManager.getPackageArchiveInfo(apk.absolutePath, PackageManager.GET_SIGNATURES)
        }.getOrNull() ?: return ApkSignatureState.Unknown
        val incoming = archive.signatures?.firstOrNull()?.toByteArray() ?: return ApkSignatureState.Unknown
        val installed = runCatching {
            context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
        }.getOrNull() ?: return ApkSignatureState.Unknown
        val current = installed.signatures?.firstOrNull()?.toByteArray() ?: return ApkSignatureState.Unknown
        return if (incoming.contentEquals(current)) ApkSignatureState.Match else ApkSignatureState.Mismatch
    }

    /** 签名不同只能卸载重装：直接拉起系统的「卸载应用」页，省得用户自己去设置里翻。 */
    fun uninstallIntent(context: Context): Intent =
        Intent(Intent.ACTION_DELETE, Uri.parse("package:" + context.packageName))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun install(context: Context, file: File): Intent {
        val uri = ApkProvider.uriFor(context, file)
        return Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}
