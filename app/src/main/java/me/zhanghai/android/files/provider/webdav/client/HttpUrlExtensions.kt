/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.webdav.client

import okhttp3.HttpUrl

/** This URL with the trailing slash that addresses a WebDAV collection; a no-op if present. */
fun HttpUrl.toCollectionUrl(): HttpUrl =
    if (pathSegments.last().isEmpty()) this else newBuilder().addPathSegment("").build()
