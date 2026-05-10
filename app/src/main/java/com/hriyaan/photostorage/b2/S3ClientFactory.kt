package com.hriyaan.photostorage.b2

import aws.sdk.kotlin.runtime.auth.credentials.StaticCredentialsProvider
import aws.sdk.kotlin.services.s3.S3Client
import aws.smithy.kotlin.runtime.http.engine.okhttp.OkHttpEngine
import aws.smithy.kotlin.runtime.net.url.Url
import com.hriyaan.photostorage.data.B2Credentials

object S3ClientFactory {
    fun create(credentials: B2Credentials, config: S3Config): S3Client = S3Client {
        region = config.region
        endpointUrl = Url.parse(config.endpoint)
        credentialsProvider = StaticCredentialsProvider {
            accessKeyId = credentials.keyId
            secretAccessKey = credentials.applicationKey
        }
        httpClient = OkHttpEngine()
    }
}
