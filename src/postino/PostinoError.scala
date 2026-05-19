package postino

sealed trait PostinoError extends Product with Serializable:
  def message: String

object PostinoError:
  case object UnexpectedEnd extends PostinoError:
    val message = "unexpected end of input"

  final case class VarintTooLong(maxBytes: Int) extends PostinoError:
    def message = s"varint did not terminate within $maxBytes bytes"

  final case class VarintOverflow(target: String) extends PostinoError:
    def message = s"varint value does not fit in $target"

  final case class LengthTooLarge(length: BigInt) extends PostinoError:
    def message = s"length $length does not fit in a JVM array"

  final case class NegativeLength(length: Int) extends PostinoError:
    def message = s"negative byte length $length"

  final case class CollectionLengthTooLarge(length: Int, max: Int) extends PostinoError:
    def message = s"collection length $length exceeds configured maximum $max"

  final case class CollectionElementLimitExceeded(requested: Int, remaining: Long, max: Long)
      extends PostinoError:
    def message =
      s"collection length $requested exceeds remaining element budget $remaining of $max"

  final case class InvalidBoolean(value: Int) extends PostinoError:
    def message = s"invalid boolean tag $value"

  final case class InvalidOptionTag(value: Int) extends PostinoError:
    def message = s"invalid option tag $value"

  final case class InvalidUtf8(reason: String) extends PostinoError:
    def message = s"invalid UTF-8 string: $reason"

  final case class TrailingBytes(count: Int) extends PostinoError:
    def message = s"$count trailing byte(s) after decoded value"

  final case class InvalidUnsignedValue(target: String, value: BigInt) extends PostinoError:
    def message = s"value $value is outside $target"

  final case class ProductConstructionFailed(product: String, reason: String) extends PostinoError:
    def message = s"failed to construct $product: $reason"

  final case class ProductFieldFailed(product: String, field: String, cause: PostinoError)
      extends PostinoError:
    def message = s"failed to decode $product.$field: ${cause.message}"

  final case class UnknownVariant(discriminant: Long) extends PostinoError:
    def message = s"unknown enum discriminant $discriminant"

  final case class UnmatchedVariant(runtimeClass: String) extends PostinoError:
    def message = s"no enum variant registered for $runtimeClass"

  final case class AmbiguousVariant(runtimeClass: String, discriminants: Vector[Long])
      extends PostinoError:
    def message =
      s"multiple enum variants match $runtimeClass: ${discriminants.mkString(", ")}"
