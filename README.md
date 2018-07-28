<img alt="Goodddd Saga bro!" src="https://nerdgeistdotcom.files.wordpress.com/2017/12/30b97ff83bf648e9dec82c58f3be35e6-vikings-tv-show-vikings-floki.jpg"/>

# goed verhaal

[![Build Status](https://api.travis-ci.org/vectos/goedverhaal.svg)](https://travis-ci.org/vectos/goedverhaal)
[![codecov.io](http://codecov.io/github/vectos/goedverhaal/coverage.svg?branch=master)](http://codecov.io/github/vectos/goedverhaal?branch=master)

Run effects which have a `cats.effect.Sync` instance. When one thing breaks at the end, all the accumulated compensating actions will be executed in order to reverse the effects.


### Motivation

I think we've been all working on a project where you have to mix API calls and database interactions in one go. So for example first going to an API, persist something in the database, fetch something from the database and then go another API and so on. What if something breaks? `Future.recoverWith` only works with *one* `Future`. This library keeps track of all the compensating actions accumulated so far.

The key is `cats.effect.Sync` which is a type class which constrains the computations to be lazy evaluated and not eagerly like a `Future`.

A real world example is a financial system, which reaches out to some 3rd party API and does it's own bookkeeping and then reaches out to another API. Thanks to a Saga we can undo the API call if the database is down for example.

Another example is a travel website where you would like to book a trip. This trip consists of booking two flights (back and forth), a hotel and a car. This would need at least 3 API calls, but what if one fails? Thanks to a Saga we can rollback all the actions which were succesful before.

### Minimal example

```scala
import cats.effect.IO
import cats.implicits._
import cats.effect.concurrent.Ref
import goedverhaal._
import scala.util.control.NonFatal

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

```

The outcome of `main` will be zero, as the `prg` will crash at the end. The first action will increase the `Ref[IO, Int]` to `500` and the second action by another `500`, but since it crashes the compensating actions will roll it back to `0`.

### EitherT

What if you have a computation which has to short circuit ? When you use pure functions you can use `Either` and `flatMap` over that. When it's effectful, you can use `EitherT`. It turns out that you combine `EitherT` and `Saga` together:

```scala
import cats.data.EitherT
import cats.effect.IO
import cats.implicits._
import cats.effect.concurrent.Ref
import goedverhaal._
import scala.util.control.NonFatal


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
```

The outcome of `main` will be zero, as the `prg` will be `Left` at the end. `decide` is a function on `Saga` which is defined as:

`def decide[B](f: (A, List[F[Unit]]) => F[B])(implicit F: Sync[F]): F[B]`

So we pass in the function `def decider(value: Either[String, Unit], compensatingActions: List[IO[Unit]]): IO[Unit]` to decide over the output of the `EitherT` computation. Since we got the stack of compensating actions in our hands we can decide to rollback when it meets a special condition. In this case we rollback when we receive a `Left` value.

If any `IO` effect would cause a `Exception` in between, it will never reach the `f` function as defined as above. It will execute all the compensating actions accumulated up till that point.

### What does 'goed verhaal' mean?

*Goed verhaal* is dutch for a *good story*. This library resides around this concept as we'll introduce a `Saga` Monad. Sagas are stories mostly about ancient Nordic and Germanic history, early Viking voyages, the battles that took place during the voyages, and migration to Iceland and of feuds between Icelandic families. This is why the library is called *good story* (phun intended).

A Saga is also a concept which comes from the DDD (Domain Driven Design) landscape. There they are long running processes which also need compensating actions, but are persistable. The scope of this library is not to offer that. It's only for short running processes which need compensating actions.
