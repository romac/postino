package postino

object Postino:
  def encode[A](value: A)(using encoder: Encoder[A]): Either[PostinoError, Array[Byte]] =
    val out = Writer.empty
    encoder.encode(value, out).map(_ => out.toByteArray)

  def decode[A](bytes: Array[Byte])(using decoder: Decoder[A]): Either[PostinoError, A] =
    decode(bytes, DecodeOptions.default)

  def decode[A](
      bytes: Array[Byte],
      decodeOptions: DecodeOptions
  )(using decoder: Decoder[A]): Either[PostinoError, A] =
    val in = Reader.from(bytes, decodeOptions)
    decoder
      .decode(in)
      .flatMap: value =>
        if in.remaining == 0 then Right(value)
        else Left(PostinoError.TrailingBytes(in.remaining, in.position))

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

  private def appendCrc(payload: Array[Byte], crc: Crc): Array[Byte] =
    payload ++ crc.checksum(payload)

  private def decodeCrcPayload[A](
      bytes: Array[Byte],
      decodeOptions: DecodeOptions,
      crc: Crc
  )(using decoder: Decoder[A]): Either[PostinoError, A] =
    splitCrc(bytes, crc).flatMap(decode(_, decodeOptions))

  private def splitCrc(bytes: Array[Byte], crc: Crc): Either[PostinoError, Array[Byte]] =
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
