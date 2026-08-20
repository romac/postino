package postino.circe

import postino.{Codec as PostinoCodec, FixedArray, U16, U32, U64, U128}
import _root_.io.circe.{ACursor, Codec as CirceCodec, Decoder as CirceDecoder, DecodingFailure}
import _root_.io.circe.{Encoder as CirceEncoder, Json}

import scala.collection.immutable.{SortedMap, SortedSet}
import scala.compiletime.{constValue, erasedValue, summonInline}
import scala.deriving.Mirror
import scala.reflect.ClassTag
import scala.util.control.NonFatal

object PostinoCirce:
  inline def toCirce[A](using PostinoCodec[A]): CirceCodec[A] =
    val schema = summonInline[PostinoCirceSchema[A]]
    CirceCodec.from(
      CirceDecoder.instance(cursor => schema.decode(cursor)),
      CirceEncoder.instance(schema.encode)
    )

trait PostinoCirceSchema[A]:
  def encode(value: A): Json
  def decode(cursor: ACursor): CirceDecoder.Result[A]

object PostinoCirceSchema extends LowPriorityPostinoCirceSchemas:
  def apply[A](using schema: PostinoCirceSchema[A]): PostinoCirceSchema[A] =
    schema

  given PostinoCirceSchema[Unit] with
    def encode(value: Unit): Json =
      Json.obj()

    def decode(cursor: ACursor): CirceDecoder.Result[Unit] =
      Right(())

  given PostinoCirceSchema[Boolean] = fromCirce[Boolean]
  given PostinoCirceSchema[Char]    = fromCirce[Char]
  given PostinoCirceSchema[Byte]    = fromCirce[Byte]
  given PostinoCirceSchema[Short]   = fromCirce[Short]
  given PostinoCirceSchema[Int]     = fromCirce[Int]
  given PostinoCirceSchema[Long]    = fromCirce[Long]
  given PostinoCirceSchema[BigInt]  = fromCirce[BigInt]
  given PostinoCirceSchema[Float]   = fromCirce[Float]
  given PostinoCirceSchema[Double]  = fromCirce[Double]
  given PostinoCirceSchema[String]  = fromCirce[String]

  given PostinoCirceSchema[Array[Byte]] with
    def encode(value: Array[Byte]): Json =
      Json.arr(value.toSeq.map(byte => Json.fromInt(byte & 0xff))*)

    def decode(cursor: ACursor): CirceDecoder.Result[Array[Byte]] =
      cursor
        .as[Vector[Int]]
        .flatMap: values =>
          val bytes                          = new Array[Byte](values.length)
          var index: Int                     = 0
          var error: Option[DecodingFailure] = None
          while index < values.length && error.isEmpty do
            val value = values(index)
            if value < 0 || value > 0xff then
              error = Some(DecodingFailure(s"byte value $value is outside u8", cursor.history))
            else bytes(index) = value.toByte
            index += 1
          error match
            case Some(error) => Left(error)
            case None        => Right(bytes)

  given PostinoCirceSchema[U16] with
    def encode(value: U16): Json =
      Json.fromInt(value.toInt)

    def decode(cursor: ACursor): CirceDecoder.Result[U16] =
      cursor.as[Int].flatMap(value => fromPostino("u16", cursor, U16.fromInt(value)))

  given PostinoCirceSchema[U32] with
    def encode(value: U32): Json =
      Json.fromLong(value.toLong)

    def decode(cursor: ACursor): CirceDecoder.Result[U32] =
      cursor.as[Long].flatMap(value => fromPostino("u32", cursor, U32.fromLong(value)))

  given PostinoCirceSchema[U64] with
    def encode(value: U64): Json =
      Json.fromBigInt(value.toBigInt)

    def decode(cursor: ACursor): CirceDecoder.Result[U64] =
      cursor.as[BigInt].flatMap(value => fromPostino("u64", cursor, U64.fromBigInt(value)))

  given PostinoCirceSchema[U128] with
    def encode(value: U128): Json =
      Json.fromBigInt(value.toBigInt)

    def decode(cursor: ACursor): CirceDecoder.Result[U128] =
      cursor.as[BigInt].flatMap(value => fromPostino("u128", cursor, U128.fromBigInt(value)))

  private def fromCirce[A](using
      encoder: CirceEncoder[A],
      decoder: CirceDecoder[A]
  ): PostinoCirceSchema[A] =
    new PostinoCirceSchema[A]:
      def encode(value: A): Json =
        encoder(value)

      def decode(cursor: ACursor): CirceDecoder.Result[A] =
        cursor.as[A](using decoder)

  private def fromPostino[A](
      label: String,
      cursor: ACursor,
      value: Either[postino.PostinoError, A]
  ): CirceDecoder.Result[A] =
    value.left.map(error => DecodingFailure(s"invalid $label: ${error.message}", cursor.history))

trait LowPriorityPostinoCirceSchemas:
  inline given derived[A](using mirror: Mirror.Of[A]): PostinoCirceSchema[A] =
    inline mirror match
      case product: Mirror.ProductOf[A] => productSchema[A](product)
      case sum: Mirror.SumOf[A]         => sumSchema[A](sum)

  given optionSchema[A](using schema: PostinoCirceSchema[A]): PostinoCirceSchema[Option[A]] with
    def encode(value: Option[A]): Json =
      value match
        case Some(inner) => schema.encode(inner)
        case None        => Json.Null

    def decode(cursor: ACursor): CirceDecoder.Result[Option[A]] =
      if cursor.focus.exists(_.isNull) then Right(None)
      else schema.decode(cursor).map(Some(_))

  given listSchema[A](using schema: PostinoCirceSchema[A]): PostinoCirceSchema[List[A]] with
    def encode(value: List[A]): Json =
      encodeIterable(value, schema)

    def decode(cursor: ACursor): CirceDecoder.Result[List[A]] =
      decodeVector(cursor, schema).map(_.toList)

  given vectorSchema[A](using schema: PostinoCirceSchema[A]): PostinoCirceSchema[Vector[A]] with
    def encode(value: Vector[A]): Json =
      encodeIterable(value, schema)

    def decode(cursor: ACursor): CirceDecoder.Result[Vector[A]] =
      decodeVector(cursor, schema)

  given arraySchema[A](using
      schema: PostinoCirceSchema[A],
      classTag: ClassTag[A]
  ): PostinoCirceSchema[Array[A]] with
    def encode(value: Array[A]): Json =
      encodeIterable(value, schema)

    def decode(cursor: ACursor): CirceDecoder.Result[Array[A]] =
      decodeVector(cursor, schema).map(_.toArray)

  given fixedArraySchema[A, N <: Int](using
      schema: PostinoCirceSchema[A],
      length: ValueOf[N]
  ): PostinoCirceSchema[FixedArray[A, N]] with
    def encode(value: FixedArray[A, N]): Json =
      encodeIterable(value, schema)

    def decode(cursor: ACursor): CirceDecoder.Result[FixedArray[A, N]] =
      decodeVector(cursor, schema)
        .flatMap: values =>
          FixedArray
            .from[A, N](values)
            .left
            .map(error => DecodingFailure(error.message, cursor.history))

  given mapSchema[K, V](using
      keySchema: PostinoCirceSchema[K],
      valueSchema: PostinoCirceSchema[V]
  ): PostinoCirceSchema[Map[K, V]] with
    def encode(value: Map[K, V]): Json =
      encodeEntries(value, keySchema, valueSchema)

    def decode(cursor: ACursor): CirceDecoder.Result[Map[K, V]] =
      decodeEntries(cursor, keySchema, valueSchema).map(_.toMap)

  given sortedMapSchema[K, V](using
      keySchema: PostinoCirceSchema[K],
      valueSchema: PostinoCirceSchema[V],
      ordering: Ordering[K]
  ): PostinoCirceSchema[SortedMap[K, V]] with
    def encode(value: SortedMap[K, V]): Json =
      encodeEntries(value, keySchema, valueSchema)

    def decode(cursor: ACursor): CirceDecoder.Result[SortedMap[K, V]] =
      decodeEntries(cursor, keySchema, valueSchema)
        .map(entries => SortedMap.from(entries)(using ordering))

  given sortedSetSchema[A](using
      schema: PostinoCirceSchema[A],
      ordering: Ordering[A]
  ): PostinoCirceSchema[SortedSet[A]] with
    def encode(value: SortedSet[A]): Json =
      encodeIterable(value, schema)

    def decode(cursor: ACursor): CirceDecoder.Result[SortedSet[A]] =
      decodeVector(cursor, schema).map(values => SortedSet.from(values)(using ordering))

  private inline def productSchema[A](mirror: Mirror.ProductOf[A]): PostinoCirceSchema[A] =
    val productName = constValue[mirror.MirroredLabel].toString
    val fieldNames  = Vector.from(summonLabels[mirror.MirroredElemLabels])
    val fieldSchemas =
      Vector
        .from(summonSchemas[mirror.MirroredElemTypes])
        .asInstanceOf[Vector[PostinoCirceSchema[Any]]]
    productSchema(mirror, productName, fieldNames, fieldSchemas)

  private def productSchema[A](
      mirror: Mirror.ProductOf[A],
      productName: String,
      fieldNames: Vector[String],
      fieldSchemas: Vector[PostinoCirceSchema[Any]]
  ): PostinoCirceSchema[A] =
    new PostinoCirceSchema[A]:
      def encode(value: A): Json =
        if value == null then Json.Null
        else
          val product = value.asInstanceOf[Product]
          val fields =
            fieldNames.indices.map: index =>
              fieldNames(index) -> fieldSchemas(index).encode(product.productElement(index))
          Json.obj(fields*)

      def decode(cursor: ACursor): CirceDecoder.Result[A] =
        val values = new Array[Any](fieldSchemas.length)
        var index  = 0
        while index < fieldSchemas.length do
          fieldSchemas(index).decode(cursor.downField(fieldNames(index))) match
            case Right(value) =>
              values(index) = value
              index += 1
            case Left(error) => return Left(error)

        try Right(mirror.fromProduct(Tuple.fromArray(values)))
        catch
          case NonFatal(error) =>
            val reason = Option(error.getMessage).getOrElse(error.getClass.getName)
            Left(DecodingFailure(s"failed to construct $productName: $reason", cursor.history))

  private inline def sumSchema[A](mirror: Mirror.SumOf[A]): PostinoCirceSchema[A] =
    val variantLabels = Vector.from(summonLabels[mirror.MirroredElemLabels])
    val variantSchemas = Vector
      .from(summonSchemas[mirror.MirroredElemTypes])
      .asInstanceOf[Vector[PostinoCirceSchema[Any]]]
    sumSchema(mirror, variantLabels, variantSchemas)

  private def sumSchema[A](
      mirror: Mirror.SumOf[A],
      variantLabels: Vector[String],
      variantSchemas: Vector[PostinoCirceSchema[Any]]
  ): PostinoCirceSchema[A] =
    new PostinoCirceSchema[A]:
      def encode(value: A): Json =
        if value == null then Json.Null
        else
          val ordinal = mirror.ordinal(value)
          if ordinal < 0 || ordinal >= variantLabels.length then Json.Null
          else
            Json.obj(
              "tag"   -> Json.fromString(variantLabels(ordinal)),
              "value" -> variantSchemas(ordinal).encode(value.asInstanceOf[Any])
            )

      def decode(cursor: ACursor): CirceDecoder.Result[A] =
        cursor
          .downField("tag")
          .as[String]
          .flatMap: tag =>
            variantLabels.indexOf(tag) match
              case -1 =>
                Left(DecodingFailure(s"unknown Postino Circe variant tag $tag", cursor.history))
              case index =>
                variantSchemas(index)
                  .decode(cursor.downField("value"))
                  .map(_.asInstanceOf[A])

  private inline def summonLabels[Labels <: Tuple]: List[String] =
    inline erasedValue[Labels] match
      case _: EmptyTuple => Nil
      case _: (label *: rest) =>
        constValue[label].toString :: summonLabels[rest]

  private inline def summonSchemas[Values <: Tuple]: List[PostinoCirceSchema[?]] =
    inline erasedValue[Values] match
      case _: EmptyTuple => Nil
      case _: (value *: rest) =>
        summonInline[PostinoCirceSchema[value]] :: summonSchemas[rest]

  private def encodeIterable[A](values: Iterable[A], schema: PostinoCirceSchema[A]): Json =
    Json.arr(values.map(schema.encode).toSeq*)

  private def decodeVector[A](
      cursor: ACursor,
      schema: PostinoCirceSchema[A]
  ): CirceDecoder.Result[Vector[A]] =
    cursor
      .as[Vector[Json]]
      .flatMap: values =>
        val builder                        = Vector.newBuilder[A]
        var error: Option[DecodingFailure] = None
        val iterator                       = values.iterator
        while iterator.hasNext && error.isEmpty do
          val value = iterator.next()
          schema.decode(value.hcursor) match
            case Right(decoded) => builder += decoded
            case Left(cause)    => error = Some(cause)
        error match
          case Some(error) => Left(error)
          case None        => Right(builder.result())

  private def encodeEntries[K, V](
      values: Iterable[(K, V)],
      keySchema: PostinoCirceSchema[K],
      valueSchema: PostinoCirceSchema[V]
  ): Json =
    Json.arr(
      values
        .map: (key, value) =>
          Json.obj(
            "key"   -> keySchema.encode(key),
            "value" -> valueSchema.encode(value)
          )
        .toSeq*
    )

  private def decodeEntries[K, V](
      cursor: ACursor,
      keySchema: PostinoCirceSchema[K],
      valueSchema: PostinoCirceSchema[V]
  ): CirceDecoder.Result[Vector[(K, V)]] =
    cursor
      .as[Vector[Json]]
      .flatMap: values =>
        val builder                        = Vector.newBuilder[(K, V)]
        var error: Option[DecodingFailure] = None
        val iterator                       = values.iterator
        while iterator.hasNext && error.isEmpty do
          val value = iterator.next()
          val entry = value.hcursor
          (
            keySchema.decode(entry.downField("key")),
            valueSchema.decode(entry.downField("value"))
          ) match
            case (Right(key), Right(decoded)) => builder += ((key, decoded))
            case (Left(cause), _)             => error = Some(cause)
            case (_, Left(cause))             => error = Some(cause)
        error match
          case Some(error) => Left(error)
          case None        => Right(builder.result())
