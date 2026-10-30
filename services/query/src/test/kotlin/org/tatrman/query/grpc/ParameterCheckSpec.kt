// SPDX-License-Identifier: Apache-2.0
package org.tatrman.query.grpc

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.tatrman.plan.v1.Expression
import org.tatrman.plan.v1.FilterNode
import org.tatrman.plan.v1.FunctionCall
import org.tatrman.plan.v1.ParameterBinding
import org.tatrman.plan.v1.ParameterRef
import org.tatrman.plan.v1.PipelineContext
import org.tatrman.plan.v1.PlanNode
import org.tatrman.plan.v1.Value

class ParameterCheckSpec :
    StringSpec({
        fun plan(vararg names: String): PlanNode =
            PlanNode
                .newBuilder()
                .setFilter(
                    FilterNode.newBuilder().setCondition(
                        Expression.newBuilder().setFunction(
                            FunctionCall
                                .newBuilder()
                                .setOperation("test")
                                .addAllOperands(
                                    names.map {
                                        Expression
                                            .newBuilder()
                                            .setParameter(
                                                ParameterRef.newBuilder().setName(it),
                                            ).build()
                                    },
                                ),
                        ),
                    ),
                ).build()

        fun binding(
            name: String,
            type: String,
        ) = ParameterBinding.newBuilder().setName(name).setType(type)

        "requirements follow first reference order, deduplicate and exclude anonymous or unused declarations" {
            val ctx = PipelineContext.newBuilder().addParameters(binding("unused", "text")).build()
            val required = ParameterCheck.required(plan("z", "id", "z", "?0", "", "{third}"), ctx)
            required.map { it.name } shouldBe listOf("z", "id", "third")
            required.all { !it.hasValue() && it.type.isEmpty() } shouldBe true
        }

        "types prefer this caller then this translation; translation values never become caller values" {
            val caller =
                PipelineContext
                    .newBuilder()
                    .addParameters(
                        binding("id", "caller")
                            .setValue(Value.newBuilder().setIntValue(7)),
                    ).build()
            val translated =
                PipelineContext
                    .newBuilder()
                    .addParameters(binding("id", "translated"))
                    .addParameters(
                        binding("other", "text").setValue(Value.newBuilder().setStringValue("not-the-callers-value")),
                    ).build()
            val required = ParameterCheck.required(plan("id", "other", "missing"), caller, translated)
            required.map { it.type } shouldBe listOf("caller", "text", "")
            required.map { it.hasValue() } shouldBe listOf(true, false, false)
            required.first().value.intValue shouldBe 7
        }

        "Compile requirements match binding names exactly as the guard and worker do" {
            val caller =
                PipelineContext
                    .newBuilder()
                    .addParameters(
                        binding("{id}", "int")
                            .setValue(Value.newBuilder().setIntValue(7)),
                    ).build()
            val required = ParameterCheck.required(plan("id"), caller).single()
            required.name shouldBe "id"
            required.type shouldBe ""
            required.hasValue() shouldBe false
        }
    })
