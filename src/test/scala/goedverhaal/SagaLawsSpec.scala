package goedverhaal

import cats.Eq
import cats.effect._
import cats.effect.concurrent.Ref
import cats.implicits._
import cats.laws.discipline.MonadTests
import org.scalacheck.{Arbitrary, Gen}
import org.scalatest.{FunSuite, Matchers}
import org.typelevel.discipline.scalatest.Discipline

import scala.util.control.NonFatal

class SagaFunctionalitiesSpec extends FunSuite with Matchers {
  test("should rollback") {

    def prg(ref: Ref[IO, Int]): Saga[IO, Unit] = for {
      _ <- Saga.recoverable(ref.tryUpdate(_ + 1), ref.tryUpdate(_ - 1) *> IO.unit).replicateA(1000)
      _ <- Saga.nonRecoverable[IO, Nothing](IO.raiseError(new Throwable("Error")))
    } yield ()

    def main = for {
      ref <- Ref.of[IO, Int](0)
      _ <- prg(ref).run.recoverWith { case NonFatal(ex) => IO.unit }
      current <- ref.get
    } yield current

    main.unsafeRunSync() shouldBe 0

  }
}

class SagaLawsSpec extends FunSuite with Discipline {

  checkAll("Monad[Saga[Either[Throwable, ?], ?]] laws", MonadTests[Saga[Either[Throwable, ?],?]].monad[Int, Int, Int])

  implicit val arbSagaInt: Arbitrary[Saga[Either[Throwable, ?], Int]] =
    Arbitrary(Gen.choose(Int.MinValue, Int.MaxValue).map(Saga.pure[Either[Throwable, ?], Int]))
  implicit val arbSagaIntFunc: Arbitrary[Saga[Either[Throwable, ?], Int => Int]] =
    Arbitrary(Gen.choose(Int.MinValue, Int.MaxValue).map(v => Saga.pure[Either[Throwable, ?], Int => Int]((x: Int) => x * v)))
  implicit def eqSaga[A]: Eq[Saga[Either[Throwable, ?], A]] =
    Eq.instance { case (x, y) => x.run == y.run }
}
