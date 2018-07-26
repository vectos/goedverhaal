goed verhaal
---

A compensating (do-undo) effect Monad. Run effects which have a `MonadError` instance, when they fail all the accumulated compensating actions will be executed in order to reverse the effects.


### What does 'goed verhaal' mean?

*Goed verhaal* is dutch for a *good story*. This library resides around this concept as we'll introduce a `Saga` Monad. Sagas are stories mostly about ancient Nordic and Germanic history, early Viking voyages, the battles that took place during the voyages, and migration to Iceland and of feuds between Icelandic families. This is why the library is called *good story* (phun intended).

A Saga is also a concept which comes from the DDD (Domain Driven Design) landscape. There they are long running processes which also need compensating actions, but are persistable. The scope of this library is not to offer that. It's only for short running processes which need compensating actions.

### Why?

Real world examples? Well I've been working on a few projects where people interleave I/O interactions like first going to an API, persist something in the database, fetch something from the database and then go another API and so on. What if something breaks? `Future.recoverWith` only works with *one* `Future`.

A real world example is a financial system, which reaches out to some 3rd party API and does it's own bookkeeping and then reaches out to another API. Thanks to a Saga we can undo the API call if the database is down for example.

Another example is a travel website where you would like to book a trip. This trip consists of booking two flights (back and forth), a hotel and a car. This would need at least 3 API calls, but what if one fails? Thanks to a Saga we can rollback all the actions which were succesful before.

### Minimal example


```
import cats.effect.IO
import cats.implicits._
import cats.effect.concurrent.Ref
import goedverhaal._
import scala.util.control.NonFatal


def main: IO[Int] = for {
  ref <- Ref.of[IO, Int](0)
  _ <- prg(ref).run.recoverWith { case NonFatal(ex) => IO.unit }
  current <- ref.get
} yield current

def prg(ref: Ref[IO, Int]): Saga[IO, Unit] = for {
  _ <- Saga.recoverable(ref.tryUpdate(_ + 1), ref.tryUpdate(_ - 1) *> IO.unit).replicateA(1000)
  _ <- Saga.nonRecoverable[IO, Nothing](IO.raiseError(new Throwable("Error")))
} yield ()

```

The outcome of `main` will be zero, as the `prg` will crash at the end. The first action will increase the `Ref[IO, Int]` to `1000`, but since it crashes the compensating action will roll it back to `0`.


