package org.adk4s.orchestration.state

import cats.effect.{IO, Ref}
import cats.syntax.all.*

trait StateRef[F[_], S]:
  def get: F[S]
  def set(s: S): F[Unit]
  def update(f: S => S): F[Unit]
  def modify[A](f: S => (S, A)): F[A]
  def getAndUpdate(f: S => S): F[S]
  def updateAndGet(f: S => S): F[S]

object StateRef:
  def of[S](initial: S): IO[StateRef[IO, S]] =
    Ref.of[IO, S](initial).map(fromRef)

  def fromRef[S](ref: Ref[IO, S]): StateRef[IO, S] = new StateRef[IO, S]:
    def get: IO[S] = ref.get
    def set(s: S): IO[Unit] = ref.set(s)
    def update(f: S => S): IO[Unit] = ref.update(f)
    def modify[A](f: S => (S, A)): IO[A] = ref.modify(f)
    def getAndUpdate(f: S => S): IO[S] = ref.getAndUpdate(f)
    def updateAndGet(f: S => S): IO[S] = ref.updateAndGet(f)

  def empty[S](default: S): StateRef[IO, S] = new StateRef[IO, S]:
    def get: IO[S] = IO.pure(default)
    def set(s: S): IO[Unit] = IO.unit
    def update(f: S => S): IO[Unit] = IO.unit
    def modify[A](f: S => (S, A)): IO[A] = IO.pure(f(default)._2)
    def getAndUpdate(f: S => S): IO[S] = IO.pure(default)
    def updateAndGet(f: S => S): IO[S] = IO.pure(f(default))
