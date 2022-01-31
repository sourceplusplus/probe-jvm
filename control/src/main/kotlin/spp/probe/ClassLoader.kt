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
package spp.probe

import java.io.IOException
import java.net.URL
import java.net.URLClassLoader
import java.nio.file.Paths

/*
 * Required when this classloader is used as the system classloader
 */
class ClassLoader(parent: java.lang.ClassLoader?) : URLClassLoader(arrayOfNulls(0), parent) {

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
}
