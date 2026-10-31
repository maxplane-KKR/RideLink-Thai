// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.zanderp.opencfmoto

fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

fun ByteArray.asText(): String = runCatching {
    String(this, Charsets.UTF_8).filter { it in ' '..'~' || it == '\n' || it == '\r' || it == '\t' }
}.getOrDefault("")
