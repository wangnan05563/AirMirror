package io.github.wangnan05563.airmirror.net

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log

object Nsd {
    const val SERVICE_TYPE = "_airmirror._tcp."
    const val TAG = "AirMirrorNsd"
}

/** 接收端在局域网内广播自己（PRD 8.2「后台常驻与发现」的发现部分）。 */
class ServiceAdvertiser(
    context: Context,
    private val serviceName: String,
    private val port: Int,
) {
    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private var listener: NsdManager.RegistrationListener? = null

    fun start() {
        if (listener != null) return
        val info = NsdServiceInfo().apply {
            serviceName = this@ServiceAdvertiser.serviceName
            serviceType = Nsd.SERVICE_TYPE
            setPort(this@ServiceAdvertiser.port)
        }
        val registration = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                Log.i(Nsd.TAG, "advertised as ${info.serviceName}:$port")
            }

            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.w(Nsd.TAG, "registration failed: $errorCode")
            }

            override fun onServiceUnregistered(info: NsdServiceInfo) = Unit

            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) = Unit
        }
        listener = registration
        nsdManager.registerService(info, NsdManager.PROTOCOL_DNS_SD, registration)
    }

    fun stop() {
        listener?.let {
            runCatching { nsdManager.unregisterService(it) }
            listener = null
        }
    }
}

/** 发送端发现接收端。 */
class ServiceBrowser(
    context: Context,
    private val onFound: (DiscoveredService) -> Unit,
    private val onLost: (String) -> Unit,
) {
    data class DiscoveredService(val name: String, val host: String, val port: Int)

    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private var discovery: NsdManager.DiscoveryListener? = null
    private val resolving = HashSet<String>()

    fun start() {
        if (discovery != null) return
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit

            override fun onServiceFound(info: NsdServiceInfo) {
                if (info.serviceType != Nsd.SERVICE_TYPE) return
                if (!resolving.add(info.serviceName)) return
                @Suppress("DEPRECATION")
                nsdManager.resolveService(info, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                        resolving.remove(info.serviceName)
                    }

                    override fun onServiceResolved(info: NsdServiceInfo) {
                        val host = info.host?.hostAddress ?: return
                        onFound(DiscoveredService(info.serviceName, host, info.port))
                    }
                })
            }

            override fun onServiceLost(info: NsdServiceInfo) {
                resolving.remove(info.serviceName)
                onLost(info.serviceName)
            }

            override fun onDiscoveryStopped(serviceType: String) = Unit

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(Nsd.TAG, "start discovery failed: $errorCode")
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        }
        discovery = listener
        nsdManager.discoverServices(Nsd.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    fun stop() {
        discovery?.let {
            runCatching { nsdManager.stopServiceDiscovery(it) }
            discovery = null
        }
    }
}
