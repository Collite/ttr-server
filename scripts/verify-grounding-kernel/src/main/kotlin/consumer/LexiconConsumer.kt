// SPDX-License-Identifier: Apache-2.0
package consumer

import org.tatrman.grounding.lexicon.GroundingSlice
import org.tatrman.grounding.lexicon.GroundingSliceSource

/** Compiles against `grounding-lexicon`: a slice source with no archive serves the empty slice. */
object LexiconConsumer {
    fun current(): GroundingSlice = GroundingSliceSource("chrono", null).refresh().forLang("cs-CZ")
}
