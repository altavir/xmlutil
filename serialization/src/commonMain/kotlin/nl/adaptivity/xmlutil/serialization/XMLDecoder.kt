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
import kotlinx.serialization.internal.EnumDescriptor
import kotlinx.serialization.modules.SerialModule
import nl.adaptivity.xmlutil.*
import nl.adaptivity.xmlutil.multiplatform.assert
import nl.adaptivity.xmlutil.serialization.canary.Canary
import nl.adaptivity.xmlutil.serialization.canary.ExtSerialDescriptor
import nl.adaptivity.xmlutil.util.CompactFragment
import kotlin.collections.set

internal open class XmlDecoderBase internal constructor(
    context: SerialModule,
    val input: XmlReader
                                                       ) : XmlCodecBase(context) {

    var skipRead = false

    internal open inner class XmlDecoder(
        parentNamespace: Namespace,
        parentDesc: SerialDescriptor,
        elementIndex: Int,
        childDesc: SerialDescriptor?,
        protected val polyInfo: PolyInfo? = null,
        private val attrIndex: Int = -1
                                        ) :
        XmlCodec(parentNamespace, parentDesc, elementIndex, childDesc), Decoder {

        override val context get() = this@XmlDecoderBase.context

        override val updateMode: UpdateMode get() = UpdateMode.BANNED

        override fun decodeNotNullMark(): Boolean {
            // No null values unless the entire document is empty (not sure the parser is happy with it)
            return input.eventType != EventType.END_DOCUMENT
        }

        override fun decodeNull(): Nothing? {
            // We don't write nulls, so if we know that we have a null we just return it
            return null
        }

        override fun decodeUnit() {
            val position = input.locationInfo
            val stringContent = decodeStringImpl(true)
            if (stringContent != "kotlin.Unit")
                throw XmlParsingException(position, "Did not find kotlin.Unit where expected $stringContent")
        }

        override fun decodeBoolean(): Boolean = decodeStringImpl(true).toBoolean()

        override fun decodeByte(): Byte = decodeStringImpl(true).toByte()

        override fun decodeShort(): Short {
            return decodeStringImpl(true).toShort()
        }

        override fun decodeInt(): Int {
            return decodeStringImpl(true).toInt()
        }

        override fun decodeLong(): Long {
            return decodeStringImpl(true).toLong()
        }

        override fun decodeFloat(): Float {
            return decodeStringImpl(true).toFloat()
        }

        override fun decodeDouble(): Double {
            return decodeStringImpl(true).toDouble()
        }

        override fun decodeChar(): Char {
            return decodeStringImpl(true).single()
        }

        override fun decodeString(): String {
            return decodeStringImpl(false)
        }

        override fun decodeEnum(enumDescription: EnumDescriptor): Int {
            val name = decodeString()
            return enumDescription.elementDescriptors().indexOfFirst { it.name == name }
        }

        private fun decodeStringImpl(defaultOverEmpty: Boolean): String {
            val defaultString = parentDesc.getElementAnnotations(elementIndex).firstOrNull<XmlDefault>()?.value

            val stringValue = if (attrIndex >= 0) {
                input.getAttributeValue(attrIndex)
            } else when (parentDesc.outputKind(elementIndex, childDesc)) {
                OutputKind.Element   -> { // This may occur with list values.
                    input.require(EventType.START_ELEMENT, serialName.namespaceURI, serialName.localPart)
                    input.readSimpleElement()
                }
                OutputKind.Attribute -> throw SerializationException(
                    "Attribute parsing without a concrete index is unsupported"
                                                                    )
                OutputKind.Text      -> input.allText()
            }
            return when {
                defaultOverEmpty && stringValue.isEmpty() && defaultString != null -> defaultString
                else                                                               -> stringValue
            }
        }

        override fun beginStructure(desc: SerialDescriptor, vararg typeParams: KSerializer<*>): CompositeDecoder {
            throw AssertionError("This should not happen as decodeSerializableValue should be called first")
        }

        override fun <T> decodeSerializableValue(deserializer: DeserializationStrategy<T>): T {
            val extDesc = childDesc as? ExtSerialDescriptor ?: Canary.serialDescriptor(deserializer)
            return deserializer.deserialize(
                SerialValueDecoder(
                    parentNamespace,
                    parentDesc,
                    elementIndex,
                    extDesc,
                    polyInfo,
                    attrIndex
                                  )
                                           )
        }

        override fun <T> updateSerializableValue(deserializer: DeserializationStrategy<T>, old: T): T {
            val extDesc = childDesc as? ExtSerialDescriptor ?: Canary.serialDescriptor(deserializer)
            val oldSize = (old as? Collection<*>)?.size ?: -1
            return deserializer.deserialize(
                SerialValueDecoder(parentNamespace, parentDesc, elementIndex, extDesc, polyInfo, attrIndex, oldSize)
                                           )
        }
    }

    internal open inner class SerialValueDecoder(
        parentNamespace: Namespace,
        parentDesc: SerialDescriptor,
        elementIndex: Int,
        private val extDesc: ExtSerialDescriptor,
        polyInfo: PolyInfo?/* = null*/,
        attrIndex: Int/* = -1*/,
        private val nextListIndex: Int = 0
                                                ) : XmlDecoder(
        parentNamespace,
        parentDesc,
        elementIndex,
        extDesc,
        polyInfo,
        attrIndex
                                                              ) {

        override fun beginStructure(desc: SerialDescriptor, vararg typeParams: KSerializer<*>): CompositeDecoder {
            if (extDesc.isNullable) return TagDecoder(serialName, parentNamespace, parentDesc, elementIndex, extDesc)
            return when (extDesc.kind) {
                is PrimitiveKind      -> throw AssertionError("A primitive is not a composite")
                StructureKind.MAP,
                StructureKind.CLASS   -> TagDecoder(serialName, serialName.toNamespace(), parentDesc, elementIndex, extDesc)
                StructureKind.LIST    -> {
                    val childName = parentDesc.requestedChildName(elementIndex)
                    if (childName != null) {
                        NamedListDecoder(serialName, serialName.toNamespace(), parentDesc, elementIndex, extDesc, childName)
                    } else {
                        AnonymousListDecoder(
                            serialName,
                            parentNamespace,
                            parentDesc,
                            elementIndex,
                            extDesc,
                            polyInfo,
                            nextListIndex
                                            )
                    }
                }
                UnionKind.OBJECT,
                UnionKind.ENUM_KIND   -> TagDecoder(serialName, serialName.toNamespace(), parentDesc, elementIndex, extDesc)
                UnionKind.SEALED,
                UnionKind.POLYMORPHIC -> PolymorphicDecoder(
                    serialName,
                    serialName.toNamespace(),
                    parentDesc,
                    elementIndex,
                    extDesc,
                    polyInfo
                                                           )
            }
        }

    }

    internal fun <T> DeserializationStrategy<T>.parseDefaultValue(
        parentNamespace: Namespace,
        desc: SerialDescriptor,
        index: Int,
        default: String,
        childDesc: SerialDescriptor?
                                                                 ): T {
        val decoder =
            XmlDecoderBase(context, CompactFragment(default).getXmlReader()).XmlDecoder(
                parentNamespace = parentNamespace,
                parentDesc = desc,
                elementIndex = index,
                childDesc = childDesc
                                                                                       )
        return deserialize(decoder)

    }

    /**
     * Special class that handles null values that are not mere primitives. Nice side-effect is that XmlDefault values
     * are actually parsed as XML and can be complex
     */
    internal inner class NullDecoder(
        parentNamespace: Namespace,
        parentDesc: SerialDescriptor,
        elementIndex: Int,
        childDesc: SerialDescriptor?
                                    ) :
        XmlDecoder(parentNamespace, parentDesc, elementIndex, childDesc), CompositeDecoder {

        override fun decodeNotNullMark() = false

        override fun <T> decodeSerializableElement(
            desc: SerialDescriptor,
            index: Int,
            deserializer: DeserializationStrategy<T>
                                                  ): T {
            val default = desc.getElementAnnotations(index).firstOrNull<XmlDefault>()?.value
            @Suppress("UNCHECKED_CAST")
            return when (default) {
                null -> null as T
                else -> {
                    val decoder = XmlDecoderBase(context, CompactFragment(default).getXmlReader())
                        .XmlDecoder(
                            parentNamespace = parentNamespace,
                            parentDesc = parentDesc,
                            elementIndex = elementIndex,
                            childDesc = childDesc
                                   )
                    deserializer.deserialize(decoder)
                }
            }
        }

        override fun beginStructure(desc: SerialDescriptor, vararg typeParams: KSerializer<*>): CompositeDecoder {
            return this
        }

        override fun endStructure(desc: SerialDescriptor) {}

        override fun decodeElementIndex(desc: SerialDescriptor): Int {
            return CompositeDecoder.READ_ALL // Let the reader worry about position (and only read the size: 0)
        }

        override fun <T> updateSerializableElement(
            desc: SerialDescriptor,
            index: Int,
            deserializer: DeserializationStrategy<T>,
            old: T
                                                  ): T =
            throw AssertionError("Null objects have no members")

        override fun decodeUnitElement(desc: SerialDescriptor, index: Int): Unit =
            throw AssertionError("Null objects have no members")

        override fun decodeBooleanElement(desc: SerialDescriptor, index: Int): Boolean =
            throw AssertionError("Null objects have no members")

        override fun decodeByteElement(desc: SerialDescriptor, index: Int): Byte =
            throw AssertionError("Null objects have no members")

        override fun decodeShortElement(desc: SerialDescriptor, index: Int): Short =
            throw AssertionError("Null objects have no members")

        override fun decodeIntElement(desc: SerialDescriptor, index: Int): Int =  // Size of map/list
            throw AssertionError("Null objects have no members")

        override fun decodeCollectionSize(desc: SerialDescriptor): Int {
            return 0
        }

        override fun decodeLongElement(desc: SerialDescriptor, index: Int): Long =
            throw AssertionError("Null objects have no members")

        override fun decodeFloatElement(desc: SerialDescriptor, index: Int): Float =
            throw AssertionError("Null objects have no members")

        override fun decodeDoubleElement(desc: SerialDescriptor, index: Int): Double =
            throw AssertionError("Null objects have no members")

        override fun decodeCharElement(desc: SerialDescriptor, index: Int): Char =
            throw AssertionError("Null objects have no members")

        override fun decodeStringElement(desc: SerialDescriptor, index: Int): String =
            throw AssertionError("Null objects have no members")

        override fun <T : Any> decodeNullableSerializableElement(
            desc: SerialDescriptor,
            index: Int,
            deserializer: DeserializationStrategy<T?>
                                                                ): T? {
            throw AssertionError("Null objects have no members")
        }

        override fun <T : Any> updateNullableSerializableElement(
            desc: SerialDescriptor,
            index: Int,
            deserializer: DeserializationStrategy<T?>,
            old: T?
                                                                ): T? {
            throw AssertionError("Null objects have no members")
        }
    }

    internal inner class RenamedDecoder(
        override val serialName: QName,
        parentNamespace: Namespace,
        parentDesc: SerialDescriptor,
        elementIndex: Int,
        extDesc: ExtSerialDescriptor,
        polyInfo: PolyInfo? = null,
        attrIndex: Int = Int.MIN_VALUE
                                       ) : SerialValueDecoder(
        parentNamespace, parentDesc, elementIndex, extDesc,
        polyInfo,
        attrIndex
                                                             )

    internal open inner class TagDecoder(
        serialName: QName,
        parentNamespace: Namespace,
        parentDesc: SerialDescriptor,
        elementIndex: Int,
        desc: ExtSerialDescriptor
                                        ) :
        XmlTagCodec(parentDesc, elementIndex, desc, parentNamespace), CompositeDecoder, XML.XmlInput {

        final override val serialName: QName = serialName

        override val updateMode: UpdateMode get() = UpdateMode.BANNED

        private var nameToMembers: Map<QName, Int>
        private var polyChildren: Map<QName, PolyInfo>

        private val seenItems = BooleanArray(desc.elementsCount)

        private var nulledItemsIdx = -1

        // We need to do this here so we can move up when reading (allowing for repeated queries for nullable values)
        protected var lastAttrIndex: Int = -1
            private set

        var currentPolyInfo: PolyInfo? = null

        init {
            val polyMap: MutableMap<QName, PolyInfo> = mutableMapOf()
            val nameMap: MutableMap<QName, Int> = mutableMapOf()

            for (idx in 0 until desc.elementsCount) {
                desc.getElementAnnotations(idx).firstOrNull<XmlPolyChildren>().let { xmlPolyChildren ->
                    if (xmlPolyChildren != null) {
                        for (child in xmlPolyChildren.value) {
                            val polyInfo = polyTagName(serialName, child, idx)
                            polyMap[polyInfo.tagName.normalize()] = polyInfo
                        }
                    } else {
                        val elemDesc = desc.getSafeElementDescriptor(idx)
                        val tagName = when (elemDesc?.kind) {
                            UnionKind.SEALED, // For now sealed is treated like polymorphic. We can't enumerate elements yet.
                            UnionKind.POLYMORPHIC -> desc.getElementName(idx).toQname(serialName.toNamespace())
                            else                  -> desc.requestedName(serialName.toNamespace(), idx, elemDesc)
                        }
                        nameMap[tagName.normalize()] = idx
                    }

                }
            }
            polyChildren = polyMap
            nameToMembers = nameMap

        }

        override val input: XmlReader get() = this@XmlDecoderBase.input

        override val namespaceContext: NamespaceContext get() = input.namespaceContext

        override fun <T> decodeSerializableElement(
            desc: SerialDescriptor,
            index: Int,
            deserializer: DeserializationStrategy<T>
                                                  ): T {
            val decoder = when {
                nulledItemsIdx >= 0
                     -> NullDecoder(parentNamespace, desc, index, deserializer.descriptor)

                desc.kind is PrimitiveKind
                     -> XmlDecoder(
                    parentNamespace,
                    desc,
                    index,
                    deserializer.descriptor,
                    currentPolyInfo,
                    lastAttrIndex
                                  )

                else -> SerialValueDecoder(
                    parentNamespace,
                    desc,
                    index,
                    Canary.serialDescriptor(deserializer),
                    currentPolyInfo,
                    lastAttrIndex
                                          )
            }

            return deserializer.deserialize(decoder).also {
                seenItems[index] = true
            }
        }

        override fun <T : Any> decodeNullableSerializableElement(
            desc: SerialDescriptor,
            index: Int,
            deserializer: DeserializationStrategy<T?>
                                                                ): T? {
            val decoder = when {
                nulledItemsIdx >= 0 -> return null
                deserializer.descriptor.kind is PrimitiveKind
                                    -> XmlDecoder(
                    parentNamespace,
                    desc,
                    index,
                    deserializer.descriptor,
                    currentPolyInfo,
                    lastAttrIndex
                                                 )

                else                -> SerialValueDecoder(
                    parentNamespace, desc, index, Canary.serialDescriptor(deserializer),
                    currentPolyInfo,
                    lastAttrIndex
                                                         )
            }

            return deserializer.deserialize(decoder).also {
                seenItems[index] = true
            }
        }

        override fun <T> updateSerializableElement(
            desc: SerialDescriptor,
            index: Int,
            deserializer: DeserializationStrategy<T>,
            old: T
                                                  ): T {
            val decoder: XmlDecoder = when {
                nulledItemsIdx >= 0 -> NullDecoder(parentNamespace, desc, index, deserializer.descriptor)

                desc.kind is PrimitiveKind
                                    -> XmlDecoder(
                    parentNamespace,
                    desc,
                    index,
                    deserializer.descriptor,
                    currentPolyInfo,
                    lastAttrIndex
                                                 )

                else                -> SerialValueDecoder(
                    parentNamespace, desc, index, Canary.serialDescriptor(deserializer),
                    currentPolyInfo,
                    lastAttrIndex
                                                         )
            }

            return deserializer.patch(decoder, old).also {
                seenItems[index] = true
            }
        }

        override fun <T : Any> updateNullableSerializableElement(
            desc: SerialDescriptor,
            index: Int,
            deserializer: DeserializationStrategy<T?>,
            old: T?
                                                                ): T? {
            val decoder = when {
                nulledItemsIdx >= 0 -> return null
                else                -> XmlDecoder(
                    parentNamespace,
                    desc,
                    index,
                    deserializer.descriptor,
                    currentPolyInfo,
                    lastAttrIndex
                                                 )
            }

            return (if (old == null) deserializer.deserialize(decoder) else deserializer.patch(decoder, old)).also {
                seenItems[index] = true
            }
        }

        open fun indexOf(name: QName, attr: Boolean): Int {
            currentPolyInfo = null

            val polyMap = polyChildren
            val nameMap = nameToMembers

            val normalName = name.normalize()
            nameMap[normalName]?.let { return it }
            polyMap[normalName]?.let {
                currentPolyInfo = it
                return it.index
            }

            val containingNamespaceUri = serialName.namespaceURI
            // Allow attributes in the null namespace to match candidates with a name that is that of the parent tag
            if (attr && name.namespaceURI.isEmpty()) {
                val attrName = normalName.copy(namespaceURI = containingNamespaceUri)
                nameMap[attrName]?.let { return it }
                polyMap[normalName.copy(namespaceURI = containingNamespaceUri)]?.let { return it.index }
            }

            // If the parent namespace uri is the same as the namespace uri of the element, try looking for an element
            // with a null namespace instead
            if (containingNamespaceUri.isNotEmpty() && containingNamespaceUri == name.namespaceURI) {
                nameMap[QName(name.getLocalPart())]?.let { return it }
            }

            throw UnknownXmlFieldException(input.locationInfo, name.toString(), (nameMap.keys + polyMap.keys))
        }

        override fun decodeElementIndex(desc: SerialDescriptor): Int {
            /* Decoding works in 3 stages: attributes, child content, null values.
             * Attribute decoding is finished when attrIndex<0
             * child content is finished when nulledItemsIdx >=0
             * Fully finished when the nulled items returns a DONE value
             */
            if (nulledItemsIdx >= 0) {
                input.require(EventType.END_ELEMENT, null, null)

                if (nulledItemsIdx >= seenItems.size) return CompositeDecoder.READ_DONE
                val i = nulledItemsIdx
                nextNulledItemsIdx()
                return i
            }
            lastAttrIndex++

            if (lastAttrIndex >= 0 && lastAttrIndex < input.attributeCount) {

                val name = input.getAttributeName(lastAttrIndex)

                return if (name.getNamespaceURI() == XMLConstants.XMLNS_ATTRIBUTE_NS_URI ||
                    name.prefix == XMLConstants.XMLNS_ATTRIBUTE ||
                    (name.prefix.isEmpty() && name.localPart == XMLConstants.XMLNS_ATTRIBUTE)
                ) {
                    // Ignore namespace decls
                    decodeElementIndex(desc)
                } else {
                    indexOf(name, true)
                }
            }
            lastAttrIndex = Int.MIN_VALUE // Ensure to reset here, this should not practically get bigger than 0


            if (skipRead) { // reading values will already move to the end tag.
                assert(input.isEndElement())
                skipRead = false
                return readElementEnd(desc)
            }

            for (eventType in input) {
                if (!eventType.isIgnorable) {
                    // The android reader doesn't check whitespaceness

                    when (eventType) {
                        EventType.END_ELEMENT   -> return readElementEnd(desc)
                        EventType.TEXT          -> if (!input.isWhitespace()) return desc.getValueChild()
                        EventType.ATTRIBUTE     -> return indexOf(input.name, true)
                        EventType.START_ELEMENT -> return indexOf(input.name, false)
                        else                    -> throw AssertionError("Unexpected event in stream")
                    }
                }
            }
            return CompositeDecoder.READ_DONE
        }

        private fun nextNulledItemsIdx() {
            for (i in (nulledItemsIdx + 1) until seenItems.size) {
                // Items that have been seen don't need to be passed as null
                // Items that are declared as optional, don't need to be passed as ull
                if (!(seenItems[i] || desc.isElementOptional(i))) {
                    val default = desc.getElementAnnotations(i).firstOrNull<XmlDefault>()

                    val defaultOrList = when {
                        default != null -> true
                        else            -> {

                            val childDesc = try {
                                desc.getElementDescriptor(i)
                            } catch (e: Exception) {
                                null
                            }
                            val kind = childDesc?.kind
                            // If there is no child descriptor and it is missing this can only be because it is
                            // either a list or nullable. It can be treated as such.
                            childDesc == null || childDesc.isNullable || when (kind) {
                                StructureKind.LIST,
                                StructureKind.MAP -> true
                                else              -> false
                            }
                        }
                    }
                    if (defaultOrList) {
                        nulledItemsIdx = i
                        return
                    }
                }
            }
            nulledItemsIdx = seenItems.size
        }

        override fun endStructure(desc: SerialDescriptor) {
            // TODO record the tag name used to be able to validate leaving
//            input.require(EventType.END_ELEMENT, serialName.namespaceURI, serialName.localPart)
            input.require(EventType.END_ELEMENT, null, null)
        }

        open fun readElementEnd(desc: SerialDescriptor): Int {
            nextNulledItemsIdx()
            return when {
                nulledItemsIdx < seenItems.size -> nulledItemsIdx
                else                            -> CompositeDecoder.READ_DONE
            }
        }

        open fun doReadAttribute(desc: SerialDescriptor, index: Int): String {
            return input.getAttributeValue(lastAttrIndex)
        }

        override fun decodeStringElement(desc: SerialDescriptor, index: Int): String {
            seenItems[index] = true
            val isAttribute = lastAttrIndex >= 0
            if (isAttribute) {
                return doReadAttribute(desc, index)
            } else if (nulledItemsIdx >= 0) { // Now reading nulls
                return desc.getElementAnnotations(index).firstOrNull<XmlDefault>()?.value
                    ?: throw MissingFieldException("${desc.getElementName(index)}:$index")
            }

            val outputKind = desc.outputKind(index, null)
            return when (outputKind) {
                OutputKind.Element   -> input.readSimpleElement()
                OutputKind.Text      -> {
                    skipRead = true
                    input.allText()
                }
                OutputKind.Attribute -> error("Attributes should already be read now")
            }
        }

        override fun decodeIntElement(desc: SerialDescriptor, index: Int): Int {
            when (desc.kind) {
                is StructureKind.MAP,
                is StructureKind.LIST -> if (index == 0) {
                    seenItems[0] = true; return 1
                }// Always read a list element by element
            }
            return decodeStringElement(desc, index).toInt()
        }

        override fun decodeUnitElement(desc: SerialDescriptor, index: Int) {
            val location = input.locationInfo
            if (decodeStringElement(desc, index) != "kotlin.Unit") throw XmlParsingException(
                location,
                "Kotlin Unit not valid"
                                                                                            )
        }

        override fun decodeBooleanElement(desc: SerialDescriptor, index: Int): Boolean {
            return decodeStringElement(desc, index).toBoolean()
        }

        override fun decodeByteElement(desc: SerialDescriptor, index: Int): Byte {
            return decodeStringElement(desc, index).toByte()
        }

        override fun decodeShortElement(desc: SerialDescriptor, index: Int): Short {
            return decodeStringElement(desc, index).toShort()
        }

        override fun decodeLongElement(desc: SerialDescriptor, index: Int): Long {
            return decodeStringElement(desc, index).toLong()
        }

        override fun decodeFloatElement(desc: SerialDescriptor, index: Int): Float {
            return decodeStringElement(desc, index).toFloat()
        }

        override fun decodeDoubleElement(desc: SerialDescriptor, index: Int): Double {
            return decodeStringElement(desc, index).toDouble()
        }

        override fun decodeCharElement(desc: SerialDescriptor, index: Int): Char {
            return decodeStringElement(desc, index).single()
        }


    }

    internal inner class AnonymousListDecoder(
        serialName: QName,
        parentNamespace: Namespace,
        parentDesc: SerialDescriptor,
        elementIndex: Int,
        desc: ExtSerialDescriptor,
        private val polyInfo: PolyInfo?,
        private val listIndex: Int
                                             ) : TagDecoder(serialName, parentNamespace, parentDesc, elementIndex, desc) {
        override val updateMode: UpdateMode get() = UpdateMode.UPDATE

        private var finished: Boolean = false

        override fun decodeElementIndex(desc: SerialDescriptor): Int {
            return when {
                finished -> CompositeDecoder.READ_DONE
                else     -> {
                    finished = true; listIndex
                }
            }
        }

        override fun <T> decodeSerializableElement(
            desc: SerialDescriptor,
            index: Int,
            deserializer: DeserializationStrategy<T>
                                                  ): T {
            val childName = polyInfo?.tagName ?: parentDesc.requestedChildName(elementIndex) ?: serialName

            // This is an anonymous list decoder. The descriptor passed here is for a list, not the xml parent element.

            val decoder =
                RenamedDecoder(
                    childName,
                    parentNamespace,
                    parentDesc,
                    elementIndex,
                    Canary.serialDescriptor(deserializer),
                    polyInfo,
                    Int.MIN_VALUE
                              )
            return deserializer.deserialize(decoder)
        }

        override fun <T> updateSerializableElement(
            desc: SerialDescriptor,
            index: Int,
            deserializer: DeserializationStrategy<T>,
            old: T
                                                  ): T {
            val childName = polyInfo?.tagName ?: parentDesc.requestedChildName(elementIndex) ?: serialName

            val decoder = RenamedDecoder(
                childName, parentNamespace, desc, index, Canary.serialDescriptor(deserializer),
                polyInfo,
                Int.MIN_VALUE
                                        )
            return deserializer.patch(decoder, old)
        }

        override fun decodeCollectionSize(desc: SerialDescriptor): Int {
            return 1
        }
    }

    internal inner class NamedListDecoder(
        serialName: QName,
        parentNamespace: Namespace,
        parentDesc: SerialDescriptor,
        elementIndex: Int,
        desc: ExtSerialDescriptor,
        private val childName: QName
                                         ) :
        TagDecoder(serialName, parentNamespace, parentDesc, elementIndex, desc) {
        override val updateMode: UpdateMode get() = UpdateMode.UPDATE
        private var childCount = 0

        override fun decodeElementIndex(desc: SerialDescriptor): Int {
            return when (input.nextTag()) {
                EventType.END_ELEMENT -> CompositeDecoder.READ_DONE
                else                  -> childCount++ // This is important to ensure appending in the list.
            }
        }

        override fun <T> decodeSerializableElement(
            desc: SerialDescriptor,
            index: Int,
            deserializer: DeserializationStrategy<T>
                                                  ): T {
            val decoder =
                RenamedDecoder(
                    childName, serialName.toNamespace(), desc, index, Canary.serialDescriptor(deserializer),
                    super.currentPolyInfo,
                    super.lastAttrIndex
                              )
            return deserializer.deserialize(decoder)
        }

        override fun <T> updateSerializableElement(
            desc: SerialDescriptor,
            index: Int,
            deserializer: DeserializationStrategy<T>,
            old: T
                                                  ): T {
            val decoder =
                RenamedDecoder(
                    childName, serialName.toNamespace(), desc, index, Canary.serialDescriptor(deserializer),
                    super.currentPolyInfo,
                    super.lastAttrIndex
                              )
            return deserializer.patch(decoder, old)
        }
    }

    internal inner class PolymorphicDecoder(
        serialName: QName,
        parentNamespace: Namespace,
        parentDesc: SerialDescriptor,
        elementIndex: Int,
        desc: ExtSerialDescriptor,
        private val polyInfo: PolyInfo?
                                           ) : TagDecoder(serialName, parentNamespace, parentDesc, elementIndex, desc) {

        private val transparent get() = polyInfo != null

        override fun decodeElementIndex(desc: SerialDescriptor): Int {
            return CompositeDecoder.READ_ALL // We don't need housekeeping this way
        }

        override fun decodeStringElement(desc: SerialDescriptor, index: Int): String {
            return when (index) {
                0    -> when (polyInfo) {
                    null -> input.getAttributeValue(null, "type")?.expandTypeNameIfNeeded(parentDesc.name)
                        ?: throw XmlParsingException(input.locationInfo, "Missing type for polymorphic value")
                    else -> polyInfo.kClass
                }
                else -> super.decodeStringElement(desc, index)
            }
        }

        override fun doReadAttribute(desc: SerialDescriptor, index: Int): String {
            return if (!transparent) {
                input.getAttributeValue(null, "type")
                    ?: throw XmlParsingException(input.locationInfo, "Missing type for polymorphic value")
            } else {
                polyInfo?.kClass ?: input.name.localPart // Likely to fail unless the tagname matches the type
            }
        }

        override fun indexOf(name: QName, attr: Boolean): Int {
            return if (name.namespaceURI == "" && name.localPart == "type") 0 else 1
        }

        override fun <T> decodeSerializableElement(
            desc: SerialDescriptor,
            index: Int,
            deserializer: DeserializationStrategy<T>
                                                  ): T {
            if (!transparent) {
                input.nextTag()
                input.require(EventType.START_ELEMENT, null, "value")
            }
            return super.decodeSerializableElement(desc, index, deserializer)
        }

        override fun <T> updateSerializableElement(
            desc: SerialDescriptor,
            index: Int,
            deserializer: DeserializationStrategy<T>,
            old: T
                                                  ): T {
            if (!transparent) {
                input.nextTag()
                input.require(EventType.START_ELEMENT, null, "value")
            }
            return super.updateSerializableElement(desc, index, deserializer, old)
        }

        override fun endStructure(desc: SerialDescriptor) {
            if (!transparent) {
                input.nextTag()
                input.require(EventType.END_ELEMENT, serialName.namespaceURI, serialName.localPart)
            }
            super.endStructure(desc)
        }
    }

}
