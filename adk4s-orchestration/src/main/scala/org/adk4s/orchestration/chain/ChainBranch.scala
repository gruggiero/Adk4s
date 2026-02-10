package org.adk4s.orchestration.chain

import cats.effect.IO
import org.adk4s.core.runnable.Runnable

sealed trait ChainBranch[I, O]:
  def toRunnable: Runnable[I, O]

case class BinaryChainBranch[I](
  predicate: I => IO[Boolean],
  ifTrue: Runnable[I, I],
  ifFalse: Runnable[I, I]
) extends ChainBranch[I, I]:
  def toRunnable: Runnable[I, I] = Runnable.fromInvoke { (input: I) =>
    predicate(input).flatMap { condition =>
      if condition then ifTrue.invoke(input)
      else ifFalse.invoke(input)
    }
  }

case class EndIfChainBranch[I](
  predicate: I => IO[Boolean],
  otherwise: Runnable[I, I]
) extends ChainBranch[I, I]:
  def toRunnable: Runnable[I, I] = Runnable.fromInvoke { (input: I) =>
    predicate(input).flatMap { condition =>
      if condition then IO.pure(input)
      else otherwise.invoke(input)
    }
  }

object ChainBranch:
  def binary[I](
    predicate: I => IO[Boolean],
    ifTrue: Runnable[I, I],
    ifFalse: Runnable[I, I]
  ): ChainBranch[I, I] =
    BinaryChainBranch(predicate, ifTrue, ifFalse)

  def binaryPure[I](
    predicate: I => Boolean,
    ifTrue: Runnable[I, I],
    ifFalse: Runnable[I, I]
  ): ChainBranch[I, I] =
    BinaryChainBranch(i => IO.pure(predicate(i)), ifTrue, ifFalse)

  def endIf[I](
    predicate: I => IO[Boolean],
    otherwise: Runnable[I, I]
  ): ChainBranch[I, I] =
    EndIfChainBranch(predicate, otherwise)

  def endIfPure[I](
    predicate: I => Boolean,
    otherwise: Runnable[I, I]
  ): ChainBranch[I, I] =
    EndIfChainBranch(i => IO.pure(predicate(i)), otherwise)
