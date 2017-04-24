Kafka -> Spark Streaming -> Phoenix/HBase
<br>
<br><b>Contents:</b>
<br>
<br>&nbsp;&nbsp;<b>SparkCx:</b> Contains Spark code
<br>&nbsp;&nbsp;<b>Docker:</b> Includes docker containers (Base, Zeppelin/Spark), Kafka, Phoenix, NiFi) and startup scripts.
<br>&nbsp;&nbsp;<b>Phoenix_DDL:</b> Contains the SQL schemas for each Phoenix table.
<br>&nbsp;&nbsp;<b>Scripts:</b> Contains misc scripts used to create the topology mapping table, inject Kafka events, etc.
<br>
<br>References:
<br><a href="http://spark.apache.org/docs/2.0.0/streaming-programming-guide.html">Spark Streaming Docs</a>
<br><a href="http://spark.apache.org/docs/2.0.0/streaming-kafka-integration.html">Spark Streaming + Kafka Integration</a>
<br><a href="https://databricks.com/blog/2016/02/01/faster-stateful-stream-processing-in-apache-spark-streaming.html">Stateful Stream Processing (mapWithState) Example</a>
<br><a href="https://docs.cloud.databricks.com/docs/spark/1.6/examples/Streaming%20mapWithState.html">Databricks MapWithState Example</a>
<br><a href="https://phoenix.apache.org/phoenix_spark.html">Phoenix - Spark Plugin</a>
