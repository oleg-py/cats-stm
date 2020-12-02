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

import cats.effect.IO
import munit.CatsEffectSuite

class TLogTest extends CatsEffectSuite {

  val stm = STM[IO].unsafeRunSync()
  import stm._
  import stm.Internals._

  val inc: Int => Int = _ + 1

  test("get when not present") {
    for {
      tvar <- stm.commit(TVar.of[Any](1))
      res <- IO {
        assertEquals(TLog.empty.get(tvar), 1)
      }
    } yield res
  }

  test("get when present") {
    for {
      tvar <- stm.commit(TVar.of[Any](1))
      res <- IO {
        val tlog = TLog.empty
        tlog.get(tvar.asInstanceOf[TVar[Any]])
        tlog.modify(tvar, inc.asInstanceOf[Any => Any])
        assertEquals(tlog.get(tvar), 2)
      }
    } yield res
  }

  test("isDirty when empty") {
    val tlog = TLog.empty
    assertEquals(tlog.isDirty, false)
  }

  test("isDirty when non-empty") {
    for {
      tvar <- stm.commit(TVar.of[Any](1))
      res <- IO {
        val tlog = TLog.empty
        tlog.get(tvar)
        assertEquals(tlog.isDirty, false)
      }
    } yield res
  }

  test("isDirty when non-empty and dirty") {
    for {
      tvar <- stm.commit(TVar.of[Any](1))
      res <- IO {
        val tlog = TLog.empty
        tlog.modify(tvar, inc.asInstanceOf[Any => Any])
        tvar.value = 2
        assertEquals(tlog.isDirty, true)
      }
    } yield res
  }

  test("commit") {
    for {
      tvar <- stm.commit(TVar.of[Any](1))
      tlog <- IO {
        val tlog = TLog.empty
        tlog.modify(tvar, inc.asInstanceOf[Any => Any])
        tlog
      }
      _ <- tlog.commit
      res <- IO {
        assertEquals(tvar.value, 2)
      }
    } yield res
  }

  test("snapshot") {
    for {
      tvar  <- stm.commit(TVar.of[Any](1))
      tvar2 <- stm.commit(TVar.of[Any](2))
      res <- IO {
        val tlog = TLog.empty
        tlog.modify(tvar, inc.asInstanceOf[Any => Any])
        val tlog2 = tlog.snapshot()
        tlog.modify(tvar, inc.asInstanceOf[Any => Any])
        assertEquals(tlog2.get(tvar), 2)
        assertEquals(tlog2.get(tvar2), 2)

      }
    } yield res
  }

  test("delta") {
    for {
      tvar  <- stm.commit(TVar.of[Any](1))
      tvar2 <- stm.commit(TVar.of[Any](2))
      res <- IO {
        val tlog = TLog.empty
        tlog.modify(tvar, inc.asInstanceOf[Any => Any])
        tlog.modify(tvar2, inc.asInstanceOf[Any => Any])
        val tlog2 = tlog.snapshot()
        tlog2.modify(tvar2, inc.asInstanceOf[Any => Any])
        val d = tlog2.delta(tlog)
        assertEquals(d.get(tvar), 2)
        assertEquals(d.get(tvar2), 3)
      }
    } yield res
  }

}
