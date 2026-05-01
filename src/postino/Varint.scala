package postino

private[postino] object Varint:
  private val SevenBits = BigInt(0x7f)

  def maxBytes(bits: Int): Int =
    (bits + 6) / 7

  def maxUnsigned(bits: Int): BigInt =
    (BigInt(1) << bits) - 1

  def writeUnsigned(
      value: BigInt,
      bits: Int,
      target: String,
      out: Writer
  ): Either[PostinoError, Unit] =
    if value < 0 || value > maxUnsigned(bits) then Left(PostinoError.VarintOverflow(target))
    else
      var remaining                          = value
      var done                               = false
      var result: Either[PostinoError, Unit] = Right(())

      while !done && result.isRight do
        val payload = (remaining & SevenBits).toInt
        remaining = remaining >> 7
        val byte = if remaining == 0 then payload else payload | 0x80
        result = out.writeUnsignedByte(byte)
        done = remaining == 0

      result

  def readUnsigned(in: Reader, bits: Int, target: String): Either[PostinoError, BigInt] =
    val limit = maxBytes(bits)
    var index = 0
    var out   = BigInt(0)

    while index < limit do
      in.readUnsignedByte() match
        case Left(error) => return Left(error)
        case Right(byte) =>
          val payload = byte & 0x7f
          out |= BigInt(payload) << (7 * index)

          if (byte & 0x80) == 0 then
            if out > maxUnsigned(bits) then return Left(PostinoError.VarintOverflow(target))
            else return Right(out)

          index += 1

    Left(PostinoError.VarintTooLong(limit))

  def zigZagEncode(value: BigInt, bits: Int, target: String): Either[PostinoError, BigInt] =
    val min = -(BigInt(1) << (bits - 1))
    val max = (BigInt(1) << (bits - 1)) - 1

    if value < min || value > max then Left(PostinoError.VarintOverflow(target))
    else if value >= 0 then Right(value << 1)
    else Right(((-value) << 1) - 1)

  def zigZagDecode(value: BigInt, bits: Int, target: String): Either[PostinoError, BigInt] =
    val decoded =
      if (value & 1) == 0 then value >> 1
      else -((value >> 1) + 1)

    val min = -(BigInt(1) << (bits - 1))
    val max = (BigInt(1) << (bits - 1)) - 1
    if decoded < min || decoded > max then Left(PostinoError.VarintOverflow(target))
    else Right(decoded)
