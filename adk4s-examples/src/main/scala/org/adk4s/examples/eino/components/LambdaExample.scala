package org.adk4s.examples.eino.components

import cats.effect.IO
import cats.effect.IOApp
import fs2.Stream
import org.adk4s.core.runnable.Lambda
import org.adk4s.core.runnable.Runnable
import org.adk4s.examples.eino.common.ExampleUtils

/**
 * Eino equivalent: components/lambda
 *
 * Demonstrates the Lambda abstraction with all four Runnable modes:
 * invoke, stream, transform, collect. Also shows composition via andThen.
 */
object LambdaExample extends IOApp.Simple:

  def run: IO[Unit] =
    for
      _ <- ExampleUtils.printSection("Lambda Example (Eino: components/lambda)")

      // 1. Invoke mode — single input → single output
      _ <- ExampleUtils.printSubSection("1. Invoke Mode (I => IO[O])")
      invokeLambda = Lambda[String, Int]((s: String) => IO.pure(s.length))
      invokeResult <- invokeLambda.toRunnable.invoke("Hello, Lambda!")
      _ <- IO.println(s"   Input: \"Hello, Lambda!\" → Length: $invokeResult")

      // 2. Stream mode — single input → stream of outputs
      _ <- ExampleUtils.printSubSection("2. Stream Mode (I => Stream[IO, O])")
      streamLambda = Lambda.stream[String, String]((s: String) =>
        Stream.emits(s.split(" ").toList).covary[IO]
      )
      words <- streamLambda.toRunnable.stream("Hello World from Lambda").compile.toList
      _ <- IO.println(s"   Input: \"Hello World from Lambda\" → Words: $words")

      // 3. Transform mode — stream of inputs → stream of outputs
      _ <- ExampleUtils.printSubSection("3. Transform Mode (Stream[IO, I] => Stream[IO, O])")
      transformLambda = Lambda.transform[String, String]((input: Stream[IO, String]) =>
        input.map((s: String) => s.toUpperCase)
      )
      transformed <- transformLambda.toRunnable
        .transform(Stream.emits(List("hello", "world")).covary[IO])
        .compile
        .toList
      _ <- IO.println(s"   Input: [\"hello\", \"world\"] → Transformed: $transformed")

      // 4. Collect mode — stream of inputs → single output
      _ <- ExampleUtils.printSubSection("4. Collect Mode (Stream[IO, I] => IO[O])")
      collectLambda = Lambda.collect[Int, Int]((input: Stream[IO, Int]) =>
        input.compile.foldMonoid
      )
      sum <- collectLambda.toRunnable.collect(Stream.emits(List(1, 2, 3, 4, 5)).covary[IO])
      _ <- IO.println(s"   Input: [1, 2, 3, 4, 5] → Sum: $sum")

      // 5. Pure lambda — no effects
      _ <- ExampleUtils.printSubSection("5. Pure Lambda (I => O)")
      pureLambda = Lambda.pure[String, Int]((s: String) => s.length * 2)
      pureResult <- pureLambda.toRunnable.invoke("test")
      _ <- IO.println(s"   Input: \"test\" → Double length: $pureResult")

      // 6. Named lambda with config
      _ <- ExampleUtils.printSubSection("6. Named Lambda")
      namedLambda = Lambda[String, String]((s: String) => IO.pure(s.reverse))
        .named("reverser")
        .described("Reverses the input string")
      namedResult <- namedLambda.toRunnable.invoke("Lambda")
      _ <- IO.println(s"   Name: ${namedLambda.config.name.getOrElse("unnamed")}")
      _ <- IO.println(s"   Description: ${namedLambda.config.description.getOrElse("none")}")
      _ <- IO.println(s"   Input: \"Lambda\" → Reversed: $namedResult")

      // 7. Full lambda — explicit all four modes
      _ <- ExampleUtils.printSubSection("7. Full Lambda (all four modes explicit)")
      fullLambda = Lambda.full[String, Int](
        invoke = (s: String) => IO.pure(s.length),
        stream = (s: String) => Stream.emit(s.length).covary[IO],
        collect = (input: Stream[IO, String]) => input.compile.toList.map(_.map(_.length).sum),
        transform = (input: Stream[IO, String]) => input.map(_.length)
      )
      fullInvoke <- fullLambda.toRunnable.invoke("full")
      fullStream <- fullLambda.toRunnable.stream("full").compile.toList
      fullCollect <- fullLambda.toRunnable.collect(Stream.emits(List("a", "bb", "ccc")).covary[IO])
      fullTransform <- fullLambda.toRunnable.transform(Stream.emits(List("a", "bb")).covary[IO]).compile.toList
      _ <- IO.println(s"   invoke(\"full\") = $fullInvoke")
      _ <- IO.println(s"   stream(\"full\") = $fullStream")
      _ <- IO.println(s"   collect([\"a\",\"bb\",\"ccc\"]) = $fullCollect")
      _ <- IO.println(s"   transform([\"a\",\"bb\"]) = $fullTransform")

      _ <- IO.println("\nLambda example completed.")
    yield ()
