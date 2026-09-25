/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.viewer.video

import androidx.media3.common.PlaybackException
import java.io.FileNotFoundException
import java.io.IOException
import java.net.ConnectException
import me.zhanghai.android.files.R
import me.zhanghai.android.files.provider.common.AuthenticationFailedException
import org.junit.Assert.assertEquals
import org.junit.Test

/** What the player says when a video cannot be played, instead of just that it cannot. */
class VideoPlaybackErrorsTest {
    private fun parts(errorCode: Int, cause: Throwable? = null) =
        playbackErrorUserMessageParts(errorCode, "ERROR_CODE_$errorCode", cause)

    @Test
    fun aFileWeCouldNotReadSaysWhy() {
        // How the content data source reports our file provider failing.
        val notFound = IOException("wrapped", FileNotFoundException("/share/clip.mp4"))
        assertEquals(
            R.string.error_file_not_found to "/share/clip.mp4",
            parts(PlaybackException.ERROR_CODE_IO_UNSPECIFIED, notFound)
        )
        assertEquals(
            R.string.error_authentication_failed to "/share/clip.mp4",
            parts(
                PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
                AuthenticationFailedException("/share/clip.mp4")
            )
        )
        assertEquals(
            R.string.error_connection_failed to "refused",
            parts(PlaybackException.ERROR_CODE_IO_UNSPECIFIED, ConnectException("refused"))
        )
    }

    @Test
    fun anInputErrorCodeSaysWhatTheCauseDoesNot() {
        val cause = IOException("EACCES")
        assertEquals(
            R.string.error_access_denied to "EACCES",
            parts(PlaybackException.ERROR_CODE_IO_NO_PERMISSION, cause)
        )
        assertEquals(
            R.string.error_file_not_found to null,
            parts(PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND)
        )
        assertEquals(
            R.string.error_connection_failed to null,
            parts(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)
        )
        assertEquals(
            R.string.error_connection_timed_out to null,
            parts(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT)
        )
    }

    @Test
    fun aFormatTheDeviceCannotPlayLeavesOutTheDecoderDump() {
        for (errorCode in listOf(
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES
        )) {
            assertEquals(
                R.string.video_viewer_error_unsupported to null,
                parts(errorCode, IllegalStateException("Format(id=1, mimeType=video/hevc)"))
            )
        }
    }

    @Test
    fun aDamagedFileSaysSo() {
        assertEquals(
            R.string.video_viewer_error_malformed to null,
            parts(PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED)
        )
        assertEquals(
            R.string.video_viewer_error_malformed to null,
            parts(PlaybackException.ERROR_CODE_DECODING_FAILED)
        )
    }

    @Test
    fun anythingElseFallsBackToTheCauseOrTheErrorCodeName() {
        assertEquals(
            null to "renderer died",
            parts(PlaybackException.ERROR_CODE_UNSPECIFIED, RuntimeException("renderer died"))
        )
        assertEquals(
            null to "ERROR_CODE_${PlaybackException.ERROR_CODE_REMOTE_ERROR}",
            parts(PlaybackException.ERROR_CODE_REMOTE_ERROR)
        )
    }
}
