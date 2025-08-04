package com.v2ray.ang.service

import android.content.Context
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.annotation.Keep
import com.v2ray.ang.AppConfig
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsManager
import java.io.File

/**
 * Manages the tun2socks process that handles VPN traffic
 */
class HevTun2SocksService(
    private val context: Context,
    private val vpnInterface: ParcelFileDescriptor,
    @Suppress("UNUSED")
    private val isRunningProvider: () -> Boolean,
    @Suppress("UNUSED")
    private val restartCallback: () -> Unit,
    private val stopCallback: () -> Unit,
) {
    companion object {
        init {
            System.loadLibrary("hev-socks5-tunnel")
        }

        // 参阅：
        // hev-socks5-tunnel\src\hev-jni.c | static JNINativeMethod native_methods[]
        // jni/Application.mk | APP_CFLAGS := -O3 -DPKGNAME=com/v2ray/ang/service -DCLSNAME=HevTun2SocksService
        @Keep
        @JvmStatic
        private external fun TProxyStartService(configPath: String, fd: Int)

        @Keep
        @JvmStatic
        private external fun TProxyStopService()

        @Suppress("UNUSED")
        @Keep
        @JvmStatic
        private external fun TProxyGetStats(): LongArray
    }

    /**
     * Starts the tun2socks process with the appropriate parameters.
     */
    fun startTun2Socks() {
        Log.i(AppConfig.TAG, "Starting hev-socks5-tunnel via JNI")

        val configContent = buildConfig()
        val configFile = File(context.applicationContext.filesDir, "hev-socks5-tunnel.yaml").apply {
            writeText(configContent)
        }
        Log.i(AppConfig.TAG, "Config file created: ${configFile.absolutePath}")
        Log.d(AppConfig.TAG, "Config content:\n$configContent")

        val fd = vpnInterface.fd

        try {
            Log.i(AppConfig.TAG, "Tun Service start...")
            TProxyStartService(
                configFile.absolutePath,
                fd
            )
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "Failed to start Tun Service: ${e.message}", e)
            stopCallback()
        }
    }

    private fun buildConfig(): String {
        val vpnConfig = SettingsManager.getCurrentVpnInterfaceAddressConfig()
        return buildString {
            appendLine("tunnel:")
            appendLine("  name: tun0")
            appendLine("  mtu: ${AppConfig.HEV_TUN_VPN_MTU}")
            appendLine("  multi-queue: ${AppConfig.HEV_TUN_MULTI_QUEUE}")
            appendLine("  ipv4: ${vpnConfig.ipv4Client}")

            if (MmkvManager.decodeSettingsBool(AppConfig.PREF_PREFER_IPV6)) {
                appendLine("  # IPv6 address")
                appendLine("  ipv6: \"${vpnConfig.ipv6Client}\"")
            }

            appendLine("socks5:")
            appendLine("  port: ${SettingsManager.getSocksPort()}")
            appendLine("  address: ${AppConfig.LOOPBACK}")
            appendLine("  udp: 'udp'")

            appendLine("misc:")
            val logPref = MmkvManager.decodeSettingsString(AppConfig.PREF_LOGLEVEL)
            val logLevel = when (logPref) {
                "none" -> "error"
                "warning" -> "warn"
                null, "" -> "error" // 空值或未设置默认 error
                else -> logPref
            }
            appendLine("  log-level: $logLevel")
        }
    }

    /**
     * Stops the tun2socks process
     */
    fun stopTun2Socks() {
        try {
            Log.i(AppConfig.TAG, "Tun Service stop...")
            TProxyStopService()
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "Failed to stop Tun Service: ${e.message}", e)
        }
    }
}
