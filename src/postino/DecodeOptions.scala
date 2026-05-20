package postino

final case class DecodeOptions(
    maxCollectionLength: Int = DecodeOptions.DefaultMaxCollectionLength,
    maxCollectionElements: Long = DecodeOptions.DefaultMaxCollectionElements,
    maxByteLength: Int = DecodeOptions.DefaultMaxByteLength
):
  require(maxCollectionLength >= 0, "maxCollectionLength must be non-negative")
  require(maxCollectionElements >= 0L, "maxCollectionElements must be non-negative")
  require(maxByteLength >= 0, "maxByteLength must be non-negative")

object DecodeOptions:
  val DefaultMaxCollectionLength: Int    = 1_000_000
  val DefaultMaxCollectionElements: Long = 1_000_000L
  val DefaultMaxByteLength: Int          = 1_000_000
  val default: DecodeOptions             = DecodeOptions()
