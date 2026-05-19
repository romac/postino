package postino

final class Reader private (
    private val bytes: Array[Byte],
    private var offset: Int,
    val decodeOptions: DecodeOptions,
    private var remainingCollectionElements: Long
):
  def position: Int = offset

  def remaining: Int = bytes.length - offset

  def readByte(): Either[PostinoError, Byte] =
    if offset >= bytes.length then Left(PostinoError.UnexpectedEnd)
    else
      val value = bytes(offset)
      offset += 1
      Right(value)

  def readUnsignedByte(): Either[PostinoError, Int] =
    readByte().map(_ & 0xff)

  def readBytes(length: Int): Either[PostinoError, Array[Byte]] =
    if length < 0 then Left(PostinoError.LengthTooLarge(BigInt(length)))
    else if remaining < length then Left(PostinoError.UnexpectedEnd)
    else
      val value = java.util.Arrays.copyOfRange(bytes, offset, offset + length)
      offset += length
      Right(value)

  private[postino] def reserveCollectionElements(length: Int): Either[PostinoError, Unit] =
    if length > decodeOptions.maxCollectionLength then
      Left(PostinoError.CollectionLengthTooLarge(length, decodeOptions.maxCollectionLength))
    else if length.toLong > remainingCollectionElements then
      Left(
        PostinoError.CollectionElementLimitExceeded(
          length,
          remainingCollectionElements,
          decodeOptions.maxCollectionElements
        )
      )
    else
      remainingCollectionElements -= length.toLong
      Right(())

object Reader:
  def from(bytes: Array[Byte]): Reader =
    from(bytes, DecodeOptions.default)

  def from(bytes: Array[Byte], decodeOptions: DecodeOptions): Reader =
    new Reader(bytes, 0, decodeOptions, decodeOptions.maxCollectionElements)
