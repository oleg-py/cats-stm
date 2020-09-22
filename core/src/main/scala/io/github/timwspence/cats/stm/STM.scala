package io.github.timwspence.cats.stm

import java.util.concurrent.atomic.AtomicLong

import cats.implicits._
import cats.{Monad, Monoid, MonoidK}
import cats.effect.Concurrent
import cats.effect.implicits._
import cats.effect.concurrent.Deferred

import scala.annotation.tailrec

import io.github.timwspence.cats.stm.STM.internal._
import java.util.concurrent.atomic.AtomicReference

/**
  * Monad representing transactions involving one or more
  * `TVar`s.
  *
  * This design was inspired by [Beautiful Concurrency](https://www.microsoft.com/en-us/research/wp-content/uploads/2016/02/beautiful.pdf) and informed by ZIO
  * which has a common origin in that paper via the [stm package](http://hackage.haskell.org/package/stm).
  */
sealed abstract class STM[+A] {

  /**
    * Functor map on `STM`.
    */
  final def map[B](f: A => B): STM[B] = Bind(this, f.andThen(Pure(_)))

  /**
    * Monadic bind on `STM`.
    */
  final def flatMap[B](f: A => STM[B]): STM[B] = Bind(this, f)

  /**
    * Try an alternative `STM` action if this one retries.
    */
  final def orElse[B >: A](fallback: STM[B]): STM[B] = OrElse(this, fallback)

  /**
    * Run transaction atomically
    */
  def atomically[F[+_]: Concurrent]: F[A] = STM.atomically[F](this)

}

//TODO find non-thread blocking alternative to synchronized or else shift to
//blocking pool for it
object STM {

  val debug: AtomicReference[Map[TxId, Set[TVarId]]] = new AtomicReference(Map.empty)

  /**
    * Commit the `STM` action as an `IO` action. The mutable
    * state of `TVar`s is only modified when this is invoked
    * (hence the `IO` context - modifying mutable state
    * is a side effect).
    */
  def atomically[F[_]] = new AtomicallyPartiallyApplied[F]

  /**
    * Convenience definition.
    */
  def retry[A]: STM[A] = Retry

  /**
    * Fallback to an alternative `STM` action if the first one
    * retries. The whole `orElse` action is retried if both
    * {@code attempt} and {@code fallback} retry.
    */
  def orElse[A](attempt: STM[A], fallback: STM[A]): STM[A] = attempt.orElse(fallback)

  /**
    * Retry transaction until {@code check} succeeds.
    */
  def check(check: => Boolean): STM[Unit] = if (check) unit else retry

  /**
    * Abort a transaction. Will raise {@code error} whenever
    * evaluated with [[atomically]].
    */
  def abort[A](error: Throwable): STM[A] = Abort(error)

  /**
    * Monadic return.
    */
  def pure[A](a: A): STM[A] = Pure(a)

  /**
    * Alias for `pure(())`.
    */
  val unit: STM[Unit] = pure(())

  //TODO can we make an ApplicativeError/MonadError here?
  implicit val stmMonad: Monad[STM] with MonoidK[STM] = new Monad[STM] with MonoidK[STM] {
    override def flatMap[A, B](fa: STM[A])(f: A => STM[B]): STM[B] = fa.flatMap(f)

    //TODO reimplement this
    override def tailRecM[A, B](a: A)(f: A => STM[Either[A, B]]): STM[B] = ???

    override def pure[A](x: A): STM[A] = STM.pure(x)

    override def empty[A]: STM[A] = STM.retry

    override def combineK[A](x: STM[A], y: STM[A]): STM[A] = x.orElse(y)
  }

  implicit def stmMonoid[A](implicit M: Monoid[A]): Monoid[STM[A]] =
    new Monoid[STM[A]] {
      override def empty: STM[A] = STM.pure(M.empty)

      override def combine(x: STM[A], y: STM[A]): STM[A] =
        for {
          l <- x
          r <- y
        } yield M.combine(l, r)
    }

  final class AtomicallyPartiallyApplied[F[_]] {

    def apply[A](stm: STM[A])(implicit F: Concurrent[F]): F[A] =
      for {
        e <- F.delay(eval(stm))
        (r, log) = e
        res <- r match {
          case TSuccess(res) =>
            for {
              x <- F.delay {
                var commit                    = false
                var pending: List[RetryFiber] = Nil
                //TODO could sort tvars in log by id and then acquire lock on each
                //instead of a global lock
                //but note that we still need a global lock to collect pending (I think...)
                STM.synchronized {
                  if (!log.isDirty) {
                    commit = true
                    println(s"success: commit is $commit")
                    println("Committing")
                    log.commit()
                    pending = log.collectPending()
                  }
                }
                commit -> pending
              }
              r <-
                if (x._1)
                  x._2.traverse_(_.run.asInstanceOf[F[Unit]].start) >>
                    F.pure(res)
                else apply(stm)
            } yield r
          case TFailure(e) =>
            for {
              dirty <- F.delay {
                STM.synchronized {
                  log.isDirty
                }
              }
              res <- if (dirty) apply(stm) else F.raiseError[A](e)
            } yield res
          case TRetry =>
            for {
              txId  <- F.delay(IdGen.incrementAndGet())
              defer <- Deferred[F, Either[Throwable, A]]
              retryFiber = RetryFiber.make(stm, txId, defer)
              dirty <- F.delay {
                if (log.isDirty) true
                else {
                  log.registerRetry(txId, retryFiber)
                  false
                }
              }
              res <-
                if (dirty) F.delay(println("re-running retry immediately")) >> apply(stm)
                else
                  for {
                    _ <- F.delay(println("retrying later"))
                    e   <- defer.get
                    res <- e.fold(F.raiseError[A](_), F.pure(_))
                  } yield res
            } yield res
        }

      } yield res

  }

  private[stm] object internal {

    val IdGen = new AtomicLong()

    final case class Pure[A](a: A)                                extends STM[A]
    final case class Alloc[A](a: A)                               extends STM[TVar[A]]
    final case class Bind[A, B](stm: STM[B], f: B => STM[A])      extends STM[A]
    final case class Get[A](tvar: TVar[A])                        extends STM[A]
    final case class Modify[A](tvar: TVar[A], f: A => A)          extends STM[Unit]
    final case class OrElse[A](attempt: STM[A], fallback: STM[A]) extends STM[A]
    final case class Abort(error: Throwable)                      extends STM[Nothing]
    final case object Retry                                       extends STM[Nothing]

    sealed trait TResult[+A]                    extends Product with Serializable
    final case class TSuccess[A](value: A)      extends TResult[A]
    final case class TFailure(error: Throwable) extends TResult[Nothing]
    final case object TRetry                    extends TResult[Nothing]

    abstract class RetryFiber {

      type Effect[X]
      type Result

      val defer: Deferred[Effect, Either[Throwable, Result]]
      val stm: STM[Result]
      val txId: TxId
      var tvars: Set[TVar[_]] = Set.empty
      implicit def F: Concurrent[Effect]

      def run: Effect[Unit] =
        for {
          _ <- F.delay(println(s"running fiber $txId"))
          e <- F.delay(eval(stm))
          (r, log) = e
          res <- r match {
            case TSuccess(res) =>
              for {
                x <- F.delay {
                  var commit                    = false
                  var pending: List[RetryFiber] = Nil
                  STM.synchronized {
                    if (!log.isDirty) {
                      println(s"Committing from retry $txId")
                      commit = true
                      log.commit()
                      pending = log.collectPending()
                    }
                  }
                  (commit, pending)
                }
                _ <-
                  if (x._1)
                    x._2.traverse_(_.run.asInstanceOf[Effect[Unit]].start) >>
                      defer.complete(Right(res))
                  else run
              } yield ()
            case TFailure(e) =>
              for {
                dirty <- F.delay {
                  STM.synchronized {
                    log.isDirty
                  }
                }
                res <- if (dirty) run else defer.complete(Left(e))
              } yield res
            case TRetry =>
              for {
                dirty <- F.delay {
                  STM.synchronized {
                    if (log.isDirty) true else {
                      log.registerRetry(txId, this)
                      false
                    }
                  }
                }
                res <- if (dirty) F.delay(println("retrying fiber now")) >> run else F.delay(println("retrying fiber later")) >> F.unit
              } yield res
          }
        } yield res
    }

    object RetryFiber {
      type Aux[F[_], A] = RetryFiber { type Effect[X] = F[X]; type Result = A }

      def make[F[_], A](stm0: STM[A], txId0: TxId, defer0: Deferred[F, Either[Throwable, A]])(implicit
        F0: Concurrent[F]
      ): RetryFiber.Aux[F, A] =
        new RetryFiber {
          type Effect[X] = F[X]
          type Result    = A

          val defer: Deferred[F, Either[Throwable, A]] = defer0
          val stm: STM[A]                              = stm0
          val txId                                     = txId0
          implicit def F: Concurrent[F]                = F0
        }
    }

    //Note that this should always be suspended using F.delay as it increments the global
    //id generator. However, we don't want eval itself suspended as we want it to be
    //tail-recursive and compiled to a loop
    def eval[A](stm: STM[A]): (TResult[A], TLog) = {
      var conts: List[Cont]                             = Nil
      var fallbacks: List[(STM[Any], TLog, List[Cont])] = Nil
      var log: TLog                                     = TLog.empty

      @tailrec
      def go(stm: STM[Any]): TResult[Any] =
        stm match {
          case Pure(a) =>
            if (conts.isEmpty)
              TSuccess(a)
            else {
              val f = conts.head
              conts = conts.tail
              go(f(a))
            }
          case Alloc(a) => go(Pure((new TVar(IdGen.incrementAndGet(), a, new AtomicReference(Map())))))
          case Bind(stm, f) =>
            conts = f :: conts
            go(stm)
          case Get(tvar)       => go(Pure(log.get(tvar)))
          case Modify(tvar, f) => go(Pure(log.modify(tvar.asInstanceOf[TVar[Any]], f)))
          case OrElse(attempt, fallback) =>
            fallbacks = (fallback, log.snapshot(), conts) :: fallbacks
            println(log.values)
            go(attempt)
          case Abort(error) =>
            TFailure(error)
          case Retry =>
            if (fallbacks.isEmpty) TRetry
            else {
              println("Using fallback")
              val (fb, lg, cts) = fallbacks.head
              log = log.delta(lg)
              conts = cts
              fallbacks = fallbacks.tail
              go(fb)
            }
        }

      //Safe by construction
      go(stm).asInstanceOf[TResult[A]] -> log
    }

    case class TLog(private var map: Map[TVarId, TLogEntry]) {

      def values: Iterable[TLogEntry] = map.values

      def get(tvar: TVar[Any]): Any =
        if (map.contains(tvar.id))
          map(tvar.id).unsafeGet[Any]
        else {
          val current = tvar.value
          map = map + (tvar.id -> TLogEntry(tvar, current))
          current
        }

      def modify(tvar: TVar[Any], f: Any => Any): Unit = {
        val current = get(tvar)
        val entry   = map(tvar.id)
        entry.unsafeSet(f(current))
      }

      def isDirty: Boolean = values.exists(_.isDirty)

      def snapshot(): TLog = TLog(Map.from(map.view.mapValues(_.snapshot())))

      //Use tlog as a base and add to it any tvars in the current log, but with their
      //current values reset to initial
      //tlog should already have been snapshotted
      def delta(tlog: TLog): TLog =
        TLog(
          Map.from(
            map.foldLeft(tlog.map) { (acc, p) =>
              val (id, e) = p
              if (acc.contains(id)) acc else acc + (id -> TLogEntry(e.tvar, e.tvar.value))
            }
          )
        )

      def commit(): Unit = values.foreach(_.commit())

      def registerRetry(txId: TxId, fiber: RetryFiber): Unit = {
        debug.updateAndGet(m => m + (txId -> values.map(_.tvar.id).toSet))
        fiber.tvars = values.map(_.tvar).toSet
        STM.synchronized {
          values.foreach { e =>
            e.tvar.registerRetry(txId, fiber)
          }
        }
      }

      def collectPending(): List[RetryFiber] = {
        var pending: Map[TxId, RetryFiber] = Map.empty
        values.foreach { e =>
          val p = e.tvar.unregisterAll()
          pending = pending ++ p

        }
        println(s"Retrying ${pending.keys.toList}")
        //Remove the fiber to be run from all tvars
        pending.values.foreach { retry =>
          retry.tvars.foreach { t =>
            t.unregisterRetry(retry.txId)
          }
        }
        pending.values.toList
      }

    }

    object TLog {
      def empty: TLog = TLog(Map.empty)
    }

    type Cont = Any => STM[Any]

    type TxId   = Long
    type TVarId = Long

    //Can we replace this with TVar[_] and Any?
    abstract class TLogEntry { self =>
      type Repr
      var current: Repr
      val initial: Repr
      val tvar: TVar[Repr]

      def unsafeGet[A]: A = current.asInstanceOf[A]

      def unsafeSet[A](a: A): Unit = current = a.asInstanceOf[Repr]

      def commit(): Unit = {
        println(s"Committing value $current to tvar ${tvar.id}")
        tvar.value = current
        println(s"tvar ${tvar.id} value is now ${tvar.value}")
      }

      def isDirty: Boolean = initial != tvar.value.asInstanceOf[Repr]

      def snapshot(): TLogEntry =
        new TLogEntry {
          override type Repr = self.Repr
          override var current: Repr    = self.current
          override val initial: Repr    = self.initial
          override val tvar: TVar[Repr] = self.tvar
        }

    }

    object TLogEntry {

      def apply[A](tvar0: TVar[A], current0: A): TLogEntry =
        new TLogEntry {
          override type Repr = A
          override var current: A    = current0
          override val initial: A    = tvar0.value.asInstanceOf[A]
          override val tvar: TVar[A] = tvar0
        }

    }

  }

}
