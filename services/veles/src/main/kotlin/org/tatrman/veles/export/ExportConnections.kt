// SPDX-License-Identifier: Apache-2.0
package org.tatrman.veles.export

import org.tomlj.Toml
import org.tomlj.TomlTable
import org.tatrman.ttr.metadata.export.ConnectionDescriptor

/** Only the public locator contract enters defs.json; credentials never travel. */
internal fun readExportConnections(text: String?): List<ConnectionDescriptor> {
    if (text == null) return emptyList()
    val doc = Toml.parse(text)
    require(!doc.hasErrors()) { "invalid connections.toml: ${doc.errors().first()}" }

    fun table(value: TomlTable): Map<String, Any> =
        value.keySet().associateWith { key ->
            val item = requireNotNull(value.get(key))
            if (item is TomlTable) table(item) else item
        }
    val array = doc.getArray("connection") ?: return emptyList()
    return (0 until array.size()).map { index ->
        val item = array.getTable(index)
        ConnectionDescriptor(
            requireNotNull(item.getString("id")),
            item.getString("database"),
            item.getString("schema"),
            item.getTable("locators")?.let(::table).orEmpty(),
        )
    }
}
