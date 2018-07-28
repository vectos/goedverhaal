package goedverhaal

import cats._
import cats.effect.Sync
import cats.implicits._

import scala.util.control.NonFatal

/**
  * A Saga is a Monad which can execute any F[_] type which has a `cats.effect.Sync` instance.
  * If there occurs any error it will execute the given compensating action.
  * You could also call this the 'do-undo' monad as it keeps track of undo actions.
  *
  * @tparam F The effect type, this should have a `cats.effect.Sync` instance in order to run
  * @tparam A The output type
  */
sealed abstract class Saga[F[_], A] {

  def flatMap[B](f: A => Saga[F, B]): Saga[F, B] =
    Saga.Bind(this, a => f(a))

  def map[B](f: A => B): Saga[F, B] =
    Saga.Bind(this, (a: A) => Saga.Pure(f(a)))

  /**
    * Execute all the actions inside the Saga. If any error occurs, it will compensate the actions
    * @param F The effect type, this should have a `cats.effect.Sync` instance in order to run
    * @return The output value of the Saga
    */
  def run(implicit F: Sync[F]): F[A] =
    decide { case (a, _) => F.pure(a) }

  /**
    * Execute all the actions inside the Saga. If any error occurs, it will compensate the actions
    *
    * However you'll be able to decide what to do if manages to run the computation successfully with a function.
    * This function receives the value which is returned by the computation, plus a stack of the compensating actions
    * which are collected over time by executing the Saga. This could be useful when you use an `EitherT` for example
    * and you want to rollback all the effects when a Left comes out of the computation.
    *
    * @param f The decide function which allows you to execute the compensating actions
    * @param F The effect type, this should have a `cats.effect.Sync` instance in order to run
    * @tparam B The output value after you execute the `f` function
    * @return The output value after executing the `f` function

    * @return
    */
  def decide[B](f: (A, List[F[Unit]]) => F[B])(implicit F: Sync[F]): F[B] = {
    def loop[X](step: Saga[F, X], stack: List[F[Unit]]): F[(A, List[F[Unit]])] = step match {
      case Saga.Pure(a) =>
        F.pure(a.asInstanceOf[A] -> stack)
      case Saga.Next(action, _) =>
        action.asInstanceOf[F[(A, List[F[Unit]])]]
      case Saga.Bind(fa, bind) => fa match {
        case Saga.Pure(a) =>
          loop(bind(a), stack)
        case Saga.Next(action, compensate) =>
          F.onError(F.flatMap(action) { x => loop(bind(x), compensate(x) :: stack) }) {
            case Saga.Halted(_) => F.unit
            case NonFatal(ex) => stack.sequence *> F.raiseError(Saga.Halted(ex))
          }
        case Saga.Bind(fb, bb) =>
          loop(Saga.Bind(fb, (b: Any) => Saga.Bind(bb(b), bind)), stack)
      }
    }

    F.flatMap(loop(this, Nil)) { case (res, stack) => f(res, stack) }
  }
}

object Saga {

  final case class Halted(throwable: Throwable) extends Throwable

  protected [goedverhaal] case class Pure[F[_], A](action: A) extends Saga[F, A]
  protected [goedverhaal] case class Next[F[_], A](action: F[A], compensate: A => F[Unit]) extends Saga[F, A]
  protected [goedverhaal] case class Bind[F[_], A, B](fa: Saga[F, A], f: A => Saga[F, B]) extends Saga[F, B]

  /**
    * Lifts a value inside the Saga
    * @param value The value
    * @param F The effect type, this should have a `cats.effect.Sync` instance in order to run
    * @tparam A The value type
    * @return A Saga with this value
    */
  def pure[F[_], A](value: A): Saga[F, A] =
    Pure(value)

  /**
    * Lifts a 'do' computation and 'undo' computation inside the Saga
    *
    * @param comp The do computation
    * @param rollback The undo computation
    * @param F The effect type, this should have a `cats.effect.Sync` instance in order to run
    * @tparam A The value type
    * @return A Saga
    */
  def recoverable[F[_], A](comp: F[A])(rollback: A => F[Unit]): Saga[F, A] =
    Next(comp, rollback)

  /**
    * Lifts a 'do' computation, but it has no undo computation.
    *
    * @param comp The do computation
    * @param F The effect type, this should have a `cats.effect.Sync` instance in order to run
    * @tparam A The value type
    * @return A Saga
    */
  def nonRecoverable[F[_], A](comp: F[A])(implicit F: Applicative[F]): Saga[F, A] =
    Next(comp, _ => F.unit)

  implicit def monad[F[_]]: Monad[Saga[F, ?]] = new Monad[Saga[F, ?]] {
    override def pure[A](x: A): Saga[F, A] = Saga.pure(x)

    override def flatMap[A, B](fa: Saga[F, A])(f: A => Saga[F, B]): Saga[F, B] = fa.flatMap(f)

    override def tailRecM[A, B](a: A)(f: A => Saga[F, Either[A, B]]): Saga[F, B] = flatMap(f(a)) {
      case Left(aa) => tailRecM(aa)(f)
      case Right(b) => pure(b)
    }
  }
}

