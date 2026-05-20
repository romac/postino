package postino

final class Reader private (
    private val source: Source,
    val decodeOptions: DecodeOptions,
    private var remainingCollectionElements: Long
):
  def position: Int =
    math.min(source.position, Int.MaxValue.toLong).toInt

  def remaining: Int =
    source.remaining.getOrElse(0)

  def readByte(): Either[PostinoError, Byte] =
    source.readByte()

  def readUnsignedByte(): Either[PostinoError, Int] =
    readByte().map(_ & 0xff)

  def readBytes(length: Int): Either[PostinoError, Array[Byte]] =
    if length < 0 then Left(PostinoError.NegativeLength(length))
    else if length > decodeOptions.maxByteLength then
      Left(PostinoError.ByteLengthTooLarge(length, decodeOptions.maxByteLength))
    else source.readBytes(length)

  private[postino] def finish(): Either[PostinoError, Unit] =
    source.remaining match
      case Some(0) => Right(())
      case Some(count) =>
        Left(PostinoError.TrailingBytes(count, position))
      case None =>
        source.atEnd.flatMap: atEnd =>
          if atEnd then Right(())
          else Left(PostinoError.TrailingBytes(1, position))

  private[postino] def reserveCollection(
      length: Int,
      elementCount: Long
  ): Either[PostinoError, Unit] =
    if length > decodeOptions.maxCollectionLength then
      Left(PostinoError.CollectionLengthTooLarge(length, decodeOptions.maxCollectionLength))
    else if elementCount > remainingCollectionElements then
      Left(
        PostinoError.CollectionElementLimitExceeded(
          elementCount,
          remainingCollectionElements,
          decodeOptions.maxCollectionElements
        )
      )
    else
      remainingCollectionElements -= elementCount
      Right(())

object Reader:
  def from(bytes: Array[Byte]): Reader =
    from(bytes, DecodeOptions.default)

  def from(bytes: Array[Byte], decodeOptions: DecodeOptions): Reader =
    from(Source.from(bytes), decodeOptions)

  def from(source: Source): Reader =
    from(source, DecodeOptions.default)

  def from(source: Source, decodeOptions: DecodeOptions): Reader =
    new Reader(source, decodeOptions, decodeOptions.maxCollectionElements)

  def from(input: java.io.InputStream): Reader =
    from(Source.from(input), DecodeOptions.default)

  def from(input: java.io.InputStream, decodeOptions: DecodeOptions): Reader =
    from(Source.from(input), decodeOptions)
