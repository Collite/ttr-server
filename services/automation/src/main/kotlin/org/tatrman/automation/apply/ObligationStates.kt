// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.apply

/** D-98: these states all occupy the single active obligation identity. */
internal object ObligationStates {
    val active = setOf("open", "overdue", "reopened")
}
