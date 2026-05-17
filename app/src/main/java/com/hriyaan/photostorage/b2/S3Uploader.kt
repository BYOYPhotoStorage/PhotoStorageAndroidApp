package com.hriyaan.photostorage.b2

import aws.sdk.kotlin.services.s3.S3Client
import aws.sdk.kotlin.services.s3.model.DeleteObjectRequest
import aws.sdk.kotlin.services.s3.model.GetObjectRequest
import aws.sdk.kotlin.services.s3.model.HeadObjectRequest
import aws.sdk.kotlin.services.s3.model.ListBucketsRequest
import aws.sdk.kotlin.services.s3.model.NoSuchKey
import aws.sdk.kotlin.services.s3.model.NotFound
import aws.sdk.kotlin.services.s3.model.PutObjectRequest
import aws.sdk.kotlin.services.s3.presigners.presignGetObject
import aws.smithy.kotlin.runtime.content.ByteStream
import aws.smithy.kotlin.runtime.content.writeToFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import kotlin.time.Duration.Companion.seconds

class S3Uploader(
    private val client: S3Client,
    private val bucket: String
) {

    suspend fun validateCredentials(): Result<Unit> = runCatching {
        client.listBuckets(ListBucketsRequest { })
        Unit
    }

    suspend fun upload(
        key: String,
        contentType: String,
        contentLength: Long,
        body: ByteStream
    ): Result<Unit> = runCatching {
        client.putObject(PutObjectRequest {
            this.bucket = this@S3Uploader.bucket
            this.key = key
            this.body = body
            this.contentType = contentType
            this.contentLength = contentLength
            this.checksumAlgorithm = null
        })
        Unit
    }

    suspend fun deleteObject(path: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            try {
                client.deleteObject(DeleteObjectRequest {
                    this.bucket = this@S3Uploader.bucket
                    this.key = path
                })
            } catch (_: NoSuchKey) {
            } catch (_: NotFound) {
            }
            Unit
        }
    }

    suspend fun headObject(path: String): Result<Boolean> = withContext(Dispatchers.IO) {
        runCatching {
            try {
                client.headObject(HeadObjectRequest {
                    this.bucket = this@S3Uploader.bucket
                    this.key = path
                })
                true
            } catch (_: NotFound) {
                false
            } catch (_: NoSuchKey) {
                false
            }
        }
    }

    suspend fun downloadObject(path: String, dest: File): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            client.getObject(GetObjectRequest {
                this.bucket = this@S3Uploader.bucket
                this.key = path
            }) { response ->
                val body = response.body ?: throw IOException("Empty response body for $path")
                body.writeToFile(dest)
            }
            Unit
        }.onFailure {
            runCatching { dest.delete() }
        }
    }

    suspend fun presignGetUrl(path: String, ttlSeconds: Long): Result<String> = withContext(Dispatchers.IO) {
        if (ttlSeconds !in MIN_PRESIGN_TTL_SECONDS..MAX_PRESIGN_TTL_SECONDS) {
            return@withContext Result.failure(IllegalArgumentException("ttl out of range"))
        }
        runCatching {
            val request = GetObjectRequest {
                this.bucket = this@S3Uploader.bucket
                this.key = path
            }
            val presigned = client.presignGetObject(request, ttlSeconds.seconds)
            presigned.url.toString()
        }
    }

    fun close() {
        client.close()
    }

    companion object {
        private const val MIN_PRESIGN_TTL_SECONDS = 60L
        private const val MAX_PRESIGN_TTL_SECONDS = 7L * 86_400L
    }
}
