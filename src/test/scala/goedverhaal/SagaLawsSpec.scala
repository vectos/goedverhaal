package goedverhaal

import cats.Eq
import cats.effect.IO
import cats.implicits._
import cats.laws.discipline.MonadTests
import org.scalacheck.{Arbitrary, Gen}
import org.scalatest.FunSuite
import org.typelevel.discipline.scalatest.Discipline

class SagaLawsSpec extends FunSuite with Discipline {

  checkAll("Monad[Saga[IO, ?]] laws", MonadTests[Saga[IO,?]].monad[Int, Int, Int])

  implicit def arbSagaInt: Arbitrary[Saga[IO, Int]] =
    Arbitrary(Gen.choose(Int.MinValue, Int.MaxValue).map(Saga.pure[IO, Int]))

  implicit def arbSagaIntFunc: Arbitrary[Saga[IO, Int => Int]] =
    Arbitrary(Gen.choose(Int.MinValue, Int.MaxValue).map(v => Saga.pure[IO, Int => Int]((x: Int) => x * v)))

  implicit def eqSaga[A]: Eq[Saga[IO, A]] =
    Eq.instance { case (x, y) => x.run.unsafeRunSync() == y.run.unsafeRunSync() }
}
