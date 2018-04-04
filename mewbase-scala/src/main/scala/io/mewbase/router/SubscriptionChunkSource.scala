package io.mewbase.router


import java.util.concurrent.LinkedBlockingQueue

import akka.http.scaladsl.model.HttpEntity.ChunkStreamPart
import akka.stream.{Attributes, Outlet, SourceShape}
import akka.stream.stage.{GraphStage, GraphStageLogic, OutHandler}
import io.mewbase.bson.BsonObject
import io.mewbase.eventsource.{Event, EventHandler, EventSource, Subscription}
import io.mewbase.eventsource.impl.http.SubscriptionRequest
import io.mewbase.eventsource.impl.http.SubscriptionRequest.SubscriptionType


case class SubscriptionChunkSource(eventSource : EventSource, subsRq : SubscriptionRequest ) extends GraphStage[SourceShape[ChunkStreamPart]] {

  // Define the (sole) output port of this stage
  val out: Outlet[ChunkStreamPart] = Outlet("ChunkSource")

  // Define the shape of this stage, which is SourceShape with the port we defined above
  override val shape: SourceShape[ChunkStreamPart] = SourceShape(out)

  val queue = new LinkedBlockingQueue[ChunkStreamPart](16)
  val subs = subscribe()

  // This is where the actual (possibly stateful) logic will live
  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
    new GraphStageLogic(shape) {

      setHandler(out, new OutHandler {
        override def onPull() : Unit = {
           push(out, queue.poll() )
        }

        override def onDownstreamFinish(): Unit = {
          super.onDownstreamFinish()
          subs.close()
          complete(out)
        }
      })
    }


  def subscribe() : Subscription = {
    val handler =  new EventHandler {
      override def onEvent(evt: Event): Unit = {
        val boj = new BsonObject()
          .put("EventNumber",evt.getEventNumber)
          .put("EpochMillis",evt.getInstant.toEpochMilli)
          .put("Crc32",evt.getCrc32)
          .put("Event",evt.getBson)
        queue.offer(boj.encode.getBytes)
      }
    }

    import SubscriptionType._
    val channel = subsRq.channel
    subsRq.`type` match {
      case  FromNow => eventSource.subscribe(channel, handler)
      case  FromMostRecent => eventSource.subscribeFromMostRecent(channel, handler)
      case  FromEventNumber => eventSource.subscribeFromEventNumber(channel, subsRq.startInclusive, handler)
      case  FromInstant => eventSource.subscribeFromInstant(channel,subsRq.startInstant,handler)
      case  FromStart => eventSource.subscribeAll(channel, handler)
    }
  }


}
