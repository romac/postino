package postino.fs2

import postino.{Crc, DecodeOptions, Decoder, Encoder, PostinoError}
import _root_.fs2.{Chunk, Pipe, Pull, RaiseThrowable, Stream}

object PostinoFs2:
  def encodeCobs[F[_], A](using Encoder[A], RaiseThrowable[F]): Pipe[F, A, Byte] =
    values =>
      values.flatMap: value =>
        postino.Postino.encodeCobs(value) match
          case Right(bytes) => Stream.chunk(Chunk.array(bytes))
          case Left(error)  => Stream.raiseError(PostinoFs2Exception(error))

  def decodeCobs[F[_], A](using Decoder[A], RaiseThrowable[F]): Pipe[F, Byte, A] =
    decodeCobs(DecodeOptions.default)

  def decodeCobs[F[_], A](decodeOptions: DecodeOptions)(using
      Decoder[A],
      RaiseThrowable[F]
  ): Pipe[F, Byte, A] =
    bytes => decodeCobsPull(bytes, FrameBuffer(decodeOptions.maxByteLength), decodeOptions).stream

  def encodeCrc[F[_], A](using
      Encoder[A],
      Crc,
      RaiseThrowable[F]
  ): Pipe[F, A, Byte] =
    encodeCrcWith(summon[Crc])

  def encodeCrc[F[_], A](crc: Crc)(using Encoder[A], RaiseThrowable[F]): Pipe[F, A, Byte] =
    encodeCrcWith(crc)

  def decodeCrc[F[_], A](using
      Decoder[A],
      Crc,
      RaiseThrowable[F]
  ): Pipe[F, Byte, A] =
    decodeCrc(summon[Crc], DecodeOptions.default)

  def decodeCrc[F[_], A](decodeOptions: DecodeOptions)(using
      Decoder[A],
      Crc,
      RaiseThrowable[F]
  ): Pipe[F, Byte, A] =
    decodeCrc(summon[Crc], decodeOptions)

  def decodeCrc[F[_], A](crc: Crc)(using Decoder[A], RaiseThrowable[F]): Pipe[F, Byte, A] =
    decodeCrc(crc, DecodeOptions.default)

  def decodeCrc[F[_], A](crc: Crc, decodeOptions: DecodeOptions)(using
      Decoder[A],
      RaiseThrowable[F]
  ): Pipe[F, Byte, A] =
    bytes =>
      decodeCrcPull(
        bytes,
        CrcFrameBuffer[A](decodeOptions.maxByteLength, decodeOptions, crc)
      ).stream

  private def encodeCrcWith[F[_], A](crc: Crc)(using
      Encoder[A],
      RaiseThrowable[F]
  ): Pipe[F, A, Byte] =
    values =>
      values.flatMap: value =>
        postino.Postino.encodeCrc(crc, value) match
          case Right(bytes) => Stream.chunk(Chunk.array(bytes))
          case Left(error)  => Stream.raiseError(PostinoFs2Exception(error))

  private def decodeCobsPull[F[_], A](
      bytes: Stream[F, Byte],
      buffer: FrameBuffer,
      decodeOptions: DecodeOptions
  )(using Decoder[A], RaiseThrowable[F]): Pull[F, A, Unit] =
    bytes.pull.uncons.flatMap:
      case Some((chunk, rest)) =>
        val split = splitFrames(buffer, chunk.iterator)
        emitFrames[F, A](split.frames, decodeOptions)
          .flatMap: _ =>
            split.error match
              case Some(error) => Pull.raiseError(PostinoFs2Exception(error))
              case None        => decodeCobsPull(rest, buffer, decodeOptions)
      case None =>
        if buffer.isEmpty then Pull.done
        else Pull.raiseError(PostinoFs2Exception(PostinoError.CobsFraming("missing terminator")))

  private def emitFrames[F[_], A](
      frames: Vector[Array[Byte]],
      decodeOptions: DecodeOptions
  )(using Decoder[A], RaiseThrowable[F]): Pull[F, A, Unit] =
    frames.foldLeft(Pull.done: Pull[F, A, Unit]): (pull, frame) =>
      pull.flatMap: _ =>
        postino.Postino.decodeCobs[A](frame, decodeOptions) match
          case Right(value) => Pull.output1(value)
          case Left(error)  => Pull.raiseError(PostinoFs2Exception(error))

  private def decodeCrcPull[F[_], A](
      bytes: Stream[F, Byte],
      buffer: CrcFrameBuffer[A]
  )(using RaiseThrowable[F]): Pull[F, A, Unit] =
    bytes.pull.uncons.flatMap:
      case Some((chunk, rest)) =>
        val split = splitCrcValues(buffer, chunk.iterator)
        emitValues[F, A](split.values)
          .flatMap: _ =>
            split.error match
              case Some(error) => Pull.raiseError(PostinoFs2Exception(error))
              case None        => decodeCrcPull(rest, buffer)
      case None =>
        val result = buffer.finish()
        emitValues[F, A](result.values)
          .flatMap: _ =>
            result.error match
              case Some(error) => Pull.raiseError(PostinoFs2Exception(error))
              case None        => Pull.done

  private def emitValues[F[_], A](values: Vector[A])(using
      RaiseThrowable[F]
  ): Pull[F, A, Unit] =
    values.foldLeft(Pull.done: Pull[F, A, Unit]): (pull, value) =>
      pull.flatMap(_ => Pull.output1(value))

  private def splitCrcValues[A](
      buffer: CrcFrameBuffer[A],
      bytes: Iterator[Byte]
  ): SplitCrcValues[A] =
    val values                      = Vector.newBuilder[A]
    var error: Option[PostinoError] = None

    while bytes.hasNext && error.isEmpty do
      val result = buffer.append(bytes.next())
      values ++= result.values
      error = result.error

    SplitCrcValues(values.result(), error)

  private def splitFrames(buffer: FrameBuffer, bytes: Iterator[Byte]): SplitFrames =
    val frames                      = Vector.newBuilder[Array[Byte]]
    var error: Option[PostinoError] = None

    while bytes.hasNext && error.isEmpty do
      buffer.append(bytes.next()) match
        case Right(Some(frame)) => frames += frame
        case Right(None)        => ()
        case Left(cause)        => error = Some(cause)

    SplitFrames(frames.result(), error)

  private final class FrameBuffer(maxLength: Int):
    private var bytes  = java.io.ByteArrayOutputStream(maxLength.min(8192))
    private var length = 0

    def isEmpty: Boolean =
      length == 0

    def append(byte: Byte): Either[PostinoError, Option[Array[Byte]]] =
      if length == maxLength then Left(PostinoError.CobsFrameTooLarge(length + 1, maxLength))
      else
        bytes.write(byte & 0xff)
        length += 1
        if byte == 0 then
          val frame = bytes.toByteArray
          bytes = java.io.ByteArrayOutputStream(maxLength.min(8192))
          length = 0
          Right(Some(frame))
        else Right(None)

  private object FrameBuffer:
    def apply(maxLength: Int): FrameBuffer =
      new FrameBuffer(maxLength)

  private final class CrcFrameBuffer[A](
      maxLength: Int,
      decodeOptions: DecodeOptions,
      crc: Crc
  )(using Decoder[A]):
    private var bytes                     = newBytes()
    private var length                    = 0
    private var decoded: Option[(A, Int)] = None
    private var nextDecodeLength          = 1

    def append(byte: Byte): CrcBufferResult[A] =
      if crc.widthBytes <= 0 then
        CrcBufferResult(
          Vector.empty,
          Some(PostinoError.CrcFraming("checksum width must be positive"))
        )
      else if length == maxLength then
        CrcBufferResult(
          Vector.empty,
          Some(PostinoError.CrcFrameTooLarge(length + 1, maxLength))
        )
      else
        bytes.write(byte & 0xff)
        length += 1
        drain(forceDecode = false)

    def finish(): CrcBufferResult[A] =
      val result = drain(forceDecode = true)
      if result.error.nonEmpty || length == 0 then result
      else
        val error =
          decoded match
            case Some(_) => PostinoError.CrcFraming("missing checksum")
            case None    => PostinoError.CrcFraming("truncated payload")
        result.copy(error = Some(error))

    private def drain(forceDecode: Boolean): CrcBufferResult[A] =
      val values                      = Vector.newBuilder[A]
      var error: Option[PostinoError] = None
      var continue                    = true

      while continue && error.isEmpty do
        decoded match
          case None if forceDecode || length >= nextDecodeLength =>
            postino.Postino.decodePrefix[A](bytes.toByteArray, decodeOptions) match
              case Right(result) => decoded = Some(result)
              case Left(cause) if needsMoreBytes(cause) =>
                scheduleNextDecode()
                continue = false
              case Left(cause) =>
                error = Some(cause)
          case None =>
            continue = false
          case Some((value, payloadLength)) =>
            val frameLength = payloadLength.toLong + crc.widthBytes.toLong
            if length.toLong < frameLength then continue = false
            else if frameLength > Int.MaxValue then
              error = Some(PostinoError.CrcFrameTooLarge(Int.MaxValue, maxLength))
            else
              val current = bytes.toByteArray
              val frame   = java.util.Arrays.copyOfRange(current, 0, frameLength.toInt)
              postino.Postino.splitCrc(frame, crc) match
                case Left(cause) => error = Some(cause)
                case Right(_) =>
                  values += value
                  discardPrefix(current, frameLength.toInt)

      CrcBufferResult(values.result(), error)

    private def discardPrefix(current: Array[Byte], count: Int): Unit =
      val remaining = java.util.Arrays.copyOfRange(current, count, current.length)
      bytes = newBytes()
      bytes.writeBytes(remaining)
      length = remaining.length
      decoded = None
      nextDecodeLength = 1

    private def scheduleNextDecode(): Unit =
      val doubled = math.max(length.toLong + 1L, length.toLong * 2L)
      nextDecodeLength = math.min(maxLength.toLong, doubled).toInt

    private def needsMoreBytes(error: PostinoError): Boolean =
      error match
        case PostinoError.UnexpectedEnd                   => true
        case PostinoError.ProductFieldFailed(_, _, cause) => needsMoreBytes(cause)
        case _                                            => false

    private def newBytes(): java.io.ByteArrayOutputStream =
      java.io.ByteArrayOutputStream(maxLength.min(8192))

  private object CrcFrameBuffer:
    def apply[A](maxLength: Int, decodeOptions: DecodeOptions, crc: Crc)(using
        Decoder[A]
    ): CrcFrameBuffer[A] =
      new CrcFrameBuffer(maxLength, decodeOptions, crc)

  private final case class SplitFrames(frames: Vector[Array[Byte]], error: Option[PostinoError])

  private final case class SplitCrcValues[A](values: Vector[A], error: Option[PostinoError])

  private final case class CrcBufferResult[A](values: Vector[A], error: Option[PostinoError])

final case class PostinoFs2Exception(error: PostinoError) extends RuntimeException(error.message)
