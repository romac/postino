package postino

import scala.reflect.ClassTag

final class SumCodecBuilder[A] private (variants: Vector[SumCodecBuilder.Entry[A]]):
  def variant[B <: A](discriminant: Int, codec: Codec[B])(using
      classTag: ClassTag[B]
  ): SumCodecBuilder[A] =
    variant(discriminant.toLong, codec)

  def variant[B <: A](discriminant: Long, codec: Codec[B])(using
      classTag: ClassTag[B]
  ): SumCodecBuilder[A] =
    require(
      discriminant >= 0L && discriminant <= U32.MaxValue,
      s"Postino enum discriminant $discriminant is outside u32"
    )
    new SumCodecBuilder(variants :+ SumCodecBuilder.TypedEntry(discriminant, codec, classTag))

  def build: Codec[A] =
    val duplicate = variants
      .groupBy(_.discriminant)
      .collectFirst:
        case (discriminant, grouped) if grouped.size > 1 => discriminant

    duplicate.foreach: discriminant =>
      throw IllegalArgumentException(s"duplicate Postino enum discriminant $discriminant")

    new Codec[A]:
      def encode(value: A, out: Writer): Either[PostinoError, Unit] =
        variants.find(_.matches(value)) match
          case Some(variant) =>
            for
              _ <- Varint.writeUnsigned(BigInt(variant.discriminant), 32, "u32", out)
              _ <- variant.encode(value, out)
            yield ()
          case None =>
            Left(PostinoError.UnmatchedVariant(value.getClass.getName))

      def decode(in: Reader): Either[PostinoError, A] =
        Varint
          .readUnsigned(in, 32, "u32")
          .flatMap: discriminant =>
            variants.find(_.discriminant == discriminant.toLong) match
              case Some(variant) => variant.decode(in)
              case None          => Left(PostinoError.UnknownVariant(discriminant.toLong))

object SumCodecBuilder:
  private[postino] def empty[A]: SumCodecBuilder[A] =
    new SumCodecBuilder(Vector.empty)

  private final case class TypedEntry[A, B <: A](
      discriminant: Long,
      codec: Codec[B],
      classTag: ClassTag[B]
  ) extends SumCodecBuilder.Entry[A]:
    def matches(value: A): Boolean =
      classTag.runtimeClass.isInstance(value)

    def encode(value: A, out: Writer): Either[PostinoError, Unit] =
      codec.encode(value.asInstanceOf[B], out)

    def decode(in: Reader): Either[PostinoError, A] =
      codec.decode(in)

  private trait Entry[A]:
    def discriminant: Long
    def matches(value: A): Boolean
    def encode(value: A, out: Writer): Either[PostinoError, Unit]
    def decode(in: Reader): Either[PostinoError, A]
