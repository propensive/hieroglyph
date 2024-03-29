/*
    Hieroglyph, version [unreleased]. Copyright 2024 Jon Pretty, Propensive OÜ.

    The primary distribution site is: https://propensive.com/

    Licensed under the Apache License, Version 2.0 (the "License"); you may not use this
    file except in compliance with the License. You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing, software distributed under the
    License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
    either express or implied. See the License for the specific language governing permissions
    and limitations under the License.
*/

package hieroglyph

import vacuous.*
import rudiments.*
import fulminate.*
import contingency.*
import anticipation.*

import scala.collection.mutable as scm
import scala.jdk.CollectionConverters.SetHasAsScala
import scala.quoted.*

import java.nio as jn, jn.charset as jnc

import language.experimental.captureChecking

object Encoding:
  given Textualizer[Encoding] = _.name
  given Communicable[Encoding] = encoding => Message(encoding.name)

  private val allCharsets: Set[jnc.Charset] =
    jnc.Charset.availableCharsets.nn.asScala.to(Map).values.to(Set)

  private[hieroglyph] val codecs: Map[Text, Encoding { type CanEncode = true }] =
    allCharsets.filter(_.canEncode).flatMap: charset =>
      (charset.aliases.nn.asScala.to(Set) + charset.displayName.nn).map: name =>
        name.toLowerCase.nn.tt -> Encoding(name.tt, true)
    .to(Map)
  
  private[hieroglyph] val decodeOnly: Map[Text, Encoding { type CanEncode = false }] =
    allCharsets.filter(!_.canEncode).flatMap: charset =>
      (charset.aliases.nn.asScala.to(Set) + charset.displayName.nn).map: name =>
        name.toLowerCase.nn.tt -> Encoding(name.tt, false)
    .to(Map)
  
  def unapply(name: Text): Option[Encoding] =
    codecs.get(name.s.toLowerCase.nn.tt).orElse(decodeOnly.get(name.s.toLowerCase.nn.tt))

  def apply(name: Text, canEncode: Boolean): Encoding { type CanEncode = canEncode.type } =
    new Encoding(name) { type CanEncode = canEncode.type }

class Encoding(initName: Text):
  def name: Text = charset.displayName.nn.tt
  type CanEncode <: Boolean
  def decoder(using BadEncodingHandler): CharDecoder = CharDecoder(this)
  lazy val charset: jnc.Charset = jnc.Charset.forName(initName.s).nn

  override def toString: String = s"enc\"${charset.displayName}\""

extension (encoding: Encoding { type CanEncode = true }) def encoder: CharEncoder =
  CharEncoder(encoding)

object CharDecoder:
  given default(using Quickstart): CharDecoder = charDecoders.utf8

  def system(using BadEncodingHandler): CharDecoder =
    unapply(jnc.Charset.defaultCharset.nn.displayName.nn.tt).get

  def unapply(name: Text)(using BadEncodingHandler): Option[CharDecoder] =
    Encoding.unapply(name).map(CharDecoder(_))

object CharEncoder:
  
  given default(using Quickstart): CharEncoder = charEncoders.utf8
  
  def system: CharEncoder = unapply(jnc.Charset.defaultCharset.nn.displayName.nn.tt).get
  
  def unapply(name: Text): Option[CharEncoder] =
    Encoding.codecs.get(name.s.toLowerCase.nn.tt).map(CharEncoder(_))

class CharEncoder(val encoding: Encoding { type CanEncode = true }):
  def encode(text: Text): Bytes = text.s.getBytes(encoding.name.s).nn.immutable(using Unsafe)
  def encode(stream: LazyList[Text]): LazyList[Bytes] = stream.map(encode)

class SafeCharDecoder(safeEncoding: Encoding)
extends CharDecoder(safeEncoding)(using badEncodingHandlers.skip)

class CharDecoder(val encoding: Encoding)(using handler: BadEncodingHandler):
  def decode(bytes: Bytes): Text =
    val buf: StringBuilder = StringBuilder()
    decode(LazyList(bytes)).each { text => buf.append(text.s) }
    buf.toString.tt
  
  def decode(stream: LazyList[Bytes]): LazyList[Text] =
    val decoder = encoding.charset.newDecoder().nn
    val out = jn.CharBuffer.allocate(4096).nn
    val in = jn.ByteBuffer.allocate(4096).nn

    def recur(todo: LazyList[Array[Byte]], offset: Int = 0, total: Int = 0): LazyList[Text] =
      val count = in.remaining

      if !todo.isEmpty then in.put(todo.head, offset, in.remaining.min(todo.head.length - offset))
      in.flip()
      
      def decode(): jnc.CoderResult =
        val result = decoder.decode(in, out, todo.isEmpty).nn
        
        if !result.isMalformed then result else
          handler.handle(total + in.position, encoding).let(out.put(_))
          in.position(in.position + result.length)
          decode()
      
      val status = decode()
      val text = out.flip().nn.toString.tt
      in.compact()
      out.clear()
      
      def continue =
        if todo.isEmpty && !status.isOverflow then LazyList()
        else if !todo.isEmpty && count >= todo.head.length - offset
        then recur(todo.tail, 0, total + todo.head.length - offset)
        else recur(todo, offset + count, total + count)
      
      if text.s.isEmpty then continue else text #:: continue
    
    recur(stream.map(_.mutable(using Unsafe)))

package charDecoders:
  given utf8(using BadEncodingHandler): CharDecoder = CharDecoder.unapply("UTF-8".tt).get
  given utf16(using BadEncodingHandler): CharDecoder = CharDecoder.unapply("UTF-16".tt).get
  given utf16Le(using BadEncodingHandler): CharDecoder = CharDecoder.unapply("UTF-16LE".tt).get
  given utf16Be(using BadEncodingHandler): CharDecoder = CharDecoder.unapply("UTF-16BE".tt).get
  given ascii(using BadEncodingHandler): CharDecoder = CharDecoder.unapply("ASCII".tt).get
  //given iso88591: CharDecoder = SafeCharDecoder(Encoding.all("ISO-8859-1".tt).get

package charEncoders:
  given utf8: CharEncoder = CharEncoder.unapply("UTF-8".tt).get
  given utf16: CharEncoder = CharEncoder.unapply("UTF-16".tt).get
  given utf16Le: CharEncoder = CharEncoder.unapply("UTF-16LE".tt).get
  given utf16Be: CharEncoder = CharEncoder.unapply("UTF-16BE".tt).get
  given ascii: CharEncoder = CharEncoder.unapply("ASCII".tt).get
  //given iso88591: CharEncoder = CharEncoder.unapply("ISO-8859-1".tt).get

object BadEncodingHandler:
  given default(using Quickstart): BadEncodingHandler = badEncodingHandlers.substitute

trait BadEncodingHandler:
  def handle(pos: Int, encoding: Encoding): Optional[Char]
  def complete(): Unit

package badEncodingHandlers:
  given strict(using badEncoding: Raises[UndecodableCharError]): BadEncodingHandler^{badEncoding} =
    new BadEncodingHandler:
      def handle(pos: Int, encoding: Encoding): Char = raise(UndecodableCharError(pos, encoding))('?')
      def complete(): Unit = ()
  
  given skip: BadEncodingHandler with
    def handle(pos: Int, encoding: Encoding): Optional[Char] = Unset
    def complete(): Unit = ()
  
  given substitute: BadEncodingHandler with
    def handle(pos: Int, encoding: Encoding): Optional[Char] = '?'
    def complete(): Unit = ()
  
  given collect(using aggregate: Raises[AggregateError[UndecodableCharError]]): BadEncodingHandler^{aggregate} =
    new BadEncodingHandler:
      private val mistakes: scm.ArrayBuffer[UndecodableCharError] = scm.ArrayBuffer()
      def handle(pos: Int, encoding: Encoding): Optional[Char] = Unset
      def complete(): Unit = if !mistakes.isEmpty then raise(AggregateError(mistakes.to(List)))(())

case class UndecodableCharError(pos: Int, encoding: Encoding)
extends Error(msg"The byte sequence at position $pos could not be decoded with the encoding $encoding")

case class UnencodableCharError(char: Char, encoding: Encoding)
extends Error(msg"The character '$char' cannot be encoded with the encoding $encoding")

extension (inline context: StringContext)
  transparent inline def enc(): Encoding = ${Hieroglyph.encoding('context)}

object Hieroglyph:
  given Realm = realm"hieroglyph"
  opaque type CharRange = Long
  
  object CharRange:
    def apply(from: Int, to: Int): CharRange = (from.toLong << 32) + to.toLong
    def apply(char: Char): CharRange = (char.toLong << 32) + char.toInt
    def apply(char: Int): CharRange = (char.toLong << 32) + char

    given show: Textualizer[CharRange] = range => "${range.from}..${range.to}".tt

  given Ordering[CharRange] = Ordering.Long

  extension (range: CharRange)
    def from: Int = (range >> 32).toInt
    def to: Int = range.toInt
    def contains(char: Char): Boolean = char.toInt >= from && char.toInt <= to

  def encoding(contextExpr: Expr[StringContext])(using Quotes): Expr[Encoding] =
    import quotes.reflect.*

    val context: StringContext = contextExpr.valueOrAbort
    Encoding.unapply(context.parts.head.tt) match
      case None =>
        fail(msg"the encoding ${context.parts.head.tt} was not available")
      
      case Some(encoding) =>
        if !encoding.charset.isRegistered
        then report.warning(
          s"hieroglyph: the encoding ${encoding.charset.displayName} is not an IANA-registered "+
              "encoding, and may not be universally available")
        
        val name = context.parts.head.toLowerCase.nn
        if encoding.charset.canEncode then '{Encoding.codecs(${Expr(name)}.tt)}
        else '{Encoding.decodeOnly(${Expr(name)}.tt)}
