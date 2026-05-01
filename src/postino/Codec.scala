package postino

import scala.annotation.tailrec
import scala.compiletime.{erasedValue, error, summonInline}
import scala.deriving.Mirror
import scala.reflect.ClassTag
import scala.util.control.NonFatal

trait Encoder[-A]:
  def encode(value: A, out: Writer): Either[PostinoError, Unit]

trait Decoder[+A]:
  def decode(in: Reader): Either[PostinoError, A]

trait Codec[A] extends Encoder[A] with Decoder[A]

object Encoder:
  given fromCodec[A](using codec: Codec[A]): Encoder[A] =
    codec

object Decoder:
  given fromCodec[A](using codec: Codec[A]): Decoder[A] =
    codec

object Codec extends LowPriorityCodecs:
  export PrimitiveCodecs.given

  def apply[A](using codec: Codec[A]): Codec[A] =
    codec

  inline def derived[A](using mirror: Mirror.Of[A]): Codec[A] =
    inline mirror match
      case product: Mirror.ProductOf[A] => ProductCodecs.derivedProduct[A](product)
      case _: Mirror.SumOf[A] =>
        error("Postino sum codecs must be explicit. Use Postino.sum[A].variant(...).build.")

private[postino] object PrimitiveCodecs:
  given Codec[Unit] with
    def encode(value: Unit, out: Writer): Either[PostinoError, Unit] =
      Right(())

    def decode(in: Reader): Either[PostinoError, Unit] =
      Right(())

  given Codec[Boolean] with
    def encode(value: Boolean, out: Writer): Either[PostinoError, Unit] =
      out.writeUnsignedByte(if value then 1 else 0)

    def decode(in: Reader): Either[PostinoError, Boolean] =
      in.readUnsignedByte()
        .flatMap:
          case 0     => Right(false)
          case 1     => Right(true)
          case other => Left(PostinoError.InvalidBoolean(other))

  given Codec[Byte] with
    def encode(value: Byte, out: Writer): Either[PostinoError, Unit] =
      out.writeByte(value)

    def decode(in: Reader): Either[PostinoError, Byte] =
      in.readByte()

  given Codec[Short] with
    def encode(value: Short, out: Writer): Either[PostinoError, Unit] =
      for
        unsigned <- Varint.zigZagEncode(BigInt(value), 16, "i16")
        _        <- Varint.writeUnsigned(unsigned, 16, "u16", out)
      yield ()

    def decode(in: Reader): Either[PostinoError, Short] =
      for
        unsigned <- Varint.readUnsigned(in, 16, "u16")
        signed   <- Varint.zigZagDecode(unsigned, 16, "i16")
      yield signed.toShort

  given Codec[Int] with
    def encode(value: Int, out: Writer): Either[PostinoError, Unit] =
      for
        unsigned <- Varint.zigZagEncode(BigInt(value), 32, "i32")
        _        <- Varint.writeUnsigned(unsigned, 32, "u32", out)
      yield ()

    def decode(in: Reader): Either[PostinoError, Int] =
      for
        unsigned <- Varint.readUnsigned(in, 32, "u32")
        signed   <- Varint.zigZagDecode(unsigned, 32, "i32")
      yield signed.toInt

  given Codec[Long] with
    def encode(value: Long, out: Writer): Either[PostinoError, Unit] =
      for
        unsigned <- Varint.zigZagEncode(BigInt(value), 64, "i64")
        _        <- Varint.writeUnsigned(unsigned, 64, "u64", out)
      yield ()

    def decode(in: Reader): Either[PostinoError, Long] =
      for
        unsigned <- Varint.readUnsigned(in, 64, "u64")
        signed   <- Varint.zigZagDecode(unsigned, 64, "i64")
      yield signed.toLong

  given Codec[Float] with
    def encode(value: Float, out: Writer): Either[PostinoError, Unit] =
      val bits = java.lang.Float.floatToRawIntBits(value)
      val bytes = Array(
        bits.toByte,
        (bits >>> 8).toByte,
        (bits >>> 16).toByte,
        (bits >>> 24).toByte
      )
      out.writeBytes(bytes)

    def decode(in: Reader): Either[PostinoError, Float] =
      in.readBytes(4)
        .map: bytes =>
          val bits =
            (bytes(0) & 0xff) |
              ((bytes(1) & 0xff) << 8) |
              ((bytes(2) & 0xff) << 16) |
              ((bytes(3) & 0xff) << 24)
          java.lang.Float.intBitsToFloat(bits)

  given Codec[Double] with
    def encode(value: Double, out: Writer): Either[PostinoError, Unit] =
      val bits  = java.lang.Double.doubleToRawLongBits(value)
      val bytes = Array.tabulate(8)(index => (bits >>> (index * 8)).toByte)
      out.writeBytes(bytes)

    def decode(in: Reader): Either[PostinoError, Double] =
      in.readBytes(8)
        .map: bytes =>
          var bits  = 0L
          var index = 0
          while index < 8 do
            bits |= (bytes(index).toLong & 0xffL) << (index * 8)
            index += 1
          java.lang.Double.longBitsToDouble(bits)

  given Codec[String] with
    def encode(value: String, out: Writer): Either[PostinoError, Unit] =
      val bytes = value.getBytes(java.nio.charset.StandardCharsets.UTF_8)
      for
        _ <- writeLength(bytes.length, out)
        _ <- out.writeBytes(bytes)
      yield ()

    def decode(in: Reader): Either[PostinoError, String] =
      readLength(in).flatMap: length =>
        in.readBytes(length)
          .flatMap: bytes =>
            val decoder =
              java.nio.charset.StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
            try Right(decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString)
            catch case NonFatal(error) => Left(PostinoError.InvalidUtf8(error.getMessage))

  given Codec[Array[Byte]] with
    def encode(value: Array[Byte], out: Writer): Either[PostinoError, Unit] =
      for
        _ <- writeLength(value.length, out)
        _ <- out.writeBytes(value)
      yield ()

    def decode(in: Reader): Either[PostinoError, Array[Byte]] =
      readLength(in).flatMap(in.readBytes)

  given Codec[U16] with
    def encode(value: U16, out: Writer): Either[PostinoError, Unit] =
      Varint.writeUnsigned(BigInt(value.toInt), 16, "u16", out)

    def decode(in: Reader): Either[PostinoError, U16] =
      Varint.readUnsigned(in, 16, "u16").flatMap(value => U16.fromInt(value.toInt))

  given Codec[U32] with
    def encode(value: U32, out: Writer): Either[PostinoError, Unit] =
      Varint.writeUnsigned(BigInt(value.toLong), 32, "u32", out)

    def decode(in: Reader): Either[PostinoError, U32] =
      Varint.readUnsigned(in, 32, "u32").flatMap(value => U32.fromLong(value.toLong))

  given Codec[U64] with
    def encode(value: U64, out: Writer): Either[PostinoError, Unit] =
      Varint.writeUnsigned(value.toBigInt, 64, "u64", out)

    def decode(in: Reader): Either[PostinoError, U64] =
      Varint.readUnsigned(in, 64, "u64").flatMap(U64.fromBigInt)

  private[postino] def writeLength(length: Int, out: Writer): Either[PostinoError, Unit] =
    Varint.writeUnsigned(BigInt(length), 64, "usize", out)

  private[postino] def readLength(in: Reader): Either[PostinoError, Int] =
    Varint
      .readUnsigned(in, 64, "usize")
      .flatMap: value =>
        if value <= Int.MaxValue then Right(value.toInt)
        else Left(PostinoError.LengthTooLarge(value))

trait LowPriorityCodecs:
  given optionCodec[A](using valueCodec: Codec[A]): Codec[Option[A]] with
    def encode(value: Option[A], out: Writer): Either[PostinoError, Unit] =
      value match
        case None => out.writeUnsignedByte(0)
        case Some(inner) =>
          for
            _ <- out.writeUnsignedByte(1)
            _ <- valueCodec.encode(inner, out)
          yield ()

    def decode(in: Reader): Either[PostinoError, Option[A]] =
      in.readUnsignedByte()
        .flatMap:
          case 0     => Right(None)
          case 1     => valueCodec.decode(in).map(Some(_))
          case other => Left(PostinoError.InvalidOptionTag(other))

  given listCodec[A](using valueCodec: Codec[A]): Codec[List[A]] with
    def encode(value: List[A], out: Writer): Either[PostinoError, Unit] =
      encodeIterable(value, valueCodec, out)

    def decode(in: Reader): Either[PostinoError, List[A]] =
      decodeIndexed(in, valueCodec).map(_.toList)

  given vectorCodec[A](using valueCodec: Codec[A]): Codec[Vector[A]] with
    def encode(value: Vector[A], out: Writer): Either[PostinoError, Unit] =
      encodeIterable(value, valueCodec, out)

    def decode(in: Reader): Either[PostinoError, Vector[A]] =
      decodeIndexed(in, valueCodec).map(_.toVector)

  given arrayCodec[A](using valueCodec: Codec[A], classTag: ClassTag[A]): Codec[Array[A]] with
    def encode(value: Array[A], out: Writer): Either[PostinoError, Unit] =
      encodeIterable(value, valueCodec, out)

    def decode(in: Reader): Either[PostinoError, Array[A]] =
      decodeIndexed(in, valueCodec).map(_.toArray)

  private def encodeIterable[A](
      values: IterableOnce[A],
      valueCodec: Codec[A],
      out: Writer
  ): Either[PostinoError, Unit] =
    val indexed = values.iterator.toIndexedSeq

    PrimitiveCodecs
      .writeLength(indexed.length, out)
      .flatMap: _ =>
        encodeAll(indexed.iterator, valueCodec, out)

  @tailrec
  private def encodeAll[A](
      values: Iterator[A],
      valueCodec: Codec[A],
      out: Writer
  ): Either[PostinoError, Unit] =
    if values.hasNext then
      valueCodec.encode(values.next(), out) match
        case Right(())   => encodeAll(values, valueCodec, out)
        case Left(error) => Left(error)
    else Right(())

  private def decodeIndexed[A](
      in: Reader,
      valueCodec: Codec[A]
  ): Either[PostinoError, IndexedSeq[A]] =
    PrimitiveCodecs
      .readLength(in)
      .flatMap: length =>
        val builder                     = IndexedSeq.newBuilder[A]
        var index                       = 0
        var error: Option[PostinoError] = None

        while index < length && error.isEmpty do
          valueCodec.decode(in) match
            case Right(value) => builder += value
            case Left(cause)  => error = Some(cause)
          index += 1

        error match
          case Some(cause) => Left(cause)
          case None        => Right(builder.result())

private[postino] object ProductCodecs:
  inline def derivedProduct[A](mirror: Mirror.ProductOf[A]): Codec[A] =
    val fieldCodecs = summonCodecs[mirror.MirroredElemTypes]
    productCodec(mirror, fieldCodecs)

  private def productCodec[A](
      mirror: Mirror.ProductOf[A],
      fieldCodecs: List[Codec[Any]]
  ): Codec[A] =
    new Codec[A]:
      def encode(value: A, out: Writer): Either[PostinoError, Unit] =
        encodeFields(value.asInstanceOf[Product], fieldCodecs, out)

      def decode(in: Reader): Either[PostinoError, A] =
        decodeFields(fieldCodecs, in).map: values =>
          mirror.fromProduct(Tuple.fromArray(values.toArray))

  private inline def summonCodecs[Fields <: Tuple]: List[Codec[Any]] =
    inline erasedValue[Fields] match
      case _: EmptyTuple => Nil
      case _: (field *: rest) =>
        summonInline[Codec[field]].asInstanceOf[Codec[Any]] :: summonCodecs[rest]

  private def encodeFields(
      product: Product,
      fieldCodecs: List[Codec[Any]],
      out: Writer
  ): Either[PostinoError, Unit] =
    var index = 0
    while index < fieldCodecs.length do
      fieldCodecs(index).encode(product.productElement(index), out) match
        case Right(())   => index += 1
        case Left(error) => return Left(error)
    Right(())

  private def decodeFields(
      fieldCodecs: List[Codec[Any]],
      in: Reader
  ): Either[PostinoError, List[Any]] =
    val builder   = List.newBuilder[Any]
    var remaining = fieldCodecs
    while remaining.nonEmpty do
      remaining.head.decode(in) match
        case Right(value) => builder += value
        case Left(error)  => return Left(error)
      remaining = remaining.tail
    Right(builder.result())
