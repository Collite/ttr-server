// SPDX-License-Identifier: Apache-2.0
package org.tatrman.resolver

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.tatrman.resolver.pipeline.Bindings
import org.tatrman.resolver.v1.Binding
import org.tatrman.resolver.v1.TargetClass

/**
 * LP-P2b·T4 (contracts §3.4) — a `pred:` binding never gates a value.
 *
 * The rule is one line in [Bindings]; the reason it needs a test of its own is that
 * STRING_PREDICATE is the class MOST likely to win its span. *obsahující* is a whole word an
 * author declared EXACT, so on *dodací místa obsahující "Pelex"* it takes its anchor outright —
 * where an operator or a grounding trigger usually competes with nothing.
 *
 * ⛑ Admitted, it would write `attribute_ref = "pred:contains"` into the lattice and the query door
 * would refuse the turn with `'pred:contains' is not an addressable object or attribute`. That is
 * the hartland 2026-09-16 failure verbatim — where `ground:chrono` reached `attribute_ref` and the
 * refusal named the one part that was working — with a different prefix. The lesson was paid for
 * once. These cases are it applied before the second time.
 */
class PredicateNotAttributableTest :
    StringSpec({

        fun binding(targetClass: TargetClass): Binding =
            Binding
                .newBuilder()
                .setRef("pred:contains")
                .setTargetClass(targetClass)
                .build()

        "a STRING_PREDICATE binding is not attributable" {
            Bindings.attributable(binding(TargetClass.TARGET_CLASS_STRING_PREDICATE)) shouldBe false
        }

        "it joins OPERATOR and GROUNDING_TRIGGER — one list, one reason" {
            listOf(
                TargetClass.TARGET_CLASS_OPERATOR,
                TargetClass.TARGET_CLASS_GROUNDING_TRIGGER,
                TargetClass.TARGET_CLASS_STRING_PREDICATE,
            ).forEach { Bindings.attributable(binding(it)) shouldBe false }
        }

        "the classes that CAN name an attribute are untouched" {
            listOf(
                TargetClass.TARGET_CLASS_MODEL_OBJECT,
                TargetClass.TARGET_CLASS_MEMBER,
            ).forEach { Bindings.attributable(binding(it)) shouldBe true }
        }

        "UNSPECIFIED stays attributable — the asymmetry is the point" {
            // A member row carries NO class at all, so excluding "no class" would exclude exactly
            // the rows the value tier exists to reach. The rule rejects a positively-declared
            // non-attributable class and keeps silence, which needs no cooperation from the
            // matcher (contracts §1 addendum, rule 4 — `RoundPlannerTest` pins the other half).
            Bindings.attributable(binding(TargetClass.TARGET_CLASS_UNSPECIFIED)) shouldBe true
        }

        "a class number from a newer producer stays attributable, by the same rule" {
            // A peer one version ahead can send a class this build has no name for. Modelled by
            // NUMBER, because a builder refuses to take `UNRECOGNIZED` by name — which is itself
            // the reminder that this state only ever arrives off the wire.
            val future =
                Binding
                    .newBuilder()
                    .setRef("pred:whatever")
                    .setTargetClassValue(99)
                    .build()

            future.targetClass shouldBe TargetClass.UNRECOGNIZED
            // Kept, not rejected: a class we cannot reason about is silence, the same posture as
            // UNSPECIFIED — never a positive claim of non-attribution made on our behalf.
            Bindings.attributable(future) shouldBe true
        }
    })
