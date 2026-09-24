/*
 * Copyright (c) 2020 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.util

import android.location.Address
import android.location.Geocoder
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

val isGeocoderPresent by lazy { Geocoder.isPresent() }

/**
 * The addresses at a location, from the platform geocoder, which does its own work off the
 * calling thread and calls back with either a result or an error message.
 */
@Throws(IOException::class)
suspend fun Geocoder.awaitGetFromLocation(
    latitude: Double,
    longitude: Double,
    maxResults: Int
): List<Address> = suspendCancellableCoroutine { continuation ->
    getFromLocation(
        latitude,
        longitude,
        maxResults,
        object : Geocoder.GeocodeListener {
            override fun onGeocode(addresses: MutableList<Address>) {
                continuation.resume(addresses)
            }

            override fun onError(errorMessage: String?) {
                continuation.resumeWithException(IOException(errorMessage))
            }
        }
    )
}
