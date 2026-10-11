// SPDX-License-Identifier: Apache-2.0
package org.tatrman.money

import org.tatrman.money.discover.AmountColumns
import org.tatrman.money.discover.ColumnRef
import org.tatrman.money.discover.FxTable
import org.tatrman.money.discover.MoneyDiscovery
import org.tatrman.plan.v1.QualifiedName
import org.tatrman.plan.v1.SchemaCode

/**
 * Domain fake for the [MoneyDiscovery] port (RG-P3.S0 metadata seam). Backs money's discovery/recipe
 * tests from an in-memory object list, answering [amountColumns]/[fxTable]/[eventDateColumn] with the
 * recipe layer's domain types directly — **no metadata protos** (the real proto mapping lives in
 * `MetaV1MoneyDiscovery`, proven by its component spec against in-process Veles).
 */
class FakeMetadataClient(
    private val objects: List<Obj>,
) : MoneyDiscovery {
    /** One metadata object. `role`/`semanticKind` are the semantics strings; null = none. */
    data class Obj(
        val pkg: String,
        val entityName: String,
        val name: String,
        val kind: String, // "entity" | "attribute"
        val role: String? = null, // attribute role, e.g. "amount_domestic"
        val semanticKind: String? = null, // entity kind, e.g. "fx_rate"
    ) {
        val qname: QualifiedName =
            QualifiedName
                .newBuilder()
                .setSchemaCode(SchemaCode.ER)
                .setNamespace(entityName)
                .setName(name)
                .setPackage(pkg)
                .build()
    }

    private fun Obj.toColumnRef(): ColumnRef = ColumnRef(qname = qname, entityName = entityName, columnName = name)

    /** Package-wide attribute columns carrying [role] (mirrors the predecessor platform's role filter). */
    private fun roleColumns(
        role: String,
        pkg: String,
    ): List<ColumnRef> =
        objects
            .filter { it.pkg == pkg && it.kind == "attribute" && it.role == role }
            .map { it.toColumnRef() }

    override suspend fun amountColumns(pkg: String): AmountColumns =
        AmountColumns(
            domestic = roleColumns("amount_domestic", pkg).firstOrNull(),
            amount = roleColumns("amount", pkg),
            currencyCode = roleColumns("currency_code", pkg).firstOrNull(),
        )

    override suspend fun fxTable(pkg: String): FxTable? {
        val entity =
            objects.firstOrNull {
                it.pkg == pkg && it.kind == "entity" && it.semanticKind == "fx_rate"
            } ?: return null
        val rate = roleColumns("fx_rate", pkg).firstOrNull() ?: return null
        val from = roleColumns("fx_from_currency", pkg).firstOrNull() ?: return null
        val to = roleColumns("fx_to_currency", pkg).firstOrNull() ?: return null
        return FxTable(
            entity = entity.qname,
            entityName = entity.entityName,
            rate = rate,
            fromCurrency = from,
            toCurrency = to,
            validFrom = roleColumns("valid_from", pkg).firstOrNull(),
            validTo = roleColumns("valid_to", pkg).firstOrNull(),
        )
    }

    override suspend fun eventDateColumn(pkg: String): ColumnRef? = roleColumns("event_date", pkg).firstOrNull()

    override suspend fun probeReady(): Boolean = true

    companion object {
        /** Sale fact with amount_domestic + native amount + event_date — the domestic-shortcut fixture. */
        fun domestic(pkg: String = "cnc"): FakeMetadataClient =
            FakeMetadataClient(
                listOf(
                    Obj(pkg, "Sale", "amount_dom", "attribute", role = "amount_domestic"),
                    Obj(pkg, "Sale", "amount", "attribute", role = "amount"),
                    Obj(pkg, "Sale", "date", "attribute", role = "event_date"),
                ),
            )

        /** Sale + an FxRate table (rate/from/to + validity) — the FX JoinRecipe fixture. */
        fun withFxTable(pkg: String = "cnc"): FakeMetadataClient =
            FakeMetadataClient(
                listOf(
                    Obj(pkg, "Sale", "amount", "attribute", role = "amount"),
                    Obj(pkg, "Sale", "date", "attribute", role = "event_date"),
                    Obj(pkg, "FxRate", "FxRate", "entity", semanticKind = "fx_rate"),
                    Obj(pkg, "FxRate", "rate", "attribute", role = "fx_rate"),
                    Obj(pkg, "FxRate", "from_ccy", "attribute", role = "fx_from_currency"),
                    Obj(pkg, "FxRate", "to_ccy", "attribute", role = "fx_to_currency"),
                    Obj(pkg, "FxRate", "valid_from", "attribute", role = "valid_from"),
                    Obj(pkg, "FxRate", "valid_to", "attribute", role = "valid_to"),
                ),
            )

        /**
         * Sale (amount, NO event_date) + a time-versioned FxRate (rate/from/to + valid_from/valid_to).
         * RG-GND-002: the transaction-date FX policy needs the fact's event_date to pick the applicable
         * rate row; without it the join would match every historical rate version — so grounding must
         * fail loudly rather than silently guess.
         */
        fun withFxTableNoEventDate(pkg: String = "cnc"): FakeMetadataClient =
            FakeMetadataClient(
                listOf(
                    Obj(pkg, "Sale", "amount", "attribute", role = "amount"),
                    Obj(pkg, "FxRate", "FxRate", "entity", semanticKind = "fx_rate"),
                    Obj(pkg, "FxRate", "rate", "attribute", role = "fx_rate"),
                    Obj(pkg, "FxRate", "from_ccy", "attribute", role = "fx_from_currency"),
                    Obj(pkg, "FxRate", "to_ccy", "attribute", role = "fx_to_currency"),
                    Obj(pkg, "FxRate", "valid_from", "attribute", role = "valid_from"),
                    Obj(pkg, "FxRate", "valid_to", "attribute", role = "valid_to"),
                ),
            )

        /** Sale with a native amount + currency_code, no fx table — the native-foreign-filter fixture. */
        fun withCurrencyCode(pkg: String = "cnc"): FakeMetadataClient =
            FakeMetadataClient(
                listOf(
                    Obj(pkg, "Sale", "amount", "attribute", role = "amount"),
                    Obj(pkg, "Sale", "currency", "attribute", role = "currency_code"),
                ),
            )

        /** Sale with only a native amount — foreign currency here is UNGROUNDABLE. */
        fun amountOnly(pkg: String = "cnc"): FakeMetadataClient =
            FakeMetadataClient(listOf(Obj(pkg, "Sale", "amount", "attribute", role = "amount")))

        /** Sale with two amount-role columns (net + gross), no domestic — the ambiguity fixture. */
        fun ambiguousAmounts(pkg: String = "cnc"): FakeMetadataClient =
            FakeMetadataClient(
                listOf(
                    Obj(pkg, "Sale", "net_amount", "attribute", role = "amount"),
                    Obj(pkg, "Sale", "gross_amount", "attribute", role = "amount"),
                ),
            )
    }
}
