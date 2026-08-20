package postino

import scala.reflect.ClassTag

/** An explicit sum builder that tracks unregistered direct variants in its type. */
final class ExhaustiveSumCodecBuilder[A, Remaining <: Tuple] private (
    delegate: SumCodecBuilder[A]
):
  def variant[B <: A, Next <: Tuple](discriminant: Int, codec: Codec[B])(using
      ClassTag[B],
      ExhaustiveSumCodecBuilder.Removes[Remaining, B, Next]
  ): ExhaustiveSumCodecBuilder[A, Next] =
    new ExhaustiveSumCodecBuilder(delegate.variant(discriminant, codec))

  def variant[B <: A, Next <: Tuple](discriminant: Long, codec: Codec[B])(using
      ClassTag[B],
      ExhaustiveSumCodecBuilder.Removes[Remaining, B, Next]
  ): ExhaustiveSumCodecBuilder[A, Next] =
    new ExhaustiveSumCodecBuilder(delegate.variant(discriminant, codec))

  def build(using Remaining =:= EmptyTuple): Codec[A] =
    delegate.build

object ExhaustiveSumCodecBuilder:
  /** Evidence that removing `Variant` from `Remaining` produces `Next`. */
  sealed trait Removes[Remaining <: Tuple, Variant, Next <: Tuple]

  object Removes:
    given head[Variant, Tail <: Tuple]: Removes[Variant *: Tail, Variant, Tail] with {}

    given tail[Head, Tail <: Tuple, Variant, Next <: Tuple](using
        Removes[Tail, Variant, Next]
    ): Removes[Head *: Tail, Variant, Head *: Next] with {}

  private[postino] def empty[A, Variants <: Tuple]: ExhaustiveSumCodecBuilder[A, Variants] =
    new ExhaustiveSumCodecBuilder(SumCodecBuilder.empty[A])
