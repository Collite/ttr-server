// SPDX-License-Identifier: Apache-2.0
package org.tatrman.veles.cn

import com.typesafe.config.Config
import org.slf4j.LoggerFactory
import org.tatrman.mcp.identity.HttpJwksKeySource
import org.tatrman.mcp.identity.JwksBearerVerifier
import org.tatrman.ttr.metadata.source.ModelStorage
import java.net.URI
import java.nio.file.Path

/** The promotion source as the store sees it: a storage of its own, and where the model sits in its repository. */
data class PromotionTarget(
    val storage: ModelStorage,
    val remoteUri: String = "",
    val branch: String = "main",
    val subdirectory: String = "",
)

/** What `metadata.cn` turns on: the API surface, the vocabulary source for the refresher, the source to watch. */
class CnSetup(
    val surface: CnSurface,
    val store: CandidateStore? = null,
    val vocabularySource: VocabularySource? = null,
    /** The model source whose loads decide merges (its version is `canon_ref`'s commit); null = promotion off. */
    val promotionSourceId: String? = null,
) {
    val service: CandidateService? get() = (surface as? CnSurface.On)?.service

    companion object {
        private val log = LoggerFactory.getLogger(CnSetup::class.java)

        /**
         * `metadata.cn` (A-CN-90…95): the store is on with a database (`METADATA_CN_DB_*`) AND a token issuer
         * (`METADATA_CN_AUTH_ISSUER` + `_JWKS_URI`) — the API records who did what, so it never runs unauthenticated.
         * Promotion is on when [promotionTarget] builds the `promotion.source` block (default the git source); a pull
         * request opens when `promotion.github.token` is set, an inbox file is written when `promotion.inbox-dir` is.
         */
        fun fromConfig(
            config: Config,
            promotionTarget: (sourceId: String) -> PromotionTarget?,
        ): CnSetup {
            fun str(path: String) = if (config.hasPath(path)) config.getString(path).trim() else ""
            val off =
                PostgresCandidateStore.offReason(config)
                    ?: if (str("metadata.cn.auth.issuer").isEmpty() || str("metadata.cn.auth.jwks-uri").isEmpty()) {
                        "no METADATA_CN_AUTH_ISSUER / METADATA_CN_AUTH_JWKS_URI"
                    } else {
                        null
                    }
            if (off != null) {
                log.info("cn candidates: off ({})", off)
                return CnSetup(CnSurface.Off("the candidate store is off: $off"))
            }
            val store = checkNotNull(PostgresCandidateStore.fromConfig(config))
            val verifier =
                JwksBearerVerifier(
                    HttpJwksKeySource(str("metadata.cn.auth.jwks-uri")),
                    issuer = str("metadata.cn.auth.issuer"),
                    audience = str("metadata.cn.auth.audience").ifEmpty { null },
                )
            val policy =
                CnPolicy(
                    stewardRole = str("metadata.cn.steward-role").ifEmpty { "cn-steward" },
                    importerRole = str("metadata.cn.importer-role").ifEmpty { "cn-importer" },
                    producerPrincipals = CnPolicy.parsePrincipals(str("metadata.cn.producers")),
                )
            val sourceId = str("metadata.cn.promotion.source")
            val target = sourceId.takeIf { it.isNotEmpty() }?.let(promotionTarget)
            val promoter = target?.let { promoter(config, it) }
            if (promoter == null) {
                log.warn("cn candidates: promotion off — promotion source '{}' is not configured", sourceId)
            }
            val notifier =
                str("metadata.cn.notify-url").takeIf { it.isNotEmpty() }?.let { WebhookNotifier(URI.create(it)) }
                    ?: CandidateNotifier.NONE
            log.info(
                "cn candidates: on (steward role {}, {} producer id(s) mapped, promotion {})",
                policy.stewardRole,
                policy.producerPrincipals.size,
                if (promoter == null) "off" else "from '$sourceId'",
            )
            return CnSetup(
                surface = CnSurface.On(CandidateService(store, policy, promoter, notifier), CnCallers(verifier)),
                store = store,
                vocabularySource = VocabularySource(store),
                promotionSourceId = if (promoter == null) null else sourceId,
            )
        }

        private fun promoter(
            config: Config,
            target: PromotionTarget,
        ): Promoter {
            fun str(path: String) = if (config.hasPath(path)) config.getString(path).trim() else ""
            val deliveries = mutableListOf<PromotionDelivery>()
            val token = str("metadata.cn.promotion.github.token")
            if (token.isNotEmpty()) {
                val repository =
                    str("metadata.cn.promotion.github.repository").ifEmpty { null }
                        ?: GitHubPullRequests.repositoryOf(target.remoteUri)
                if (repository == null) {
                    log.warn("cn candidates: a GitHub token is set but no repository — set METADATA_CN_PR_REPOSITORY")
                } else {
                    deliveries +=
                        GitHubPullRequests(
                            repository = repository,
                            token = token,
                            baseBranch = target.branch,
                            pathPrefix = target.subdirectory,
                            api =
                                URI.create(
                                    str("metadata.cn.promotion.github.api-url").ifEmpty { "https://api.github.com" },
                                ),
                        )
                    log.info("cn candidates: promotions open pull requests on {} (base {})", repository, target.branch)
                }
            }
            str("metadata.cn.promotion.inbox-dir").takeIf { it.isNotEmpty() }?.let {
                deliveries +=
                    InboxDelivery(Path.of(it))
            }
            return Promoter(StoragePromotionFiles(target.storage), deliveries)
        }
    }
}
