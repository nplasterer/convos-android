package com.naomiplasterer.convos.data.api

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "ConvosApiClient"

sealed class ApiResult<out T> {
    data class Success<T>(val data: T) : ApiResult<T>()
    data class Error(val message: String, val code: Int? = null) : ApiResult<Nothing>()
}

interface ConvosApiClientProtocol {
    suspend fun uploadAttachment(
        data: ByteArray,
        filename: String,
        contentType: String = "image/jpeg"
    ): ApiResult<String>
}

@Singleton
class ConvosApiClient @Inject constructor() : ConvosApiClientProtocol {

    private val baseUrl = "https://api.popup.convos.org"

    override suspend fun uploadAttachment(
        data: ByteArray,
        filename: String,
        contentType: String
    ): ApiResult<String> = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Starting attachment upload process for file: $filename")
            Log.d(TAG, "File data size: ${data.size} bytes")

            val presignedUrlResult = getPresignedUrl(filename, contentType)
            if (presignedUrlResult is ApiResult.Error) {
                return@withContext presignedUrlResult
            }

            val presignedUrl = (presignedUrlResult as ApiResult.Success).data

            Log.d(TAG, "Received presigned URL: $presignedUrl")

            val publicUrl = extractPublicUrl(presignedUrl)
            if (publicUrl == null) {
                return@withContext ApiResult.Error("Failed to extract public URL from presigned URL")
            }

            Log.d(TAG, "Final public URL will be: $publicUrl")

            val uploadResult = uploadToS3(presignedUrl, data, contentType)
            if (uploadResult is ApiResult.Error) {
                return@withContext uploadResult
            }

            Log.d(TAG, "Successfully uploaded to S3, public URL: $publicUrl")
            ApiResult.Success(publicUrl)

        } catch (e: Exception) {
            Log.e(TAG, "Failed to upload attachment", e)
            ApiResult.Error(e.message ?: "Unknown error during upload")
        }
    }

    private suspend fun getPresignedUrl(
        filename: String,
        contentType: String
    ): ApiResult<String> = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            val urlString = "$baseUrl/v2/attachments/presigned?contentType=$contentType&filename=$filename"
            val url = URL(urlString)
            connection = url.openConnection() as HttpURLConnection

            connection.requestMethod = "GET"
            connection.connectTimeout = 30000
            connection.readTimeout = 30000

            Log.d(TAG, "Getting presigned URL from: $urlString")

            val responseCode = connection.responseCode
            val responseBody = if (responseCode in 200..299) {
                connection.inputStream.bufferedReader().use { it.readText() }
            } else {
                connection.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
            }

            if (responseCode !in 200..299) {
                Log.e(TAG, "Failed to get presigned URL: $responseCode - $responseBody")
                return@withContext ApiResult.Error(
                    "Failed to get presigned URL",
                    responseCode
                )
            }

            val jsonObject = JSONObject(responseBody)
            val presignedUrl = jsonObject.getString("url")
            ApiResult.Success(presignedUrl)

        } catch (e: IOException) {
            Log.e(TAG, "Network error getting presigned URL", e)
            ApiResult.Error("Network error: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "Error getting presigned URL", e)
            ApiResult.Error(e.message ?: "Unknown error")
        } finally {
            connection?.disconnect()
        }
    }

    private suspend fun uploadToS3(
        presignedUrl: String,
        data: ByteArray,
        contentType: String
    ): ApiResult<Unit> = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            val url = URL(presignedUrl)
            connection = url.openConnection() as HttpURLConnection

            connection.requestMethod = "PUT"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", contentType)
            connection.setRequestProperty("Content-Length", data.size.toString())
            connection.connectTimeout = 30000
            connection.readTimeout = 30000

            Log.d(TAG, "Uploading to S3: $presignedUrl")
            Log.d(TAG, "S3 upload data size: ${data.size} bytes")

            connection.outputStream.use { outputStream ->
                outputStream.write(data)
                outputStream.flush()
            }

            val responseCode = connection.responseCode

            Log.d(TAG, "S3 response status: $responseCode")

            if (responseCode !in 200..299) {
                val errorBody = connection.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                Log.e(TAG, "S3 upload failed with status: $responseCode")
                Log.e(TAG, "S3 error response: $errorBody")
                return@withContext ApiResult.Error(
                    "S3 upload failed",
                    responseCode
                )
            }

            ApiResult.Success(Unit)

        } catch (e: IOException) {
            Log.e(TAG, "Network error uploading to S3", e)
            ApiResult.Error("Network error: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "Error uploading to S3", e)
            ApiResult.Error(e.message ?: "Unknown error")
        } finally {
            connection?.disconnect()
        }
    }

    private fun extractPublicUrl(presignedUrl: String): String? {
        return try {
            val url = URL(presignedUrl)
            val scheme = url.protocol
            val host = url.host
            val path = url.path
            "$scheme://$host$path"
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract public URL", e)
            null
        }
    }
}
