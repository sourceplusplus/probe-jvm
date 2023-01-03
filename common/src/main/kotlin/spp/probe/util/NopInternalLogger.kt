/*
 * Source++, the continuous feedback platform for developers.
 * Copyright (C) 2022-2023 CodeBrig, Inc.
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
package spp.probe.util

import io.netty.util.internal.logging.InternalLogLevel
import io.netty.util.internal.logging.InternalLogger

class NopInternalLogger : InternalLogger {
    override fun name(): String {
        return "nop"
    }

    override fun isTraceEnabled(): Boolean {
        return false
    }

    override fun trace(msg: String) {}
    override fun trace(format: String, arg: Any) {}
    override fun trace(format: String, argA: Any, argB: Any) {}
    override fun trace(format: String, vararg arguments: Any) {}
    override fun trace(msg: String, t: Throwable) {}
    override fun trace(t: Throwable) {}
    override fun isDebugEnabled(): Boolean {
        return false
    }

    override fun debug(msg: String) {}
    override fun debug(format: String, arg: Any) {}
    override fun debug(format: String, argA: Any, argB: Any) {}
    override fun debug(format: String, vararg arguments: Any) {}
    override fun debug(msg: String, t: Throwable) {}
    override fun debug(t: Throwable) {}
    override fun isInfoEnabled(): Boolean {
        return false
    }

    override fun info(msg: String) {}
    override fun info(format: String, arg: Any) {}
    override fun info(format: String, argA: Any, argB: Any) {}
    override fun info(format: String, vararg arguments: Any) {}
    override fun info(msg: String, t: Throwable) {}
    override fun info(t: Throwable) {}
    override fun isWarnEnabled(): Boolean {
        return false
    }

    override fun warn(msg: String) {}
    override fun warn(format: String, arg: Any) {}
    override fun warn(format: String, vararg arguments: Any) {}
    override fun warn(format: String, argA: Any, argB: Any) {}
    override fun warn(msg: String, t: Throwable) {}
    override fun warn(t: Throwable) {}
    override fun isErrorEnabled(): Boolean {
        return false
    }

    override fun error(msg: String) {}
    override fun error(format: String, arg: Any) {}
    override fun error(format: String, argA: Any, argB: Any) {}
    override fun error(format: String, vararg arguments: Any) {}
    override fun error(msg: String, t: Throwable) {}
    override fun error(t: Throwable) {}
    override fun isEnabled(level: InternalLogLevel): Boolean {
        return false
    }

    override fun log(level: InternalLogLevel, msg: String) {}
    override fun log(level: InternalLogLevel, format: String, arg: Any) {}
    override fun log(level: InternalLogLevel, format: String, argA: Any, argB: Any) {}
    override fun log(level: InternalLogLevel, format: String, vararg arguments: Any) {}
    override fun log(level: InternalLogLevel, msg: String, t: Throwable) {}
    override fun log(level: InternalLogLevel, t: Throwable) {}
}
