/*
 * Copyright 2020 TimWSpence
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.github.timwspence.cats.stm

import scala.annotation.tailrec

import cats.effect.std.Semaphore
import cats.effect.{Async, Concurrent, Deferred, Ref, Resource}
import cats.implicits._
import cats.{MonadError, Monoid, MonoidK, StackSafeMonad}

trait STMLike[F[_]] {
  import Internals._

  def pure[A](a: A): Txn[A] = Txn.pure(a)

  def commit[A](txn: Txn[A]): F[A]

  def check(cond: => Boolean): Txn[Unit] = if (cond) unit else retry

  def retry[A]: Txn[A] = Retry

  val unit: Txn[Unit] = pure(())

  def abort[A](e: Throwable): Txn[A] = Txn.abort(e)

  def raiseError[A](e: Throwable): Txn[A] = abort(e)

  class TVar[A] private[stm] (
    private[stm] val id: TVarId,
    private[stm] val value: Ref[F, A],
    private[stm] val lock: Semaphore[F],
    private[stm] val retries: Ref[F, List[Deferred[F, Unit]]]
  ) {
    def get: Txn[A] = Get(this)

    def modify(f: A => A): Txn[Unit] = Modify(this, f)

    def set(a: A): Txn[Unit] = modify(_ => a)

    private[stm] def registerRetry(signal: Deferred[F, Unit]): F[Unit] =
      retries.update(signal :: _)

  }

  object TVar {
    def of[A](a: A)(implicit F: Concurrent[F]): Txn[TVar[A]] = Alloc(F.ref(a))
  }

  sealed abstract class Txn[+A] {

    /**
      * Functor map on `STM`.
      */
    final def map[B](f: A => B): Txn[B] = Bind(this, f.andThen(Pure(_)))

    /**
      * Monadic bind on `STM`.
      */
    final def flatMap[B](f: A => Txn[B]): Txn[B] = Bind(this, f)

    /**
      * Try an alternative `STM` action if this one retries.
      */
    final def orElse[B >: A](other: Txn[B]): Txn[B] = OrElse(this, other)

    final def handleErrorWith[B >: A](f: Throwable => Txn[B]): Txn[B] = HandleError(this, f)
  }

  object Txn {
    def pure[A](a: A): Txn[A] = Pure(a)

    def retry[A]: Txn[A] = Retry

    def abort[A](e: Throwable): Txn[A] = Abort(e)

    implicit val txnMonad: StackSafeMonad[Txn] with MonadError[Txn, Throwable] with MonoidK[Txn] =
      new StackSafeMonad[Txn] with MonadError[Txn, Throwable] with MonoidK[Txn] {
        override def flatMap[A, B](fa: Txn[A])(f: A => Txn[B]): Txn[B] = fa.flatMap(f)

        override def pure[A](x: A): Txn[A] = Txn.pure(x)

        override def empty[A]: Txn[A] = Txn.retry

        override def combineK[A](x: Txn[A], y: Txn[A]): Txn[A] = x.orElse(y)

        override def raiseError[A](e: Throwable): Txn[A] = Txn.abort(e)

        override def handleErrorWith[A](fa: Txn[A])(f: Throwable => Txn[A]): Txn[A] = fa.handleErrorWith(f)
      }

    implicit def stmMonoid[A](implicit M: Monoid[A]): Monoid[Txn[A]] =
      new Monoid[Txn[A]] {
        override def empty: Txn[A] = Txn.pure(M.empty)

        override def combine(x: Txn[A], y: Txn[A]): Txn[A] =
          for {
            l <- x
            r <- y
          } yield M.combine(l, r)
      }
  }

  private[stm] object Internals {

    case class Pure[A](a: A)                                             extends Txn[A]
    case class Alloc[A](v: F[Ref[F, A]])                                 extends Txn[TVar[A]]
    case class Bind[A, B](txn: Txn[B], f: B => Txn[A])                   extends Txn[A]
    case class HandleError[A](txn: Txn[A], recover: Throwable => Txn[A]) extends Txn[A]
    case class Get[A](tvar: TVar[A])                                     extends Txn[A]
    case class Modify[A](tvar: TVar[A], f: A => A)                       extends Txn[Unit]
    case class OrElse[A](attempt: Txn[A], fallback: Txn[A])              extends Txn[A]
    case class Abort(error: Throwable)                                   extends Txn[Nothing]
    case object Retry                                                    extends Txn[Nothing]

    sealed trait TResult[+A]              extends Product with Serializable
    case class TSuccess[A](value: A)      extends TResult[A]
    case class TFailure(error: Throwable) extends TResult[Nothing]
    case object TRetry                    extends TResult[Nothing]

    type Cont   = Any => Txn[Any]
    type TVarId = Long
    type TxId   = Long

    case class TLog(private var map: Map[TVarId, TLogEntry]) {

      def values: Iterable[TLogEntry] = map.values

      def contains(tvar: TVar[Any]): Boolean = map.contains(tvar.id)

      /*
       * Get the current value of tvar in the txn. Throws if
       * tvar is not present in the log already
       */
      def get(tvar: TVar[Any]): Any = map(tvar.id).get

      /*
       * Get the current value of tvar in the txn log
       *
       * Assumes the tvar is not already in the txn log so
       * fetches the current value directly from the tvar
       */
      def getF(tvar: TVar[Any])(implicit F: Async[F]): F[Any] =
        tvar.value.get.flatMap { v =>
          F.delay {
            val e = TLogEntry(v, v, tvar)
            map = map + (tvar.id -> e)
            v
          }
        }

      /*
       * Modify the current value of tvar in the txn log. Throws if
       * tvar is not present in the log already
       */
      def modify(tvar: TVar[Any], f: Any => Any): Unit = {
        val e       = map(tvar.id)
        val current = e.get
        val entry   = e.set(f(current))
        map = map + (tvar.id -> entry)
      }

      /*
       * Modify the current value of tvar in the txn log
       *
       * Assumes the tvar is not already in the txn log so
       * fetches the current value directly from the tvar
       */
      def modifyF(tvar: TVar[Any], f: Any => Any)(implicit F: Async[F]): F[Unit] =
        tvar.value.get.flatMap { v =>
          F.delay {
            val e = TLogEntry(v, f(v), tvar)
            map = map + (tvar.id -> e)
          }
        }

      def isDirty(implicit F: Concurrent[F]): F[Boolean] =
        values.foldLeft(F.pure(false))((acc, v) =>
          for {
            d  <- acc
            d1 <- v.isDirty
          } yield d || d1
        )

      def snapshot(): TLog = TLog(map)

      def delta(tlog: TLog): TLog =
        TLog(
          map.foldLeft(tlog.map) { (acc, p) =>
            val (id, e) = p
            if (acc.contains(id)) acc else acc + (id -> TLogEntry(e.initial, e.initial, e.tvar))
          }
        )

      def withLock[A](fa: F[A])(implicit F: Concurrent[F]): F[A] =
        values.toList
          .sortBy(_.tvar.id)
          .foldLeft(Resource.liftF(F.unit)) { (locks, e) =>
            locks >> e.tvar.lock.permit
          }
          .use(_ => fa)

      def commit(implicit F: Concurrent[F]): F[Unit] = F.uncancelable(_ => values.toList.traverse_(_.commit))

      def signal(implicit F: Concurrent[F]): F[Unit] =
        //TODO use chain to avoid reverse?
        F.uncancelable(_ =>
          values.toList.reverse.traverse_(e =>
            for {
              signals <- e.tvar.retries.getAndSet(Nil)
              _       <- signals.traverse_(s => s.complete(()))
            } yield ()
          )
        )

      def registerRetry(signal: Deferred[F, Unit])(implicit F: Concurrent[F]): F[Unit] =
        values.toList.traverse_(e => e.tvar.registerRetry(signal))
    }

    object TLog {
      def empty: TLog = TLog(Map.empty)
    }

    case class TLogEntry(initial: Any, current: Any, tvar: TVar[Any]) { self =>

      def get: Any = current

      def set(a: Any): TLogEntry = TLogEntry(initial, a, tvar)

      def commit: F[Unit] = tvar.value.set(current)

      def isDirty(implicit F: Concurrent[F]): F[Boolean] = tvar.value.get.map(_ != initial)

      def snapshot(): TLogEntry = TLogEntry(self.initial, self.current, self.tvar)

    }

    object TLogEntry {

      def applyF[A](tvar0: TVar[A], current0: A)(implicit F: Async[F]): F[TLogEntry] =
        tvar0.value.get.map { v =>
          TLogEntry(v, current0, tvar0.asInstanceOf[TVar[Any]])
        }

    }

    def eval[A](idGen: Ref[F, Long], txn: Txn[A])(implicit F: Async[F]): F[(TResult[A], TLog)] = {

      sealed trait Trampoline
      case class Done(result: TResult[Any]) extends Trampoline
      case class Eff(run: F[Txn[Any]])      extends Trampoline

      type Tag = Int
      val cont: Tag   = 0
      val handle: Tag = 1

      var conts: List[Cont]                                                    = Nil
      var tags: List[Tag]                                                      = Nil
      var fallbacks: List[(Txn[Any], TLog, List[Cont], List[Tag], List[TLog])] = Nil
      var errorFallbacks: List[TLog]                                           = Nil
      var log: TLog                                                            = TLog.empty

      //Construction of a TVar requires allocating state but we want this to be tail-recursive
      //and non-effectful so we trampoline it with run
      @tailrec
      def go(
        nextId: TVarId,
        lock: Semaphore[F],
        ref: Ref[F, List[Deferred[F, Unit]]],
        txn: Txn[Any]
      ): Trampoline =
        txn match {
          case Pure(a) =>
            while (!tags.isEmpty && !(tags.head == cont)) {
              tags = tags.tail
              conts = conts.tail
            }
            if (tags.isEmpty)
              Done(TSuccess(a))
            else {
              val f = conts.head
              conts = conts.tail
              tags = tags.tail
              go(nextId, lock, ref, f(a))
            }
          case Alloc(r) => Eff(r.map(v => Pure((new TVar(nextId, v, lock, ref)))))
          case Bind(txn, f) =>
            conts = f :: conts
            tags = cont :: tags
            go(nextId, lock, ref, txn)
          case HandleError(txn, f) =>
            conts = f.asInstanceOf[Cont] :: conts
            tags = handle :: tags
            errorFallbacks = log.snapshot() :: errorFallbacks
            go(nextId, lock, ref, txn)
          case Get(tvar) =>
            if (log.contains(tvar))
              go(nextId, lock, ref, Pure(log.get(tvar)))
            else
              Eff(log.getF(tvar).map(Pure(_)))
          case Modify(tvar, f) =>
            if (log.contains(tvar))
              go(nextId, lock, ref, Pure(log.modify(tvar, f)))
            else
              Eff(log.modifyF(tvar, f).map(Pure(_)))
          case OrElse(attempt, fallback) =>
            fallbacks = (fallback, log.snapshot(), conts, tags, errorFallbacks) :: fallbacks
            go(nextId, lock, ref, attempt)
          case Abort(error) =>
            while (!tags.isEmpty && !(tags.head == handle)) {
              tags = tags.tail
              conts = conts.tail
            }
            if (tags.isEmpty) Done(TFailure(error))
            else {
              val f = conts.head
              conts = conts.tail
              tags = tags.tail
              log = errorFallbacks.head
              errorFallbacks = errorFallbacks.tail
              go(nextId, lock, ref, f(error))
            }
          case Retry =>
            if (fallbacks.isEmpty) Done(TRetry)
            else {
              val (fb, lg, cts, tgs, efbs) = fallbacks.head
              log = log.delta(lg)
              conts = cts
              tags = tgs
              fallbacks = fallbacks.tail
              errorFallbacks = efbs
              go(nextId, lock, ref, fb)
            }
        }

      def run(txn: Txn[Any]): F[TResult[Any]] =
        for {
          id   <- idGen.updateAndGet(_ + 1)
          lock <- Semaphore[F](1)
          ref  <- Ref.of[F, List[Deferred[F, Unit]]](Nil)
          //Trampoline so we can generate a new id/lock/ref to supply
          //if we need to contruct a new tvar
          res <- go(id, lock, ref, txn) match {
            case Done(v)   => F.pure(v)
            case Eff(ftxn) => ftxn.flatMap(run(_))
          }
        } yield res

      //Safe by construction
      // F.delay(println(s"eval'ing $txn")) >> run(txn.asInstanceOf[Txn[Any]]).map(res => res.asInstanceOf[TResult[A]] -> log)
      run(txn.asInstanceOf[Txn[Any]]).map(res => res.asInstanceOf[TResult[A]] -> log)
    }

  }

}
