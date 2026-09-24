/*
 * Copyright (c) 2023 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.compat

import android.app.LocaleConfig
import android.content.Context
import androidx.core.os.LocaleListCompat

/**
 * Wraps [android.app.LocaleConfig]; the manifest parser that stood in for it before Android 13
 * went away with minSdk 35.
 */
class LocaleConfigCompat(context: Context) {
    val status: Int

    val supportedLocales: LocaleListCompat?

    init {
        val platformLocaleConfig = LocaleConfig(context)
        status = platformLocaleConfig.status
        supportedLocales = platformLocaleConfig.supportedLocales?.let { LocaleListCompat.wrap(it) }
    }

    companion object {
        /**
         * Succeeded reading the LocaleConfig structure stored in an XML file.
         */
        const val STATUS_SUCCESS = LocaleConfig.STATUS_SUCCESS

        /**
         * No android:localeConfig tag on <application>.
         */
        const val STATUS_NOT_SPECIFIED = LocaleConfig.STATUS_NOT_SPECIFIED

        /**
         * Malformed input in the XML file where the LocaleConfig was stored.
         */
        const val STATUS_PARSING_FAILED = LocaleConfig.STATUS_PARSING_FAILED
    }
}
