package goedverhaal

import cats.effect.IO
import cats.implicits._
import cats.effect.concurrent.Ref
import org.scalatest.{FunSuite, Matchers}

import scala.util.control.NonFatal

class SagaFunctionalitiesSpec extends FunSuite with Matchers {
  test("should rollback") {

    def prg(ref: Ref[IO, Int]): Saga[IO, Unit] = for {
      _ <- Saga.recoverable(ref.tryUpdate(_ + 1), ref.tryUpdate(_ - 1) *> IO.unit).replicateA(1000)
      _ <- Saga.nonRecoverable[IO, Nothing](IO.raiseError(new Throwable("Error")))
    } yield ()

    def main: IO[Int] = for {
      ref <- Ref.of[IO, Int](0)
      _ <- prg(ref).run.recoverWith { case NonFatal(ex) => IO.unit }
      current <- ref.get
    } yield current

    main.unsafeRunSync() shouldBe 0

  }
}
