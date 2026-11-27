package com.linkme.androidbridge.core.transport

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo

/**
 * Registers and discovers `_linkme._tcp.` mDNS services on the local Wi-Fi network.
 */
class NsdDiscoveryManager(context: Context) {
    private val nsdManager = context.getSystemService(NsdManager::class.java)
    private var registrationListener: NsdManager.RegistrationListener? = null

    fun registerService(serviceName: String, port: Int) {
        val info = NsdServiceInfo().apply {
            this.serviceName = serviceName
            this.serviceType = SERVICE_TYPE
            this.port = port
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {}
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {}
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
        }
        registrationListener = listener
        nsdManager?.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    fun unregister() {
        registrationListener?.let { listener ->
            nsdManager?.unregisterService(listener)
        }
        registrationListener = null
    }

    companion object {
        const val SERVICE_TYPE = "_andromac._tcp."
    }
}
