// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.staging

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.*
import org.tatrman.charon.core.ImmutableBlobStore
import software.amazon.awssdk.core.ResponseBytes
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.*
import java.util.UUID

class RemoteStagingUnitSpec :
    StringSpec({
        fun failure(status: Int) =
            S3Exception
                .builder()
                .statusCode(status)
                .message("store refusal")
                .build()

        fun bytes(data: ByteArray) = ResponseBytes.fromByteArray(GetObjectResponse.builder().build(), data)

        "conditional put never reads before create and accepts only identical existing bytes" {
            val client = mockk<S3Client>()
            val store = S3BlobStore(client, "bucket")
            every { client.putObject(any<PutObjectRequest>(), any<RequestBody>()) } returns
                PutObjectResponse.builder().build()
            store.putIfAbsent("k", byteArrayOf(1)) shouldBe true
            verify(exactly = 0) { client.getObjectAsBytes(any<GetObjectRequest>()) }
            every { client.putObject(any<PutObjectRequest>(), any<RequestBody>()) } throws failure(412)
            every { client.getObjectAsBytes(any<GetObjectRequest>()) } returns bytes(byteArrayOf(1))
            store.putIfAbsent("k", byteArrayOf(1)) shouldBe false
            shouldThrow<IllegalStateException> { store.putIfAbsent("k", byteArrayOf(2)) }
            verify(
                exactly = 3,
            ) { client.putObject(match<PutObjectRequest> { it.ifNoneMatch() == "*" }, any<RequestBody>()) }
        }

        "conditional conflict retries stay bounded and never fall back to an unconditional write" {
            val client = mockk<S3Client>()
            val store = S3BlobStore(client, "bucket")
            every { client.putObject(any<PutObjectRequest>(), any<RequestBody>()) } throws failure(409)
            shouldThrow<S3Exception> { store.putIfAbsent("k", byteArrayOf(1)) }.statusCode() shouldBe 409
            verify(
                exactly = 3,
            ) { client.putObject(match<PutObjectRequest> { it.ifNoneMatch() == "*" }, any<RequestBody>()) }
            clearMocks(client)
            every { client.putObject(any<PutObjectRequest>(), any<RequestBody>()) } throws failure(501)
            shouldThrow<S3Exception> { store.putIfAbsent("k", byteArrayOf(1)) }.statusCode() shouldBe 501
            verify(exactly = 1) { client.putObject(any<PutObjectRequest>(), any<RequestBody>()) }
            verify(exactly = 0) { client.getObjectAsBytes(any<GetObjectRequest>()) }
        }

        "a gateway which silently ignores conditional PUT fails its capability check" {
            val client = mockk<S3Client>()
            every { client.putObject(any<PutObjectRequest>(), any<RequestBody>()) } returns
                PutObjectResponse.builder().build()
            shouldThrow<IllegalStateException> { S3BlobStore(client, "bucket").verifyConditionalWrites("au") }
            verify(exactly = 2) {
                client.putObject(
                    match<PutObjectRequest> { it.key() == "au/_capabilities/conditional-create-v1" },
                    any<RequestBody>(),
                )
            }
        }

        "partial batch deletion, escaped keys and nonadvancing continuation tokens refuse cleanup" {
            val client = mockk<S3Client>()
            val store = S3BlobStore(client, "bucket")
            every { client.listObjectsV2(any<ListObjectsV2Request>()) } returns
                ListObjectsV2Response
                    .builder()
                    .contents(S3Object.builder().key("au/runs/id/file").build())
                    .isTruncated(false)
                    .build()
            every { client.deleteObjects(any<DeleteObjectsRequest>()) } returns
                DeleteObjectsResponse
                    .builder()
                    .errors(
                        S3Error
                            .builder()
                            .key("au/runs/id/file")
                            .code("AccessDenied")
                            .build(),
                    ).build()
            shouldThrow<IllegalStateException> { store.deletePrefix("au/runs/id/") }
            every { client.listObjectsV2(any<ListObjectsV2Request>()) } returns
                ListObjectsV2Response
                    .builder()
                    .contents(S3Object.builder().key("au/runs/other/file").build())
                    .isTruncated(false)
                    .build()
            clearMocks(client, answers = false, recordedCalls = true)
            shouldThrow<IllegalStateException> { store.deletePrefix("au/runs/id/") }
            verify(exactly = 0) { client.deleteObjects(any<DeleteObjectsRequest>()) }
            every { client.listObjectsV2(any<ListObjectsV2Request>()) } returns
                ListObjectsV2Response
                    .builder()
                    .isTruncated(true)
                    .build()
            shouldThrow<IllegalStateException> { store.deletePrefix("au/runs/id/") }
            shouldThrow<IllegalArgumentException> { store.deletePrefix("") }
        }

        "the AU adapter scopes deletion by canonical UUID and rejects path traversal before IO" {
            val blobs = mockk<ImmutableBlobStore>()
            val store = TransferStagingStore(blobs, "hartland/au")
            val id = UUID.randomUUID()
            every { blobs.deletePrefix("hartland/au/runs/$id/") } just Runs
            every { blobs.close() } just Runs
            store.deleteRun(id)
            verify(exactly = 1) { blobs.deletePrefix("hartland/au/runs/$id/") }
            for (path in listOf(
                "runs/$id/../other",
                "/runs/$id/out",
                "runs/1-1-1-1-1/out",
                "runs/$id/a?b",
                "runs/$id/",
            )) {
                shouldThrow<IllegalArgumentException> { store.get(path) }
            }
            verify(exactly = 0) { blobs.get(any()) }
            store.close()
            verify(exactly = 1) { blobs.close() }
        }

        "versioned and suspended buckets refuse startup before writing a capability marker" {
            val client = mockk<S3Client>()
            val store = S3BlobStore(client, "bucket")
            for (state in listOf("Enabled", "Suspended")) {
                every { client.getBucketVersioning(any<GetBucketVersioningRequest>()) } returns
                    GetBucketVersioningResponse.builder().status(state).build()
                shouldThrow<IllegalStateException> { store.verifyUnversionedBucket() }
            }
            every { client.getBucketVersioning(any<GetBucketVersioningRequest>()) } returns
                GetBucketVersioningResponse.builder().build()
            store.verifyUnversionedBucket()
            verify(exactly = 0) { client.putObject(any<PutObjectRequest>(), any<RequestBody>()) }
        }

        "S3 shutdown closes credentials even if the client close fails" {
            val client = mockk<S3Client>()
            every { client.close() } throws IllegalStateException("client close failed")
            var credentialsClosed = false
            val store = S3BlobStore(client, "bucket", closeCredentials = { credentialsClosed = true })
            shouldThrow<IllegalStateException> { store.close() }
            credentialsClosed shouldBe true
        }
    })
