package com.hriyaan.photostorage.b2

import aws.sdk.kotlin.services.s3.S3Client
import aws.sdk.kotlin.services.s3.model.ListBucketsRequest
import aws.sdk.kotlin.services.s3.model.PutObjectRequest
import aws.smithy.kotlin.runtime.content.ByteStream

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

    fun close() {
        client.close()
    }
}
