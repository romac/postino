package postino

import scala.quoted.*

private[postino] object Macros:
  def summonFieldCodec[A: Type](using quotes: Quotes): Expr[Codec[A]] =
    import quotes.reflect.*
    Expr.summon[Codec[A]] match
      case Some(codec) => codec
      case None =>
        report.errorAndAbort(
          s"Postino.derived: missing given Codec[${Type.show[A]}] for a product field. " +
            s"Provide a Codec for that type, or use `derives Codec` if it is a case class."
        )
