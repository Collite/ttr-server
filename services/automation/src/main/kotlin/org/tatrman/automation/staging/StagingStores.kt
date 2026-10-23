// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.staging

import io.lettuce.core.RedisClient
import io.lettuce.core.RedisURI
import io.lettuce.core.codec.ByteArrayCodec
import org.tatrman.automation.config.StagingConfig
import software.amazon.awssdk.auth.credentials.AnonymousCredentialsProvider
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import java.net.URI
import java.nio.file.Path
import java.time.Duration

/** Application owns the result and closes it after run workers/readers have drained. */
object StagingStores {
    fun open(config: StagingConfig): StagingStore =
        when (config.kind) {
            "local" -> LocalStagingStore(Path.of(requireNotNull(config.path)))
            "redis" -> {
                val uri = RedisURI.create(config.url).apply { timeout = Duration.ofSeconds(10) }
                val client = RedisClient.create(uri)
                try {
                    TransferStagingStore(RedisBlobStore(client.connect(ByteArrayCodec.INSTANCE), client), config.prefix)
                } catch (e: Exception) {
                    client.shutdown()
                    throw e
                }
            }
            "seaweed" -> openS3(config)
            else -> error("unsupported staging kind") // StagingConfig validates before any IO.
        }

    private fun openS3(config: StagingConfig): StagingStore {
        val credentials =
            if (config.anonymous) {
                AnonymousCredentialsProvider.create()
            } else {
                DefaultCredentialsProvider.builder().build()
            }
        val client =
            try {
                S3Client
                    .builder()
                    .endpointOverride(URI(requireNotNull(config.url)))
                    .region(Region.of(config.region))
                    .forcePathStyle(true)
                    .credentialsProvider(credentials)
                    .overrideConfiguration(
                        ClientOverrideConfiguration
                            .builder()
                            .apiCallTimeout(Duration.ofSeconds(20))
                            .apiCallAttemptTimeout(Duration.ofSeconds(10))
                            .build(),
                    ).build()
            } catch (e: Exception) {
                (credentials as? AutoCloseable)?.close()
                throw e
            }
        val blobs =
            S3BlobStore(client, requireNotNull(config.bucket), closeCredentials = {
                (credentials as? AutoCloseable)?.close()
            })
        try {
            blobs.verifyUnversionedBucket()
            blobs.verifyConditionalWrites(config.prefix)
            return TransferStagingStore(blobs, config.prefix)
        } catch (e: Exception) {
            blobs.close()
            throw e
        }
    }
}
