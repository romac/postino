package postino.scodec

import postino.{Codec as PostinoCodec, DecodeOptions, PostinoError, Reader}
import _root_.scodec.bits.BitVector
import _root_.scodec.{Attempt, DecodeResult, Err, SizeBound}

object PostinoScodec:
  def toScodec[A](using postinoCodec: PostinoCodec[A]): _root_.scodec.Codec[A] =
    toScodec(DecodeOptions.default)

  def toScodec[A](decodeOptions: DecodeOptions)(using
      postinoCodec: PostinoCodec[A]
  ): _root_.scodec.Codec[A] =
    new _root_.scodec.Codec[A]:
      def sizeBound: SizeBound =
        SizeBound.unknown

      def encode(value: A): Attempt[BitVector] =
        postino.Postino
          .encode(value)
          .fold(
            error => Attempt.failure(toErr(error)),
            bytes => Attempt.successful(BitVector.view(bytes))
          )

      def decode(bits: BitVector): Attempt[DecodeResult[A]] =
        if bits.size % 8 != 0 then
          Attempt.failure(Err("Postino scodec adapter requires byte-aligned input"))
        else
          val in = Reader.from(bits.toByteArray, decodeOptions)
          postinoCodec
            .decode(in)
            .fold(
              error => Attempt.failure(toErr(error)),
              value => Attempt.successful(DecodeResult(value, bits.drop(in.position.toLong * 8)))
            )

  private def toErr(error: PostinoError): Err =
    Err(error.message)
