package goedverhaal
import java.util.concurrent.Executors

import cats.effect.{IO, Timer}
import cats.implicits._
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.{FunSuite, Matchers}

import scala.concurrent.ExecutionContext
import scala.util.Random
import scala.concurrent.duration._
import scala.util.control.NonFatal

class CircuitBreakerSpec extends FunSuite with Matchers with ScalaFutures {

  implicit val timer: Timer[IO] = IO.timer(ExecutionContext.fromExecutor(Executors.newFixedThreadPool(4)))

  val callTimeout = 10.milliseconds
  val resetTimeout = 100.milliseconds

  def fail = IO.raiseError(new Throwable("err"))
  def timeout = IO.sleep(callTimeout + 10.milliseconds)
  def cb = CircuitBreaker[IO](CircuitBreaker.Settings(3, callTimeout, resetTimeout))

  test("should transition to open state after a few failures") {
    def prg = for {
      circuitBreaker <- cb
      _ <- circuitBreaker.protect(fail).handleErrorWith { case NonFatal(_) => IO.unit } replicateA 3
      _ <- circuitBreaker.protect(fail)
    } yield ()

    whenReady(prg.unsafeToFuture().failed) { _ shouldBe a [CircuitBreaker.OpenException.type] }
  }

  test("should transition to open state after a few timeouts") {
    def prg = for {
      circuitBreaker <- cb
      _ <- circuitBreaker.protect(timeout).handleErrorWith { case NonFatal(_) => IO.unit } replicateA 3
      _ <- circuitBreaker.protect(fail)
    } yield ()

    whenReady(prg.unsafeToFuture().failed) { _ shouldBe a [CircuitBreaker.OpenException.type] }
  }

  test("should transition to closed") {
    def prg = for {
      circuitBreaker <- cb
      _ <- circuitBreaker.protect(fail).handleErrorWith { case NonFatal(_) => IO.unit } replicateA 3
      _ <- timer.sleep(resetTimeout)
      _ <- circuitBreaker.protect(IO.unit)
    } yield ()

    whenReady(prg.unsafeToFuture()) { _ shouldBe(()) }

  }

}
