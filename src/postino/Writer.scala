package postino

final class Writer private (private val sink: Sink):
  def writeByte(value: Byte): Either[PostinoError, Unit] =
    sink.writeByte(value)

  def writeUnsignedByte(value: Int): Either[PostinoError, Unit] =
    if value < 0 || value > 0xff then Left(PostinoError.InvalidUnsignedValue("u8", BigInt(value)))
    else writeByte(value.toByte)

  def writeBytes(values: Array[Byte]): Either[PostinoError, Unit] =
    sink.writeBytes(values)

  def toByteArray: Array[Byte] =
    sink match
      case arraySink: Sink.ArraySink => arraySink.toByteArray
      case _ => throw UnsupportedOperationException("Writer is not array-backed")

object Writer:
  def empty: Writer =
    new Writer(Sink.array)

  def to(sink: Sink): Writer =
    new Writer(sink)

  def to(output: java.io.OutputStream): Writer =
    new Writer(Sink.outputStream(output))
