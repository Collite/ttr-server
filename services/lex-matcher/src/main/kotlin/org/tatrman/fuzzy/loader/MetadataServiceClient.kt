// SPDX-License-Identifier: Apache-2.0
package org.tatrman.fuzzy.loader

import io.grpc.Channel
import io.grpc.Status
import io.grpc.StatusRuntimeException
import org.tatrman.meta.v1.ListMemberVocabulariesRequest
import org.tatrman.meta.v1.MemberVocabulary
import org.tatrman.meta.v1.PageRequest
import org.tatrman.meta.v1.VelesServiceGrpc
import java.util.concurrent.TimeUnit

/** What `ListMemberVocabularies` answered: every vocabulary, or why there is no listing to load. */
sealed interface MemberVocabularyListing {
    /** Every page, in category order. */
    data class Listed(
        val items: List<MemberVocabulary>,
    ) : MemberVocabularyListing

    /**
     * No listing to load from — the loader keeps its previous cache (RG-FUZ-004). [reason] is
     * `unimplemented` (a Veles that predates member vocabularies, contracts §6), or the code of the
     * message Veles answered with instead of items (`metadata_not_ready`, `unknown_dialect`).
     */
    data class Unavailable(
        val reason: String,
        val detail: String,
    ) : MemberVocabularyListing
}

/**
 * Thin gRPC client over Veles (`VelesService`, the metadata service) for the member loader. The
 * channel is owned by the caller (`Application.module`); this class never builds or shuts it.
 *
 * MV-T2 — one call: `ListMemberVocabularies`. Veles decides which vocabularies exist and renders
 * each one's read plan for the warehouse's [dialect]; this client never learns what a table or a
 * primary key is. (It used to list fuzzy-tagged columns and fetch each table's detail, and the
 * loader composed the SQL — keyed by the column, so two entities over one table shared an index.)
 *
 * Timeouts are enforced via gRPC's `withDeadlineAfter(...)` per call — the deadline propagates on
 * the wire and cancels the RPC server-side, unlike a `kotlinx.coroutines.withTimeout` wrapper around
 * a blocking call (which only cancels the coroutine, not the underlying blocking I/O).
 */
class MetadataServiceClient(
    channel: Channel,
    private val timeoutMs: Long,
    private val pageSize: Int = 100,
) {
    private val stub: VelesServiceGrpc.VelesServiceBlockingStub =
        VelesServiceGrpc.newBlockingStub(channel)

    private fun deadlined(): VelesServiceGrpc.VelesServiceBlockingStub =
        stub.withDeadlineAfter(timeoutMs, TimeUnit.MILLISECONDS)

    /**
     * Every member vocabulary, read plans rendered for [dialect] (a `translate.v1.SqlDialect` value
     * name). UNIMPLEMENTED and an answer carrying messages instead of items are [Unavailable]; any
     * other transport failure throws, as it always did, and the loader treats it the same way.
     */
    fun listMemberVocabularies(dialect: String): MemberVocabularyListing {
        val items = mutableListOf<MemberVocabulary>()
        var pageToken = ""
        do {
            val request =
                ListMemberVocabulariesRequest
                    .newBuilder()
                    .setDialect(dialect)
                    .setPage(PageRequest.newBuilder().setPageSize(pageSize).setPageToken(pageToken))
                    .build()
            val response =
                try {
                    deadlined().listMemberVocabularies(request)
                } catch (e: StatusRuntimeException) {
                    if (e.status.code == Status.Code.UNIMPLEMENTED) {
                        return MemberVocabularyListing.Unavailable(
                            reason = "unimplemented",
                            detail = "Veles does not serve ListMemberVocabularies",
                        )
                    }
                    throw e
                }
            // Not ready / dialect refused: Veles answers with a message and no items. That empty
            // page is NOT "the estate has no member vocabularies" — loading it would wipe the member
            // layer on every Veles restart. (A page that has items is a listing, whatever else it says.)
            if (response.itemsCount == 0) {
                response.messagesList.firstOrNull()?.let {
                    return MemberVocabularyListing.Unavailable(reason = it.code, detail = it.humanMessage)
                }
            }
            items += response.itemsList
            pageToken = response.pageInfo.nextPageToken
        } while (pageToken.isNotEmpty())
        return MemberVocabularyListing.Listed(items)
    }
}
