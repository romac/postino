package postino

import java.io.{ByteArrayOutputStream, IOException, OutputStream}

trait Sink:
  def writeByte(value: Byte): Either[PostinoError, Unit]
  def writeBytes(values: Array[Byte]): Either[PostinoError, Unit]

object Sink:
  def array: ArraySink =
    ArraySink(ByteArrayOutputStream())

  def outputStream(output: OutputStream): Sink =
    OutputStreamSink(output)

  final class ArraySink private[Sink] (bytes: ByteArrayOutputStream) extends Sink:
    def writeByte(value: Byte): Either[PostinoError, Unit] =
      bytes.write(value & 0xff)
      Right(())

    def writeBytes(values: Array[Byte]): Either[PostinoError, Unit] =
      bytes.writeBytes(values)
      Right(())

    def toByteArray: Array[Byte] =
      bytes.toByteArray

  private final class OutputStreamSink(output: OutputStream) extends Sink:
    def writeByte(value: Byte): Either[PostinoError, Unit] =
      try
        output.write(value & 0xff)
        Right(())
      catch case error: IOException => Left(PostinoError.Io("write byte", errorMessage(error)))

    def writeBytes(values: Array[Byte]): Either[PostinoError, Unit] =
      try
        output.write(values)
        Right(())
      catch case error: IOException => Left(PostinoError.Io("write bytes", errorMessage(error)))

    private def errorMessage(error: IOException): String =
      Option(error.getMessage).getOrElse(error.getClass.getName)
