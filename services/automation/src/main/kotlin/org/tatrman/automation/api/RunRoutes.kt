// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.api

import io.ktor.server.routing.Route
import org.tatrman.automation.runner.RunRuntime
import org.tatrman.automation.runner.RunStatusReader
import org.tatrman.automation.protocol.ProtocolRecorder

/** Install INSIDE authenticate(AUTOMATION_AUTH), after bearer verification and error/JSON plugins. */
fun Route.runRoutes(runtime: RunRuntime) {
    openRoutes(runtime.sessions)
    evaluateRoutes(runtime.sessions)
    runReadRoutes(runtime.sessions, runtime.displayRows)
    routeRows(runtime.sessions, runtime.displayRows)
    outputRoutes(runtime.sessions, runtime.displayRows)
    durableRunRoutes(runtime.status, runtime.protocol)
    closeRoutes(runtime.closer)
}

/** Install inside bearer authentication. These readers remain usable without any execution runtime. */
fun Route.durableRunRoutes(
    status: RunStatusReader,
    protocol: ProtocolRecorder,
) {
    runStatusRoutes(status::read)
    protocolRoutes(protocol)
    batchRoutes(status)
}
