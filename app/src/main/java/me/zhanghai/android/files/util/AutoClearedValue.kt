/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.util

import androidx.fragment.app.Fragment
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.Observer
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

/**
 * A property that is cleared when the fragment's view is destroyed, so that a detached fragment
 * does not keep its whole view tree reachable through e.g. a view binding.
 *
 * The value is cleared before [Fragment.onDestroyView] is called.
 */
class AutoClearedValue<T : Any>(fragment: Fragment) : ReadWriteProperty<Fragment, T> {
    private var value: T? = null

    init {
        fragment.lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                private val viewLifecycleOwnerObserver = Observer<LifecycleOwner?> { owner ->
                    owner?.lifecycle?.addObserver(
                        object : DefaultLifecycleObserver {
                            override fun onDestroy(owner: LifecycleOwner) {
                                value = null
                            }
                        }
                    )
                }

                override fun onCreate(owner: LifecycleOwner) {
                    fragment.viewLifecycleOwnerLiveData.observeForever(viewLifecycleOwnerObserver)
                }

                override fun onDestroy(owner: LifecycleOwner) {
                    fragment.viewLifecycleOwnerLiveData.removeObserver(viewLifecycleOwnerObserver)
                }
            }
        )
    }

    override fun getValue(thisRef: Fragment, property: KProperty<*>): T =
        value ?: throw IllegalStateException(
            "${property.name} is only available while the fragment's view exists"
        )

    override fun setValue(thisRef: Fragment, property: KProperty<*>, value: T) {
        this.value = value
    }
}

fun <T : Any> Fragment.autoCleared(): AutoClearedValue<T> = AutoClearedValue(this)
