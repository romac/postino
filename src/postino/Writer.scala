package postino

import java.io.ByteArrayOutputStream

final class Writer private (private val bytes: ByteArrayOutputStream):
  def writeByte(value: Byte): Either[PostinoError, Unit] =
    bytes.write(value & 0xff)
    Right(())

  def writeUnsignedByte(value: Int): Either[PostinoError, Unit] =
    if value < 0 || value > 0xff then Left(PostinoError.InvalidUnsignedValue("u8", BigInt(value)))
    else writeByte(value.toByte)

  def writeBytes(values: Array[Byte]): Either[PostinoError, Unit] =
    bytes.writeBytes(values)
    Right(())

  def toByteArray: Array[Byte] =
    bytes.toByteArray

object Writer:
  def empty: Writer =
    new Writer(ByteArrayOutputStream())
