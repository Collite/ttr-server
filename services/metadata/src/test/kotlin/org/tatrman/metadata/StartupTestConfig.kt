// SPDX-License-Identifier: Apache-2.0
package org.tatrman.metadata

import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory

/** Application tests opt into content explicitly and never inherit an offline-demo fallback. */
internal fun startupConfig(overrides: Map<String, Any>): Config =
    ConfigFactory
        .parseMap(overrides)
        .withFallback(
            ConfigFactory.parseMap(
                mapOf(
                    "metadata.sources.github-model.remote-uri" to "",
                    "metadata.sources.github-model.fallback" to "",
                    "metadata.sources.local-model.enabled" to false,
                    "metadata.sources.second-model.enabled" to false,
                    "metadata.refresh-scheduler.interval-seconds" to 0,
                    "grpc.port" to 0,
                    "telemetry.enabled" to false,
                ),
            ),
        ).withFallback(ConfigFactory.load())
        .resolve()
