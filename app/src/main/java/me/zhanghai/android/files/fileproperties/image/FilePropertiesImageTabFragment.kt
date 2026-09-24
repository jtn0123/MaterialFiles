/*
 * Copyright (c) 2020 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.fileproperties.image

import java8.nio.file.Path
import kotlinx.coroutines.Job
import kotlinx.parcelize.Parcelize
import kotlinx.parcelize.WriteWith
import me.zhanghai.android.files.R
import me.zhanghai.android.files.file.FileItem
import me.zhanghai.android.files.file.MimeType
import me.zhanghai.android.files.file.formatLong
import me.zhanghai.android.files.file.isImage
import me.zhanghai.android.files.filelist.name
import me.zhanghai.android.files.fileproperties.FilePropertiesTabFragment
import me.zhanghai.android.files.util.ParcelableArgs
import me.zhanghai.android.files.util.ParcelableParceler
import me.zhanghai.android.files.util.Stateful
import me.zhanghai.android.files.util.args
import me.zhanghai.android.files.util.viewModels

class FilePropertiesImageTabFragment : FilePropertiesTabFragment() {
    private val args by args<Args>()

    private val viewModel by viewModels {
        { FilePropertiesImageTabViewModel(args.path, args.mimeType) }
    }

    private var addressJob: Job? = null

    override fun onResume() {
        super.onResume()

        viewModel.imageInfoLiveData.observe(viewLifecycleOwner) { onImageInfoChanged(it) }
    }

    override fun refresh() {
        viewModel.reload()
    }

    private fun onImageInfoChanged(stateful: Stateful<ImageInfo>) {
        addressJob?.cancel()
        addressJob = null
        bindView(stateful) { imageInfo ->
            addItemView(
                R.string.file_properties_media_dimensions,
                imageInfo.dimensions?.let {
                    getString(R.string.file_properties_media_dimensions_format, it.width, it.height)
                } ?: getString(R.string.unknown)
            )
            imageInfo.exifInfo?.let { addExifItemViews(it) }
        }
    }

    private fun ViewBuilder.addExifItemViews(exifInfo: ExifInfo) {
        addItemViewIfNotNull(R.string.file_properties_media_date_time, exifInfo.dateTimeOriginal) {
            it.formatLong()
        }
        val gpsCoordinates = exifInfo.gpsCoordinates
        if (gpsCoordinates != null) {
            addressJob = addLocationItemViews(
                getString(
                    R.string.file_properties_media_coordinates_format,
                    gpsCoordinates.first,
                    gpsCoordinates.second
                ),
                gpsCoordinates.first,
                gpsCoordinates.second,
                args.path.name
            )
        }
        addItemViewIfNotNull(R.string.file_properties_image_gps_altitude, exifInfo.gpsAltitude) {
            getString(R.string.file_properties_image_gps_altitude_format, it)
        }
        addCameraItemViews(exifInfo)
        addItemViewIfNotNull(R.string.file_properties_image_software, exifInfo.software)
        addItemViewIfNotNull(R.string.file_properties_image_description, exifInfo.description)
        addItemViewIfNotNull(R.string.file_properties_image_artist, exifInfo.artist)
        addItemViewIfNotNull(R.string.file_properties_image_copyright, exifInfo.copyright)
    }

    private fun ViewBuilder.addCameraItemViews(exifInfo: ExifInfo) {
        addItemViewIfNotNull(
            R.string.file_properties_image_equipment,
            getEquipment(exifInfo.make, exifInfo.model) { make, model ->
                getString(R.string.file_properties_image_equipment_format, make, model)
            }
        )
        addItemViewIfNotNull(R.string.file_properties_image_f_number, exifInfo.fNumber) {
            getString(R.string.file_properties_image_f_number_format, it)
        }
        addItemViewIfNotNull(
            R.string.file_properties_image_shutter_speed,
            exifInfo.shutterSpeedValue
        ) { value ->
            getShutterSpeedText(value) {
                getString(R.string.file_properties_image_shutter_speed_with_denominator_format, it)
            }
        }
        addItemViewIfNotNull(R.string.file_properties_image_focal_length, exifInfo.focalLength) {
            getString(R.string.file_properties_image_focal_length_format, it)
        }
        addItemViewIfNotNull(
            R.string.file_properties_image_photographic_sensitivity,
            exifInfo.photographicSensitivity
        ) { getString(R.string.file_properties_image_photographic_sensitivity_format, it) }
    }

    companion object {
        fun isAvailable(file: FileItem): Boolean = file.mimeType.isImage
    }

    @Parcelize
    class Args(val path: @WriteWith<ParcelableParceler> Path, val mimeType: MimeType) :
        ParcelableArgs
}
