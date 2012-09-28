/**
 * Copyright (C) 2009-2011 the original author or authors.
 * See the notice.md file distributed with this work for additional
 * information regarding copyright ownership.
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
package com.github.stomp.benchmark

import org.fusesource.hawtdispatch._
import java.util.concurrent.{CountDownLatch, TimeUnit}
import java.lang.Throwable
import org.fusesource.hawtbuf.AsciiBuffer
import org.fusesource.hawtbuf.Buffer._
import org.fusesource.stomp.client._
import org.fusesource.stomp.client.Constants._
import collection.mutable.{ListBuffer, HashMap}
import org.fusesource.stomp.codec.StompFrame
import java.net.URI
import java.util.Random

//object NonBlockingScenario {
//  def main(args:Array[String]):Unit = {
//    val scenario = new com.github.stomp.benchmark.NonBlockingScenario
//    scenario.login = Some("admin")
//    scenario.passcode = Some("password")
//
//    scenario.port = 61614
//    scenario.protocol = "tls"
//    scenario.key_store_file = Some("/Users/chirino/sandbox/stomp-benchmark/keystore")
//    scenario.key_store_password = Some("password")
//    scenario.key_password = Some("password")
//
//    scenario.message_size = 20
//    scenario.request_response = false
//    scenario.display_errors = true
//
//    scenario.consumers = 0
//    scenario.run
//  }
//}

/**
 * <p>
 * Simulates load on the a stomp broker using non blocking io.
 * </p>
 *
 * @author <a href="http://hiramchirino.com">Hiram Chirino</a>
 */
class NonBlockingScenario extends Scenario {

  var current_connects = 0
  val scenario_queue = createQueue("scenario_queue")
  val pending_connects = ListBuffer[()=>Unit]()


  private def kick_off_next_connect = {
    while(!pending_connects.isEmpty && current_connects < max_concurrent_connects) {
      val fun = pending_connects.remove(0)
      current_connects += 1
      fun()
    }
  }

  private def connecting_start(fun : =>Unit) = scenario_queue {
    pending_connects.append(fun _)
    kick_off_next_connect
  }

  private def connecting_end = scenario_queue {
    current_connects -= 1
    kick_off_next_connect
  }


  def createProducer(i:Int) = {
    if(this.request_response) {
      new RequestingClient((i))
    } else {
      new ProducerClient(i)
    }
  }
  def createConsumer(i:Int) = {
    if(this.request_response) {
      new RespondingClient(i)
    } else {
      new ConsumerClient(i)
    }
  }

  trait NonBlockingClient extends Client {

    protected var queue = createQueue("client")

    var message_counter=0L
    var reconnect_delay = 0L

    sealed trait State

    case class INIT() extends State

    case class CONNECTING(host: String, port: Int, on_complete: ()=>Unit) extends State {
      
      def connect():Unit = {
        connecting_start { queue {
          val stomp = new Stomp(new URI(protocol+"://" + host + ":" + port))
          stomp.setDispatchQueue(queue)
          stomp.setVersion("1.0")
          stomp.setSslContext(ssl_context)
          stomp.setHost(null) // RabbitMQ barfs if the host is set.
          stomp.setReceiveBufferSize(receive_buffer_size)
          stomp.setSendBufferSize(send_buffer_size)
          login.foreach(stomp.setLogin(_))
          passcode.foreach(stomp.setPasscode(_))
          stomp.connectCallback(new Callback[CallbackConnection](){
            override def onSuccess(connection: CallbackConnection) {
              connecting_end
              state match {
                case x:CONNECTING =>
                  state = CONNECTED(connection)
                  on_complete()
                  connection.resume()
                case _ =>
                  connection.close(null)
              }
            }
            override def onFailure(value: Throwable) {
              connecting_end
              on_failure(value)
            }
          })
        }}
      }

      // We may need to delay the connection attempt.
      if( reconnect_delay==0 ) {
        connect
      } else {
        queue.after(5, TimeUnit.SECONDS) {
          if ( this == state ) {
            reconnect_delay=0
            connect
          }
        }
      }

      def close() = {
        state = DISCONNECTED()
      }

      def on_failure(e:Throwable) = {
        if( display_errors ) {
          e.printStackTrace
        }
        error_counter.incrementAndGet
        reconnect_delay = 1000
        close
      }

    }

    case class CONNECTED(val connection:CallbackConnection) extends State {

      connection.receive(new Callback[StompFrame](){
        override def onFailure(value: Throwable) = on_failure(value)
        override def onSuccess(value: StompFrame) = on_receive(value)
      })

      def close() = {
        state = CLOSING()
        connection.close(^{
          state = DISCONNECTED()
        })
      }

      def on_failure(e:Throwable) = {
        if( display_errors ) {
          e.printStackTrace
        }
        error_counter.incrementAndGet
        reconnect_delay = 1000
        close
      }

    }
    case class CLOSING() extends State

    case class DISCONNECTED() extends State {
      queue {
        if( state==this ){
          if( done.get ) {
            has_shutdown.countDown
          } else {
            reconnect_action
          }
        }
      }
    }

    var state:State = INIT()

    val has_shutdown = new CountDownLatch(1)
    def reconnect_action:Unit

    def on_failure(e:Throwable) = state match {
      case x:CONNECTING => x.on_failure(e)
      case x:CONNECTED => x.on_failure(e)
      case _ =>
    }

    def start = queue {
      state = DISCONNECTED()
    }

    def queue_check = queue.assertExecuting()

    def open(host: String, port: Int)(on_complete: =>Unit) = {
      assert ( state.isInstanceOf[DISCONNECTED] )
      queue_check
      state = CONNECTING(host, port, ()=>on_complete)
    }

    def close() = {
      queue_check
      state match {
        case x:CONNECTING => x.close
        case x:CONNECTED => x.close
        case _ =>
      }
    }

    def shutdown = {
      assert(done.get)
      queue {
        close
      }
      has_shutdown.await()
    }

    def send(frame:StompFrame)(func: =>Unit) = {
      queue_check
      state match {
        case state:CONNECTED => state.connection.send(frame, new Callback[Void](){
          override def onSuccess(value: Void) {
            func
          }
          override def onFailure(value: Throwable) = on_failure(value)
        })
        case _ =>
      }
    }

    def request(frame:StompFrame)(func: (StompFrame)=>Unit) = {
      queue_check
      state match {
        case state:CONNECTED => state.connection.request(frame, new Callback[StompFrame](){
          override def onSuccess(value: StompFrame) {
            func(value)
          }
          override def onFailure(value: Throwable) = on_failure(value)
        })
        case _ =>
      }
    }

    def receive_suspend = {
      queue_check
      state match {
        case state:CONNECTED => state.connection.suspend()
        case _ =>
      }
    }

    def receive_resume = {
      queue_check
      state match {
        case state:CONNECTED => state.connection.resume()
        case _ =>
      }
    }

    def on_receive(e:StompFrame) = {
    }

    def connect(proc: =>Unit):Unit = {
      queue_check
      if( !done.get ) {
        open(host, port) {
          proc
        }
      }
    }

    def name:String
  }
  
  def header_key(v:String) = ascii(v.split(":")(0))
  def header_value(v:String) = ascii(v.split(":")(1))
  
  val persistent_header_key = header_key(persistent_header)
  val persistent_header_value = header_value(persistent_header)

  class ProducerClient(val id: Int) extends NonBlockingClient {
    val name: String = "producer " + id
    queue.setLabel(name)
    val message_cache = HashMap.empty[Int, AsciiBuffer]
    val message_frame = new StompFrame(SEND)
    message_frame.addHeader(DESTINATION,ascii(destination(id)))
    if(persistent) message_frame.addHeader(persistent_header_key,persistent_header_value)
    headers_for(id).foreach{ x=>
      message_frame.addHeader(header_key(x), header_value(x))
    }

    override def reconnect_action = {
      connect {
        write_action
      }
    }

    def write_action:Unit = {
      def retry:Unit = {
        if(done.get) {
          close
        } else {
          if(producer_sleep >= 0) {
            message_frame.content(get_message())
            if( sync_send ) {
              request(message_frame) { resp =>
                producer_counter.incrementAndGet()
                message_counter += 1
                write_completed_action
              }
            } else {
              send(message_frame) {
                producer_counter.incrementAndGet()
                message_counter += 1
                write_completed_action
              }
            }
          } else {
            write_completed_action
          }
        }
      }
      retry
    }

    def write_completed_action:Unit = {
      def doit = {
        val m_p_connection = messages_per_connection.toLong
        if(m_p_connection > 0 && message_counter >= m_p_connection) {
          message_counter = 0
          close
        } else {
          write_action
        }
      }

      if(done.get) {
        close
      } else {
        if(producer_sleep != 0) {
          queue.after(math.abs(producer_sleep), TimeUnit.MILLISECONDS) {
            doit
          }
        } else {
          queue { doit }
        }
      }
    }
  
    def get_message() = {
      val m_s = message_size
      
      if(! message_cache.contains(m_s)) {
        message_cache(m_s) = message(name, m_s)
      }
      
      message_cache(m_s)
    }
  
    def message(name:String, size:Int) = {
      val buffer = new StringBuffer(size)
      buffer.append("Message from " + name + "\n")
      for( i <- buffer.length to size ) {
        buffer.append(('a'+(i%26)).toChar)
      }
      var rc = buffer.toString
      if( rc.length > size ) {
        rc.substring(0, size)
      } else {
        rc
      }
      ascii(rc)
    }
  
  }

  class ConsumerClient(val id: Int) extends NonBlockingClient {
    val name: String = "consumer " + id
    queue.setLabel(name)
    val clientAck = ack == "client"
    val subscriber_id = ascii(consumer_prefix+id)
    override def reconnect_action = {
      connect {
        val sub = new StompFrame(SUBSCRIBE)
        sub.addHeader(ID, subscriber_id)
        sub.addHeader(ACK_MODE, ascii(ack))
        sub.addHeader(DESTINATION, ascii(destination(id)))
        subscribe_headers_for(id).foreach{ x=>
          sub.addHeader(header_key(x), header_value(x))
        }
        if(durable) {
          sub.addHeader(PERSISTENT, TRUE)
        }
        if(selector!=null) {
          sub.addHeader(SELECTOR, ascii(selector))
        }
        send(sub) {
        }
      }
    }

    def index_of(haystack:Array[Byte], needle:Array[Byte]):Int = {
      var i = 0
      while( haystack.length >= i+needle.length ) {
        if( haystack.startsWith(needle, i) ) {
          return i
        }
        i += 1
      }
      return -1
    }


    var pending_acks = 0
    val max_pending_acks = 1

    override def on_receive(msg: StompFrame) = {
      if( consumer_sleep != 0 && ((consumer_counter.get()%consumer_sleep_modulo) == 0)) {
        pending_acks += 1
        if( !clientAck || pending_acks > max_pending_acks ) {
          receive_suspend
        }
        queue.after(math.abs(consumer_sleep), TimeUnit.MILLISECONDS) {
          if( !clientAck || pending_acks > max_pending_acks) {
            receive_resume
          }
          pending_acks -= 1
          process_message(msg)
        }
      } else {
        process_message(msg)
      }
    }

    def process_message(msg: StompFrame) = {
      if( clientAck ) {
        val msgId = msg.getHeader(Constants.MESSAGE_ID)
        val ack = new StompFrame(ACK)
        ack.addHeader(Constants.MESSAGE_ID, msgId)
        ack.addHeader(SUBSCRIPTION, subscriber_id)
        send(ack){
          consumer_counter.incrementAndGet()
        }
      } else {
        consumer_counter.incrementAndGet()
      }
    }

  }

  class RequestingClient(id: Int) extends ProducerClient(id) {
    override val name: String = "requestor " + id
    queue.setLabel(name)
    message_frame.addHeader(REPLY_TO,ascii(response_destination(id)))


    val subscriber_id = ascii("requestor-"+id)
    override def reconnect_action = {
      connect {
        val sub = new StompFrame(SUBSCRIBE)
        sub.addHeader(ID, subscriber_id)
        sub.addHeader(ACK_MODE, ascii(ack))
        sub.addHeader(DESTINATION, ascii(response_destination(id)))
        subscribe_headers_for(id).foreach{ x=>
          sub.addHeader(header_key(x), header_value(x))
        }
        send(sub) {
        }
        // give the response queue a chance to drain before doing new requests.
        queue.after(1000, TimeUnit.MILLISECONDS) {
          write_action
        }
      }
    }

    var request_start = 0L

    override def write_action:Unit = {
      def retry:Unit = {
        if(done.get) {
          close
        } else {
          if(producer_sleep >= 0) {
            message_frame.content(get_message())
            request_start = System.nanoTime()
            send(message_frame) {
              // don't do anything.. we complete when
              // on_receive gets called.
            }
          } else {
            write_completed_action
          }
        }
      }
      retry
    }

    override def on_receive(msg: StompFrame) = {
      if(request_start != 0L) {
        request_times.add(System.nanoTime() - request_start)
        request_start = 0
        producer_counter.incrementAndGet()
        message_counter += 1
        write_completed_action
      }
    }

  }

  class RespondingClient(id: Int) extends ConsumerClient(id) {
    override def process_message(msg: StompFrame) = {

      def send_ack = {
        if( clientAck ) {
          val msgId = msg.getHeader(Constants.MESSAGE_ID)
          val ack = new StompFrame(ACK)
          ack.addHeader(Constants.MESSAGE_ID, msgId)
          ack.addHeader(SUBSCRIPTION, subscriber_id)
          send(ack) {
            consumer_counter.incrementAndGet()
          }
        } else {
          consumer_counter.incrementAndGet()
        }
      }

      val reply_to = msg.getHeader(REPLY_TO)
      if( reply_to !=null ) {
        val response = new StompFrame(SEND)
        response.addHeader(DESTINATION,reply_to)
        val p = msg.getHeader(persistent_header_key)
        if(p!=null) response.addHeader(persistent_header_key,p)
        send(response) {
          send_ack
        }
      } else {
        send_ack
      }

    }
  }
}
