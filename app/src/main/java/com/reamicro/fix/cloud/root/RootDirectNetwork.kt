package com.reamicro.fix.cloud.root

import android.net.Network
import android.net.NetworkCapabilities
import android.os.IBinder
import android.os.Process
import java.io.IOException

internal data class RootDirectNetworkCandidate(
    val handle: Long,
    val internet: Boolean,
    val notVpn: Boolean,
    val unrestricted: Boolean,
    val vpn: Boolean,
    val active: Boolean,
    val validated: Boolean,
    val localTransport: Boolean,
)

/** 沿用系统物理默认网络；VPN 为默认时优先已验证的 Wi-Fi/以太网，排除 IMS 等专用网络。 */
internal fun rootDirectNetworkOrder(candidates: List<RootDirectNetworkCandidate>): List<Long> =
    candidates.filter { it.handle > 0 && it.internet && it.notVpn && it.unrestricted && !it.vpn }
        .sortedWith(
            compareByDescending<RootDirectNetworkCandidate> { it.active }
                .thenByDescending { it.validated }
                .thenByDescending { it.localTransport }
                .thenBy { it.handle },
        ).map { it.handle }

/** 只在 Root 运行器内保存本轮 DNS 验证过的网络，不绑定宿主进程或更改 VPN/系统代理设置。 */
internal object RootDirectNetwork {
    @Volatile private var prepared: Network? = null
    @Volatile private var required = false

    fun begin() {
        check(Process.myUid() == 0)
        prepared = null
        required = true
    }

    fun use(result: RootDnsProbeResult?) {
        check(Process.myUid() == 0 && required)
        prepared = null
        check(result?.ready == true) { "Root 直连 DNS 尚未就绪；保留任务进度，等待重试" }
        val handle = result.networkHandle ?: throw IOException("Root 直连网络回执缺失；保留任务进度，等待重试")
        // DNS 探测在子进程完成；重新取得同一条仍可直连的网络，兼容 Android 8。
        prepared = available().first.firstOrNull { it.networkHandle == handle }
            ?: throw IOException("Root 直连网络已失效；保留任务进度，等待重试")
    }

    fun forRequest(): Network? = if (required) {
        prepared ?: throw IOException("Root 直连网络尚未就绪；保留任务进度，等待重试")
    } else null

    fun clear() {
        prepared = null
        required = false
    }

    /** app_process 没有 Application；直接读取 Connectivity Binder，避免初始化宿主。 */
    fun available(): Pair<List<Network>, Boolean> {
        check(Process.myUid() == 0)
        val binder = Class.forName("android.os.ServiceManager")
            .getMethod("checkService", String::class.java).invoke(null, "connectivity") as? IBinder
            ?: throw IOException("ConnectivityServiceUnavailable")
        val api = Class.forName("android.net.IConnectivityManager")
        val service = Class.forName(api.name + "\$Stub").getMethod("asInterface", IBinder::class.java)
            .invoke(null, binder) ?: throw IOException("ConnectivityServiceUnavailable")
        val capabilityMethod = api.methods.singleOrNull { method ->
            method.name == "getNetworkCapabilities" && method.returnType == NetworkCapabilities::class.java &&
                method.parameterTypes.size in 1..3 && method.parameterTypes.first() == Network::class.java &&
                method.parameterTypes.drop(1).all { it == String::class.java }
        } ?: throw NoSuchMethodException("ConnectivityCapabilitiesAbi")
        val active = api.getMethod("getActiveNetwork").invoke(service) as? Network
        val networks = (api.getMethod("getAllNetworks").invoke(service) as Array<*>).filterIsInstance<Network>()
        val networksByHandle = networks.associateBy { it.networkHandle }
        val candidates = networks.mapNotNull { network ->
            val caps = capabilities(service, capabilityMethod, network) ?: return@mapNotNull null
            RootDirectNetworkCandidate(
                handle = network.networkHandle,
                internet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
                notVpn = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN),
                unrestricted = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED),
                vpn = caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN),
                active = network == active,
                validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
                localTransport = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET),
            )
        }
        return rootDirectNetworkOrder(candidates).mapNotNull(networksByHandle::get) to candidates.any { it.vpn }
    }

    private fun capabilities(
        service: Any, method: java.lang.reflect.Method, network: Network,
    ): NetworkCapabilities? {
        val arguments = arrayOfNulls<Any>(method.parameterCount)
        arguments[0] = network
        // 新固件要求包归属非空，UID 0 使用系统包，附加 attributionTag 保持为空。
        if (arguments.size > 1) arguments[1] = "android"
        return method.invoke(service, *arguments) as? NetworkCapabilities
    }
}
