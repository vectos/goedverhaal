package goedverhaal

import cats.Eq
import cats.implicits._
import cats.laws.discipline.MonadTests
import org.scalacheck.{Arbitrary, Gen}
import org.scalatest.FunSuite
import org.typelevel.discipline.scalatest.Discipline

class SagaLawsSpec extends FunSuite with Discipline {

  checkAll("Monad[Saga[Either[Throwable, ?], ?]] laws", MonadTests[Saga[Either[Throwable, ?],?]].monad[Int, Int, Int])

  implicit val arbSagaInt: Arbitrary[Saga[Either[Throwable, ?], Int]] =
    Arbitrary(Gen.choose(Int.MinValue, Int.MaxValue).map(Saga.pure[Either[Throwable, ?], Int]))
  implicit val arbSagaIntFunc: Arbitrary[Saga[Either[Throwable, ?], Int => Int]] =
    Arbitrary(Gen.choose(Int.MinValue, Int.MaxValue).map(v => Saga.pure[Either[Throwable, ?], Int => Int]((x: Int) => x * v)))
  implicit def eqSaga[A]: Eq[Saga[Either[Throwable, ?], A]] =
    Eq.instance { case (x, y) => x.run == y.run }
}
