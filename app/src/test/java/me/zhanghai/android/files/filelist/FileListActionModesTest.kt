/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filelist

import me.zhanghai.android.files.R
import org.junit.Assert.assertEquals
import org.junit.Test

class FileListActionModesTest {
    @Test
    fun pastingOutOfAnArchiveIsCalledExtracting() {
        assertEquals(R.string.file_list_paste_copy_title_format, getPasteTitleRes(true, false))
        assertEquals(R.string.file_list_paste_extract_title_format, getPasteTitleRes(true, true))
        assertEquals(R.string.file_list_paste_move_title_format, getPasteTitleRes(false, false))
        assertEquals(R.string.file_list_paste_move_title_format, getPasteTitleRes(false, true))
    }
}
