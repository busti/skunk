// Copyright (c) 2018-2021 by Rob Norris
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package skunk

import cats._
import cats.data._
import cats.syntax.all._
import org.typelevel.twiddles.TwiddleSyntax
import skunk.data.{Arr, Encoded, Type}
import skunk.util.Twiddler

/**
 * Symmetric encoder and decoder of Postgres text-format data to and from Scala types.
 * @group Codecs
 */
trait Codec[A] extends Encoder[A] with Decoder[A] { outer =>

  /** Forget this value is a `Codec` and treat it as an `Encoder`. */
  def asEncoder: Encoder[A] = this

  /** Forget this value is a `Codec` and treat it as a `Decoder`. */
  def asDecoder: Decoder[A] = this

  /** `Codec` is semigroupal: a pair of codecs make a codec for a pair. */
  def product[B](fb: Codec[B]): Codec[(A, B)] =
    new Codec[(A, B)] {
      private val pe = outer.asEncoder product fb.asEncoder
      private val pd = outer.asDecoder product fb.asDecoder
      override def encode(ab: (A, B)): List[Option[Encoded]] = pe.encode(ab)
      override def decode(offset: Int, ss: List[Option[String]]):Either[Decoder.Error, (A, B)] = pd.decode(offset, ss)
      override val sql: State[Int, String]   = (outer.sql, fb.sql).mapN((a, b) => s"$a, $b")
      override val types: List[Type]         = outer.types ++ fb.types
    }

  /** Shorthand for `product`. Note: consider using `a *: b *: c` instead of `a ~ b ~ c`. */
  def ~[B](fb: Codec[B]): Codec[A ~ B] =
    product(fb)

  /** Contramap inputs from, and map outputs to, a new type `B`, yielding a `Codec[B]`. */
  def imap[B](f: A => B)(g: B => A): Codec[B] = new Codec[B] {
    override def encode(b: B): List[Option[Encoded]] = outer.encode(g(b))
    override def decode(offset: Int, ss: List[Option[String]]): Either[Decoder.Error, B] = outer.decode(offset, ss).map(f)
    override val sql: State[Int, String]   = outer.sql
    override val types: List[Type]         = outer.types
  }

  /** Contramap inputs from, and map decoded results to a new type `B` or an error, yielding a `Codec[B]`. */
  def eimap[B](f: A => Either[String, B])(g: B => A): Codec[B] = new Codec[B] {
    override def encode(b: B): List[Option[Encoded]] = outer.encode(g(b))
    override def decode(offset: Int, ss: List[Option[String]]): Either[Decoder.Error, B] =
      outer.decode(offset, ss).flatMap(a => f(a).leftMap(Decoder.Error(offset, length, _)))
    override val sql: State[Int, String]   = outer.sql
    override val types: List[Type]         = outer.types
  }

  /** Adapt this `Codec` from twiddle-list type A to isomorphic case-class type `B`. */
  @deprecated("Use (a *: b *: c).to[CaseClass] instead of (a ~ b ~ c).gimap[CaseClass]", "0.6")
  def gimap[B](implicit ev: Twiddler.Aux[B, A]): Codec[B] =
    imap(ev.from)(ev.to)

  /** Lift this `Codec` into `Option`, where `None` is mapped to and from a vector of `NULL`. */
  override def opt: Codec[Option[A]] =
    new Codec[Option[A]] {
      override def encode(oa: Option[A]): List[Option[Encoded]] = oa.fold(empty)(outer.encode)
      override def decode(offset: Int, ss: List[Option[String]]): Either[Decoder.Error, Option[A]] =
        if (ss.forall(_.isEmpty)) Right(None)
        else outer.decode(offset, ss).map(Some(_))
      override val sql: State[Int, String]   = outer.sql
      override val types: List[Type]         = outer.types
    }

  override def redacted: Codec[A] = {
    val red0 = super.redacted
    new Codec[A] {
      override def encode(a: A): List[Option[Encoded]] = red0.encode(a)
      override def decode(offset: Int, ss: List[Option[String]]): Either[Decoder.Error, A] = outer.decode(offset, ss)
      override val sql: State[Int, String] = outer.sql
      override val types: List[Type] = outer.types
    }
  }

  override def toString: String =
    s"Codec(${types.mkString(", ")})"

}

/** @group Companions */
object Codec extends TwiddleSyntax[Codec] {

  /** @group Constructors */
  def apply[A](
    encode0: A => List[Option[String]],
    decode0: (Int, List[Option[String]]) => Either[Decoder.Error, A],
    oids0:   List[Type]
  ): Codec[A] =
    new Codec[A] {
      override def encode(a: A): List[Option[Encoded]] = encode0(a).map(_.map(Encoded(_)))
      override def decode(offset: Int, ss: List[Option[String]]): Either[Decoder.Error, A] = decode0(offset, ss)
      override val types: List[Type] = oids0
      override val sql: State[Int, String] = State { (n: Int) =>
        val len = types.length
        (n + len, (n until n + len).map(i => s"$$$i").mkString(", "))
      }
    }

  /** @group Constructors */
  def simple[A](encode: A => String, decode: String => Either[String, A], oid: Type): Codec[A] =
    apply(
      a => List(Some(encode(a))),
      (n, ss) => ss match {
        case Some(s) :: Nil => decode(s).leftMap(Decoder.Error(n, 1, _))
        case None    :: Nil => Left(Decoder.Error(n, 1, s"Unexpected NULL value in non-optional column."))
        case _              => Left(Decoder.Error(n, 1, s"Expected one input value to decode, got ${ss.length}."))
      },
      List(oid)
    )

  /** @group Constructors */
  def array[A](encode: A => String, decode: String => Either[String, A], oid: Type): Codec[Arr[A]] =
    Codec.simple(_.encode(encode), Arr.parseWith(decode), oid)

  /**
   * Codec is an invariant semgroupal functor.
   * @group Typeclass Instances
   */
  implicit val InvariantSemigroupalCodec: InvariantSemigroupal[Codec] =
    new InvariantSemigroupal[Codec] {
      override def imap[A, B](fa: Codec[A])(f: A => B)(g: B => A): Codec[B] = fa.imap(f)(g)
      override def product[A, B](fa: Codec[A],fb: Codec[B]): Codec[(A, B)]= fa product fb
    }

}
