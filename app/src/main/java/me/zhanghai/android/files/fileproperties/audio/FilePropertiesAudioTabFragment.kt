/*
 * Copyright (c) 2020 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.fileproperties.audio

import java8.nio.file.Path
import kotlinx.parcelize.Parcelize
import kotlinx.parcelize.WriteWith
import me.zhanghai.android.files.R
import me.zhanghai.android.files.file.FileItem
import me.zhanghai.android.files.file.format
import me.zhanghai.android.files.file.isAudio
import me.zhanghai.android.files.fileproperties.FilePropertiesTabFragment
import me.zhanghai.android.files.util.ParcelableArgs
import me.zhanghai.android.files.util.ParcelableParceler
import me.zhanghai.android.files.util.Stateful
import me.zhanghai.android.files.util.args
import me.zhanghai.android.files.util.isMediaMetadataRetrieverCompatible
import me.zhanghai.android.files.util.viewModels

class FilePropertiesAudioTabFragment : FilePropertiesTabFragment() {
    private val args by args<Args>()

    private val viewModel by viewModels { { FilePropertiesAudioTabViewModel(args.path) } }

    override fun onResume() {
        super.onResume()

        viewModel.audioInfoLiveData.observe(viewLifecycleOwner) { onAudioInfoChanged(it) }
    }

    override fun refresh() {
        viewModel.reload()
    }

    private fun onAudioInfoChanged(stateful: Stateful<AudioInfo>) {
        bindView(stateful) { audioInfo ->
            addItemViewIfNotNull(R.string.file_properties_media_title, audioInfo.title)
            addItemViewIfNotNull(R.string.file_properties_audio_artist, audioInfo.artist)
            addItemViewIfNotNull(R.string.file_properties_audio_album, audioInfo.album)
            addItemViewIfNotNull(R.string.file_properties_audio_album_artist, audioInfo.albumArtist)
            addItemViewIfNotNull(R.string.file_properties_audio_composer, audioInfo.composer)
            addItemViewIfNotNull(R.string.file_properties_audio_disc_number, audioInfo.discNumber)
            addItemViewIfNotNull(R.string.file_properties_audio_track_number, audioInfo.trackNumber)
            addItemViewIfNotNull(R.string.file_properties_audio_year, audioInfo.year)
            addItemViewIfNotNull(R.string.file_properties_audio_genre, audioInfo.genre)
            addItemViewIfNotNull(R.string.file_properties_media_duration, audioInfo.duration) {
                it.format()
            }
            addItemViewIfNotNull(R.string.file_properties_media_bit_rate, audioInfo.bitRate) {
                getString(R.string.file_properties_media_bit_rate_format, it / 1000)
            }
            addItemViewIfNotNull(R.string.file_properties_audio_sample_rate, audioInfo.sampleRate) {
                getString(R.string.file_properties_audio_sample_rate_format, it)
            }
        }
    }

    companion object {
        fun isAvailable(file: FileItem): Boolean =
            file.mimeType.isAudio && file.path.isMediaMetadataRetrieverCompatible
    }

    @Parcelize
    class Args(val path: @WriteWith<ParcelableParceler> Path) : ParcelableArgs
}
