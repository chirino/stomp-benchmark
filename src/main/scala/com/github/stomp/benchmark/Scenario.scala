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

import java.util.concurrent.atomic._
import java.util.concurrent.TimeUnit._
import scala.collection.mutable.ListBuffer
import java.util.concurrent.ConcurrentLinkedQueue
import java.security.KeyStore
import java.io.FileInputStream
import javax.net.ssl._
import org.fusesource.hawtdispatch.transport.{SslTransport, TcpTransport}

object Scenario {
  val MESSAGE_ID:Array[Byte] = "message-id"
  val NEWLINE = '\n'.toByte
  val NANOS_PER_SECOND = NANOSECONDS.convert(1, SECONDS)
  val NANOS_PER_MS = NANOSECONDS.convert(1, MILLISECONDS)
  
  implicit def toBytes(value: String):Array[Byte] = value.getBytes("UTF-8")

  def o[T](value:T):Option[T] = value match {
    case null => None
    case x => Some(x)
  }

  def percentiles(percentiles:Array[Double], values:Array[Long]) = {
    if (values.length > 0) {
      java.util.Arrays.sort(values)
      percentiles.map { p =>
        val pos = p * (values.length + 1);
        if (pos < 1) {
          values(0)
        } else if (pos >= values.length) {
          values(values.length - 1);
        } else {
           val lower = values((pos - 1).toInt);
           val upper = values(pos.toInt);
           lower + ((pos - pos.floor) * (upper - lower)).toLong;
        }
      }
    } else {
      percentiles.map(p => 0L)
    }
  }
}

trait Scenario {
  import Scenario._

  var login: Option[String] = None
  var passcode: Option[String] = None
  var request_response = false

  private var _producer_sleep: { def apply(): Int; def init(time: Long) } = new { def apply() = 0; def init(time: Long) {}  }
  def producer_sleep = _producer_sleep()
  def producer_sleep_= (new_value: Int) = _producer_sleep = new { def apply() = new_value; def init(time: Long) {}  }
  def producer_sleep_= (new_func: { def apply(): Int; def init(time: Long) }) = _producer_sleep = new_func

  private var _consumer_sleep: { def apply(): Int; def init(time: Long) } = new { def apply() = 0; def init(time: Long) {}  }
  def consumer_sleep = _consumer_sleep()
  def consumer_sleep_= (new_value: Int) = _consumer_sleep = new { def apply() = new_value; def init(time: Long) {}  }
  def consumer_sleep_= (new_func: { def apply(): Int; def init(time: Long) }) = _consumer_sleep = new_func

  var producers = 1
  var producers_per_sample = 0
  var connections_per_second = 100

  var consumers = 1
  var consumers_per_sample = 0
  var sample_interval = 1000
  var protocol = "tcp"
  var host = "127.0.0.1"
  var port = 61613
  var buffer_size = 32*1024
  
  private var _message_size: { def apply(): Int; def init(time: Long) } = new { def apply() = 1024; def init(time: Long) {}  }
  def message_size = _message_size()
  def message_size_= (new_value: Int) = _message_size = new { def apply() = new_value; def init(time: Long) {}  }
  def message_size_= (new_func: { def apply(): Int; def init(time: Long) }) = _message_size = new_func

  var persistent = false
  var persistent_header = "persistent:true"
  var sync_send = false
  var headers = Array[Array[String]]()
  var subscribe_headers = Array[Array[String]]()
  var ack = "auto"
  var selector:String = null
  var durable = false
  var consumer_prefix = "consumer-"

  var consumer_sleep_modulo = 1
  var producer_sleep_modulo = 1
  private var _messages_per_connection: { def apply(): Int; def init(time: Long) } = new { def apply() = -1; def init(time: Long) {}  }
  def messages_per_connection = _messages_per_connection()
  def messages_per_connection_= (new_value: Int) = _messages_per_connection = new { def apply() = new_value; def init(time: Long) {}  }
  def messages_per_connection_= (new_func: { def apply(): Int; def init(time: Long) }) = _messages_per_connection = new_func

  var display_errors = false

  var destination_type = "queue"
  private var _destination_name: () => String = () => ""
  def destination_name = _destination_name()
  def destination_name_=(new_name: String) = _destination_name = () => new_name
  def destination_name_=(new_func: () => String) = _destination_name = new_func

  private var _response_destination_name: () => String = () => "response"
  def response_destination_name = _response_destination_name()
  def response_destination_name_=(new_name: String) = _response_destination_name = () => new_name
  def response_destination_name_=(new_func: () => String) = _response_destination_name = new_func

  var destination_count = 1

  val producer_counter = new AtomicLong()
  val consumer_counter = new AtomicLong()
  val request_times = new ConcurrentLinkedQueue[Long]()

  val error_counter = new AtomicLong()
  val done = new AtomicBoolean()

  var queue_prefix = "/queue/"
  var topic_prefix = "/topic/"
  var name = "custom"

  var drain_timeout = 2000L

  var key_store_file:Option[String] = None
  var key_store_password:Option[String] = None
  var key_password:Option[String] = None

  var key_store:KeyStore = _
  var trust_managers:Array[TrustManager] = _
  var key_managers:Array[KeyManager] = _

  var receive_buffer_size = 1024*64;
  var send_buffer_size = 1024*64;

  def ssl_context:SSLContext = {
    Option(SslTransport.protocol(protocol)).map { protocol =>
      val rc = SSLContext.getInstance(protocol)
      rc.init(get_key_managers, get_trust_managers, null)
      rc
    }.getOrElse(null)
  }

  def get_key_store = {
    if( key_store==null && key_store_file.isDefined ) {
      key_store = {
        val store = KeyStore.getInstance("JKS")
        store.load(new FileInputStream(key_store_file.get), key_store_password.getOrElse("").toCharArray())
        store
      }
    }
    key_store
  }

  def get_trust_managers = {
    val store = get_key_store
    if( trust_managers==null && store!=null ) {
      val factory = TrustManagerFactory.getInstance("SunX509")
      factory.init(store)
      trust_managers = factory.getTrustManagers
    }
    trust_managers
  }

  def get_key_managers = {
    val store = get_key_store
    if( key_managers==null && store!=null) {
      val factory = KeyManagerFactory.getInstance("SunX509")
      factory.init(store, key_password.getOrElse("").toCharArray())
      key_managers = factory.getKeyManagers
    }
    key_managers
  }


  def run() = {
    print(toString)
    println("--------------------------------------")
    println("     Running: Press ENTER to stop")
    println("--------------------------------------")
    println("")

    with_load {

      // start a sampling client...
      val sample_thread = new Thread() {
        override def run() = {
          
          def print_rate(name: String, periodCount:Long, totalCount:Long, nanos: Long) = {
            val rate_per_second: java.lang.Float = ((1.0f * periodCount / nanos) * NANOS_PER_SECOND)
            println("%s total: %,d, rate: %,.3f per second".format(name, totalCount, rate_per_second))
          }
          def print_percentil(name: String, value:Long, max:Long) = {
            println("%sth percentile response time: %,.3f ms, max: %,.3f ms".format(name, (1.0f * value / NANOS_PER_MS), (1.0f * max / NANOS_PER_MS)))
          }

          try {
            var start = System.nanoTime
            var total_producer_count = 0L
            var total_consumer_count = 0L
            var total_error_count = 0L
            var max_p90 = 0L
            var max_p99 = 0L
            var max_p999 = 0L

            collection_start
            while( !done.get ) {
              Thread.sleep(sample_interval)
              val end = System.nanoTime
              collection_sample
              val samples = collection_end
              samples.get("p_custom").foreach { case (_, count)::Nil =>
                total_producer_count += count
                print_rate("Producer", count, total_producer_count, end - start)
                case _ =>
              }
              samples.get("c_custom").foreach { case (_, count)::Nil =>
                total_consumer_count += count
                print_rate("Consumer", count, total_consumer_count, end - start)
                case _ =>
              }
              samples.get("p90_custom").foreach { case (_, value)::Nil =>
                max_p90 = max_p90.max(value)
                print_percentil("90", value, max_p90)
                case _ =>
              }
              samples.get("p99_custom").foreach { case (_, value)::Nil =>
                max_p99 = max_p99.max(value)
                print_percentil("99", value, max_p99)
                case _ =>
              }
              samples.get("p999_custom").foreach { case (_, value)::Nil =>
                max_p999 = max_p999.max(value)
                print_percentil("99.9", value, max_p999)
                case _ =>
              }
              samples.get("e_custom").foreach { case (_, count)::Nil =>
                if( count!= 0 ) {
                  total_error_count += count
                  print_rate("Error", count, total_error_count, end - start)
                }
                case _ =>
              }
              start = end
            }
          } catch {
            case e:InterruptedException =>
          }
        }
      }
      sample_thread.start()

      System.in.read()
      done.set(true)

      sample_thread.interrupt
      sample_thread.join
    }

  }

  override def toString() = {
    "--------------------------------------\n"+
    "Scenario Settings\n"+
    "--------------------------------------\n"+
    "  host                  = "+host+"\n"+
    "  port                  = "+port+"\n"+
    "  destination_type      = "+destination_type+"\n"+
    "  queue_prefix          = "+queue_prefix+"\n"+
    "  topic_prefix          = "+topic_prefix+"\n"+
    "  destination_count     = "+destination_count+"\n" +
    "  destination_name      = "+destination_name+"\n" +
    "  sample_interval (ms)  = "+sample_interval+"\n" +
    "  \n"+
    "  --- Producer Properties ---\n"+
    "  producers             = "+producers+"\n"+
    "  message_size          = "+message_size+"\n"+
    "  persistent            = "+persistent+"\n"+
    "  sync_send             = "+sync_send+"\n"+
    "  producer_sleep (ms)   = "+producer_sleep+"\n"+
    "  headers               = "+headers.map( _.mkString(", ") ).mkString("(", "), (", ")")+"\n"+
    "  \n"+
    "  --- Consumer Properties ---\n"+
    "  consumers             = "+consumers+"\n"+
    "  consumer_sleep (ms)   = "+consumer_sleep+"\n"+
    "  ack                   = "+ack+"\n"+
    "  selector              = "+selector+"\n"+
    "  durable               = "+durable+"\n"+
    "  consumer_prefix       = "+consumer_prefix+"\n"+
    "  subscribe_headers     = "+subscribe_headers.map( _.mkString(", ") ).mkString("(", "), (", ")")+"\n"+
    ""

  }
  
  def settings(): List[(String, String)] = {
    var s: List[(String, String)] = Nil
    
    s :+= ("host", host)
    s :+= ("port", port.toString)
    s :+= ("destination_type", destination_type)
    s :+= ("queue_prefix", queue_prefix)
    s :+= ("topic_prefix", topic_prefix)
    s :+= ("destination_count", destination_count.toString)
    s :+= ("destination_name", destination_name)
    s :+= ("sample_interval", sample_interval.toString)
    s :+= ("producers", producers.toString)
    s :+= ("message_size", message_size.toString)
    s :+= ("persistent", persistent.toString)
    s :+= ("sync_send", sync_send.toString)
    s :+= ("producer_sleep", producer_sleep.toString)
    s :+= ("headers", headers.map( _.mkString(", ") ).mkString("(", "), (", ")"))
    s :+= ("subscribe_headers", subscribe_headers.map( _.mkString(", ") ).mkString("(", "), (", ")"))
    s :+= ("consumers", consumers.toString)
    s :+= ("consumer_sleep", consumer_sleep.toString)
    s :+= ("ack", ack)
    s :+= ("selector", selector)
    s :+= ("durable", durable.toString)
    s :+= ("consumer_prefix", consumer_prefix)
    
    s
  }

  protected def destination(i:Int) = destination_type match {
    case "queue" => queue_prefix+destination_name+"-"+(i%destination_count)
    case "topic" => topic_prefix+destination_name+"-"+(i%destination_count)
    case "raw_queue" => destination_name
    case "raw_topic" => destination_name
    case _ => throw new Exception("Unsuported destination type: "+destination_type)
  }

  protected def response_destination(i:Int) = queue_prefix+response_destination_name+"-"+i

  protected def headers_for(i:Int) = {
    if ( headers.isEmpty ) {
      Array[String]()
    } else {
      headers(i%headers.size)
    }
  }

  protected def subscribe_headers_for(i:Int) = {
    if ( subscribe_headers.isEmpty ) {
      Array[String]()
    } else {
      subscribe_headers(i%subscribe_headers.size)
    }
  }

  var producer_samples:Option[ListBuffer[(Long,Long)]] = None
  var consumer_samples:Option[ListBuffer[(Long,Long)]] = None
  var error_samples = ListBuffer[(Long,Long)]()

  var request_time_p90_samples = ListBuffer[(Long,Long)]()
  var request_time_p99_samples = ListBuffer[(Long,Long)]()
  var request_time_p999_samples = ListBuffer[(Long,Long)]()

  def collection_start: Unit = {
    producer_counter.set(0)
    consumer_counter.set(0)
    error_counter.set(0)
    request_times.clear()

    producer_samples = if (producers > 0 || producers_per_sample>0 ) {
      Some(ListBuffer[(Long,Long)]())
    } else {
      None
    }
    consumer_samples = if (consumers > 0 || consumers_per_sample>0 ) {
      Some(ListBuffer[(Long,Long)]())
    } else {
      None
    }
    request_time_p90_samples = ListBuffer[(Long,Long)]()
    request_time_p99_samples = ListBuffer[(Long,Long)]()
    request_time_p999_samples = ListBuffer[(Long,Long)]()
  }

  def collection_end: Map[String, scala.List[(Long,Long)]] = {
    var rc = Map[String, List[(Long,Long)]]()
    producer_samples.foreach{ samples =>
      rc += "p_"+name -> samples.toList
      samples.clear
    }
    consumer_samples.foreach{ samples =>
      rc += "c_"+name -> samples.toList
      samples.clear
    }
    rc += "e_"+name -> error_samples.toList
    error_samples.clear
    if(request_response) {
      rc += "p90_"+name -> request_time_p90_samples.toList
      request_time_p90_samples.clear
      rc += "p99_"+name -> request_time_p99_samples.toList
      request_time_p99_samples.clear
      rc += "p999_"+name -> request_time_p999_samples.toList
      request_time_p999_samples.clear
    }

    rc
  }

  trait Client {
    def start():Unit
    def shutdown():Unit
  }

  var producer_clients = List[Client]()
  var consumer_clients = List[Client]()

  def with_load[T](func: =>T ):T = {
    done.set(false)

    val now = System.currentTimeMillis()
    _producer_sleep.init(now)
    _consumer_sleep.init(now)
    _message_size.init(now)
    _messages_per_connection.init(now)

    var created_connections = 0
    var allowed_connections = connections_per_second
    def connection_wait_check = if( connections_per_second > 0 ) {
      if( allowed_connections == 0 ) {
        System.out.println("Started %d connections (pausing for 1 second)", created_connections)
        Thread.sleep(1000)
        allowed_connections = connections_per_second
      }
      allowed_connections -= 1
      created_connections += 1
    }

    for (i <- 0 until consumers) {
      connection_wait_check
      val client = createConsumer(i)
      consumer_clients ::= client
      client.start()
    }

    for (i <- 0 until producers) {
      connection_wait_check
      val client = createProducer(i)
      producer_clients ::= client
      client.start()
    }

    if( created_connections > connections_per_second) {
      System.out.println("All %d connections are now starte", created_connections)
    }
    try {
      func
    } finally {
      done.set(true)
      // wait for the threads to finish..
      for( client <- consumer_clients ) {
        client.shutdown
      }
      consumer_clients = List()
      for( client <- producer_clients ) {
        client.shutdown
      }
      producer_clients = List()
    }
  }

  def drain = {
    done.set(false)
    if( destination_type=="queue" || destination_type=="raw_queue" || durable==true ) {
      print("draining")
      consumer_counter.set(0)
      var consumer_clients = List[Client]()
      for (i <- 0 until destination_count) {
        val client = createConsumer(i)
        consumer_clients ::= client
        client.start()
      }

      // Keep sleeping until we stop draining messages.
      var drained = 0L
      try {
        Thread.sleep(drain_timeout);
        def done() = {
          val c = consumer_counter.getAndSet(0)
          drained += c
          c == 0
        }
        while( !done ) {
          print(".")
          Thread.sleep(drain_timeout);
        }
      } finally {
        done.set(true)
        for( client <- consumer_clients ) {
          client.shutdown
        }
        println(". (drained %d)".format(drained))
      }
    }
  }


  def collection_sample: Unit = {

    val now = System.currentTimeMillis()
    producer_samples.foreach(_.append((now, producer_counter.getAndSet(0))))
    consumer_samples.foreach(_.append((now, consumer_counter.getAndSet(0))))

    if( request_response ) {

      var count = producer_samples.get.last._2.toInt
      val times = new Array[Long](count)
      while(count > 0 ) {
        count -= 1
        times(count) = request_times.poll()
      }
      val p = percentiles(Array(0.9, 0.99, 0.999), times)
      request_time_p90_samples.append((now, p(0)))
      request_time_p99_samples.append((now, p(1)))
      request_time_p999_samples.append((now, p(2)))
    }

    error_samples.append((now, error_counter.getAndSet(0)))

    // we might need to increment number the producers..
    for (i <- 0 until producers_per_sample) {
      val client = createProducer(producer_clients.length)
      producer_clients ::= client
      client.start()
    }

    // we might need to increment number the consumers..
    for (i <- 0 until consumers_per_sample) {
      val client = createConsumer(consumer_clients.length)
      consumer_clients ::= client
      client.start()
    }

  }

  def createProducer(i:Int):Client
  def createConsumer(i:Int):Client

  protected def ignore_failure(func: =>Unit):Unit = try {
    func
  } catch { case _ =>
  }

}


