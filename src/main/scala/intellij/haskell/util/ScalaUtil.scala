/*
 * Copyright 2014-2018 Rik van der Kleij
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package intellij.haskell.util

import java.util.concurrent.Callable

import com.intellij.openapi.util.{Computable, Condition}

import scala.collection.mutable

object ScalaUtil {

  implicit class RichBoolean(val b: Boolean) extends AnyVal {
    final def option[A](a: => A): Option[A] = if (b) Option(a) else None

    final def optionNot[A](a: => A): Option[A] = if (b) None else Option(a)
  }

  def runnable(f: => Unit): Runnable {
    def run(): Unit} = new Runnable {
    override def run(): Unit = f
  }

  def computable[A](f: => A): Computable[A] {
    def compute(): A} = new Computable[A] {
    override def compute(): A = f
  }

  def callable[A](f: => A): Callable[A] {
    def call(): A} = new Callable[A] {
    override def call(): A = f
  }

  def condition[A](f: A => Boolean): Condition[A] {
    def value(t: A): Boolean
  } = new Condition[A] {
    override def value(t: A): Boolean = f(t)
  }

  def maxsBy[A, B](xs: Iterable[A])(f: A => B)(implicit cmp: Ordering[B]): Iterable[A] = {
    val maxElems = mutable.Map[A, B]()

    for (elem <- xs) {
      val fx = f(elem)
      val removeKeys = maxElems.filter({ case (_, v) => cmp.gt(fx, v) }).keys
      removeKeys.map(maxElems.remove)

      maxElems.put(elem, fx)
    }
    maxElems.keys
  }
}
