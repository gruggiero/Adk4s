package org.adk4s.core

import fs2.Stream
import cats.effect.IO

package object streaming:
  type AdkStream[A] = Stream[IO, A]
