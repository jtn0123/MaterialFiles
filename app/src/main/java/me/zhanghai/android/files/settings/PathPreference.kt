/*
 * Copyright (c) 2019 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.settings

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.util.AttributeSet
import androidx.annotation.AttrRes
import androidx.annotation.StyleRes
import androidx.core.content.res.TypedArrayUtils
import androidx.core.content.res.use
import androidx.preference.Preference
import java8.nio.file.Path
import me.zhanghai.android.files.filelist.FileListActivity
import me.zhanghai.android.files.filelist.toUserFriendlyString
import me.zhanghai.android.files.navigation.NavigationRootMapLiveData
import me.zhanghai.android.files.ui.ActivityResultPreference
import me.zhanghai.android.files.util.valueCompat

abstract class PathPreference :
    Preference,
    ActivityResultPreference {
    private val openPathContract = FileListActivity.OpenDirectoryContract()

    var path: Path = persistedPath
        set(value) {
            if (field == value) {
                return
            }
            field = value
            persistedPath = value
            notifyChanged()
        }

    constructor(context: Context) : super(context) {
        init(null, 0, 0)
    }

    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs) {
        init(attrs, 0, 0)
    }

    constructor(
        context: Context,
        attrs: AttributeSet?,
        @AttrRes defStyleAttr: Int,
        @StyleRes defStyleRes: Int
    ) : super(context, attrs, defStyleAttr, defStyleRes) {
        init(attrs, defStyleAttr, defStyleRes)
    }

    @SuppressLint("PrivateResource", "RestrictedApi")
    private fun init(attrs: AttributeSet?, @AttrRes defStyleAttr: Int, @StyleRes defStyleRes: Int) {
        isPersistent = false
        context.obtainStyledAttributes(
            attrs,
            androidx.preference.R.styleable.EditTextPreference,
            defStyleAttr,
            defStyleRes
        ).use {
            if (TypedArrayUtils.getBoolean(
                    it,
                    androidx.preference.R.styleable.EditTextPreference_useSimpleSummaryProvider,
                    androidx.preference.R.styleable.EditTextPreference_useSimpleSummaryProvider,
                    false
                )
            ) {
                summaryProvider = SimpleSummaryProvider
            }
        }
    }

    override fun createIntent(context: Context): Intent =
        openPathContract.createIntent(context, path)

    override fun onActivityResult(resultCode: Int, data: Intent?) {
        openPathContract.parseResult(resultCode, data)?.let { path = it }
    }

    protected abstract var persistedPath: Path

    companion object {
        /** Shows the navigation root name for the path, or the path itself. */
        val SimpleSummaryProvider = SummaryProvider<PathPreference> { preference ->
            val path = preference.path
            val navigationRoot = NavigationRootMapLiveData.valueCompat[path]
            navigationRoot?.getName(preference.context) ?: path.toUserFriendlyString()
        }
    }
}
