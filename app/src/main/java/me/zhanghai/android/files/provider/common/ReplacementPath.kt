/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.common

import java8.nio.file.Path
import kotlin.random.Random

/**
 * A hidden sibling to write a replacement into, so that the file being replaced is only removed
 * once its replacement is complete. Random so that two jobs replacing the same file cannot pick
 * the same name.
 */
fun Path.replacementSibling(): Path =
    resolveSibling(".$fileName.${Random.nextLong().toULong().toString(16)}.part")
