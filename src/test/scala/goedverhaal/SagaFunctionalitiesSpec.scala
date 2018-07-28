package goedverhaal

import cats.data.EitherT
import cats.effect.IO
import cats.implicits._
import cats.effect.concurrent.Ref
import org.scalatest.{FunSuite, Matchers}

import scala.util.control.NonFatal

class SagaFunctionalitiesSpec extends FunSuite with Matchers {
  test("should rollback") {

    def prg(ref: Ref[IO, Int]): Saga[IO, Unit] = for {
      _ <- Saga.recoverable(ref.tryUpdate(_ + 1))(_ => ref.tryUpdate(_ - 1) *> IO.unit).replicateA(500)
      _ <- Saga.recoverable(ref.tryUpdate(_ + 1))(_ => ref.tryUpdate(_ - 1) *> IO.unit).replicateA(500)
      _ <- Saga.nonRecoverable[IO, Nothing](IO.raiseError(new Throwable("Error")))
    } yield ()

    def main: IO[Int] = for {
      ref <- Ref.of[IO, Int](0)
      _ <- prg(ref).run.recoverWith { case NonFatal(_) => IO.unit }
      current <- ref.get
    } yield current

    main.unsafeRunSync() shouldBe 0
  }

  test("should work with decide") {
    def prg(ref: Ref[IO, Int]): EitherT[Saga[IO, ?], String, Unit] = for {
      _ <- EitherT.liftF(Saga.recoverable(ref.tryUpdate(_ + 1))(_ => ref.tryUpdate(_ - 1) *> IO.unit).replicateA(500))
      _ <- EitherT.liftF(Saga.recoverable(ref.tryUpdate(_ + 1))(_ => ref.tryUpdate(_ - 1) *> IO.unit).replicateA(500))
      _ <- EitherT.leftT[Saga[IO, ?], Unit]("Ouch error occurred")
    } yield ()

    def decider(value: Either[String, Unit], compensatingActions: List[IO[Unit]]): IO[Unit] = value match {
      case Left(_) =>
        compensatingActions.sequence *> IO.unit
      case Right(_) =>
        IO.unit
    }

    def main: IO[Int] = for {
      ref <- Ref.of[IO, Int](0)
      _ <- prg(ref).value.decide(decider).recoverWith { case NonFatal(_) => IO.unit }
      current <- ref.get
    } yield current

    main.unsafeRunSync() shouldBe 0
  }

  test("should be able to map") {
    Saga.nonRecoverable(IO.pure(2)).map(_ * 2).run.unsafeRunSync() shouldBe 4
  }

  test("single recoverable statement should be intrepreted correctly") {
    def main: IO[Int] = for {
      ref <- Ref.of[IO, Int](0)
      _ <- Saga.recoverable(IO.raiseError(new Throwable("test")) *> IO.unit)(_ => ref.tryUpdate(_ - 1) *> IO.unit).run.recoverWith { case NonFatal(_) => IO.unit }
      current <- ref.get
    } yield current

    main.unsafeRunSync() shouldBe 0
  }
}
