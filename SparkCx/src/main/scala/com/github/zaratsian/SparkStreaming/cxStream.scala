

/**********************************************************************************************************************

Input Data source = Kafka stream as a pipe-delimited string
Here's an examples input event:
"000Xxx8|895|85Xxx090|D|1|Y|215XxxxX860|25Xxx0|X007|34.2|48.7|-12.3|26-OCT-16 13:02:27|066xx8908|XxxX98|220|DATA_Xx|Xx120|20Xx1|2.0531|0|6.4|-17.0982|-25.4403|4"

Usage:
spark-submit --packages org.apache.spark:spark-streaming-kafka_2.10:1.6.0 --jars /usr/hdp/current/phoenix-client/phoenix-client.jar --class "cxStream" --master yarn-client ./target/SparkStreaming-0.0.1.jar sandbox.hortonworks.com:2181 mytestgroup dztopic1 1

Usage (Docker):
/apache-maven-3.3.9/bin/mvn clean package
/spark/bin/spark-submit --master local[*] --class "cxStream" --jars /phoenix-spark-4.8.1-HBase-1.1.jar target/SparkStreaming-0.0.1.jar phoenix.dev:2181 mytestgroup dztopic1 1 kafka.dev:9092


Create HBase / Phoenix table:

echo "CREATE TABLE IF NOT EXISTS CX_TOPOLOGY (
DEVICE_OF_INTEREST CHAR(30) NOT NULL PRIMARY KEY,
MER_FLAG FLOAT,
NEXT_DEVICE_OF_INTEREST CHAR(30),  
TOPOLOGY_LEVEL INTEGER);" > /tmp/create_topology.sql

/usr/hdp/current/phoenix-client/bin/sqlline.py localhost:2181:/hbase-unsecure /tmp/create_topology.sql 

**********************************************************************************************************************/


import java.util.HashMap
import java.util.Arrays
import java.sql.DriverManager

import org.apache.spark.{SparkContext, SparkConf}
import org.apache.spark.sql.SQLContext
import org.apache.spark.rdd.RDD
import org.apache.spark.streaming._
import org.apache.spark.streaming.kafka._

import org.apache.kafka.clients.producer.{KafkaProducer, ProducerConfig, ProducerRecord}
import _root_.kafka.serializer.StringDecoder

import org.apache.spark.sql.Row
import org.apache.spark.sql.types.{StructType,StructField,StringType,IntegerType,FloatType}

import org.apache.phoenix.spark._

import org.apache.spark.HashPartitioner


object cxStream {

   case class stateCase(sum: Double, red: Double, seen_keys: List[String])

   def main(args: Array[String]) {
      if (args.length < 5) {
         System.err.println("Usage: cxStream <zkQuorum> <group> <topics> <numThreads> <kafkabroker>")
         System.exit(1)
      }

      val batchIntervalSeconds = 10              // 10  seconds
      val slidingInterval = Duration(2000L)      // 2   seconds
      val windowSize = Duration(10000L)          // 10  seconds
      val checkpointInterval = Duration(120000L) // 120 seconds

      val Array(zkQuorum, group, topics, numThreads, kafkabroker) = args
      val sparkConf = new SparkConf().setAppName("cxStream")
      val sc  = new SparkContext(sparkConf)
      val ssc = new StreamingContext(sc, Seconds(batchIntervalSeconds))
      //val sc = ssc.sparkContext
      ssc.checkpoint(".")

      val sqlContext = new SQLContext(sc)
      import sqlContext.implicits._

      // Approach 1: Kafka Receiver-based Approach (http://spark.apache.org/docs/1.6.0/streaming-kafka-integration.html)
      //val topicMap = topics.split(",").map((_, numThreads.toInt)).toMap
      //val events = KafkaUtils.createStream(ssc, zkQuorum, group, topicMap).map(_._2)

      // Approach 2: Kafka Direct Approach
      val topicsSet = topics.split(",").toSet
      //val kafkaParams = Map[String, String]("metadata.broker.list" -> "sandbox.hortonworks.com:6667")
      val kafkaParams = Map[String, String]("metadata.broker.list" -> kafkabroker)
      val events = KafkaUtils.createDirectStream[String, String, StringDecoder, StringDecoder](ssc, kafkaParams, topicsSet).map(_._2)

      /********************************************************************************************
      *
      *  Parse each Kafka (DStream) and add 2 new flags
      *      Rule #1: Bad Wiring
      *      Rule #2: Bad Device
      *
      *********************************************************************************************/

      val event = events.map(_.split("\\|")).map(p =>   
          (p(6), (p(0),p(2),p(6),p(8),p(9).toFloat,p(13),p(19).toFloat,p(20).toInt,p(21).toFloat,p(22).toFloat,p(23).toFloat,

          // Rule #1: Bad Wiring
          if ( (p(19).toFloat >= 4) && (p(20).toFloat <= 2) && (p(22).toFloat >= -10) && (p(23).toFloat >= -15) ) 1 else 0,

          // Rule #2: Bad Device
          if ( (p(9).toFloat < 32 ) ) 1 else 0
          ))
      )

      /******************************************************************
      *  Write Enriched Records to HBase via Phoenix
      *  (with new/enriched flags)
      *******************************************************************/

      event.map(x => x._2 ).print()

/*
      event.map(x => x._2 ).foreachRDD { rdd =>
          rdd.take(1).foreach(x => println(x))
          rdd.saveToPhoenix("CX_HOUSEHOLD_INFO",
              Seq("AAA", "BBB", "CCC", "DDD"),
              zkUrl = Some("sandbox.hortonworks.com:2181:/hbase-unsecure"))
      }
*/

      event.map(x => x._2 ).foreachRDD { rdd =>
            rdd.foreachPartition { rddpartition =>
                //val thinUrl = "jdbc:phoenix:thin:url=http://phoenix.dev:8765;serialization=PROTOBUF"
                val thinUrl = "jdbc:phoenix:phoenix.dev:2181:/hbase"
                val conn = DriverManager.getConnection(thinUrl)
                rddpartition.foreach { record =>
                     conn.createStatement().execute("upsert into CX_HOUSEHOLD_INFO values ('" + record._1 + "', '" + record._2 + "', '" + record._3 + "', '" + record._4 + "', " + record._5 + ", '" + record._6 + "', " + record._7 + ", " + record._8 + ", " + record._9 + ", " + record._10 + ", " + record._11 + ", " + record._12 + ", " + record._13 + ")" )
                     //conn.createStatement().execute("UPSERT INTO CX_TOPOLOGY VALUES('mydevice2',1.0,'next_device2',0)")
                }
                conn.commit()
            }
      } 


      /********************************************************************************************
      *
      *  Join DStream (Data from Kafka) with Static Data (Topology Map)
      *
      *********************************************************************************************/
      val partitioner = new HashPartitioner(2)

      val topology_map = sc.textFile("/mosaic_topology_mapped.csv").map(x => x.split(",")).map(x => (x(1).toString, (x) ) ).cache()

      val eventJoined= event.transform(rdd => topology_map.join(rdd, partitioner))

      //eventJoined.map(x => (x._2._1(0),x._2._1(0) )).print()
      //(215Xxx318,([Ljava.lang.String;@3280e6a2,(0095ZXxx7,978xxx08,2150xx318,Xx07,38.6,7YxxxX8899,1.2181,0,6.4,-30.1787,-36.5279,0,0)))


      /********************************************************************************************
      *  
      *  LEVEL 0 - Calculate Bad Devices at Local Level
      *
      *  Update State by Key (where key = HSE_ID)
      *
      *********************************************************************************************/
 
      val eventLevel0 = eventJoined.map(x => (x._1, (x._1, x._2._2._13.toDouble, 1.0, 0.0, x._2._2._1, x._2._1(2), x._2._1, 0, x._2._1(0))) )

      // INPUTS: (DEVICE_OF_INTEREST, (DEVICE_OF_INTEREST, FLAG, SUM, RED, PREVIOUS_DEVICE, NEXT_DEVICE_OF_INTEREST, DEVICE_TOPOLOGY, TOPOLOGY_LEVEL, MAC) 
      // OUTPUT: (DEVICE_OF_INTEREST, (DEVICE_OF_INTEREST, FLAG, SUM, RED, PREVIOUS_DEVICE, NEXT_DEVICE_OF_INTEREST, DEVICE_TOPOLOGY, TOPOLOGY_LEVEL, MAC))
    
      def trackStateFunc(batchTime: Time, key: String, value: Option[(String, Double, Double, Double, String, String, Array[String], Int, String)], state: State[(Map[String,Double],(String,Double,Double,Double,String,String,Array[String],Int,String))] ): Option[(String, (String, Double, Double, Double, String, String, Array[String], Int, String))] = {
         
          val currentState = state.getOption.getOrElse( (Map("aaa" -> 0.0), ("key", 0.0, 0.0, 0.0, "previous_key", "next_key", Array("temp"), 0, "mac")) )
          val seen_devices = currentState._1
          val previous_device = value.get._5

          if (state.exists()) {
              if (seen_devices.contains(previous_device)) {
                  val sum = currentState._2._3

                  val red_old_value = seen_devices.get(previous_device).get.toDouble
                  val red_new_value = value.get._2
                  
                  val red = if (red_old_value == red_new_value) currentState._2._4 else if (red_old_value > red_new_value) currentState._2._4 - 1.0 else currentState._2._4 + 1.0

                  val red_percentage = red / sum
                  
                  val device_mer_flag = if (red_percentage >= 0.50) 1.0 else 0.0
                  state.update( (seen_devices + (previous_device -> value.get._2) , (key, device_mer_flag, sum, red, value.get._5, value.get._6, value.get._7, value.get._8, value.get._9)) )
                  val output = (key, (key, device_mer_flag, sum, red, value.get._5, value.get._6, value.get._7, value.get._8, value.get._9))
                  Some(output)
              } else {
                  val sum = currentState._2._3 + value.get._3
                  val red = currentState._2._4 + value.get._4
                  val red_percentage = red / sum
                  val device_mer_flag = if (red_percentage >= 0.50) 1.0 else 0.0
                  state.update( (seen_devices + (previous_device -> value.get._2) , (key, device_mer_flag, sum, red, value.get._5, value.get._6, value.get._7, value.get._8, value.get._9)) )
                  val output = (key, (key, device_mer_flag, sum, red, value.get._5, value.get._6, value.get._7, value.get._8, value.get._9))
                  Some(output)
              }
          } else {
              val sum = value.get._3
              //val red = value.get._2
              val red = if (value.get._8 == 0) value.get._2 else value.get._4
              val red_percentage = red / sum
              val device_mer_flag = if (red_percentage >= 0.50) 1.0 else 0.0
              state.update( ( Map(previous_device -> value.get._2) , (key, device_mer_flag, sum, red, value.get._5, value.get._6, value.get._7, value.get._8, value.get._9)) )
              val output = (key, (key, device_mer_flag, sum, red, value.get._5, value.get._6, value.get._7, value.get._8, value.get._9))
              Some(output)
          }

      }

      val initialRDD = sc.parallelize( Array((0.0, (0.0, List("xyz")))) )
 
      val stateSpec = StateSpec.function(trackStateFunc _)
                         //.initialState(initialRDD)
                         //.numPartitions(2)
                         //.timeout(Seconds(60))

      val eventStateLevel0 = eventLevel0.mapWithState(stateSpec)
      eventStateLevel0.print()
      //(2150Xxx81,(2150Xxx81,0.0,000Xxx095Z,474710Xxx,[Ljava.lang.String;@1863ede8,0))

      // Snapshot of the state for the current batch - This DStream contains one entry per key.
      val eventStateSnapshot0 = eventStateLevel0.stateSnapshots() 
      eventStateSnapshot0.print(10)
      //(2150Xxx81,(List(000XXxx095Z),(2150Xxx81,0.0,1.0,0.0,000XXxx5Z,47471Xxx,[Ljava.lang.String;@2e2f30d1,0)))

      /*********************************************************
      *  Write State SnapShot to Phoenix
      **********************************************************/

      eventStateSnapshot0.map(x => (x._2._2._1, x._2._2._2, x._2._2._6, x._2._2._8, x._2._2._9) ).print(15)

/*
      eventStateSnapshot0.map(x => (x._2._2._1, x._2._2._2, x._2._2._6, x._2._2._8) ).foreachRDD { rdd =>
          rdd.take(1).foreach(x => println(x))
          rdd.saveToPhoenix("CX_TOPOLOGY",
              Seq("DEVICE_OF_INTEREST", "FLAG", "NEXT_DEVICE_OF_INTEREST", "TOPOLOGY_LEVEL"),
              zkUrl = Some("phoenix.dev:2181:/hbase"))
      }
*/
      
      eventStateSnapshot0.map(x => (x._2._2._9, x._2._2._2, x._2._2._8) ).foreachRDD { rdd =>
            rdd.foreachPartition { rddpartition =>
                //val thinUrl = "jdbc:phoenix:thin:url=http://phoenix.dev:8765;serialization=PROTOBUF"
                val thinUrl = "jdbc:phoenix:phoenix.dev:2181:/hbase"
                val conn = DriverManager.getConnection(thinUrl)
                rddpartition.foreach { record =>
                     conn.createStatement().execute("upsert into CX_LOOKUP (ID,MER_FLAG,TOPOLOGY_LEVEL) values ('" + record._1 + "', " + record._2 + ", " + record._3 + ")" )
                     //conn.createStatement().execute("UPSERT INTO CX_TOPOLOGY VALUES('mydevice2',1.0,'next_device2',0)")
                }
                conn.commit()
            }
      } 


      /********************************************************************************************
      *  
      *  LEVEL 1 - Calculate Bad devices 1-Level up from local
      *
      *  Update State by Key (where key = first non-household device in topology)
      *
      *********************************************************************************************/

      val eventLevel1 = eventStateSnapshot0.map(x => (x._2._2._6, (x._2._2._6, x._2._2._2, x._2._2._3, x._2._2._4, x._1, x._2._2._7(2), x._2._2._7, 1, x._2._2._7(0))) )

      val eventStateLevel1 = eventLevel1.mapWithState(stateSpec)
      eventStateLevel1.print()
      //(4XX710721Xxx,(4XX710721Xxx,0.0,215Xxxx589,474710720Yyy,[Ljava.lang.String;@4699c13d,1))

      // Snapshot of the state for the current batch - This DStream contains one entry per key.
      val eventStateSnapshot1 = eventStateLevel1.stateSnapshots()
      eventStateSnapshot1.print(10)
      //(47471Xxx,(List(2150205045, 215014Xx, 215X),(4747Xxx,1.0,3.0,2.0,215X,47471Xxx,1)))

      /*********************************************************
      *  Write State SnapShot to Phoenix
      **********************************************************/

      //eventStateSnapshot1.map(x => (x._2._2._1, x._2._2._2, x._2._2._6, x._2._2._8) ).print(10)

      eventStateSnapshot1.map(x => (x._2._2._9, x._2._2._2, x._2._2._8) ).foreachRDD { rdd =>
            rdd.foreachPartition { rddpartition =>
                //val thinUrl = "jdbc:phoenix:thin:url=http://phoenix.dev:8765;serialization=PROTOBUF"
                val thinUrl = "jdbc:phoenix:phoenix.dev:2181:/hbase"
                val conn = DriverManager.getConnection(thinUrl)
                rddpartition.foreach { record =>
                     conn.createStatement().execute("upsert into CX_LOOKUP (ID,MER_FLAG,TOPOLOGY_LEVEL) values ('" + record._1 + "', " + record._2 + ", " + record._3 + ")" )
                }
                conn.commit()
            }
      }


      /********************************************************************************************
      *
      *  LEVEL 2 - Calculate "red" devices 2-Levels up from household
      *  For a device to be "red", over 50% of the devices under this device must be red.
      *
      *  Update State by Key (where key = second non-household device in topology)
      *
      *********************************************************************************************/

      val eventLevel2 = eventStateSnapshot1.map(x => (x._2._2._6, (x._2._2._6, x._2._2._2, x._2._2._3, x._2._2._4, x._1, x._2._2._7(3), x._2._2._7, 2, x._2._2._7(0) )) )

      val eventStateLevel2 = eventLevel2.mapWithState(stateSpec)
      eventStateLevel2.print()
      //(XXX,(XXX,0.0,YYY,XXX,[Ljava.lang.String;@15bd2030,1))

      // Snapshot of the state for the current batch - This DStream contains one entry per key.
      val eventStateSnapshot2 = eventStateLevel2.stateSnapshots()
      eventStateSnapshot2.print(10)
      //(XXX,(List(YYY, ZZZ),(XXX,0.0,2.0,1.0,ZZZ,YYY,1)))

      /*********************************************************
      *  Write State SnapShot to Phoenix
      **********************************************************/

      //eventStateSnapshot2.map(x => (x._2._2._1, x._2._2._2, x._2._2._6, x._2._2._8) ).print(10)

      eventStateSnapshot2.map(x => (x._1.split("_")(0) , x._2._2._2, x._2._2._8) ).foreachRDD { rdd =>
            rdd.foreachPartition { rddpartition =>
                //val thinUrl = "jdbc:phoenix:thin:url=http://phoenix.dev:8765;serialization=PROTOBUF"
                val thinUrl = "jdbc:phoenix:phoenix.dev:2181:/hbase"
                val conn = DriverManager.getConnection(thinUrl)
                rddpartition.foreach { record =>
                     conn.createStatement().execute("upsert into CX_LOOKUP (ID,MER_FLAG,TOPOLOGY_LEVEL) values ('" + record._1 + "', " + record._2 + ", " + record._3 + ")" )
                }
                conn.commit()
            }
      }



      ssc.start()
      ssc.awaitTermination()
   
   }

}


//ZEND
