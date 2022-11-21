/*
 * Source++, the continuous feedback platform for developers.
 * Copyright (C) 2022 CodeBrig, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package spp.probe.remotes

import io.vertx.core.AbstractVerticle

abstract class ILiveInstrumentRemote : AbstractVerticle() {
    abstract fun isInstrumentEnabled(instrumentId: String): Boolean
    abstract fun isHit(instrumentId: String): Boolean
    abstract fun putBreakpoint(breakpointId: String, source: String, line: Int, ex: Throwable)
    abstract fun putLog(logId: String, logFormat: String, vararg logArguments: String?)
    abstract fun putMeter(meterId: String)
    abstract fun openLocalSpan(spanId: String)
    abstract fun closeLocalSpan(spanId: String)
    abstract fun closeLocalSpanAndThrowException(throwable: Throwable, spanId: String): Throwable
    abstract fun putContext(instrumentId: String, key: String, value: Any)
    abstract fun putLocalVariable(instrumentId: String, key: String, value: Any?, type: String)
    abstract fun putField(instrumentId: String, key: String, value: Any?, type: String)
    abstract fun putStaticField(instrumentId: String, key: String, value: Any?, type: String)
    abstract fun putReturn(instrumentId: String, value: Any?, type: String)
    abstract fun startTimer(meterId: String)
    abstract fun stopTimer(meterId: String)
}
