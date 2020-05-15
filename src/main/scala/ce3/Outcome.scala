/*
 * Copyright 2020 Typelevel
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

package ce3

import cats.{~>, Applicative, ApplicativeError, Eq, Eval, Monad, MonadError, Order, Show, Traverse}
import cats.implicits._

import scala.annotation.tailrec
import scala.util.{Either, Left, Right}

sealed trait Outcome[+F[_], +E, +A] extends Product with Serializable

private[ce3] trait LowPriorityImplicits {
  import Outcome.{Canceled, Completed, Errored}

  // variant for when F[A] doesn't have a Show (which is, like, most of the time)
  implicit def showUnknown[F[_], E: Show, A]: Show[Outcome[F, E, A]] = Show show {
    case Canceled => "Canceled"
    case Errored(left) => s"Errored(${left.show})"
    case Completed(right) => s"Completed(<unknown>)"
  }

  implicit def eq[F[_], E: Eq, A](implicit FA: Eq[F[A]]): Eq[Outcome[F, E, A]] = Eq instance {
    case (Canceled, Canceled) => true
    case (Errored(left), Errored(right)) => left === right
    case (Completed(left), Completed(right)) => left === right
    case _ => false
  }

  implicit def applicativeError[F[_]: Applicative, E]: ApplicativeError[Outcome[F, E, ?], E] =
    new ExitCaseApplicativeError[F, E]

  //todo needs renaming
  protected class ExitCaseApplicativeError[F[_]: Applicative, E] extends ApplicativeError[Outcome[F, E, ?], E] {

    def pure[A](x: A): Outcome[F, E, A] = Completed(x.pure[F])

    def handleErrorWith[A](fa: Outcome[F, E, A])(f: E => Outcome[F, E, A]): Outcome[F, E, A] =
      fa.fold(Canceled, f, Completed(_: F[A]))

    def raiseError[A](e: E): Outcome[F, E, A] = Errored(e)

    def ap[A, B](ff: Outcome[F, E, A => B])(fa: Outcome[F, E, A]): Outcome[F, E, B] =
      (ff, fa) match {
        case (c: Completed[F, A => B], Completed(fa)) =>
          Completed(c.fa.ap(fa))

        case (Errored(e), _) =>
          Errored(e)

        case (Canceled, _) =>
          Canceled

        case (_, Errored(e)) =>
          Errored(e)

        case (_, Canceled) =>
          Canceled
      }
  }
}

object Outcome extends LowPriorityImplicits {

  def fromEither[F[_]: Applicative, E, A](either: Either[E, A]): Outcome[F, E, A] =
    either.fold(Errored(_), a => Completed(a.pure[F]))

  implicit class Syntax[F[_], E, A](val self: Outcome[F, E, A]) extends AnyVal {

    def fold[B](
        canceled: => B,
        errored: E => B,
        completed: F[A] => B)
        : B = self match {
      case Canceled => canceled
      case Errored(e) => errored(e)
      case Completed(fa) => completed(fa)
    }

    def mapK[G[_]](f: F ~> G): Outcome[G, E, A] = self match {
      case Outcome.Canceled => Outcome.Canceled
      case Outcome.Errored(e) => Outcome.Errored(e)
      case Outcome.Completed(fa) => Outcome.Completed(f(fa))
    }
  }

  implicit def order[F[_], E: Order, A](implicit FA: Order[F[A]]): Order[Outcome[F, E, A]] =
    Order from {
      case (Canceled, Canceled) => 0
      case (Errored(left), Errored(right)) => left.compare(right)
      case (Completed(left), Completed(right)) => left.compare(right)

      case (Canceled, _) => -1
      case (_, Canceled) => 1
      case (Errored(_), Completed(_)) => -1
      case (Completed(_), Errored(_)) => 1
    }

  implicit def show[F[_], E: Show, A](implicit FA: Show[F[A]]): Show[Outcome[F, E, A]] = Show show {
    case Canceled => "Canceled"
    case Errored(left) => s"Errored(${left.show})"
    case Completed(right) => s"Completed(${right.show})"
  }

  implicit def monadError[F[_]: Traverse, E](implicit F: Monad[F]): MonadError[Outcome[F, E, ?], E] =
    new ExitCaseApplicativeError[F, E]()(F) with MonadError[Outcome[F, E, ?], E] {

      override def map[A, B](fa: Outcome[F, E, A])(f: A => B): Outcome[F, E, B] = fa match {
        case c: Completed[F, A] => Completed(F.map(c.fa)(f))
        case Errored(e) => Errored(e)
        case Canceled => Canceled
      }

      def flatMap[A, B](fa: Outcome[F, E, A])(f: A => Outcome[F, E, B]): Outcome[F, E, B] = fa match {
        case Completed(ifa) =>
          Traverse[F].traverse(ifa)(f) match {
            case Completed(iffa) => Completed(Monad[F].flatten(iffa))
            case Errored(e) => Errored(e)
            case Canceled => Canceled
          }

        case Errored(e) => Errored(e)
        case Canceled => Canceled
      }

      @tailrec
      def tailRecM[A, B](a: A)(f: A => Outcome[F, E, Either[A, B]]): Outcome[F, E, B] =
        f(a) match {
          case c: Completed[F, Either[A, B]] =>
            Traverse[F].sequence(c.fa) match {
              case Left(a) => tailRecM(a)(f)
              case Right(fb) => Completed(fb)
            }

          case Errored(e) => Errored(e)
          case Canceled => Canceled
        }
    }

  final case class Completed[F[_], A](fa: F[A]) extends Outcome[F, Nothing, A]
  final case class Errored[E](e: E) extends Outcome[Nothing, E, Nothing]
  case object Canceled extends Outcome[Nothing, Nothing, Nothing]
}
