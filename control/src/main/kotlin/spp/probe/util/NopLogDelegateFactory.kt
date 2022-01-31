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

import io.vertx.core.spi.logging.LogDelegate
import io.vertx.core.spi.logging.LogDelegateFactory

class NopLogDelegateFactory : LogDelegateFactory {
    private val nop: LogDelegate = object : LogDelegate {
        override fun isWarnEnabled(): Boolean {
            return false
        }

        override fun isInfoEnabled(): Boolean {
            return false
        }

        override fun isDebugEnabled(): Boolean {
            return false
        }

        override fun isTraceEnabled(): Boolean {
            return false
        }

        override fun fatal(message: Any) {}
        override fun fatal(message: Any, t: Throwable) {}
        override fun error(message: Any) {}
        override fun error(message: Any, vararg params: Any) {}
        override fun error(message: Any, t: Throwable) {}
        override fun error(message: Any, t: Throwable, vararg params: Any) {}
        override fun warn(message: Any) {}
        override fun warn(message: Any, vararg params: Any) {}
        override fun warn(message: Any, t: Throwable) {}
        override fun warn(message: Any, t: Throwable, vararg params: Any) {}
        override fun info(message: Any) {}
        override fun info(message: Any, vararg params: Any) {}
        override fun info(message: Any, t: Throwable) {}
        override fun info(message: Any, t: Throwable, vararg params: Any) {}
        override fun debug(message: Any) {}
        override fun debug(message: Any, vararg params: Any) {}
        override fun debug(message: Any, t: Throwable) {}
        override fun debug(message: Any, t: Throwable, vararg params: Any) {}
        override fun trace(message: Any) {}
        override fun trace(message: Any, vararg params: Any) {}
        override fun trace(message: Any, t: Throwable) {}
        override fun trace(message: Any, t: Throwable, vararg params: Any) {}
    }

    override fun isAvailable(): Boolean {
        return true //needs to be true or gets the "Using ..." output line
    }

    override fun createDelegate(name: String): LogDelegate {
        return nop
    }
}
