package postino

final case class DecodeOptions(
    maxCollectionLength: Int = DecodeOptions.DefaultMaxCollectionLength,
    maxCollectionElements: Long = DecodeOptions.DefaultMaxCollectionElements
):
  require(maxCollectionLength >= 0, "maxCollectionLength must be non-negative")
  require(maxCollectionElements >= 0L, "maxCollectionElements must be non-negative")

object DecodeOptions:
  val DefaultMaxCollectionLength: Int    = 1_000_000
  val DefaultMaxCollectionElements: Long = 1_000_000L
  val default: DecodeOptions             = DecodeOptions()
