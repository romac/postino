package postino

import scala.collection.mutable.ArrayBuffer

private[postino] object Cobs:
  def encode(payload: Array[Byte]): Either[PostinoError, Array[Byte]] =
    val out       = ArrayBuffer[Byte](0)
    var codeIndex = 0
    var code      = 1

    var payloadIndex = 0
    while payloadIndex < payload.length do
      val byte = payload(payloadIndex)
      if byte == 0 then
        out(codeIndex) = code.toByte
        codeIndex = out.length
        out += 0
        code = 1
      else
        out += byte
        code += 1
        if code == 0xff then
          out(codeIndex) = 0xff.toByte
          if payloadIndex == payload.length - 1 then codeIndex = -1
          else
            codeIndex = out.length
            out += 0
          code = 1
      payloadIndex += 1

    if codeIndex >= 0 then out(codeIndex) = code.toByte
    out += 0
    Right(out.toArray)

  def decode(frame: Array[Byte]): Either[PostinoError, Array[Byte]] =
    if frame.isEmpty then Left(PostinoError.CobsFraming("empty frame"))
    else if frame.last != 0 then Left(PostinoError.CobsFraming("missing terminator"))
    else
      val encodedLength = frame.length - 1
      val payloadZero   = firstZeroBeforeTerminator(frame)

      if payloadZero >= 0 then Left(PostinoError.CobsZeroInPayload(payloadZero))
      else if encodedLength == 0 then Left(PostinoError.CobsFraming("empty payload"))
      else decodePayload(frame, encodedLength)

  private def decodePayload(
      frame: Array[Byte],
      encodedLength: Int
  ): Either[PostinoError, Array[Byte]] =
    val out   = ArrayBuffer.empty[Byte]
    var index = 0

    while index < encodedLength do
      val code = frame(index) & 0xff
      if code == 0 then return Left(PostinoError.CobsFraming("zero code byte"))

      index += 1
      val runLength = code - 1
      if index + runLength > encodedLength then
        return Left(PostinoError.CobsFraming("invalid run length"))

      var copied = 0
      while copied < runLength do
        out += frame(index)
        index += 1
        copied += 1

      if code != 0xff && index < encodedLength then out += 0

    Right(out.toArray)

  private def firstZeroBeforeTerminator(frame: Array[Byte]): Int =
    var index = 0
    while index < frame.length - 1 do
      if frame(index) == 0 then return index
      index += 1
    -1
