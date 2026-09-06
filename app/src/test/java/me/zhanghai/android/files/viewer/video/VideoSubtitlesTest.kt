/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.viewer.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VideoSubtitlesTest {
    @Test
    fun exactBaseNameIsASidecarWithoutSuffix() {
        assertEquals("", VideoSubtitles.sidecarSuffix("Movie", "Movie"))
    }

    @Test
    fun dottedSuffixIsASidecar() {
        assertEquals("en", VideoSubtitles.sidecarSuffix("Movie", "Movie.en"))
        assertEquals("forced.en", VideoSubtitles.sidecarSuffix("Movie", "Movie.forced.en"))
        // "Movie.mp4.srt" has the base name "Movie.mp4".
        assertEquals("mp4", VideoSubtitles.sidecarSuffix("Movie", "Movie.mp4"))
    }

    @Test
    fun otherNamesAreNotSidecars() {
        assertNull(VideoSubtitles.sidecarSuffix("Movie", "Movie 2"))
        assertNull(VideoSubtitles.sidecarSuffix("Movie", "Movies"))
        assertNull(VideoSubtitles.sidecarSuffix("Movie", "Movi"))
        assertNull(VideoSubtitles.sidecarSuffix("Movie", "Other"))
        assertNull(VideoSubtitles.sidecarSuffix("Movie 2", "Movie"))
    }

    @Test
    fun emptyVideoBaseNameNeverMatches() {
        assertNull(VideoSubtitles.sidecarSuffix("", ""))
        assertNull(VideoSubtitles.sidecarSuffix("", ".en"))
    }

    @Test
    fun languageTagsAreRecognized() {
        assertEquals("en", VideoSubtitles.languageFromSuffix("en"))
        assertEquals("eng", VideoSubtitles.languageFromSuffix("eng"))
        assertEquals("pt-BR", VideoSubtitles.languageFromSuffix("pt-BR"))
        assertEquals("zh-Hans", VideoSubtitles.languageFromSuffix("zh-Hans"))
    }

    @Test
    fun nonLanguageSuffixesAreIgnored() {
        assertNull(VideoSubtitles.languageFromSuffix(""))
        assertNull(VideoSubtitles.languageFromSuffix("forced"))
        assertNull(VideoSubtitles.languageFromSuffix("English"))
        assertNull(VideoSubtitles.languageFromSuffix("mp4"))
        assertNull(VideoSubtitles.languageFromSuffix("forced.en"))
        assertNull(VideoSubtitles.languageFromSuffix("e"))
    }
}
