package postino

final class U16 private (val toInt: Int) extends AnyVal:
  override def toString: String = toInt.toString

object U16:
  val MaxValue: Int = 0xffff

  def fromInt(value: Int): Either[PostinoError, U16] =
    if value >= 0 && value <= MaxValue then Right(new U16(value))
    else Left(PostinoError.InvalidUnsignedValue("u16", BigInt(value)))

  def unsafeFromInt(value: Int): U16 =
    fromInt(value).fold(error => throw new IllegalArgumentException(error.message), identity)

final class U32 private (val toLong: Long) extends AnyVal:
  override def toString: String = toLong.toString

object U32:
  val MaxValue: Long = 0xffffffffL

  def fromLong(value: Long): Either[PostinoError, U32] =
    if value >= 0L && value <= MaxValue then Right(new U32(value))
    else Left(PostinoError.InvalidUnsignedValue("u32", BigInt(value)))

  def unsafeFromLong(value: Long): U32 =
    fromLong(value).fold(error => throw new IllegalArgumentException(error.message), identity)

final class U64 private (val toUnsignedLong: Long) extends AnyVal:
  def toBigInt: BigInt = U64.unsignedLongToBigInt(toUnsignedLong)

  override def toString: String = toBigInt.toString

object U64:
  val MaxValue: BigInt = (BigInt(1) << 64) - 1

  /** Interprets `value` as a signed JVM Long. Negative values are rejected. */
  def fromLong(value: Long): Either[PostinoError, U64] =
    if value >= 0 then Right(new U64(value))
    else Left(PostinoError.InvalidUnsignedValue("u64", BigInt(value)))

  /** Interprets `bits` as the two's-complement bit pattern of an unsigned 64-bit value. */
  def fromUnsignedLong(bits: Long): U64 =
    new U64(bits)

  def fromBigInt(value: BigInt): Either[PostinoError, U64] =
    if value >= 0 && value <= MaxValue then Right(new U64(value.toLong))
    else Left(PostinoError.InvalidUnsignedValue("u64", value))

  def unsafeFromLong(value: Long): U64 =
    fromLong(value).fold(error => throw new IllegalArgumentException(error.message), identity)

  def unsafeFromUnsignedLong(bits: Long): U64 =
    fromUnsignedLong(bits)

  def unsafeFromBigInt(value: BigInt): U64 =
    fromBigInt(value).fold(error => throw new IllegalArgumentException(error.message), identity)

  private def unsignedLongToBigInt(bits: Long): BigInt =
    if bits >= 0 then BigInt(bits)
    else (BigInt(bits >>> 1) << 1) + BigInt(bits & 1L)
