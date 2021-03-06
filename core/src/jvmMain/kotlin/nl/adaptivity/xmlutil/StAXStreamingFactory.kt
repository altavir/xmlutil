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

import java.io.InputStream
import java.io.OutputStream
import java.io.Reader
import java.io.Writer
import javax.xml.stream.XMLStreamException
import javax.xml.transform.Result
import javax.xml.transform.Source

class StAXStreamingFactory : XmlStreamingFactory {

  @Throws(XmlException::class)
  override fun newWriter(writer: Writer, repairNamespaces: Boolean, omitXmlDecl: Boolean): XmlWriter {
    try {
      return StAXWriter(writer, repairNamespaces, omitXmlDecl)
    } catch (e: XMLStreamException) {
      throw XmlException(e)
    }
  }

  @Throws(XmlException::class)
  override fun newWriter(outputStream: OutputStream, encoding: String, repairNamespaces: Boolean, omitXmlDecl: Boolean): XmlWriter {
    try {
      return StAXWriter(outputStream, encoding, repairNamespaces, omitXmlDecl)
    } catch (e: XMLStreamException) {
      throw XmlException(e)
    }
  }

  @Throws(XmlException::class)
  override fun newWriter(result: Result, repairNamespaces: Boolean, omitXmlDecl: Boolean): XmlWriter {
    try {
      return StAXWriter(result, repairNamespaces, omitXmlDecl)
    } catch (e: XMLStreamException) {
      throw XmlException(e)
    }
  }

  @Throws(XmlException::class)
  override fun newReader(reader: Reader): XmlReader {
    try {
      return StAXReader(reader)
    } catch (e: XMLStreamException) {
      throw XmlException(e)
    }
  }

  @Throws(XmlException::class)
  override fun newReader(inputStream: InputStream, encoding: String): XmlReader {
    try {
      return StAXReader(inputStream, encoding)
    } catch (e: XMLStreamException) {
      throw XmlException(e)
    }
  }

  @Throws(XmlException::class)
  override fun newReader(source: Source): XmlReader {
    try {
      return StAXReader(source)
    } catch (e: XMLStreamException) {
      throw XmlException(e)
    }
  }

}