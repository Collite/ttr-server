// SPDX-License-Identifier: Apache-2.0
package org.tatrman.resolver.registry

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.string.shouldContain as shouldContainText
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.runBlocking
import org.tatrman.resolver.model.Reach
import org.tatrman.resolver.model.ResolverThresholds
import org.tatrman.resolver.model.kindsByRef
import org.tatrman.resolver.model.reachByRef
import org.tatrman.resolver.pipeline.VerbatimAttribution
import org.tatrman.ttr.lexicon.CompiledLexiconHeader
import org.tatrman.ttr.lexicon.LexiconArea
import org.tatrman.ttr.lexicon.LexiconDataFile
import org.tatrman.ttr.lexicon.LexiconLoad
import org.tatrman.ttr.lexicon.LexiconArchive
import org.tatrman.ttr.lexicon.LexiconValidator
import org.tatrman.ttr.lexicon.TargetClass
import org.tatrman.ttr.lexicon.compile.CompileResult
import org.tatrman.ttr.lexicon.compile.LexiconCompiler
import org.tatrman.ttr.lexicon.compile.LexiconPacker
import org.tatrman.ttr.lexicon.compile.LexiconSources
import org.tatrman.ttr.lexicon.compile.ModelRefIndex
import org.tatrman.ttr.snapshot.SnapshotManifest
import org.tatrman.ttr.snapshot.SnapshotReadResult
import org.tatrman.ttr.snapshot.SnapshotReader
import org.tatrman.ttr.snapshot.SnapshotWriter
import org.tatrman.ttr.metadata.model.Attribute
import org.tatrman.ttr.metadata.model.Cardinality
import org.tatrman.ttr.metadata.model.Entity
import org.tatrman.ttr.metadata.model.ErSchema
import org.tatrman.ttr.metadata.model.Model
import org.tatrman.ttr.metadata.model.ModelDescriptor
import org.tatrman.ttr.metadata.model.ModelVersion
import org.tatrman.ttr.metadata.model.QualifiedName
import org.tatrman.ttr.metadata.model.Relation
import org.tatrman.ttr.metadata.model.SchemaCode
import org.tatrman.ttr.semantics.semanticsblock.MeasureRef
import org.tatrman.ttr.semantics.semanticsblock.ResolvedEntitySemantics
import org.tatrman.ttr.semantics.semanticsblock.SymbolRef
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.writeBytes

/**
 * Q-7 (Bora, 2026-08-14) — **the registry is fed from the compiled lexicon archive**, the same
 * file lex-matcher mounts. RS-24's *"one channel, two consumers … off the SAME snapshot
 * identity"*, made real on the second consumer.
 *
 * The fixture is packed by the **real `LexiconPacker`** (test scope only), exactly as
 * `LexiconArchiveSourceTest` does on the lex-matcher side — a change to the producer's layout
 * breaks this test rather than silently diverging from it. That matters more here than usual:
 * the two readers are conformant *by contract* until the RO-13 shared lib exists, so the only
 * thing holding them together is that both are held to the same producer.
 */
class LexiconArchiveRegistrySourceTest :
    StringSpec({

        val modelHash = "sha256:" + "cd".repeat(32)

        /** Compiles + packs a lexicon exactly as the RV-P1.2 build does, and writes it to disk. */
        fun writeArchive(
            dir: Path,
            yaml: String,
            classOf: (String) -> TargetClass?,
            name: String = "lexicon.tar.zst",
            producer: String = "test",
            model: Model? = null,
            transform: (CompileResult) -> CompileResult = { it },
        ): Path {
            val file =
                LexiconValidator
                    .loadDataFile(yaml, "aliases/er.lex.yaml")
                    .shouldBeInstanceOf<LexiconLoad.Ok<LexiconDataFile>>()
                    .value
            val result =
                LexiconCompiler.compile(
                    LexiconSources(area = LexiconArea(listOf(file), emptyList()), model = model),
                    ModelRefIndex { ref -> classOf(ref) },
                    modelHash,
                    "2026-08-14T00:00:00Z",
                )
            return dir
                .resolve(name)
                .also { it.writeBytes(LexiconPacker.pack(transform(result), modelHash, producer).bytes) }
        }

        // ---- MS (contracts §5/§6): the model the `targets` map is derived FROM ----------------
        //
        // Built here rather than injected into a CompiledLexicon, so the fixture exercises the
        // real producer: MentionKinds reads model-graph facts, the compiler keys the map by the
        // entry's own targetRef, and this reader looks it up. Injecting `targets` directly would
        // pass even if the compiler stopped filling it.
        val salesRef = "er.entity.sales"
        val regionDimRef = "er.entity.region_dim"

        fun mentionModel(): Model {
            val sales = QualifiedName(SchemaCode.ER, "entity", "sales")
            val regionDim = QualifiedName(SchemaCode.ER, "entity", "region_dim")

            fun attr(
                owner: QualifiedName,
                local: String,
                type: String,
            ) = Attribute(
                internalId = "a.$local",
                qname = QualifiedName(SchemaCode.ER, "entity", "${owner.name}.$local"),
                entity = owner,
                type = type,
            )
            return Model(
                descriptor = ModelDescriptor(id = "t", name = "t"),
                version = ModelVersion("v1", Instant.EPOCH),
                schemas =
                    mapOf(
                        "er" to
                            ErSchema(
                                entities =
                                    mapOf(
                                        sales to
                                            Entity(
                                                internalId = "1",
                                                qname = sales,
                                                attributes =
                                                    listOf(
                                                        attr(sales, "amount_czk", "decimal"),
                                                        attr(sales, "region", "text"),
                                                    ),
                                                mentionSemantics =
                                                    ResolvedEntitySemantics(
                                                        measures = listOf(MeasureRef(SymbolRef("amount_czk"), "sum")),
                                                    ),
                                            ),
                                        regionDim to
                                            Entity(
                                                internalId = "2",
                                                qname = regionDim,
                                                attributes =
                                                    listOf(
                                                        attr(regionDim, "name", "text"),
                                                        attr(regionDim, "region_code", "text"),
                                                    ),
                                                // LP (⚑LPQ-5): the DECLARED mention facet — which
                                                // member carries this entity's name and its code.
                                                // `sales` deliberately declares none, so one
                                                // fixture covers both halves of the contract. The
                                                // facet's `code_pattern` (review-103 D4) gives it
                                                // all three fields to carry rather than two and a
                                                // blank; the attribute-level `code_format` is a
                                                // period mask, never a regex, so it cannot.
                                                mentionSemantics =
                                                    ResolvedEntitySemantics(
                                                        name = SymbolRef("name"),
                                                        code = SymbolRef("region_code"),
                                                        codePattern = "^R[0-9]{3}$",
                                                    ),
                                            ),
                                    ),
                                // MH — the fact points AT the dimension, mandatorily: every sales
                                // row carries a region. Declared here rather than injected into a
                                // `targets` map for the same reason the mention facet is — the
                                // fixture must exercise the real producer, or it would pass even
                                // if the compiler stopped filling `reachedFrom`.
                                relations =
                                    mapOf(
                                        QualifiedName(SchemaCode.ER, "relation", "rel_sales_region") to
                                            Relation(
                                                internalId = "r1",
                                                qname = QualifiedName(SchemaCode.ER, "relation", "rel_sales_region"),
                                                fromEntity = sales,
                                                toEntity = regionDim,
                                                cardinality = Cardinality(0, -1, 1, 1),
                                            ),
                                    ),
                            ),
                    ),
                mappings = emptyList(),
                queries = emptyMap(),
            )
        }

        val mentionYaml =
            """
            schema: ttr-lexicon/v1
            defaults: { lang: en }
            entries:
              - terms: [ { text: "sales" } ]
                target: er.entity.sales
              - terms: [ { text: "revenue" } ]
                target: er.entity.sales.amount_czk
              - terms: [ { text: "region" } ]
                target: er.entity.sales.region
              - terms: [ { text: "regions" } ]
                target: er.entity.region_dim
              - terms: [ { text: "name" } ]
                target: er.entity.region_dim.name
              - terms: [ { text: "ghost" } ]
                target: er.entity.absent
            """.trimIndent()

        // `er.entity.absent` classifies as a MODEL_OBJECT but is NOT in the model — the
        // dangling-ref case contracts §6 says gets no `targets` entry.
        val mentionObjects = { ref: String ->
            if (ref.startsWith("er.entity.")) TargetClass.MODEL_OBJECT else null
        }

        fun mentionRegistry(dir: Path) =
            SnapshotRegistry(
                LexiconArchiveRegistrySource(
                    writeArchive(dir, mentionYaml, mentionObjects, model = mentionModel()),
                ),
                ResolverThresholds.LIVE,
            )

        val objectsOnly = { ref: String ->
            when (ref) {
                "er.entity.catalog_sales", "er.entity.catalog_sales.ext_sales_price" -> TargetClass.MODEL_OBJECT
                else -> null
            }
        }

        val aliases =
            """
            schema: ttr-lexicon/v1
            defaults: { lang: en }
            entries:
              - terms:
                  - { text: "marketplace" }
                  - { text: "marketplace revenue", method: TOKENS }
                target: er.entity.catalog_sales
              - terms: [ { text: "revenues", method: TYPOS(1) } ]
                target: er.entity.catalog_sales.ext_sales_price
            """.trimIndent()

        "⚠ only MODEL_OBJECT rows become anchors — members and grounding triggers do not" {
            // A decision, not a filter, and the test that makes it a decision. `anchors` are the
            // words naming a model OBJECT that Q-20's anchored proposal ties subtrees to.
            //  - a MEMBER is the value layer (RV-2). Every member literal becoming an anchor token
            //    would fragment ordinary noun phrases, because `anchorTokens` blocks a word from
            //    being folded into a sibling's phrase.
            //  - a GROUNDING_TRIGGER already has its own annotation path.
            //  - an OPERATOR is tatrman-server#58's territory: Q-20 cut over-generation 33 → 0, and
            //    widening proposal as a side effect of a plumbing change is how that gets lost.
            val mixed =
                """
                schema: ttr-lexicon/v1
                defaults: { lang: en }
                entries:
                  - terms: [ { text: "marketplace" } ]
                    target: er.entity.catalog_sales
                  - terms: [ { text: "month" } ]
                    target: ground:chrono
                """.trimIndent()
            val dir = Files.createTempDirectory("resolver-lex")
            val source = LexiconArchiveRegistrySource(writeArchive(dir, mixed, objectsOnly))

            runBlocking {
                val vocab = source.fetch()
                withMessage("a grounding trigger must not become an anchor") {
                    vocab.entries.map { it.targetRef } shouldContainExactlyInAnyOrder listOf("er.entity.catalog_sales")
                }
            }
        }

        "an archive projects into declared vocabulary keyed by target ref" {
            val dir = Files.createTempDirectory("resolver-lex")
            val source = LexiconArchiveRegistrySource(writeArchive(dir, aliases, objectsOnly))

            runBlocking {
                val vocab = source.fetch()

                vocab.entries.map { it.category } shouldContainExactlyInAnyOrder
                    listOf("er.entity.catalog_sales", "er.entity.catalog_sales.ext_sales_price")
                // category == targetRef is what `SnapshotRegistry.project` keys on. If these ever
                // diverge the registry silently produces one entity type per (category, ref) pair.
                vocab.entries.forEach { it.category shouldBe it.targetRef }

                vocab.entries
                    .single { it.targetRef == "er.entity.catalog_sales" }
                    .values
                    .map { it.value } shouldContainExactlyInAnyOrder listOf("marketplace", "marketplace revenue")
            }
        }

        "the projection reaches the registry as anchors" {
            val dir = Files.createTempDirectory("resolver-lex")
            val registry =
                SnapshotRegistry(
                    LexiconArchiveRegistrySource(writeArchive(dir, aliases, objectsOnly)),
                    ResolverThresholds.LIVE,
                )

            runBlocking {
                val types = registry.current().entityTypes

                types.map { it.ref } shouldContainExactlyInAnyOrder
                    listOf("er.entity.catalog_sales", "er.entity.catalog_sales.ext_sales_price")
                types
                    .single { it.ref == "er.entity.catalog_sales" }
                    .anchors shouldContainExactlyInAnyOrder listOf("marketplace", "marketplace revenue")
            }
        }

        // ---- MS-P2·S2: the inversion of "objectKind stays BLANK" ------------------------------
        //
        // That test pinned the half of tatrman-server#69 the Q-7 change did NOT deliver: the
        // archive stated a TargetClass (MODEL_OBJECT | MEMBER | OPERATOR | GROUNDING_TRIGGER) —
        // the CLASS — while `objectKind` wants a model fact, so FrameRoles R2 could not fire.
        // MS moved the derivation upstream: `MentionKinds` computes the kind from model-graph
        // facts at COMPILE time and the archive carries it per ref. The old test's closing
        // warning survives verbatim as the rule of this file — nothing here derives a kind from
        // a ref STRING; this reader does a lookup and a copy, and nothing else.

        "objectKind and ownerRef arrive from the archive's targets, verbatim" {
            val dir = Files.createTempDirectory("resolver-lex")
            val types = runBlocking { mentionRegistry(dir).current().entityTypes }.associateBy { it.ref }

            // The four §5 values, each from a real compile rather than a hand-written map.
            types.getValue(salesRef).objectKind shouldBe "entity_with_measures"
            types.getValue(salesRef).ownerRef shouldBe ""
            types.getValue("er.entity.sales.amount_czk").objectKind shouldBe "measure"
            types.getValue("er.entity.sales.amount_czk").ownerRef shouldBe salesRef
            types.getValue("er.entity.sales.region").objectKind shouldBe "attribute"
            types.getValue("er.entity.sales.region").ownerRef shouldBe salesRef
            types.getValue(regionDimRef).objectKind shouldBe "entity"
            types.getValue(regionDimRef).ownerRef shouldBe ""
            types.getValue("er.entity.region_dim.name").objectKind shouldBe "attribute"
            types.getValue("er.entity.region_dim.name").ownerRef shouldBe regionDimRef
        }

        "MH — reachedFrom arrives from the archive's targets, verbatim" {
            val dir = Files.createTempDirectory("resolver-lex")
            val types = runBlocking { mentionRegistry(dir).current().entityTypes }.associateBy { it.ref }

            // The DIMENSION carries the reach; the fact that points at it carries none. The
            // direction is what the Binder's rule turns on, so it is asserted in both directions.
            types.getValue(regionDimRef).reachedFrom shouldBe listOf(Reach(salesRef, mandatory = true))
            types.getValue(salesRef).reachedFrom shouldBe emptyList()
            // Members never carry reach — reachability is a fact about whole objects.
            types.getValue("er.entity.region_dim.name").reachedFrom shouldBe emptyList()
        }

        "MH — reachByRef omits the empty lists, kindsByRef omits the blanks" {
            val dir = Files.createTempDirectory("resolver-lex")
            val types = runBlocking { mentionRegistry(dir).current().entityTypes }

            // Absence IS the answer, on both maps — a lookup answers "nothing declared" by
            // missing, exactly as `ownersByRef` does. A map full of empty lists would make
            // "declared nothing" and "declared no relations" indistinguishable at the call site.
            types.reachByRef().keys shouldBe setOf(regionDimRef)
            types.reachByRef().getValue(regionDimRef) shouldBe listOf(Reach(salesRef, mandatory = true))
            types.kindsByRef().keys shouldContain salesRef
            types.kindsByRef().values.none { it.isBlank() } shouldBe true
            // `er.entity.absent` is in the registry but has no targets entry — no kind, no reach.
            types.kindsByRef().keys shouldNotContain "er.entity.absent"
            types.reachByRef().keys shouldNotContain "er.entity.absent"
        }

        // ---- LP ✅LP-10 (⚑LPQ-5, contracts §2.1): the mention facet -----------------------------
        //
        // The gap this closes, stated once: LP-P1 attributes a quoted literal to the head's
        // DECLARED `semantics { name: · code: }`, and until `ttr-lexicon-compiled/v4` the archive
        // had no field for it. The facet reached the resolver only through a per-request
        // `Registry` override, so an estate fed from the snapshot channel — every real one — left
        // every literal HEADLESS. These are the cases that say it now arrives.

        "LP — nameRef, codeRef and codeFormat arrive from the archive's targets, verbatim" {
            val dir = Files.createTempDirectory("resolver-lex")
            val types = runBlocking { mentionRegistry(dir).current().entityTypes }.associateBy { it.ref }

            // FULL attribute refs, because the resolver compares them to `Attribution.attribute_ref`
            // and a local name would make every consumer re-join.
            types.getValue(regionDimRef).nameRef shouldBe "er.entity.region_dim.name"
            types.getValue(regionDimRef).codeRef shouldBe "er.entity.region_dim.region_code"
            // The facet's `code_pattern`, so `VerbatimAttribution`'s shape test uses the model's
            // pattern as well as the fallback shape.
            types.getValue(regionDimRef).codeFormat shouldBe "^R[0-9]{3}$"
        }

        "LP — an object whose model declares no facet gets blanks, not a column picked by name" {
            val dir = Files.createTempDirectory("resolver-lex")
            val types = runBlocking { mentionRegistry(dir).current().entityTypes }.associateBy { it.ref }

            // `sales` has attributes and no `semantics { name: }`. A name-sniffing implementation
            // would answer `amount_czk` or `region` here; a declaration-reading one answers
            // nothing, and `Verbatim` then leaves the literal headless (G3) rather than
            // attributing it to a column nobody declared.
            types.getValue(salesRef).nameRef shouldBe ""
            types.getValue(salesRef).codeRef shouldBe ""
            types.getValue(salesRef).codeFormat shouldBe ""
            // A MEMBER carries none either — it has no name column, it IS one.
            types.getValue("er.entity.region_dim.name").nameRef shouldBe ""
        }

        "LP — the facet makes a quoted literal attributable with NO Registry override" {
            // The assertion the effort exists for, phrased as the door sees it: given only the
            // archive, `VerbatimAttribution` can name the column a literal restricts. Before v4
            // this returned null for every archive-fed estate.
            val dir = Files.createTempDirectory("resolver-lex")
            val types = runBlocking { mentionRegistry(dir).current().entityTypes }.associateBy { it.ref }
            val regionDim = types.getValue(regionDimRef)

            // A plain string takes `name` (§2.2's `contains` default applies downstream)…
            VerbatimAttribution.attributeRefOf("Pelex", regionDim) shouldBe "er.entity.region_dim.name"
            // …and one matching the MODEL's declared `code_pattern` takes `code`.
            VerbatimAttribution.attributeRefOf("R042", regionDim) shouldBe "er.entity.region_dim.region_code"
            // A code-SHAPED string the model's own pattern does not match is STILL a code: §2.1 is
            // the declared pattern OR the fallback shape (review-103 F6). The pattern adds codes the
            // shape cannot see; it does not take away the ones it can.
            VerbatimAttribution.attributeRefOf("XY-7", regionDim) shouldBe "er.entity.region_dim.region_code"
            // And the entity that declares nothing stays headless — null, not a guess.
            VerbatimAttribution.attributeRefOf("Pelex", types.getValue(salesRef)) shouldBe null
        }

        "LP — a v3 archive (no facet) still resolves: blanks, and the literal stays headless" {
            // The compatibility half, and the one a reader will doubt. Same shape as the v2/MH
            // case above: the three fields are defaulted in `TargetFacts`, so an archive built
            // before LP decodes here with all three blank and nothing downstream changes.
            val dir = Files.createTempDirectory("resolver-lex")
            val registry =
                SnapshotRegistry(
                    LexiconArchiveRegistrySource(writeArchive(dir, aliases, objectsOnly)),
                    ResolverThresholds.LIVE,
                )

            runBlocking {
                registry.current().entityTypes.forEach {
                    it.nameRef shouldBe ""
                    it.codeRef shouldBe ""
                    it.codeFormat shouldBe ""
                    VerbatimAttribution.attributeRefOf("Pelex", it) shouldBe null
                }
            }
        }

        "MH — a v2 archive (no reachedFrom) projects empty lists, and T3 stays inert" {
            // The compatibility direction that matters: the field is defaulted, so an archive
            // built before MH decodes here with every list empty, and every rule that reads it
            // is a no-op. No reader gate, unlike the v1→v2 crossing.
            val dir = Files.createTempDirectory("resolver-lex")
            val registry =
                SnapshotRegistry(
                    LexiconArchiveRegistrySource(writeArchive(dir, aliases, objectsOnly)),
                    ResolverThresholds.LIVE,
                )

            runBlocking {
                registry.current().entityTypes.forEach { it.reachedFrom shouldBe emptyList() }
                registry.current().entityTypes.reachByRef() shouldBe emptyMap()
            }
        }

        "an ownerRef is spelled exactly as a key is — P3's owners map depends on it" {
            val dir = Files.createTempDirectory("resolver-lex")
            val types = runBlocking { mentionRegistry(dir).current().entityTypes }
            val refs = types.map { it.ref }.toSet()
            // Every non-empty ownerRef must itself be a ref this registry declares. P3's
            // containment collapse looks its owner up in exactly this set, so an ownerRef that
            // was rendered by some other path — a re-derived qname, say — would look like an
            // unknown owner rather than like a bug.
            types.mapNotNull { it.ownerRef.ifBlank { null } }.distinct().forEach { refs shouldContain it }
        }

        "an archive with NO targets projects blanks — the pre-v3 estate stays representable" {
            // The whole point of "no behaviour change for silent estates": an estate that
            // declares no mention semantics compiles a targets-less archive, and every kind and
            // ownerRef is "" exactly as before MS. This is the OLD test's assertion, kept.
            val dir = Files.createTempDirectory("resolver-lex")
            val registry =
                SnapshotRegistry(
                    LexiconArchiveRegistrySource(writeArchive(dir, aliases, objectsOnly)),
                    ResolverThresholds.LIVE,
                )

            runBlocking {
                registry.current().entityTypes.forEach {
                    it.objectKind shouldBe ""
                    it.ownerRef shouldBe ""
                }
            }
        }

        "a ref in the entries but ABSENT from targets reads blank, never throws" {
            val dir = Files.createTempDirectory("resolver-lex")
            val types = runBlocking { mentionRegistry(dir).current().entityTypes }.associateBy { it.ref }

            // `er.entity.absent` classifies as a MODEL_OBJECT — so it IS an entity type with
            // anchors — but the model does not contain it, so contracts §6 gives it no targets
            // entry. Absence is the answer, and "" is not in VALUELESS_OBJECT_KINDS, so
            // downstream treats it exactly as it treated every ref before MS.
            types.getValue("er.entity.absent").anchors shouldBe listOf("ghost")
            types.getValue("er.entity.absent").objectKind shouldBe ""
            types.getValue("er.entity.absent").ownerRef shouldBe ""
        }

        // ---- MS-P2·S2 / contracts §6: the schemaVersion check, introduced HERE ----------------
        //
        // review-082 F2 established that neither serving reader read `schemaVersion` at all, so
        // an archive from a future producer arrived as a generic "undecodable" — the hardest
        // thing to diagnose in a cluster. It reads it now, and the WARN names BOTH versions.

        fun warnsFrom(body: () -> Unit): List<String> {
            val logger =
                (org.slf4j.LoggerFactory.getILoggerFactory() as LoggerContext)
                    .getLogger(LexiconArchiveRegistrySource::class.java.name)
            val appender = ListAppender<ILoggingEvent>().apply { start() }
            logger.addAppender(appender)
            return try {
                body()
                appender.list.filter { it.level == Level.WARN }.map { it.formattedMessage }
            } finally {
                logger.detachAppender(appender)
                appender.stop()
            }
        }

        "a foreign schemaVersion is read anyway, but WARNs naming both versions" {
            val dir = Files.createTempDirectory("resolver-lex")
            val path =
                writeArchive(dir, aliases, objectsOnly) { r ->
                    r.copy(
                        lexicon =
                            r.lexicon.copy(
                                header = r.lexicon.header.copy(schemaVersion = "ttr-lexicon-compiled/v9"),
                            ),
                    )
                }
            val source = LexiconArchiveRegistrySource(path)

            val warns = warnsFrom { runBlocking { source.fetch() } }

            // Read anyway — a version mismatch is not a reason to blank an estate's vocabulary;
            // that degrade is exactly the F1 failure mode. It is a reason to say so out loud.
            runBlocking { source.fetch() }.entries.shouldNotBeEmpty()
            val warn = warns.single { it.contains("schema") }
            warn shouldContainText "ttr-lexicon-compiled/v9"
            warn shouldContainText CompiledLexiconHeader.SCHEMA_VERSION
        }

        "the matching schemaVersion is silent" {
            val dir = Files.createTempDirectory("resolver-lex")
            val source = LexiconArchiveRegistrySource(writeArchive(dir, aliases, objectsOnly))
            warnsFrom { runBlocking { source.fetch() } }.none { it.contains("schema") } shouldBe true
        }

        "the hash is the ARCHIVE id — it moves when the file moves, and a re-read is not a re-parse" {
            val dir = Files.createTempDirectory("resolver-lex")
            val path = writeArchive(dir, aliases, objectsOnly)
            val source = LexiconArchiveRegistrySource(path)

            val first = source.hash()
            first shouldNotBe ""
            source.hash() shouldBe first

            // Repack with an extra term: same targets, different bytes.
            writeArchive(
                dir,
                aliases.replace(
                    """- terms: [ { text: "revenues", method: TYPOS(1) } ]""",
                    """- terms: [ { text: "revenues", method: TYPOS(1) }, { text: "sales", method: TOKENS } ]""",
                ),
                objectsOnly,
            )
            val second = source.hash()
            withMessage("a stale hash means the resolver serves a vocabulary the estate has replaced") {
                second shouldNotBe first
            }
            // ⚑ And the ARCHIVE id, not the lexicon's content hash. A repack with byte-identical
            // *content* still produces a different file, and the registry cache keys on this — so
            // a hash that only tracked content would let a re-delivered archive go unnoticed.
            // Mutation-checked: swapping this for `lexicon.contentHash` passes without this case.
            val samePath = Files.createTempDirectory("resolver-lex-repack")
            val once =
                LexiconArchiveRegistrySource(
                    writeArchive(samePath, aliases, objectsOnly, producer = "build-1"),
                ).hash()
            val twice =
                LexiconArchiveRegistrySource(
                    writeArchive(samePath, aliases, objectsOnly, producer = "build-2"),
                ).hash()
            withMessage("same terms, different archive — the id must move") { twice shouldNotBe once }
            runBlocking {
                source
                    .fetch()
                    .entries
                    .flatMap { it.values }
                    .map { it.value } shouldContainExactlyInAnyOrder
                    listOf("marketplace", "marketplace revenue", "revenues", "sales")
            }
        }

        "an ABSENT archive is empty and stable — not a crash, and not a reload every tick" {
            val source = LexiconArchiveRegistrySource(Path.of("/nonexistent/lexicon.tar.zst"))

            source.hash() shouldBe ""
            source.hash() shouldBe ""
            runBlocking { source.fetch().entries shouldBe emptyList() }
        }

        "unreadable BYTES are empty, not a crash" {
            val dir = Files.createTempDirectory("resolver-lex")
            val bogus = dir.resolve("lexicon.tar.zst")
            Files.write(bogus, byteArrayOf(1, 2, 3, 4))

            runBlocking { LexiconArchiveRegistrySource(bogus).fetch().entries shouldBe emptyList() }
        }

        "a WRONG-KIND archive is empty rather than silently 'the estate declared nothing'" {
            // ⚑ A REAL archive with a REAL lexicon payload, relabelled `kind: models`. Junk bytes
            // would not test this — they fail at the reader, never reaching the kind gate, and a
            // version of this test that used them passed with the gate deleted (mutation-checked).
            // The failure mode this guards is the one the whole issue is about: a model snapshot
            // pointed at the lexicon slot loads as an empty vocabulary and reads as "the estate
            // authored nothing".
            val dir = Files.createTempDirectory("resolver-lex")
            val good = LexiconArchiveRegistrySource(writeArchive(dir, aliases, objectsOnly, name = "good.tar.zst"))
            good.hash() shouldNotBe ""

            val payload =
                SnapshotReader
                    .read(
                        dir.resolve("good.tar.zst").let {
                            java.nio.file.Files
                                .readAllBytes(it)
                        },
                    ).shouldBeInstanceOf<SnapshotReadResult.Ok>()
                    .contents.docs
                    .getValue(LexiconArchive.LEXICON)
            val mislabelled =
                SnapshotWriter.write(
                    SnapshotManifest(kind = "models", producedBy = "test"),
                    mapOf(LexiconArchive.LEXICON to payload),
                )
            val path = dir.resolve("lexicon.tar.zst").also { it.writeBytes(mislabelled) }

            val source = LexiconArchiveRegistrySource(path)
            source.hash() shouldBe ""
            runBlocking { source.fetch().entries shouldBe emptyList() }
        }
    })

private fun withMessage(
    clue: String,
    block: () -> Unit,
) = io.kotest.assertions.withClue(clue, block)
