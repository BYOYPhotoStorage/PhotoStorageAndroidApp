package com.hriyaan.photostorage.b2

data class S3Config(
    val region: String,
    val endpoint: String,
    val bucketName: String
) {
    companion object {
        const val DEFAULT_REGION = "us-west-004"

        fun forBucket(
            bucketName: String,
            region: String = DEFAULT_REGION
        ): S3Config = S3Config(
            region = region,
            endpoint = "https://s3.$region.backblazeb2.com",
            bucketName = bucketName
        )
    }
}
