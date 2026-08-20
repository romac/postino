package postino

import java.io.{InputStream, OutputStream}
import scala.deriving.Mirror

object Postino:
  def encode[A](value: A)(using encoder: Encoder[A]): Either[PostinoError, Array[Byte]] =
    val out = Writer.empty
    encoder.encode(value, out).map(_ => out.toByteArray)

  def encodeTo[A](value: A, sink: Sink)(using encoder: Encoder[A]): Either[PostinoError, Unit] =
    encoder.encode(value, Writer.to(sink))

  def encodeTo[A](value: A, output: OutputStream)(using
      encoder: Encoder[A]
  ): Either[PostinoError, Unit] =
    encodeTo(value, Sink.outputStream(output))

  def decode[A](bytes: Array[Byte])(using decoder: Decoder[A]): Either[PostinoError, A] =
    decode(bytes, DecodeOptions.default)

  def decode[A](
      bytes: Array[Byte],
      decodeOptions: DecodeOptions
  )(using decoder: Decoder[A]): Either[PostinoError, A] =
    val in = Reader.from(bytes, decodeOptions)
    decodeFrom(in)

  def decodeFrom[A](source: Source)(using decoder: Decoder[A]): Either[PostinoError, A] =
    decodeFrom(source, DecodeOptions.default)

  def decodeFrom[A](
      source: Source,
      decodeOptions: DecodeOptions
  )(using decoder: Decoder[A]): Either[PostinoError, A] =
    decodeFrom(Reader.from(source, decodeOptions))

  def decodeFrom[A](input: InputStream)(using decoder: Decoder[A]): Either[PostinoError, A] =
    decodeFrom(input, DecodeOptions.default)

  def decodeFrom[A](
      input: InputStream,
      decodeOptions: DecodeOptions
  )(using decoder: Decoder[A]): Either[PostinoError, A] =
    decodeFrom(Reader.from(input, decodeOptions))

  private def decodeFrom[A](in: Reader)(using decoder: Decoder[A]): Either[PostinoError, A] =
    decoder
      .decode(in)
      .flatMap: value =>
        in.finish().map(_ => value)

  private[postino] def decodePrefix[A](
      bytes: Array[Byte],
      decodeOptions: DecodeOptions
  )(using decoder: Decoder[A]): Either[PostinoError, (A, Int)] =
    val in = Reader.from(bytes, decodeOptions)
    decoder.decode(in).map(value => value -> in.position)

  def encodeCobs[A](value: A)(using encoder: Encoder[A]): Either[PostinoError, Array[Byte]] =
    encode(value).flatMap(Cobs.encode)

  def decodeCobs[A](bytes: Array[Byte])(using decoder: Decoder[A]): Either[PostinoError, A] =
    decodeCobs(bytes, DecodeOptions.default)

  def decodeCobs[A](
      bytes: Array[Byte],
      decodeOptions: DecodeOptions
  )(using decoder: Decoder[A]): Either[PostinoError, A] =
    Cobs.decode(bytes).flatMap(decode(_, decodeOptions))

  def encodeCrc[A](
      value: A
  )(using encoder: Encoder[A], crc: Crc): Either[PostinoError, Array[Byte]] =
    encode(value).map(appendCrc(_, crc))

  def encodeCrc[A](crc: Crc, value: A)(using
      encoder: Encoder[A]
  ): Either[PostinoError, Array[Byte]] =
    encode(value).map(appendCrc(_, crc))

  def decodeCrc[A](
      bytes: Array[Byte]
  )(using decoder: Decoder[A], crc: Crc): Either[PostinoError, A] =
    decodeCrcPayload(bytes, DecodeOptions.default, crc)

  def decodeCrc[A](
      bytes: Array[Byte],
      decodeOptions: DecodeOptions
  )(using decoder: Decoder[A], crc: Crc): Either[PostinoError, A] =
    decodeCrcPayload(bytes, decodeOptions, crc)

  def decodeCrc[A](crc: Crc, bytes: Array[Byte])(using
      decoder: Decoder[A]
  ): Either[PostinoError, A] =
    decodeCrc(crc, bytes, DecodeOptions.default)

  def decodeCrc[A](
      crc: Crc,
      bytes: Array[Byte],
      decodeOptions: DecodeOptions
  )(using decoder: Decoder[A]): Either[PostinoError, A] =
    decodeCrcPayload(bytes, decodeOptions, crc)

  def sum[A]: SumCodecBuilder[A] =
    SumCodecBuilder.empty[A]

  def exhaustiveSum[A](using
      mirror: Mirror.SumOf[A]
  ): ExhaustiveSumCodecBuilder[A, mirror.MirroredElemTypes] =
    ExhaustiveSumCodecBuilder.empty[A, mirror.MirroredElemTypes]

  private def appendCrc(payload: Array[Byte], crc: Crc): Array[Byte] =
    payload ++ crc.checksum(payload)

  private def decodeCrcPayload[A](
      bytes: Array[Byte],
      decodeOptions: DecodeOptions,
      crc: Crc
  )(using decoder: Decoder[A]): Either[PostinoError, A] =
    splitCrc(bytes, crc).flatMap(decode(_, decodeOptions))

  private[postino] def splitCrc(
      bytes: Array[Byte],
      crc: Crc
  ): Either[PostinoError, Array[Byte]] =
    val checksumLength = crc.widthBytes
    if bytes.length < checksumLength then
      Left(PostinoError.CrcPayloadTooShort(bytes.length, checksumLength))
    else
      val payload  = java.util.Arrays.copyOfRange(bytes, 0, bytes.length - checksumLength)
      val expected = crc.checksum(payload)
      val actual = java.util.Arrays.copyOfRange(bytes, bytes.length - checksumLength, bytes.length)

      if java.util.Arrays.equals(expected, actual) then Right(payload)
      else Left(PostinoError.CrcMismatch(unsigned(expected), unsigned(actual)))

  private def unsigned(bytes: Array[Byte]): Vector[Int] =
    bytes.toVector.map(_ & 0xff)
