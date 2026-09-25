/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.viewer.video

import androidx.media3.common.PlaybackException
import me.zhanghai.android.files.R
import me.zhanghai.android.files.util.toUserMessageParts

/**
 * The string resource saying why playback failed with [errorCode] (a
 * [PlaybackException.errorCode]) and [cause], if it is a recognized kind, and the detail to show
 * under it. Without a resource the detail is never null.
 *
 * The cause says best what went wrong when it is a file we could not read, e.g. on a server that
 * went away, so it comes first; otherwise the error code tells a format the device cannot play
 * from a file that is damaged.
 */
internal fun playbackErrorUserMessageParts(
    errorCode: Int,
    errorCodeName: String,
    cause: Throwable?
): Pair<Int?, String?> {
    val causeParts = cause?.toUserMessageParts()
    if (causeParts?.first != null) {
        return causeParts
    }
    return when (errorCode) {
        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND ->
            R.string.error_file_not_found to causeParts?.second

        PlaybackException.ERROR_CODE_IO_NO_PERMISSION ->
            R.string.error_access_denied to causeParts?.second

        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ->
            R.string.error_connection_failed to causeParts?.second

        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ->
            R.string.error_connection_timed_out to causeParts?.second

        // The decoder's own message is a dump of the format, which says nothing to the user.
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES ->
            R.string.video_viewer_error_unsupported to null

        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
        PlaybackException.ERROR_CODE_DECODING_FAILED ->
            R.string.video_viewer_error_malformed to null

        else -> null to (causeParts?.second ?: errorCodeName)
    }
}
