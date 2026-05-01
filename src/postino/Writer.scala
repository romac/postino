package postino

import scala.collection.mutable.ArrayBuffer

final class Writer private (private val bytes: ArrayBuffer[Byte]):
  def writeByte(value: Byte): Either[PostinoError, Unit] =
    bytes += value
    Right(())

  def writeUnsignedByte(value: Int): Either[PostinoError, Unit] =
    if value < 0 || value > 0xff then Left(PostinoError.InvalidUnsignedValue("u8", BigInt(value)))
    else writeByte(value.toByte)

  def writeBytes(values: Array[Byte]): Either[PostinoError, Unit] =
    bytes ++= values
    Right(())

  def toByteArray: Array[Byte] =
    bytes.toArray

object Writer:
  def empty: Writer =
    new Writer(ArrayBuffer.empty)
