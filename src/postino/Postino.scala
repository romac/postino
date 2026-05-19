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

  def sum[A]: SumCodecBuilder[A] =
    SumCodecBuilder.empty[A]
