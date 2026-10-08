package io.github.sanitised.st

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/**
 * Deep-links into battery-exemption and OEM "app launch / autostart" screens.
 * Honor/Huawei (MagicOS) are prioritized; other OEMs fall back gracefully.
 */
object OemKeepAlive {
    fun openBatteryUnrestrictedSettings(context: Context) {
        val packageUri = Uri.fromParts("package", context.packageName, null)
        val powerManager = context.getSystemService(PowerManager::class.java)
        val isIgnoring = powerManager?.isIgnoringBatteryOptimizations(context.packageName) == true
        val candidates = buildList {
            add(
                Intent("android.settings.APP_BATTERY_SETTINGS").apply {
                    data = packageUri
                    putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    putExtra("android.intent.extra.PACKAGE_NAME", context.packageName)
                    putExtra("package_name", context.packageName)
                }
            )
            if (!isIgnoring) {
                add(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, packageUri))
            }
            add(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri))
            add(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            add(Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS))
            add(Intent(Settings.ACTION_SETTINGS))
        }
        launchFirstResolvable(context, candidates)
    }

    fun openAutostartSettings(context: Context): Boolean {
        val packageUri = Uri.fromParts("package", context.packageName, null)
        val manufacturer = Build.MANUFACTURER.orEmpty().lowercase()
        val brand = Build.BRAND.orEmpty().lowercase()
        val isHonorFamily = listOf(manufacturer, brand).any {
            it.contains("honor") || it.contains("huawei")
        }

        val componentCandidates = buildList {
            if (isHonorFamily) {
                add("com.hihonor.systemmanager/.appcontrol.activity.StartupAppControlActivity")
                add("com.hihonor.systemmanager/.startupmgr.ui.StartupNormalAppListActivity")
                add("com.hihonor.systemmanager/.optimize.process.ProtectActivity")
                add("com.huawei.systemmanager/.startupmgr.ui.StartupNormalAppListActivity")
                add("com.huawei.systemmanager/.appcontrol.activity.StartupAppControlActivity")
                add("com.huawei.systemmanager/.optimize.process.ProtectActivity")
                add("com.huawei.systemmanager/.power.ui.HwPowerManagerActivity")
            }
            // Common OEMs as soft fallbacks
            add("com.miui.securitycenter/com.miui.permcenter.autostart.AutoStartManagementActivity")
            add("com.samsung.android.lool/com.samsung.android.sm.battery.ui.BatteryActivity")
            add("com.coloros.safecenter/.startupapp.StartupAppListActivity")
            add("com.oppo.safe/.permission.startup.StartupAppListActivity")
            add("com.vivo.permissionmanager/.activity.BgStartUpManagerActivity")
            add("com.asus.mobilemanager/.autostart.AutoStartActivity")
        }

        val intentCandidates = buildList {
            add(Intent("huawei.intent.action.HSM_PROTECTED_APPS"))
            add(Intent("huawei.intent.action.POWERMANAGER"))
            add(Intent("hihonor.intent.action.HSM_PROTECTED_APPS"))
            for (raw in componentCandidates) {
                val parts = raw.split("/", limit = 2)
                if (parts.size == 2) {
                    add(
                        Intent().setComponent(
                            ComponentName(parts[0], resolveClassName(parts[0], parts[1]))
                        )
                    )
                }
            }
            add(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri))
        }

        return launchFirstResolvable(context, intentCandidates)
    }

    fun openAppDetails(context: Context) {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", context.packageName, null)
        )
        runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    private fun resolveClassName(packageName: String, classPart: String): String {
        return if (classPart.startsWith(".")) packageName + classPart else classPart
    }

    private fun launchFirstResolvable(context: Context, candidates: List<Intent>): Boolean {
        val pm = context.packageManager
        for (candidate in candidates) {
            val intent = Intent(candidate).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (intent.resolveActivity(pm) != null) {
                return runCatching {
                    context.startActivity(intent)
                    true
                }.getOrDefault(false)
            }
        }
        return false
    }
}
