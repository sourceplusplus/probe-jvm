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
