package goedverhaal

import cats.effect.concurrent.Ref
import cats.effect.{Concurrent, Timer}
import cats.implicits._
import goedverhaal.CircuitBreaker.OpenException

import scala.concurrent.duration.FiniteDuration
import scala.util.control.NoStackTrace

final class CircuitBreaker[F[_] : Concurrent : Timer] private (state: Ref[F, CircuitBreakerState], settings: CircuitBreaker.Settings) {

  private val F = implicitly[Concurrent[F]]

  def protect[A](effect: F[A]): F[A] =
    for {
      current <- state.get
      res <- current match {
        case CircuitBreakerState.HalfOpen         => F.raiseError(OpenException)
        case CircuitBreakerState.Closed(_) => timeoutEffect(effect, settings) {
          case Left(ex) => state.update(_.incrementFailures(settings)) *> F.raiseError(ex)
          case Right(value) => state.update(_.reset) *> F.pure(value)
        }
        case CircuitBreakerState.Open(expiresAt)  =>
          if(expiresAt < System.currentTimeMillis()) state.update(_ => CircuitBreakerState.HalfOpen) *> attemptClose(effect)
          else F.raiseError(OpenException)
      }
    } yield res

  private def attemptClose[A](effect: F[A]): F[A] = timeoutEffect(effect, settings) {
    case Right(value) =>
      state.update(_.reset) *> F.pure(value)
    case Left(ex) =>
      state.update(_ => CircuitBreakerState.Open(System.currentTimeMillis() + settings.resetTimeout.toMillis)) *> F.raiseError(ex)
  }

  private def timeoutEffect[A](effect: F[A], settings: CircuitBreaker.Settings)(f: Either[Throwable, A] => F[A]): F[A] =
    Concurrent[F].attempt(Concurrent.timeout[F, A](effect, settings.callTimeout)).flatMap(f)
}


protected [goedverhaal] sealed trait CircuitBreakerState {
  def reset: CircuitBreakerState
  def incrementFailures(settings: CircuitBreaker.Settings): CircuitBreakerState
}
protected [goedverhaal] object CircuitBreakerState {
  final case object HalfOpen extends CircuitBreakerState {
    def reset: CircuitBreakerState = Closed(0)
    def incrementFailures(settings: CircuitBreaker.Settings): CircuitBreakerState = this
  }
  final case class Closed(failures: Int) extends CircuitBreakerState {
    def reset: CircuitBreakerState = Closed(0)
    def incrementFailures(settings: CircuitBreaker.Settings): CircuitBreakerState =
      if(failures + 1 == settings.failures) Open(System.currentTimeMillis() + settings.resetTimeout.toMillis)
      else Closed(failures + 1)
  }
  final case class Open(expiresAt: Long) extends CircuitBreakerState {
    def reset: CircuitBreakerState = Closed(0)
    def incrementFailures(settings: CircuitBreaker.Settings): CircuitBreakerState = this
  }
}

object CircuitBreaker {
  final case object OpenException extends Exception with NoStackTrace

  final case class Settings(failures: Int, callTimeout: FiniteDuration, resetTimeout: FiniteDuration)

  def apply[F[_] : Concurrent : Timer](settings: Settings): F[CircuitBreaker[F]]=
    Ref.of(CircuitBreakerState.Closed(0) : CircuitBreakerState).map(state => new CircuitBreaker[F](state, settings))
}
