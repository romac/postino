package postino

trait Crc:
  def widthBytes: Int
  def checksum(bytes: Array[Byte]): Array[Byte]

object Crc:
  given Crc = Crc32Fast

  val Crc32Fast: Crc =
    reflectedCrc32("crc32fast", 0xedb88320L, 0xffffffffL, 0xffffffffL)

  val Crc32Iscsi: Crc =
    reflectedCrc32("crc32-iscsi", 0x82f63b78L, 0xffffffffL, 0xffffffffL)

  def reflectedCrc32(
      name: String,
      reflectedPolynomial: Long,
      initial: Long,
      xorOut: Long
  ): Crc =
    ReflectedCrc32(name, reflectedPolynomial, initial, xorOut)

  private final class ReflectedCrc32(
      name: String,
      reflectedPolynomial: Long,
      initial: Long,
      xorOut: Long
  ) extends Crc:
    private val Mask = 0xffffffffL

    def widthBytes: Int =
      4

    def checksum(bytes: Array[Byte]): Array[Byte] =
      var crc = initial & Mask

      bytes.foreach: byte =>
        crc = (crc ^ (byte.toLong & 0xffL)) & Mask

        var bit = 0
        while bit < 8 do
          crc =
            if (crc & 1L) != 0 then ((crc >>> 1) ^ reflectedPolynomial) & Mask
            else (crc >>> 1) & Mask
          bit += 1

      val value = (crc ^ xorOut) & Mask
      Array(
        value.toByte,
        (value >>> 8).toByte,
        (value >>> 16).toByte,
        (value >>> 24).toByte
      )

    override def toString: String =
      name
