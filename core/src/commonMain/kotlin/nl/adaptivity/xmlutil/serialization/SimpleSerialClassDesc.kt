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

package nl.adaptivity.xmlutil.serialization

import kotlinx.serialization.*
import nl.adaptivity.util.kotlin.xmlutil.arrayMap
import nl.adaptivity.xmlutil.multiplatform.maybeAnnotations
import nl.adaptivity.xmlutil.multiplatform.name
import kotlin.jvm.JvmName

inline fun <reified T> simpleSerialClassDesc(kind: SerialKind, vararg elements: Pair<String, SerialDescriptor>): SerialDescriptor {
    return SimpleSerialClassDesc(kind, T::class.name, T::class.maybeAnnotations, *elements)
}

inline fun <reified T> simpleSerialClassDesc(kind: SerialKind, entityAnnotations: List<Annotation>, vararg elements: Pair<String, SerialDescriptor>): SerialDescriptor {
    return SimpleSerialClassDesc(kind, T::class.name, entityAnnotations, *elements)
}

inline fun <reified T> simpleSerialClassDesc(): SerialDescriptor {
    return SimpleSerialClassDesc(StructureKind.CLASS, T::class.name, T::class.maybeAnnotations)
}

inline fun <reified T> simpleSerialClassDesc(entityAnnotations: List<Annotation>): SerialDescriptor {
    return SimpleSerialClassDesc(StructureKind.CLASS, T::class.name, entityAnnotations)
}

@JvmName("simpleSerialClassDescFromSerializer")
inline fun <reified T> simpleSerialClassDesc(vararg elements: Pair<String, KSerializer<*>>): SerialDescriptor {
    return SimpleSerialClassDesc(T::class.name, StructureKind.CLASS, T::class.maybeAnnotations, *elements)
}

@JvmName("simpleSerialClassDescFromSerializer")
inline fun <reified T> simpleSerialClassDesc(entityAnnotations: List<Annotation>, vararg elements: Pair<String, KSerializer<*>>): SerialDescriptor {
    return SimpleSerialClassDesc(T::class.name, StructureKind.CLASS, entityAnnotations, *elements)
}


class SimpleSerialClassDescPrimitive(override val kind: PrimitiveKind, override val name: String) : SerialDescriptor {

    override fun getElementIndex(name: String) = CompositeDecoder.UNKNOWN_NAME

    override fun getElementName(index: Int): String = throw IndexOutOfBoundsException(index.toString())

    override fun isElementOptional(index: Int): Boolean = false
}

class SimpleSerialClassDesc(override val kind: SerialKind = StructureKind.CLASS,
                            override val name: String,
                            private val entityAnnotations: List<Annotation>,
                            vararg val elements: Pair<String, SerialDescriptor>): SerialDescriptor {

    constructor(name:String, kind: SerialKind = StructureKind.CLASS, entityAnnotations: List<Annotation>, vararg elements: Pair<String, KSerializer<*>>): this(kind, name, entityAnnotations, *(elements.arrayMap { it.first to it.second.descriptor }))

    override fun getElementIndex(name: String): Int {
        val index = elements.indexOfFirst { it.first==name }
        return when {
            index >= 0 -> index
            else       -> CompositeDecoder.UNKNOWN_NAME
        }
    }

    override fun getEntityAnnotations(): List<Annotation> {
        return entityAnnotations
    }

    override fun getElementDescriptor(index: Int): SerialDescriptor {
        return elements[index].second
    }

    override fun getElementName(index: Int) = elements[index].first

    override fun isElementOptional(index: Int): Boolean = false

    override val elementsCount: Int get() = elements.size
}

fun SerialDescriptor.withName(name: String): SerialDescriptor = RenameDesc(this, name)

private class RenameDesc(val delegate: SerialDescriptor, override val name:String): SerialDescriptor by delegate

abstract class DelegateSerializer<T>(val delegate: KSerializer<T>): KSerializer<T> {
    override val descriptor: SerialDescriptor get() = delegate.descriptor

    override fun deserialize(decoder: Decoder): T = delegate.deserialize(decoder)

    override fun patch(decoder: Decoder, old: T): T = delegate.patch(decoder, old)

    override fun serialize(encoder: Encoder, obj: T) = delegate.serialize(encoder, obj)
}
