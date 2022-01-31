/*
 * Source++, the open-source live coding platform.
 * Copyright (C) 2022 CodeBrig, Inc.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
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
