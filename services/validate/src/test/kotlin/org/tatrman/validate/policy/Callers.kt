// SPDX-License-Identifier: Apache-2.0
package org.tatrman.validate.policy

/** A caller holding [roles] and carrying no identity attributes — what the role-gate specs need. */
internal fun rolesOnly(vararg roles: String): Caller = Caller(roles.toList(), emptySet())
