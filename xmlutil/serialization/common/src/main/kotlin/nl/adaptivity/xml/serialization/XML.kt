/*
 * Copyright (c) 2018.
 *
 * This file is part of ProcessManager.
 *
 * ProcessManager is free software: you can redistribute it and/or modify it under the terms of version 3 of the
 * GNU Lesser General Public License as published by the Free Software Foundation.
 *
 * ProcessManager is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even
 * the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License along with ProcessManager.  If not,
 * see <http://www.gnu.org/licenses/>.
 */

package nl.adaptivity.xml.serialization

import kotlinx.serialization.*
import nl.adaptivity.util.xml.CompactFragment
import nl.adaptivity.xml.*
import kotlin.reflect.KClass

class XML(val context: SerialContext? = defaultSerialContext(),
          val repairNamespaces: Boolean = true,
          val omitXmlDecl: Boolean = true) {

    inline fun <reified T : Any> stringify(obj: T): String = stringify(T::class, context.klassSerializer(T::class), obj)

    fun <T : Any> stringify(kClass: KClass<out T>, saver: KSerialSaver<T>, obj: T): String {
        return buildString {
            val writer = XmlStreaming.newWriter(this, repairNamespaces, omitXmlDecl)
            try {
                toXml(kClass, saver, obj, writer)
            } finally {
                writer.close()
            }
        }
    }

    inline fun <reified T : Any> toXml(obj: T, target: XmlWriter) {
        toXml(T::class, context.klassSerializer(T::class), obj, target)
    }

    fun <T : Any> toXml(kClass: KClass<out T>, serializer: KSerialSaver<T>, obj: T, target: XmlWriter) {

        val output = XmlOutput(context, target, kClass)

        output.write(serializer, obj)
    }

    fun <T> parse(loader: KSerialLoader<T>, str: String): T {
        TODO("Implement")
/*


        val parser = Parser(str)
        val input = JsonInput(Mode.OBJ, parser)
        val result = input.read(loader)
        check(parser.tc == TC_EOF) { "Shall parse complete string"}
        return result
*/
    }

    companion object {
        @Suppress("UNCHECKED_CAST")
        fun <T : Any> stringify(obj: T, kClass: KClass<T> = obj::class as KClass<T>): String =
            XML().run { stringify(kClass, context.klassSerializer(kClass), obj) }

        inline fun <reified T : Any> stringify(obj: T): String = stringify(obj, T::class)

        fun <T> parse(loader: KSerialLoader<T>, str: String): T = XML().parse(loader, str)
        inline fun <reified T : Any> parse(str: String): T = parse(T::class.serializer(), str)
    }

    open class XmlOutput internal constructor(context: SerialContext?,
                                              open val target: XmlWriter,
                                              serialName: QName?,
                                              protected val childName: QName?) : TaggedOutput<OutputDescriptor>() {

        protected var serialName = serialName
            private set

        init {
            this.context = context
        }

        internal constructor(context: SerialContext?, target: XmlWriter, targetType: KClass<*>?) :
            this(context, target, targetType?.getSerialName(), targetType?.getChildName())

        open fun copy(context: SerialContext? = this.context,
                      target: XmlWriter = this.target,
                      serialName: QName? = this.serialName,
                      childName: QName? = this.childName) = XmlOutput(context, target, serialName, childName)

        override fun KSerialClassDesc.getTag(index: Int): OutputDescriptor {
            return OutputDescriptor(this, index, outputKind(index), getTagName(index))
        }

        /**
         * Called when staring to write the children of this element.
         * @param desc The descriptor of the current element
         * @param typeParams The serializers for the elements
         */
        override fun writeBegin(desc: KSerialClassDesc, vararg typeParams: KSerializer<*>): KOutput {
            val tagName = getTagName(desc)
            return when (desc.kind) {
                KSerialClassKind.LIST,
                KSerialClassKind.MAP,
                KSerialClassKind.SET         -> {
                    currentTagOrNull?.run { kind = OutputKind.Element }
                    val childName = childName
                    if (childName != null) {
                        target.doSmartStartTag(tagName)

                        // If the child tag has a different namespace uri that requires a namespace declaration
                        // And we didn't just declare the prefix here already then we will declare it here rather
                        // than on each child
                        if (serialName?.prefix != childName.prefix && target.getNamespaceUri(
                                childName.prefix) != childName.namespaceURI) {
                            target.namespaceAttr(childName.prefix, childName.namespaceURI)
                        }
                    }
                    RepeatedWriter(context, target, tagName, childName)
                }

                KSerialClassKind.CLASS,
                KSerialClassKind.OBJECT,
                KSerialClassKind.SEALED,
                KSerialClassKind.POLYMORPHIC -> {
                    target.doSmartStartTag(tagName)
                    val lastInvertedIndex = desc.lastInvertedIndex()
                    if (lastInvertedIndex > 0) {
                        InvertedWriter(context, target, tagName, null, lastInvertedIndex)
                    } else {
                        XmlOutput(context, target, tagName, null)
                    }
                }

                KSerialClassKind.ENTRY       -> TODO("Maps are not yet supported")//MapEntryWriter(currentTagOrNull)
                else                         -> throw SerializationException(
                    "Primitives are not supported at top-level")
            }
        }

        private fun getTagName(desc: KSerialClassDesc) =
            (currentTagOrNull?.name ?: serialName ?: QName(desc.name.substringAfterLast('.'))).also { serialName = it }

        /**
         * Called when finished writing the current complex element.
         */
        override fun writeFinished(desc: KSerialClassDesc) {
            target.endTag(serialName!!)
        }

/*

        override fun writeElement(desc: KSerialClassDesc, index: Int): Boolean {
            if (desc.outputKind(index)) {
                target.smartStartTag(desc.getTagName(index).also { currentInnerTag = it })
            } else {
                pendingAttrName = desc.getTagName(index)
            }
            return true
        }
*/

        override fun <T> writeSerializableValue(saver: KSerialSaver<T>, value: T) {
            val tag = currentTagOrNull
            if (tag != null && tag.name != serialName && tag.childName != childName) {
                copy(serialName = tag.name, childName = tag.childName).apply {
                    if (writeElement(tag.desc, tag.index))
                        writeSerializableValue(saver, value)
                }
            } else {
                super.writeSerializableValue(saver, value)
            }
        }

        override fun writeTaggedNull(tag: OutputDescriptor) {
            // Do nothing - in xml absense is null
        }

        override fun writeTaggedBoolean(tag: OutputDescriptor, value: Boolean) = writeTaggedString(tag,
                                                                                                   value.toString())

        override fun writeTaggedByte(tag: OutputDescriptor, value: Byte) = writeTaggedString(tag, value.toString())

        override fun writeTaggedChar(tag: OutputDescriptor, value: Char) = writeTaggedString(tag, value.toString())

        override fun writeTaggedDouble(tag: OutputDescriptor, value: Double) = writeTaggedString(tag, value.toString())

        override fun writeTaggedFloat(tag: OutputDescriptor, value: Float) = writeTaggedString(tag, value.toString())

        override fun writeTaggedInt(tag: OutputDescriptor, value: Int) = writeTaggedString(tag, value.toString())

        override fun writeTaggedLong(tag: OutputDescriptor, value: Long) = writeTaggedString(tag, value.toString())

        override fun writeTaggedShort(tag: OutputDescriptor, value: Short) = writeTaggedString(tag, value.toString())

        override fun writeTaggedString(tag: OutputDescriptor, value: String) {
            when (tag.kind) {
                OutputKind.Unknown   -> {
                    tag.kind = OutputKind.Attribute; writeTaggedString(tag, value)
                }
                OutputKind.Attribute -> target.doWriteAttribute(tag.name, value)
                OutputKind.Text      -> target.doText(value)
                OutputKind.Element   -> target.doSmartStartTag(tag.name) {
                    text(value)
                }
            }
        }

        open fun XmlWriter.doWriteAttribute(name: QName, value:String) {
            writeAttribute(name, value)
        }

        inline fun XmlWriter.doSmartStartTag(name:QName, body: XmlWriter.()->Unit) {
            doSmartStartTag(name)
            body()
            endTag(name)
        }

        open fun XmlWriter.doText(value: String) = text(value)
        /**
         * Wrapper function that will allow queing events
         */
        open fun XmlWriter.doSmartStartTag(name: QName) = smartStartTag(name)

        fun KSerialClassDesc.getTagName(index: Int): QName {
            getAnnotationsForIndex(index).getXmlSerialName(serialName)?.let { return it }

            val name = getElementName(index)
            val i = name.indexOf(':')
            return when {
                i > 0 -> {
                    val prefix = name.substring(i + 1)
                    val ns = target.getNamespaceUri(prefix) ?: throw IllegalArgumentException(
                        "Missing namespace for prefix $prefix")
                    QName(ns, name.substring(0, i), prefix)
                }
                else  -> QName(serialName?.namespaceURI ?: "", name, serialName?.prefix ?: "")
            }
        }

        private class InvertedWriter(context: SerialContext?,
                                     target: XmlWriter,
                                     serialName: QName,
                                     childName: QName?,
                                     val lastInvertedIndex: Int) : XmlOutput(context, target, serialName, childName) {

            val parentWriter get() = super.target

            override var target: XmlWriter = XmlBufferedWriter()
                private set

            override fun XmlWriter.doWriteAttribute(name: QName, value: String) {
                // Write attributes directly in all cases
                parentWriter.writeAttribute(name, value)
            }

            override fun KSerialClassDesc.getTag(index: Int): OutputDescriptor {
                if (index>lastInvertedIndex) {
                    // If we are done, just flip to using a regular target
                    (target as? XmlBufferedWriter)?.flushTo(parentWriter)
                    target = parentWriter
                }
                // Duplication needed as super access doesn't work
                return OutputDescriptor(this, index, outputKind(index), getTagName(index))
            }

            override fun writeFinished(desc: KSerialClassDesc) {
                // Flush if we somehow haven't flushed yet
                (target as? XmlBufferedWriter)?.flushTo(parentWriter)
                target = parentWriter

                super.writeFinished(desc)
            }
        }

        private class RepeatedWriter(context: SerialContext?,
                                     target: XmlWriter,
                                     serialName: QName,
                                     childName: QName?) : XmlOutput(context, target, serialName, childName) {

            override fun copy(context: SerialContext?,
                              target: XmlWriter,
                              serialName: QName?,
                              childName: QName?): RepeatedWriter {
                return RepeatedWriter(context, target, serialName!!, childName)
            }

            override fun shouldWriteElement(desc: KSerialClassDesc, tag: OutputDescriptor, index: Int): Boolean {
                return index != 0
            }

            override fun KSerialClassDesc.getTag(index: Int): OutputDescriptor {
                val name = childName ?: getAnnotationsForIndex(index).getXmlSerialName(serialName) ?: serialName
                           ?: QName(this.name)

                val specifiedKind = outputKind(index).let { if (it != OutputKind.Text) OutputKind.Element else it }
                return OutputDescriptor(this, index, specifiedKind, name)
            }

            override fun writeFinished(desc: KSerialClassDesc) {
                if (childName != null) {
                    super.writeFinished(desc)
                }
            }
        }

    }
}

private fun defaultSerialContext() = SerialContext().apply {
    registerSerializer(CompactFragment::class, CompactFragmentSerializer())
}

fun Collection<Annotation>.getXmlSerialName(current: QName?): QName? {
    val serialName = firstOrNull<XmlSerialName>()
    return when {
        serialName == null -> null
        serialName.namespace == UNSET_ANNOTATION_VALUE
                           -> if (current == null) {
            QName(serialName.value)
        } else {
            QName(current.namespaceURI, serialName.value, current.prefix)
        }

        serialName.prefix == UNSET_ANNOTATION_VALUE
                           -> QName(serialName.namespace, serialName.value)

        else               -> QName(serialName.namespace, serialName.value, serialName.prefix)
    }
}

fun Collection<Annotation>.getChildName(): QName? {
    val childrenName = firstOrNull<XmlChildrenName>()
    return when {
        childrenName == null -> null
        childrenName.namespace == UNSET_ANNOTATION_VALUE
                             -> QName(childrenName.value)

        childrenName.prefix == UNSET_ANNOTATION_VALUE
                             -> QName(childrenName.namespace, childrenName.value)

        else                 -> QName(childrenName.namespace, childrenName.value, childrenName.prefix)
    }
}

fun <T : Any> KClass<T>.getSerialName(): QName? {
    return annotations.getXmlSerialName(null)
}

fun <T : Any> KClass<T>.getChildName(): QName? {
    return annotations.getChildName()
}

/**
 * Specify more detailed name information than can be provided by [SerialName].
 */
@SerialInfo
@Target(AnnotationTarget.CLASS, AnnotationTarget.PROPERTY)
annotation class XmlSerialName(val value: String,
                               val namespace: String = UNSET_ANNOTATION_VALUE,
                               val prefix: String = UNSET_ANNOTATION_VALUE)

/**
 * Specify additional information about child values. This is only used for primitives, not for classes that have their
 * own independent name
 */
@SerialInfo
@Target(AnnotationTarget.CLASS, AnnotationTarget.PROPERTY)
annotation class XmlChildrenName(val value: String,
                                 val namespace: String = UNSET_ANNOTATION_VALUE,
                                 val prefix: String = UNSET_ANNOTATION_VALUE)

/**
 * Force a property that could be an attribute to be an element
 */
@SerialInfo
@Target(AnnotationTarget.PROPERTY)
annotation class XmlElement(val value: Boolean = true)

/**
 * Force a property to be element content
 */
@SerialInfo
@Target(AnnotationTarget.PROPERTY)
annotation class XmlValue(val value: Boolean = true)

enum class OutputKind { Element, Attribute, Text, Unknown }
data class OutputDescriptor(val desc: KSerialClassDesc, val index: Int, var kind: OutputKind, val name: QName) {
    val childName: QName? by lazy { desc.getAnnotationsForIndex(index).getChildName() }
}

internal const val UNSET_ANNOTATION_VALUE = "ZXCVBNBVCXZ"

private inline fun <reified T> Iterable<*>.firstOrNull(): T? {
    for (e in this) {
        if (e is T) return e
    }
    return null
}


private fun KSerialClassDesc.outputKind(index: Int): OutputKind {
    // lists will always be elements
    getAnnotationsForIndex(index).firstOrNull<XmlChildrenName>()?.let { return OutputKind.Element }
    getAnnotationsForIndex(
        index).firstOrNull<XmlElement>()?.let { return if (it.value) OutputKind.Element else OutputKind.Attribute }
    return OutputKind.Unknown
}

/**
 * Determine the last index that may be an attribute (this is on incomplete information).
 * This will only return a positive value if there is an element before this attribute.
 */
private fun KSerialClassDesc.lastInvertedIndex(): Int {
    var seenElement = false
    var lastAttrIndex = -1
    for (i in 0 until associatedFieldsCount) {
        when (outputKind(i)) {
            OutputKind.Text,
            OutputKind.Element -> seenElement = true
            else               -> if (seenElement) lastAttrIndex = i
        }
    }
    return lastAttrIndex
}