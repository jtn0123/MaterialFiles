/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.viewer.video

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import java8.nio.file.Path
import me.zhanghai.android.files.file.fileProviderUri
import me.zhanghai.android.files.filelist.name
import me.zhanghai.android.files.provider.common.newDirectoryStream
import me.zhanghai.android.files.util.asFileNameOrNull
import java.io.IOException
import java.util.Locale

/**
 * Finds sidecar subtitle files, i.e. subtitles that sit next to the video and share its base name,
 * like `Movie.mp4` and `Movie.en.srt`.
 */
object VideoSubtitles {
    private val MIME_TYPES_BY_EXTENSION = mapOf(
        "srt" to MimeTypes.APPLICATION_SUBRIP,
        "ssa" to MimeTypes.TEXT_SSA,
        "ass" to MimeTypes.TEXT_SSA,
        "vtt" to MimeTypes.TEXT_VTT,
        "ttml" to MimeTypes.APPLICATION_TTML,
        "dfxp" to MimeTypes.APPLICATION_TTML,
        "xml" to MimeTypes.APPLICATION_TTML
    )

    /**
     * Lists the parent directory of [videoPaths] once and returns the sidecar subtitles for each of
     * them. Blocking, and so must not be called on the main thread.
     */
    fun findForAll(videoPaths: List<Path>): Map<Path, List<MediaItem.SubtitleConfiguration>> {
        val subtitlePathsByDirectory = mutableMapOf<Path, List<Path>?>()
        return videoPaths.associateWith { videoPath ->
            val directory = videoPath.parent ?: return@associateWith emptyList()
            val subtitlePaths = subtitlePathsByDirectory.getOrPut(directory) {
                listSubtitles(directory)
            } ?: return@associateWith emptyList()
            find(videoPath, subtitlePaths)
        }
    }

    private fun listSubtitles(directory: Path): List<Path>? =
        try {
            directory.newDirectoryStream().use { directoryStream ->
                directoryStream.filter { it.extension in MIME_TYPES_BY_EXTENSION }
            }
        } catch (e: IOException) {
            e.printStackTrace()
            null
        }

    private fun find(
        videoPath: Path,
        subtitlePaths: List<Path>
    ): List<MediaItem.SubtitleConfiguration> {
        val videoBaseName = videoPath.baseName
        if (videoBaseName.isEmpty()) {
            return emptyList()
        }
        return subtitlePaths
            .filter {
                val baseName = it.baseName
                // Accept both "Movie.srt" and "Movie.en.srt".
                baseName == videoBaseName
                    || (baseName.length > videoBaseName.length
                        && baseName.startsWith(videoBaseName)
                        && baseName[videoBaseName.length] == '.')
            }
            .map { subtitlePath ->
                val baseName = subtitlePath.baseName
                // "Movie.en.srt" is very likely English, and language tags are short.
                val language = baseName.substring(videoBaseName.length)
                    .removePrefix(".")
                    .takeIf { it.isNotEmpty() && it.length <= 8 }
                MediaItem.SubtitleConfiguration.Builder(subtitlePath.fileProviderUri)
                    .setMimeType(MIME_TYPES_BY_EXTENSION[subtitlePath.extension])
                    .setLanguage(language)
                    .setLabel(subtitlePath.name)
                    // Have the track selector turn it on, since that's why it is next to the
                    // video, while still letting the user turn it off from the subtitle button.
                    .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                    .build()
            }
    }

    private val Path.extension: String
        get() = name.asFileNameOrNull()?.singleExtension?.lowercase(Locale.ROOT) ?: ""

    private val Path.baseName: String
        get() = name.asFileNameOrNull()?.baseName ?: ""
}
