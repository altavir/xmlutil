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

import java.io.OutputStream
import java.io.Writer
import java.lang.invoke.MethodHandle
import java.lang.invoke.MethodHandles
import javax.xml.stream.XMLOutputFactory
import javax.xml.stream.XMLStreamException
import javax.xml.stream.XMLStreamWriter
import javax.xml.transform.Result

actual typealias PlatformXmlWriter = StAXWriter

/**
 * An implementation of [XmlWriter] that uses an underlying stax writer.
 * Created by pdvrieze on 16/11/15.
 */
class StAXWriter(val delegate: XMLStreamWriter, val omitXmlDecl: Boolean = false) : XmlWriter {
    override var indent: Int = 0

    var lastTagDepth = -1

    override var depth: Int = 0
        private set

    @Throws(XMLStreamException::class)
    constructor(writer: Writer, repairNamespaces: Boolean, omitXmlDecl: Boolean = false)
        : this(newFactory(repairNamespaces).createXMLStreamWriter(writer), omitXmlDecl)

    @Throws(XMLStreamException::class)
    constructor(outputStream: OutputStream, encoding: String, repairNamespaces: Boolean, omitXmlDecl: Boolean = false)
        : this(
        newFactory(repairNamespaces).createXMLStreamWriter(outputStream, encoding), omitXmlDecl)

    @Throws(XMLStreamException::class)
    constructor(result: Result, repairNamespaces: Boolean, omitXmlDecl: Boolean = false)
        : this(newFactory(repairNamespaces).createXMLStreamWriter(result), omitXmlDecl)

    @Throws(XmlException::class)
    override fun startTag(namespace: String?, localName: String, prefix: String?) {
        writeIndent()
        depth++
        try {
            if (namespace.isNullOrEmpty() && prefix.isNullOrEmpty() && delegate.namespaceContext.getNamespaceURI(
                    "").isNullOrEmpty()) {
                delegate.writeStartElement(localName)
            } else {
                delegate.writeStartElement(prefix ?: XMLConstants.DEFAULT_NS_PREFIX, localName, namespace)
            }
        } catch (e: XMLStreamException) {
            throw XmlException(e)
        }
    }

    @Throws(XmlException::class)
    override fun endTag(namespace: String?, localName: String, prefix: String?) {
        depth--
        writeIndent(TAG_DEPTH_FORCE_INDENT_NEXT)
        delegate.writeEndElement()
    }


    private fun writeIndent(newDepth: Int = depth) {
        if (lastTagDepth >= 0 && indent > 0 && lastTagDepth != depth) {
            delegate.writeCharacters("\n${" ".repeat(indent * depth)}")
        }
        lastTagDepth = newDepth
    }

    @Throws(XmlException::class)
    override fun endDocument() {
        assert(depth == 0) // Don't write this until really the end of the document
        try {
            delegate.writeEndDocument()
        } catch (e: XMLStreamException) {
            throw XmlException(e)
        }

    }

    @Deprecated("", ReplaceWith("endDocument()"))
    @Throws(XmlException::class)
    fun writeEndDocument() {
        endDocument()
    }

    @Throws(XmlException::class)
    override fun close() {
        try {
            delegate.close()
        } catch (e: XMLStreamException) {
            throw XmlException(e)
        }

    }

    @Throws(XmlException::class)
    override fun flush() {
        try {
            delegate.flush()
        } catch (e: XMLStreamException) {
            throw XmlException(e)
        }

    }

    @Throws(XmlException::class)
    override fun attribute(namespace: String?, name: String, prefix: String?, value: String) {
        try {
            if (namespace.isNullOrEmpty() || prefix.isNullOrEmpty()) {
                delegate.writeAttribute(name, value)
            } else {
                delegate.writeAttribute(namespace, name, value)
            }
        } catch (e: XMLStreamException) {
            throw XmlException(e)
        }

    }

    @Deprecated("", ReplaceWith("attribute(null, localName, null, value)"))
    @Throws(XmlException::class)
    fun writeAttribute(localName: String, value: String) {
        attribute(null, localName, null, value)
    }

    @Deprecated("", ReplaceWith("attribute(namespaceURI, localName, prefix, value)"))
    @Throws(XmlException::class)
    fun writeAttribute(prefix: String, namespaceURI: String, localName: String, value: String) {
        attribute(namespaceURI, localName, prefix, value)
    }

    @Deprecated("", ReplaceWith("attribute(namespaceURI, localName, null, value)"))
    @Throws(XmlException::class)
    fun writeAttribute(namespaceURI: String, localName: String, value: String) {
        attribute(namespaceURI, localName, null, value)
    }

    @Throws(XmlException::class)
    override fun namespaceAttr(namespacePrefix: String, namespaceUri: String) = try {
        delegate.writeNamespace(namespacePrefix, namespaceUri)
    } catch (e: XMLStreamException) {
        throw XmlException(e)
    }

    @Throws(XmlException::class)
    override fun comment(text: String) {
        writeIndent(TAG_DEPTH_FORCE_INDENT_NEXT)
        try {
            delegate.writeComment(text)
        } catch (e: XMLStreamException) {
            throw XmlException(e)
        }
    }

    @Deprecated("", ReplaceWith("comment(data)"))
    @Throws(XmlException::class)
    fun writeComment(data: String) {
        comment(data)
    }

    @Throws(XmlException::class)
    override fun processingInstruction(text: String) {
        writeIndent(TAG_DEPTH_FORCE_INDENT_NEXT)
        val split = text.indexOf(' ')
        try {
            if (split > 0) {
                delegate.writeProcessingInstruction(text.substring(0, split), text.substring(split, text.length))
            } else {
                delegate.writeProcessingInstruction(text)
            }
        } catch (e: XMLStreamException) {
            throw XmlException(e)
        }
    }

    @Deprecated("", ReplaceWith("processingInstruction(target)"))
    @Throws(XmlException::class)
    fun writeProcessingInstruction(target: String) {
        processingInstruction(target)
    }

    @Deprecated("", ReplaceWith("processingInstruction(target + \" \" + data)"))
    @Throws(XmlException::class)
    fun writeProcessingInstruction(target: String, data: String) {
        processingInstruction("$target $data")
    }

    @Throws(XmlException::class)
    override fun cdsect(text: String) {
        try {
            delegate.writeCData(text)
        } catch (e: XMLStreamException) {
            throw XmlException(e)
        }
        lastTagDepth = TAG_DEPTH_NOT_TAG
    }

    @Deprecated("", ReplaceWith("cdsect(data)"))
    @Throws(XmlException::class)
    fun writeCData(data: String) {
        cdsect(data)
    }

    @Throws(XmlException::class)
    override fun docdecl(text: String) {
        writeIndent(TAG_DEPTH_FORCE_INDENT_NEXT)
        try {
            delegate.writeDTD(text)
        } catch (e: XMLStreamException) {
            throw XmlException(e)
        }
    }

    @Deprecated("", ReplaceWith("docdecl(dtd)"))
    @Throws(XmlException::class)
    fun writeDTD(dtd: String) {
        docdecl(dtd)
    }

    @Throws(XmlException::class)
    override fun entityRef(text: String) {
        try {
            delegate.writeEntityRef(text)
        } catch (e: XMLStreamException) {
            throw XmlException(e)
        }
        lastTagDepth = TAG_DEPTH_NOT_TAG
    }

    @Deprecated("", ReplaceWith("entityRef(name)"))
    @Throws(XmlException::class)
    fun writeEntityRef(name: String) {
        entityRef(name)
    }

    @Throws(XmlException::class)
    override fun startDocument(version: String?, encoding: String?, standalone: Boolean?) {
        if (!omitXmlDecl) {
            writeIndent(TAG_DEPTH_FORCE_INDENT_NEXT) // should be null as length is 0
            if (standalone != null && mtdWriteStartDocument != null && clsXmlStreamWriter?.isInstance(
                    delegate) == true) {
                mtdWriteStartDocument.invoke(delegate, version, encoding, standalone)
            } else {
                delegate.writeStartDocument(encoding, version) // standalone doesn't work
            }
        }

    }

    @Throws(XmlException::class)
    override fun ignorableWhitespace(text: String) {
        text(text)
    }

    @Throws(XmlException::class)
    override fun text(text: String) {
        try {
            delegate.writeCharacters(text)
        } catch (e: XMLStreamException) {
            throw XmlException(e)
        }
        lastTagDepth = TAG_DEPTH_NOT_TAG
    }

    @Throws(XmlException::class)
    override fun getPrefix(namespaceUri: String?): String? {
        try {
            return delegate.getPrefix(namespaceUri)
        } catch (e: XMLStreamException) {
            throw XmlException(e)
        }

    }

    @Throws(XmlException::class)
    override fun setPrefix(prefix: String, namespaceUri: String) {
        try {
            delegate.setPrefix(prefix, namespaceUri)
        } catch (e: XMLStreamException) {
            throw XmlException(e)
        }

    }

    @Throws(XmlException::class)
    override fun getNamespaceUri(prefix: String): String? {
        return delegate.namespaceContext.getNamespaceURI(prefix)
    }

    override var namespaceContext: NamespaceContext
        get() = delegate.namespaceContext
        @Throws(XmlException::class)
        set(context) = if (depth == 0) {
            try {
                delegate.namespaceContext = context
            } catch (e: XMLStreamException) {
                throw XmlException(e)
            }

        } else {
            throw XmlException("Modifying the namespace context halfway in a document")
        }

    companion object {

        const val TAG_DEPTH_NOT_TAG = -1
        const val TAG_DEPTH_FORCE_INDENT_NEXT = Int.MAX_VALUE

        private val clsXmlStreamWriter: Class<out XMLStreamWriter>?
        private val mtdWriteStartDocument: MethodHandle?

        init {
            var mh: MethodHandle? = null
            val clazz = try {
                Class.forName("org.codehaus.stax2.XMLStreamWriter").apply {
                    val m = getMethod("writeStartDocument", String::class.java, String::class.java, Boolean::class.java)
                    mh = MethodHandles.lookup().unreflect(m)
                }
            } catch (e: ClassNotFoundException) {
                null
            } catch (e: NoSuchMethodException) {
                null
            }

            //noinspection unchecked
            clsXmlStreamWriter = clazz?.asSubclass(XMLStreamWriter::class.java)
            mtdWriteStartDocument = mh
        }

        private fun newFactory(repairNamespaces: Boolean): XMLOutputFactory {
            val xmlOutputFactory = XMLOutputFactory.newFactory()
            xmlOutputFactory.setProperty(XMLOutputFactory.IS_REPAIRING_NAMESPACES, repairNamespaces)
            return xmlOutputFactory
        }

    }
}
