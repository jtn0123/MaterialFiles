/*
 * Copyright (c) 2020 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.fileproperties

import android.content.Intent
import android.location.Geocoder
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.core.view.forEach
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.viewbinding.ViewBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import me.zhanghai.android.files.R
import me.zhanghai.android.files.databinding.FilePropertiesTabFragmentBinding
import me.zhanghai.android.files.databinding.FilePropertiesTabItemBinding
import me.zhanghai.android.files.util.Failure
import me.zhanghai.android.files.util.Loading
import me.zhanghai.android.files.util.Stateful
import me.zhanghai.android.files.util.autoCleared
import me.zhanghai.android.files.util.awaitGetFromLocation
import me.zhanghai.android.files.util.createViewLocation
import me.zhanghai.android.files.util.fadeToVisibilityUnsafe
import me.zhanghai.android.files.util.isGeocoderPresent
import me.zhanghai.android.files.util.layoutInflater
import me.zhanghai.android.files.util.logWarning
import me.zhanghai.android.files.util.showToast
import me.zhanghai.android.files.util.startActivitySafe
import me.zhanghai.android.files.util.userFriendlyString

abstract class FilePropertiesTabFragment : Fragment() {
    protected var binding by autoCleared<FilePropertiesTabFragmentBinding>()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = FilePropertiesTabFragmentBinding.inflate(inflater, container, false)
        .also { binding = it }
        .root

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.swipeRefreshLayout.setOnRefreshListener { refresh() }
    }

    abstract fun refresh()

    protected inline fun <T> bindView(stateful: Stateful<T>, block: ViewBuilder.(T) -> Unit) {
        val value = stateful.value
        val hasValue = value != null
        binding.progress.fadeToVisibilityUnsafe(stateful is Loading && !hasValue)
        binding.swipeRefreshLayout.isRefreshing = stateful is Loading && hasValue
        binding.errorText.fadeToVisibilityUnsafe(stateful is Failure && !hasValue)
        if (stateful is Failure) {
            stateful.throwable.logWarning("FilePropertiesTabFragment", "Load the file properties")
            val error = stateful.throwable.toString()
            if (hasValue) {
                showToast(error)
            } else {
                binding.errorText.text = error
            }
        }
        binding.scrollView.fadeToVisibilityUnsafe(hasValue)
        if (value != null) {
            ViewBuilder(binding.linearLayout).apply {
                block(value)
                build()
            }
        }
    }

    /**
     * Adds the coordinates of a location, which open it in a map app when clicked, and its address
     * once the geocoder finds it. Returns the job looking up the address, if there is a geocoder.
     */
    protected fun ViewBuilder.addLocationItemViews(
        coordinatesText: String,
        latitude: Double,
        longitude: Double,
        label: String
    ): Job? {
        addItemView(R.string.file_properties_media_coordinates, coordinatesText) {
            startActivitySafe(
                Intent::class.createViewLocation(latitude.toFloat(), longitude.toFloat(), label)
            )
        }
        if (!isGeocoderPresent) {
            return null
        }
        val textView =
            addItemView(R.string.file_properties_media_address, getString(R.string.loading))
        val geocoder = Geocoder(requireContext())
        return viewLifecycleOwner.lifecycleScope.launch {
            val address = try {
                geocoder.awaitGetFromLocation(latitude, longitude, 1).first()
            } catch (e: Exception) {
                null
            }
            if (isActive) {
                textView.text = address?.userFriendlyString ?: getString(R.string.unknown)
            }
        }
    }

    protected class ViewBuilder(val linearLayout: LinearLayout) {
        private val scrapViews = mutableMapOf<Class<out ViewBinding>, MutableList<ViewBinding>>()

        init {
            linearLayout.forEach { view ->
                val binding = view.tag as ViewBinding
                scrapViews.getOrPut(binding.javaClass) { mutableListOf() } += binding
            }
            linearLayout.removeAllViews()
        }

        @Suppress("UNCHECKED_CAST")
        fun <T : ViewBinding> getScrapItemBinding(bindingClass: Class<T>): T? =
            scrapViews[bindingClass]?.removeLastOrNull() as T?

        fun addView(binding: ViewBinding) {
            linearLayout.addView(binding.root)
        }

        fun addItemView(
            hint: String,
            text: String,
            onClickListener: ((View) -> Unit)? = null
        ): TextView {
            val itemBinding =
                getScrapItemBinding(FilePropertiesTabItemBinding::class.java)?.also { addView(it) }
                    ?: FilePropertiesTabItemBinding.inflate(
                        linearLayout.context.layoutInflater,
                        linearLayout,
                        true
                    )
                        .also { it.root.tag = it }
            itemBinding.textInputLayout.hint = hint
            itemBinding.textInputLayout.setDropDown(onClickListener != null)
            itemBinding.text.setText(text)
            itemBinding.text.setTextIsSelectable(onClickListener == null)
            itemBinding.text.setOnClickListener(onClickListener?.let { View.OnClickListener(it) })
            return itemBinding.text
        }

        fun addItemView(
            @StringRes hintRes: Int,
            text: String,
            onClickListener: ((View) -> Unit)? = null
        ): TextView = addItemView(linearLayout.context.getString(hintRes), text, onClickListener)

        /** Adds an item for [value] unless it is `null`, showing it as [format] returns. */
        fun <T : Any> addItemViewIfNotNull(
            @StringRes hintRes: Int,
            value: T?,
            format: (T) -> String
        ) {
            if (value != null) {
                addItemView(hintRes, format(value))
            }
        }

        fun addItemViewIfNotNull(@StringRes hintRes: Int, text: String?) {
            addItemViewIfNotNull(hintRes, text) { it }
        }

        fun build() {
            scrapViews.clear()
        }
    }
}
