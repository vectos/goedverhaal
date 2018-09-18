package goedverhaal

import java.util.concurrent.TimeUnit

import cats.effect.concurrent.{MVar, Ref}
import cats.effect.{Concurrent, Sync, Timer}
import cats.effect.implicits._
import cats.implicits._

import scala.concurrent.duration.FiniteDuration
import scala.util.control.NoStackTrace

final class CircuitBreaker[F[_] : Concurrent : Timer] private (state: MVar[F, CircuitBreaker.State], settings: CircuitBreaker.Settings) {
  def protect[A](effect: F[A]): F[A] =
    for {
      current <- state.read
      res <- current.protect(effect, settings, state)
    } yield res
}
object CircuitBreaker {

  protected [goedverhaal] sealed trait State {
    def protect[F[_], A](effect: F[A], settings: Settings, state: MVar[F, State])(implicit F: Concurrent[F], T: Timer[F]): F[A]
  }

  private def currentTime[A, F[_]](implicit T: Timer[F]) =
    T.clockRealTime(TimeUnit.MILLISECONDS)

  private def set[F[_] : Sync](state: MVar[F, State], actual: State): F[Unit] =
    state.take *> state.put(actual)

  private def timeoutEffect[A, F[_] : Concurrent : Timer](effect: F[A],
                                                          settings: Settings)(f: Either[Throwable, A] => F[A]): F[A] =
    Concurrent[F].attempt(Concurrent.timeout[F, A](effect, settings.callTimeout)).flatMap(f)

  protected [goedverhaal] case class Closed(failures: Int) extends State {
    override def protect[F[_], A](effect: F[A], settings: Settings, state: MVar[F, State])(implicit F: Concurrent[F], T: Timer[F]): F[A] =
      timeoutEffect(effect, settings) {
        case Right(value) =>
          set(state, Closed(0)) *> F.pure(value)
        case Left(ex) =>
          println(s"[$failures] error: $ex")
          if(failures < settings.failures) set(state, Closed(failures + 1)) *> F.raiseError(ex)
          else currentTime(T).flatMap(ts => set(state, Open(ts))) *> F.raiseError(ex)
      }
  }

  protected [goedverhaal] case class Open(startedAt: Long) extends State {

    override def protect[F[_], A](effect: F[A], settings: Settings, state: MVar[F, State])(implicit F: Concurrent[F], T: Timer[F]): F[A] = {

      def attemptClose: F[A] = timeoutEffect(effect, settings) {
        case Right(value) =>
          set(state, Closed(0)) *> F.pure(value)
        case Left(ex) =>
          currentTime.flatMap(ts => set(state, Open(ts))) *> F.raiseError(ex)
      }

      for {
        ts <- currentTime
        result <- if(ts >= startedAt + settings.resetTimeout.toMillis) set(state, HalfOpen) *> attemptClose
                  else F.raiseError(OpenException)
      } yield result
    }

  }



  protected [goedverhaal] case object HalfOpen extends State {
    override def protect[F[_], A](effect: F[A], settings: Settings, state: MVar[F, State])(implicit F: Concurrent[F], T: Timer[F]): F[A] =
      F.raiseError(OpenException)
  }

  final case object OpenException extends Exception with NoStackTrace

  final case class Settings(failures: Int, callTimeout: FiniteDuration, resetTimeout: FiniteDuration)

  def apply[F[_] : Concurrent : Timer](settings: Settings): F[CircuitBreaker[F]]=
    MVar.of(Closed(0): State).map(state => new CircuitBreaker[F](state, settings))
}
