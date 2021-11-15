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
