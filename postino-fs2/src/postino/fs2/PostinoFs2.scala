package postino.fs2

import postino.{DecodeOptions, Decoder, Encoder, PostinoError}
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

  private final case class SplitFrames(frames: Vector[Array[Byte]], error: Option[PostinoError])

final case class PostinoFs2Exception(error: PostinoError) extends RuntimeException(error.message)
