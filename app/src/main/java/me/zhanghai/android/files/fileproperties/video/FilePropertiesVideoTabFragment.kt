/*
 * Copyright (c) 2020 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.fileproperties.video

import java8.nio.file.Path
import kotlinx.coroutines.Job
import kotlinx.parcelize.Parcelize
import kotlinx.parcelize.WriteWith
import me.zhanghai.android.files.R
import me.zhanghai.android.files.file.FileItem
import me.zhanghai.android.files.file.format
import me.zhanghai.android.files.file.formatLong
import me.zhanghai.android.files.file.isVideo
import me.zhanghai.android.files.filelist.name
import me.zhanghai.android.files.fileproperties.FilePropertiesTabFragment
import me.zhanghai.android.files.util.ParcelableArgs
import me.zhanghai.android.files.util.ParcelableParceler
import me.zhanghai.android.files.util.Stateful
import me.zhanghai.android.files.util.args
import me.zhanghai.android.files.util.isMediaMetadataRetrieverCompatible
import me.zhanghai.android.files.util.viewModels

class FilePropertiesVideoTabFragment : FilePropertiesTabFragment() {
    private val args by args<Args>()

    private val viewModel by viewModels { { FilePropertiesVideoTabViewModel(args.path) } }

    private var addressJob: Job? = null

    override fun onResume() {
        super.onResume()

        viewModel.videoInfoLiveData.observe(viewLifecycleOwner) { onVideoInfoChanged(it) }
    }

    override fun refresh() {
        viewModel.reload()
    }

    private fun onVideoInfoChanged(stateful: Stateful<VideoInfo>) {
        addressJob?.cancel()
        addressJob = null
        bindView(stateful) { videoInfo ->
            addItemViewIfNotNull(R.string.file_properties_media_title, videoInfo.title)
            addItemViewIfNotNull(R.string.file_properties_media_dimensions, videoInfo.dimensions) {
                getString(R.string.file_properties_media_dimensions_format, it.width, it.height)
            }
            addItemViewIfNotNull(R.string.file_properties_media_duration, videoInfo.duration) {
                it.format()
            }
            addItemViewIfNotNull(R.string.file_properties_media_date_time, videoInfo.date) {
                it.formatLong()
            }
            val location = videoInfo.location
            if (location != null) {
                addressJob = addLocationItemViews(
                    getString(
                        R.string.file_properties_media_coordinates_format,
                        location.first,
                        location.second
                    ),
                    location.first.toDouble(),
                    location.second.toDouble(),
                    args.path.name
                )
            }
            addItemViewIfNotNull(R.string.file_properties_media_bit_rate, videoInfo.bitRate) {
                getString(R.string.file_properties_media_bit_rate_format, it / 1000)
            }
        }
    }

    companion object {
        fun isAvailable(file: FileItem): Boolean =
            file.mimeType.isVideo && file.path.isMediaMetadataRetrieverCompatible
    }

    @Parcelize
    class Args(val path: @WriteWith<ParcelableParceler> Path) : ParcelableArgs
}
