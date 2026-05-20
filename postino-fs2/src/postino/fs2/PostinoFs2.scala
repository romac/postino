package postino.fs2

import postino.{DecodeOptions, Decoder, Encoder, PostinoError}
import _root_.fs2.{Pipe, Pull, RaiseThrowable, Stream}

object PostinoFs2:
  def encodeCobs[F[_], A](using Encoder[A], RaiseThrowable[F]): Pipe[F, A, Byte] =
    values =>
      values.flatMap: value =>
        postino.Postino.encodeCobs(value) match
          case Right(bytes) => Stream.emits(bytes.toSeq)
          case Left(error)  => Stream.raiseError(PostinoFs2Exception(error))

  def decodeCobs[F[_], A](using Decoder[A], RaiseThrowable[F]): Pipe[F, Byte, A] =
    decodeCobs(DecodeOptions.default)

  def decodeCobs[F[_], A](decodeOptions: DecodeOptions)(using
      Decoder[A],
      RaiseThrowable[F]
  ): Pipe[F, Byte, A] =
    bytes => decodeCobsPull(bytes, Array.empty[Byte], decodeOptions).stream

  private def decodeCobsPull[F[_], A](
      bytes: Stream[F, Byte],
      pending: Array[Byte],
      decodeOptions: DecodeOptions
  )(using Decoder[A], RaiseThrowable[F]): Pull[F, A, Unit] =
    bytes.pull.uncons.flatMap:
      case Some((chunk, rest)) =>
        val split = splitFrames(pending, chunk.iterator)
        emitFrames[F, A](split.frames, decodeOptions)
          .flatMap(_ => decodeCobsPull(rest, split.pending, decodeOptions))
      case None =>
        if pending.isEmpty then Pull.done
        else Pull.raiseError(PostinoFs2Exception(PostinoError.CobsFraming("missing terminator")))

  private def emitFrames[F[_], A](
      frames: Vector[Array[Byte]],
      decodeOptions: DecodeOptions
  )(using Decoder[A], RaiseThrowable[F]): Pull[F, A, Unit] =
    var index                  = 0
    var pull: Pull[F, A, Unit] = Pull.done

    while index < frames.length do
      val frame = frames(index)
      pull = pull.flatMap: _ =>
        postino.Postino.decodeCobs[A](frame, decodeOptions) match
          case Right(value) => Pull.output1(value)
          case Left(error)  => Pull.raiseError(PostinoFs2Exception(error))
      index += 1

    pull

  private def splitFrames(pending: Array[Byte], bytes: Iterator[Byte]): SplitFrames =
    val frames  = Vector.newBuilder[Array[Byte]]
    var current = Array.newBuilder[Byte]
    current ++= pending

    bytes.foreach: byte =>
      current += byte
      if byte == 0 then
        frames += current.result()
        current = Array.newBuilder[Byte]

    SplitFrames(frames.result(), current.result())

  private final case class SplitFrames(frames: Vector[Array[Byte]], pending: Array[Byte])

final case class PostinoFs2Exception(error: PostinoError) extends RuntimeException(error.message)
