package com.naomiplasterer.convos.crypto

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.zip.Deflater
import java.util.zip.Inflater
import android.util.Log

private const val TAG = "InviteCompression"

/**
 * Handles compression and decompression of invite data to match iOS implementation.
 *
 * iOS uses DEFLATE compression with a special format:
 * [marker: 1 byte][size: 4 bytes big-endian][compressed data]
 *
 * The marker byte (0x1F) indicates compressed data.
 */
object InviteCompression {
    // Magic byte prefix for compressed data (must match iOS)
    const val COMPRESSION_MARKER: Byte = 0x1F

    // Maximum allowed decompressed size (1MB) to prevent decompression bombs
    private const val MAX_DECOMPRESSED_SIZE = 1 * 1024 * 1024

    /**
     * Compress data using DEFLATE, only if result is smaller than input.
     * Matches iOS's compressedIfSmaller() method.
     */
    fun compressIfSmaller(data: ByteArray): ByteArray? {
        val compressed = compress(data)
        return if (compressed != null && compressed.size < data.size) {
            compressed
        } else {
            null
        }
    }

    /**
     * Compress data using DEFLATE and prepend format metadata.
     * Format: [marker: 1 byte][size: 4 bytes big-endian][compressed data]
     */
    fun compress(data: ByteArray): ByteArray? {
        return try {
            val deflater = Deflater()
            deflater.setInput(data)
            deflater.finish()

            val outputStream = ByteArrayOutputStream()
            val buffer = ByteArray(1024)

            while (!deflater.finished()) {
                val compressedSize = deflater.deflate(buffer)
                outputStream.write(buffer, 0, compressedSize)
            }

            deflater.end()
            val compressedData = outputStream.toByteArray()

            // Build the final format with marker and size
            val result = ByteArrayOutputStream()
            result.write(COMPRESSION_MARKER.toInt())

            // Write original size as 4 bytes big-endian
            val sizeBuffer = ByteBuffer.allocate(4)
            sizeBuffer.putInt(data.size)
            result.write(sizeBuffer.array())

            // Write compressed data
            result.write(compressedData)

            result.toByteArray()
        } catch (e: Exception) {
            Log.e(TAG, "Compression failed", e)
            null
        }
    }

    /**
     * Decompress data that was compressed with compress().
     * Expects format: [marker: 1 byte][size: 4 bytes big-endian][compressed data]
     */
    fun decompress(data: ByteArray): ByteArray? {
        if (data.isEmpty() || data[0] != COMPRESSION_MARKER) {
            return null
        }

        if (data.size < 5) {
            Log.e(TAG, "Invalid compressed data format")
            return null
        }

        return try {
            // Read original size from bytes 1-4
            val sizeBuffer = ByteBuffer.wrap(data, 1, 4)
            val originalSize = sizeBuffer.getInt()

            if (originalSize < 0 || originalSize > MAX_DECOMPRESSED_SIZE) {
                Log.e(TAG, "Invalid decompressed size: $originalSize")
                return null
            }

            // Try decompression with zlib wrapper first (default)
            val result = tryDecompress(data, originalSize, nowrap = false)
                ?: tryDecompress(data, originalSize, nowrap = true) // Try raw DEFLATE if that fails

            if (result == null) {
                Log.e(TAG, "Decompression failed with both zlib and raw DEFLATE")
            }

            result
        } catch (e: Exception) {
            Log.e(TAG, "Decompression failed", e)
            null
        }
    }

    private fun tryDecompress(data: ByteArray, originalSize: Int, nowrap: Boolean): ByteArray? {
        return try {
            // Decompress the data starting from byte 5
            val inflater = Inflater(nowrap)
            inflater.setInput(data, 5, data.size - 5)

            val result = ByteArray(originalSize)
            val decompressedSize = inflater.inflate(result)
            inflater.end()

            if (decompressedSize != originalSize) {
                Log.d(
                    TAG,
                    "Decompressed size mismatch (nowrap=$nowrap): expected $originalSize, got $decompressedSize"
                )
                return null
            }

            Log.d(TAG, "Decompression successful with nowrap=$nowrap")
            result
        } catch (e: Exception) {
            Log.d(TAG, "Decompression with nowrap=$nowrap failed: ${e.message}")
            null
        }
    }
}

/**
 * Extension function to insert separator characters into a string.
 * iOS uses this to work around iMessage URL parsing limitations.
 */
fun String.insertingSeparator(separator: String, every: Int): String {
    if (this.length <= every) return this

    val result = StringBuilder()
    var i = 0
    while (i < this.length) {
        if (i > 0 && i % every == 0) {
            result.append(separator)
        }
        result.append(this[i])
        i++
    }
    return result.toString()
}

/**
 * Extension function to remove separator characters from a string.
 */
fun String.removingSeparator(separator: String): String {
    return this.replace(separator, "")
}