package postino

final class Reader private (private val bytes: Array[Byte], private var offset: Int):
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

object Reader:
  def from(bytes: Array[Byte]): Reader =
    new Reader(bytes, 0)
