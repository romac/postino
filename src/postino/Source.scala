package postino

import java.io.{IOException, InputStream, PushbackInputStream}

trait Source:
  def position: Long
  def remaining: Option[Int]
  def readByte(): Either[PostinoError, Byte]
  def readBytes(length: Int): Either[PostinoError, Array[Byte]]
  def atEnd: Either[PostinoError, Boolean]

object Source:
  def from(bytes: Array[Byte]): Source =
    ArraySource(bytes)

  def from(input: InputStream): Source =
    InputStreamSource(input)

  private final class ArraySource(bytes: Array[Byte]) extends Source:
    private var offset = 0

    def position: Long =
      offset.toLong

    def remaining: Option[Int] =
      Some(bytes.length - offset)

    def readByte(): Either[PostinoError, Byte] =
      if offset >= bytes.length then Left(PostinoError.UnexpectedEnd)
      else
        val value = bytes(offset)
        offset += 1
        Right(value)

    def readBytes(length: Int): Either[PostinoError, Array[Byte]] =
      if length < 0 then Left(PostinoError.NegativeLength(length))
      else if bytes.length - offset < length then Left(PostinoError.UnexpectedEnd)
      else
        val value = java.util.Arrays.copyOfRange(bytes, offset, offset + length)
        offset += length
        Right(value)

    def atEnd: Either[PostinoError, Boolean] =
      Right(offset >= bytes.length)

  private final class InputStreamSource(input: InputStream) extends Source:
    private val stream =
      input match
        case pushback: PushbackInputStream => pushback
        case other                         => PushbackInputStream(other, 1)
    private var offset = 0L

    def position: Long =
      offset

    def remaining: Option[Int] =
      None

    def readByte(): Either[PostinoError, Byte] =
      try
        val value = stream.read()
        if value < 0 then Left(PostinoError.UnexpectedEnd)
        else
          offset += 1
          Right(value.toByte)
      catch case error: IOException => Left(PostinoError.Io("read byte", errorMessage(error)))

    def readBytes(length: Int): Either[PostinoError, Array[Byte]] =
      if length < 0 then Left(PostinoError.NegativeLength(length))
      else
        val value = new Array[Byte](length)
        var read  = 0
        while read < length do
          readSome(value, read, length - read) match
            case Right(count) =>
              read += count
              offset += count.toLong
            case Left(error) => return Left(error)
        Right(value)

    def atEnd: Either[PostinoError, Boolean] =
      try
        val value = stream.read()
        if value < 0 then Right(true)
        else
          stream.unread(value)
          Right(false)
      catch
        case error: IOException => Left(PostinoError.Io("check end of input", errorMessage(error)))

    private def readSome(
        buffer: Array[Byte],
        start: Int,
        length: Int
    ): Either[PostinoError, Int] =
      try
        val count = stream.read(buffer, start, length)
        if count < 0 then Left(PostinoError.UnexpectedEnd)
        else if count == 0 then
          val value = stream.read()
          if value < 0 then Left(PostinoError.UnexpectedEnd)
          else
            buffer(start) = value.toByte
            Right(1)
        else Right(count)
      catch case error: IOException => Left(PostinoError.Io("read bytes", errorMessage(error)))

    private def errorMessage(error: IOException): String =
      Option(error.getMessage).getOrElse(error.getClass.getName)
