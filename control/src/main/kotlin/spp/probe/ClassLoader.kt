package spp.probe

import kotlin.Throws
import java.net.URLClassLoader
import java.io.IOException
import java.nio.file.Paths
import java.net.URL

class ClassLoader  /*
     * Required when this classloader is used as the system classloader
     */
    (parent: java.lang.ClassLoader?) : URLClassLoader(arrayOfNulls(0), parent) {
    fun add(url: URL?) {
        addURL(url)
    }

    /*
     *  Required for Java Agents when this classloader is used as the system classloader
     */
    @Throws(IOException::class)
    private fun appendToClassPathForInstrumentation(jarFile: String) {
        add(Paths.get(jarFile).toRealPath().toUri().toURL())
    }

    companion object {
        init {
            registerAsParallelCapable()
        }

        fun findAncestor(cl: java.lang.ClassLoader?): ClassLoader? {
            var cl = cl
            do {
                if (cl is ClassLoader) {
                    return cl
                }
                cl = cl!!.parent
            } while (cl != null)
            return null
        }
    }
}
