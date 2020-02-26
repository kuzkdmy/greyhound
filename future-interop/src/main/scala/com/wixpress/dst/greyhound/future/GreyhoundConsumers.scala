package com.wixpress.dst.greyhound.future

import com.wixpress.dst.greyhound.core.Group
import com.wixpress.dst.greyhound.core.consumer.EventLoop.Handler
import com.wixpress.dst.greyhound.core.consumer.{ParallelConsumer, ParallelConsumerConfig}
import com.wixpress.dst.greyhound.future.GreyhoundRuntime.Env
import zio.{Promise, ZIO}

import scala.concurrent.Future

trait GreyhoundConsumers {
  def shutdown: Future[Unit]
}

case class GreyhoundConsumersBuilder(config: GreyhoundConfig,
                                     handlers: Map[Group, Handler[Env]] = Map.empty) {

  def withConsumer[K, V](consumer: GreyhoundConsumer[K, V]): GreyhoundConsumersBuilder = {
    val group = consumer.group
    val handler = handlers.get(group).foldLeft(consumer.recordHandler)(_ combine _)
    copy(handlers = handlers + (group -> handler))
  }

  def build: Future[GreyhoundConsumers] = config.runtime.unsafeRunToFuture {
    for {
      ready <- Promise.make[Nothing, Unit]
      shutdownSignal <- Promise.make[Nothing, Unit]
      consumerConfig = ParallelConsumerConfig(config.bootstrapServers)
      fiber <- ParallelConsumer.make(consumerConfig, handlers).use_ {
        ready.succeed(()) *> shutdownSignal.await
      }.fork
      _ <- ready.await
      runtime <- ZIO.runtime[Env]
    } yield new GreyhoundConsumers {
      override def shutdown: Future[Unit] = runtime.unsafeRunToFuture {
        shutdownSignal.succeed(()) *> fiber.join
      }
    }
  }

}