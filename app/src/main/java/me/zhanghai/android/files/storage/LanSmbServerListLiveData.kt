/*
 * Copyright (c) 2021 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.storage

import java.net.Inet4Address
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import jcifs.context.SingletonContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.channels.produce
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import me.zhanghai.android.files.util.CloseableLiveData
import me.zhanghai.android.files.util.Failure
import me.zhanghai.android.files.util.Loading
import me.zhanghai.android.files.util.Stateful
import me.zhanghai.android.files.util.Success
import me.zhanghai.android.files.util.backgroundExecutor
import me.zhanghai.android.files.util.getLocalAddress
import me.zhanghai.android.files.util.toLinkedSet
import me.zhanghai.android.files.util.valueCompat

class LanSmbServerListLiveData : CloseableLiveData<Stateful<List<LanSmbServer>>>() {
    private var loadFuture: Future<*>? = null

    init {
        loadValue()
    }

    fun loadValue() {
        cancelLoadingValue()
        value = Loading(value?.value)
        loadFuture = backgroundExecutor.submit {
            try {
                val newServerSet = mutableSetOf<LanSmbServer>()
                Executors.newFixedThreadPool(60).asCoroutineDispatcher().use { dispatcher ->
                    runBlocking(dispatcher) {
                        // The NetBIOS computer-browser service (NetServerEnum) needs SMB1,
                        // which the app no longer negotiates; Windows stopped providing it
                        // years ago in any case. Scanning the subnet is the only source.
                        val serverChannel = getServersByScanningSubnet()
                        serverChannel.consumeEach {
                            // Use linked set to preserve UI stability.
                            val serverSet = valueCompat.value?.toLinkedSet() ?: linkedSetOf()
                            serverSet += it
                            val servers = serverSet.toList()
                            postValue(Loading(servers))
                            newServerSet += it
                        }
                    }
                }
                // Remove old servers that aren't found any more.
                val newServers = (valueCompat.value ?: emptyList()).toMutableList()
                newServers.retainAll(newServerSet)
                postValue(Success(newServers))
            } catch (e: Exception) {
                postValue(Failure(valueCompat.value, e))
            }
        }
    }

    private fun CoroutineScope.getServersByScanningSubnet(): ReceiveChannel<LanSmbServer> =
        produce {
            launch {
                val localAddress = InetAddress::class.getLocalAddress()
                if (localAddress !is Inet4Address || !localAddress.isSiteLocalAddress) {
                    return@launch
                }
                val nameServiceClient = SingletonContext.getInstance().nameServiceClient
                for (address in localAddress.getSubnetAddresses()) {
                    launch {
                        val nbtAddresses = try {
                            nameServiceClient.getNbtAllByAddress(address.hostAddress)
                        } catch (e: UnknownHostException) {
                            e.printStackTrace()
                            return@launch
                        }
                        val host = nbtAddresses.firstOrNull()?.hostName ?: return@launch
                        send(LanSmbServer(host, address))
                    }
                }
            }
        }

    private fun Inet4Address.getSubnetAddresses(): Sequence<Inet4Address> = sequence {
        val addressBytes = address
        for (i in 0..99) {
            for (j in 0..2) {
                val lastBit = 100 * j + i
                if (lastBit > 255) {
                    continue
                }
                addressBytes[3] = lastBit.toByte()
                yield(InetAddress.getByAddress(addressBytes) as Inet4Address)
            }
        }
    }

    override fun close() {
        cancelLoadingValue()
    }

    private fun cancelLoadingValue() {
        loadFuture?.cancel(true)
        loadFuture = null
    }
}
