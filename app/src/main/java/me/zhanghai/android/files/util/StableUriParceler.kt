/*
 * Copyright (c) 2023 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.util

import android.net.Uri
import android.os.Parcel
import kotlinx.parcelize.Parceler
import me.zhanghai.android.files.hiddenapi.RestrictedHiddenApi

// The built-in parceling of Uri isn't guaranteed to be stable and has changed 2 times, so we should
// always use our own parceling for persistence.
// The reading code here is backwards compatible in that it also handles built-in parceling of Uri,
// including the changes in
// https://android.googlesource.com/platform/frameworks/base/+/97f621d81fc51de240ba73bc008d997e0eea7939 ,
// and the String8 change in API 30.
object StableUriParceler : Parceler<Uri?> {
    private const val NULL_TYPE_ID = 0
    private const val STRING_URI_TYPE_ID = 1
    private const val OPAQUE_URI_TYPE_ID = 2
    private const val HIERARCHICAL_URI_TYPE_ID = 3

    private const val REPRESENTATION_BOTH = 0
    private const val REPRESENTATION_ENCODED = 1
    private const val REPRESENTATION_DECODED = 2

    @RestrictedHiddenApi
    private val parcelReadString8Method by lazyReflectedMethod(Parcel::class.java, "readString8")

    override fun create(parcel: Parcel): Uri? {
        val uriString = parcel.readString() ?: return null
        // Parcel.readParcelableCreator()
        return if (uriString.startsWith(Uri::class.java.name)) {
            readUri(parcel)
        } else {
            Uri.parse(uriString)
        }
    }

    // Uri.CREATOR.createFromParcel()
    private fun readUri(parcel: Parcel): Uri? {
        val uriString = when (val typeId = parcel.readInt()) {
            NULL_TYPE_ID -> return null

            // Uri.StringUri.readFrom()
            STRING_URI_TYPE_ID -> parcel.readUriString()

            OPAQUE_URI_TYPE_ID -> readOpaqueUriString(parcel)

            HIERARCHICAL_URI_TYPE_ID -> readHierarchicalUriString(parcel)

            else -> error("Unknown type ID $typeId")
        }
        return Uri.parse(uriString)
    }

    // Uri.OpaqueUri.readFrom()
    private fun readOpaqueUriString(parcel: Parcel): String {
        val scheme = checkNotNull(parcel.readUriString()) { "Opaque Uri without a scheme" }
        // Assume that we never persist a Uri with only a scheme.
        if (scheme.contains(':')) {
            return scheme
        }
        val encodedSsp = readEncodedPart(parcel)
        val encodedFragment = readEncodedPart(parcel)
        return buildOpaqueUriString(scheme, encodedSsp, encodedFragment)
    }

    // Uri.HierarchicalUri.readFrom()
    private fun readHierarchicalUriString(parcel: Parcel): String {
        // Scheme can be null for HierarchicalUri.
        val scheme = parcel.readUriString()
        // Assume that we never persist a Uri with only a scheme.
        if (scheme != null && scheme.contains(':')) {
            return scheme
        }
        val encodedAuthority = readEncodedPart(parcel)
        val hasSchemeOrAuthority = !scheme.isNullOrEmpty() || !encodedAuthority.isNullOrEmpty()
        val encodedPath = readEncodedPathPart(hasSchemeOrAuthority, parcel)
        val encodedQuery = readEncodedPart(parcel)
        val encodedFragment = readEncodedPart(parcel)
        return buildHierarchicalUriString(
            scheme,
            encodedAuthority,
            encodedPath,
            encodedQuery,
            encodedFragment
        )
    }

    // Uri.OpaqueUri.toString()
    internal fun buildOpaqueUriString(
        scheme: String,
        encodedSsp: String?,
        encodedFragment: String?
    ): String = buildString {
        append(scheme)
        append(':')
        append(encodedSsp)
        appendIfNotEmpty('#', encodedFragment)
    }

    // Uri.HierarchicalUri.toString()
    internal fun buildHierarchicalUriString(
        scheme: String?,
        encodedAuthority: String?,
        encodedPath: String?,
        encodedQuery: String?,
        encodedFragment: String?
    ): String = buildString {
        if (scheme != null) {
            append(scheme)
            append(':')
        }
        if (encodedAuthority != null) {
            append("//")
            append(encodedAuthority)
        }
        if (encodedPath != null) {
            append(encodedPath)
        }
        appendIfNotEmpty('?', encodedQuery)
        appendIfNotEmpty('#', encodedFragment)
    }

    private fun StringBuilder.appendIfNotEmpty(separator: Char, part: String?) {
        if (!part.isNullOrEmpty()) {
            append(separator)
            append(part)
        }
    }

    // Uri.Part.readFrom()
    private fun readEncodedPart(parcel: Parcel): String? =
        when (val representation = parcel.readInt()) {
            REPRESENTATION_BOTH -> parcel.readUriString().also { parcel.readUriString() }
            REPRESENTATION_ENCODED -> parcel.readUriString()
            REPRESENTATION_DECODED -> Uri.encode(parcel.readUriString())
            else -> error("Unknown representation $representation")
        }

    // Uri.PathPart.readFrom()
    private fun readEncodedPathPart(hasSchemeOrAuthority: Boolean, parcel: Parcel): String? {
        val encodedPathPart = when (val representation = parcel.readInt()) {
            REPRESENTATION_BOTH -> parcel.readUriString().also { parcel.readUriString() }
            REPRESENTATION_ENCODED -> parcel.readUriString()
            REPRESENTATION_DECODED -> Uri.encode(parcel.readUriString(), "/")
            else -> error("Unknown representation $representation")
        }
        return if (hasSchemeOrAuthority) {
            makeEncodedPathPartAbsolute(encodedPathPart)
        } else {
            encodedPathPart
        }
    }

    // Uri.PathPart.makeAbsolute()
    internal fun makeEncodedPathPartAbsolute(encodedPathPart: String?): String? =
        if (encodedPathPart.isNullOrEmpty() || encodedPathPart.startsWith("/")) {
            encodedPathPart
        } else {
            "/$encodedPathPart"
        }

    private fun Parcel.readUriString(): String? = parcelReadString8Method.invoke(this) as String?

    override fun Uri?.write(parcel: Parcel, flags: Int) {
        parcel.writeString(this?.toString())
    }
}
