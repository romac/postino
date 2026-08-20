package postino

/** A homogeneous postcard array whose length is part of its Scala type and is not encoded. */
final class FixedArray[A, N <: Int] private (private val elements: Vector[A]) extends Iterable[A]:
  override def iterator: Iterator[A] =
    elements.iterator

  override def size: Int =
    elements.size

  def apply(index: Int): A =
    elements(index)

  override def toVector: Vector[A] =
    elements

  override def equals(other: Any): Boolean =
    other match
      case that: FixedArray[?, ?] => elements == that.elements
      case _                      => false

  override def hashCode(): Int =
    elements.hashCode()

  override def toString: String =
    elements.mkString("FixedArray(", ", ", ")")

object FixedArray:
  def from[A, N <: Int](values: IterableOnce[A])(using
      length: ValueOf[N]
  ): Either[PostinoError, FixedArray[A, N]] =
    val elements = Vector.from(values)
    if elements.length == length.value then Right(new FixedArray(elements))
    else Left(PostinoError.FixedArrayLengthMismatch(length.value, elements.length))

  def unsafeFrom[A, N <: Int](values: IterableOnce[A])(using ValueOf[N]): FixedArray[A, N] =
    from[A, N](values).fold(error => throw IllegalArgumentException(error.message), identity)

  private[postino] def decoded[A, N <: Int](values: Vector[A]): FixedArray[A, N] =
    new FixedArray(values)
