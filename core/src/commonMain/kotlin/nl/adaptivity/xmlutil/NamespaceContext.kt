/*
 * Copyright (c) 2018.
 *
 * This file is part of XmlUtil.
 *
 * This file is licenced to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You should have received a copy of the license with the source distribution.
 * Alternatively, you may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package nl.adaptivity.xmlutil

expect interface NamespaceContext {
    fun getNamespaceURI(prefix: String): String?
    fun getPrefix(namespaceURI: String): String?
    @Deprecated("Don't use as unsafe", ReplaceWith("prefixesFor(namespaceURI)", "nl.adaptivity.xmlutil.prefixesFor"))
    fun getPrefixes(namespaceURI: String): Iterator<Any?>
}

interface IterableNamespaceContext: NamespaceContext, Iterable<Namespace>

@Suppress("UNCHECKED_CAST", "NOTHING_TO_INLINE", "DEPRECATION")
inline fun NamespaceContext.prefixesFor(namespaceURI: String): Iterator<String> = getPrefixes(namespaceURI) as Iterator<String>