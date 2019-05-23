/**
 * Copyright (c) 2019 Dell Inc., or its subsidiaries. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package io.pravega.connectors.spark

import java.net.URI
import java.util.{Locale, Optional}

import io.pravega.client.ClientConfig
import io.pravega.client.admin.StreamManager
import io.pravega.client.stream.{ScalingPolicy, StreamConfiguration, StreamCut}
import org.apache.spark.internal.Logging
import org.apache.spark.sql.sources._
import org.apache.spark.sql.sources.v2._
import org.apache.spark.sql.sources.v2.reader.streaming.MicroBatchReader
import org.apache.spark.sql.sources.v2.writer.DataSourceWriter
import org.apache.spark.sql.sources.v2.writer.streaming.StreamWriter
import org.apache.spark.sql.streaming.OutputMode
import org.apache.spark.sql.types._
import org.apache.spark.sql.{SQLContext, SaveMode}
import resource.managed

import scala.collection.JavaConverters._

object Encoding extends Enumeration {
  type Encoding = Value
  val None: Value = Value("none")
  val Chunked_v1: Value = Value("chunked_v1")
}

class PravegaSourceProvider extends DataSourceV2
  with MicroBatchReadSupport
  with RelationProvider
  with DataSourceRegister
  with StreamWriteSupport
  with WriteSupport
  with Logging {

  private val DEFAULT_CONTROLLER = "tcp://localhost:9090"
  private val DEFAULT_TRANSACTION_TIMEOUT_MS: Long = 30*1000
  private val DEFAULT_BATCH_TRANSACTION_TIMEOUT_MS: Long = 2*60*1000   // 2 minutes (maximum allowed by default server)

  /** String that represents the format that this data source provider uses. */
  override def shortName(): String = PravegaSourceProvider.SOURCE_PROVIDER_NAME

  /**
    * Creates a {@link MicroBatchReader} to read batches of data from this data source in a
    * streaming query.
    * This is used to read a Pravega stream as a datastream in a structured streaming job.
    *
    * The execution engine will create a micro-batch reader at the start of a streaming query,
    * alternate calls to setOffsetRange and planInputPartitions for each batch to process, and
    * then call stop() when the execution is complete. Note that a single query may have multiple
    * executions due to restart or failure recovery.
    *
    * @param schema             the user provided schema, or empty() if none was provided
    * @param checkpointLocation a path to Hadoop FS scratch space that can be used for failure
    *                           recovery. Readers for the same logical source in the same query
    *                           will be given the same checkpointLocation.
    * @param options            the options for the returned data source reader, which is an immutable
    *                           case-insensitive string-to-string map.
    */
  override def createMicroBatchReader(
                                       schema: Optional[StructType],
                                       checkpointLocation: String,
                                       options: DataSourceOptions): MicroBatchReader = {

    val parameters = options.asMap().asScala.toMap
    val caseInsensitiveParams = parameters.map { case (k, v) => (k.toLowerCase(Locale.ROOT), v) }
    validateStreamOptions(caseInsensitiveParams)

    val clientConfig = buildClientConfig(caseInsensitiveParams)
    val scopeName = caseInsensitiveParams(PravegaSourceProvider.SCOPE_OPTION_KEY)
    val streamName = caseInsensitiveParams(PravegaSourceProvider.STREAM_OPTION_KEY)
    val encoding = Encoding.withName(caseInsensitiveParams.getOrElse(PravegaSourceProvider.ENCODING_OPTION_KEY, Encoding.None.toString))

    val startStreamCut = PravegaSourceProvider.getPravegaStreamCut(
      caseInsensitiveParams, PravegaSourceProvider.START_STREAM_CUT_OPTION_KEY, LatestStreamCut)

    val endStreamCut = PravegaSourceProvider.getPravegaStreamCut(
      caseInsensitiveParams, PravegaSourceProvider.END_STREAM_CUT_OPTION_KEY, UnboundedStreamCut)

    log.info(s"createMicroBatchReader: clientConfig=${clientConfig}, scopeName=${scopeName}, streamName=${streamName}, encoding=${encoding}"
      + s" startStreamCut=${startStreamCut}, endStreamCut=${endStreamCut}")

    createStreams(caseInsensitiveParams)

    new PravegaMicroBatchReader(
      scopeName,
      streamName,
      clientConfig,
      encoding,
      options,
      startStreamCut,
      endStreamCut)
  }

  /**
    * Returns a new base relation with the given parameters.
    * This is used to read a Pravega stream as a dataframe in a batch job.
    *
    * Unbounded stream cuts (earliest, latest, unbounded) are bound only once. 
    * Late binding is not available.
    *
    * @note The parameters' keywords are case insensitive and this insensitivity is enforced
    *       by the Map that is passed to the function.
    */
  override def createRelation(
                               sqlContext: SQLContext,
                               parameters: Map[String, String]): BaseRelation = {

    log.info(s"createRelation: parameters=${parameters}")
    val caseInsensitiveParams = parameters.map { case (k, v) => (k.toLowerCase(Locale.ROOT), v) }
    validateBatchOptions(caseInsensitiveParams)

    val clientConfig = buildClientConfig(caseInsensitiveParams)
    val scopeName = caseInsensitiveParams(PravegaSourceProvider.SCOPE_OPTION_KEY)
    val streamName = caseInsensitiveParams(PravegaSourceProvider.STREAM_OPTION_KEY)
    val encoding = Encoding.withName(caseInsensitiveParams.getOrElse(PravegaSourceProvider.ENCODING_OPTION_KEY, Encoding.None.toString))

    val startStreamCut = PravegaSourceProvider.getPravegaStreamCut(
      caseInsensitiveParams, PravegaSourceProvider.START_STREAM_CUT_OPTION_KEY, EarliestStreamCut)
    assert(startStreamCut != LatestStreamCut)

    val endStreamCut = PravegaSourceProvider.getPravegaStreamCut(
      caseInsensitiveParams, PravegaSourceProvider.END_STREAM_CUT_OPTION_KEY, LatestStreamCut)
    assert(endStreamCut != EarliestStreamCut)

    log.info(s"createRelation: clientConfig=${clientConfig}, scopeName=${scopeName}, streamName=${streamName}, encoding=${encoding}"
      + s" startStreamCut=${startStreamCut}, endStreamCut=${endStreamCut}")

    createStreams(caseInsensitiveParams)

    new PravegaRelation(
      sqlContext,
      parameters,
      scopeName,
      streamName,
      clientConfig,
      encoding,
      startStreamCut,
      endStreamCut)
  }

  /**
    * Creates an optional {@link StreamWriter} to save the data to this data source. Data
    * sources can return None if there is no writing needed to be done.
    * This is used to write a datastream to a Pravega stream in a structured streaming job.
    *
    * @param queryId A unique string for the writing query. It's possible that there are many
    *                writing queries running at the same time, and the returned
    *                { @link DataSourceWriter} can use this id to distinguish itself from others.
    * @param schema  the schema of the data to be written.
    * @param mode    the output mode which determines what successive epoch output means to this
    *                sink, please refer to { @link OutputMode} for more details.
    * @param options the options for the returned data source writer, which is an immutable
    *                case-insensitive string-to-string map.
    */
  override def createStreamWriter(
                                   queryId: String,
                                   schema: StructType,
                                   mode: OutputMode,
                                   options: DataSourceOptions): StreamWriter = {

    val parameters = options.asMap().asScala.toMap
    val caseInsensitiveParams = parameters.map { case (k, v) => (k.toLowerCase(Locale.ROOT), v) }
    validateStreamOptions(caseInsensitiveParams)

    val clientConfig = buildClientConfig(caseInsensitiveParams)
    val scopeName = caseInsensitiveParams(PravegaSourceProvider.SCOPE_OPTION_KEY)
    val streamName = caseInsensitiveParams(PravegaSourceProvider.STREAM_OPTION_KEY)
    val transactionTimeoutTime = caseInsensitiveParams.get(PravegaSourceProvider.TRANSACTION_TIMEOUT_MS_OPTION_KEY) match {
      case Some(s: String) => s.toLong
      case None => DEFAULT_TRANSACTION_TIMEOUT_MS
    }

    log.info(s"createStreamWriter: clientConfig=${clientConfig}, scopeName=${scopeName}, streamName=${streamName}, transactionTimeoutTime=${transactionTimeoutTime}")

    createStreams(caseInsensitiveParams)

    new PravegaWriter(scopeName, streamName, clientConfig, transactionTimeoutTime, schema)
  }

  /**
    * Creates an optional {@link DataSourceWriter} to save the data to this data source. Data
    * sources can return None if there is no writing needed to be done according to the save mode.
    *
    * This is used to write a dataframe to a Pravega stream in a batch job.
    *
    * If this method fails (by throwing an exception), the action will fail and no Spark job will be
    * submitted.
    *
    * @param writeUUID A unique string for the writing job. It's possible that there are many writing
    *                  jobs running at the same time, and the returned { @link DataSourceWriter} can
    *                                                                          use this job id to distinguish itself from other jobs.
    * @param schema the schema of the data to be written.
    * @param mode   the save mode which determines what to do when the data are already in this data
    *               source, please refer to { @link SaveMode} for more details.
    * @param options the options for the returned data source writer, which is an immutable
    *                case-insensitive string-to-string map.
    * @return a writer to append data to this data source
    */
  override def createWriter(
                             writeUUID: String,
                             schema: StructType,
                             mode: SaveMode,
                             options: DataSourceOptions): Optional[DataSourceWriter] = {

    val parameters = options.asMap().asScala.toMap
    val caseInsensitiveParams = parameters.map { case (k, v) => (k.toLowerCase(Locale.ROOT), v) }
    validateBatchOptions(caseInsensitiveParams)

    mode match {
      case SaveMode.Overwrite | SaveMode.Ignore =>
        throw new IllegalArgumentException(s"Save mode $mode not allowed for Pravega. " +
          s"Allowed save modes are ${SaveMode.Append} and " +
          s"${SaveMode.ErrorIfExists} (default).")
      case _ => // good
    }

    val clientConfig = buildClientConfig(caseInsensitiveParams)
    val scopeName = caseInsensitiveParams(PravegaSourceProvider.SCOPE_OPTION_KEY)
    val streamName = caseInsensitiveParams(PravegaSourceProvider.STREAM_OPTION_KEY)
    val transactionTimeoutTime = caseInsensitiveParams.get(PravegaSourceProvider.TRANSACTION_TIMEOUT_MS_OPTION_KEY) match {
      case Some(s: String) => s.toLong
      case None => DEFAULT_BATCH_TRANSACTION_TIMEOUT_MS
    }

    log.info(s"createWriter: clientConfig=${clientConfig}, scopeName=${scopeName}, streamName=${streamName}, transactionTimeoutTime=${transactionTimeoutTime}")

    createStreams(caseInsensitiveParams)

    Optional.of(new PravegaWriter(scopeName, streamName, clientConfig, transactionTimeoutTime, schema))
  }

  private def validateStreamOptions(caseInsensitiveParams: Map[String, String]): Unit = {
    // TODO: validate options
    validateGeneralOptions(caseInsensitiveParams)
  }

  private def validateBatchOptions(caseInsensitiveParams: Map[String, String]): Unit = {
    // TODO: validate options
    validateGeneralOptions(caseInsensitiveParams)
  }

  private def validateGeneralOptions(caseInsensitiveParams: Map[String, String]): Unit = {
    if (caseInsensitiveParams.getOrElse(PravegaSourceProvider.SCOPE_OPTION_KEY, "").isEmpty) {
      throw new IllegalArgumentException(s"Missing required option '${PravegaSourceProvider.SCOPE_OPTION_KEY}'")
    }
    if (caseInsensitiveParams.getOrElse(PravegaSourceProvider.STREAM_OPTION_KEY, "").isEmpty) {
      throw new IllegalArgumentException(s"Missing required option '${PravegaSourceProvider.STREAM_OPTION_KEY}'")
    }
  }

  private def buildClientConfig(caseInsensitiveParams: Map[String, String]): ClientConfig = {
    val controllerURI = URI.create(caseInsensitiveParams.getOrElse(PravegaSourceProvider.CONTROLLER_OPTION_KEY, DEFAULT_CONTROLLER))
    ClientConfig.builder()
      .controllerURI(controllerURI)
      .build()
  }

  private def createStreams(caseInsensitiveParams: Map[String, String]): Unit = {
    val clientConfig = buildClientConfig(caseInsensitiveParams)
    for (streamManager <- managed(StreamManager.create(clientConfig))) {
      val allowCreateScope = caseInsensitiveParams.getOrElse(PravegaSourceProvider.ALLOW_CREATE_SCOPE_OPTION_KEY, "true").toBoolean
      val scopeName = caseInsensitiveParams(PravegaSourceProvider.SCOPE_OPTION_KEY)
      if (allowCreateScope) streamManager.createScope(scopeName)

      val streamName = caseInsensitiveParams(PravegaSourceProvider.STREAM_OPTION_KEY)
      val allowCreateStream = caseInsensitiveParams.getOrElse(PravegaSourceProvider.ALLOW_CREATE_STREAM_OPTION_KEY, "true").toBoolean
      if (allowCreateStream) {
        var streamConfig = StreamConfiguration.builder
          .scope(scopeName)
          .streamName(streamName)
        streamConfig = caseInsensitiveParams.get(PravegaSourceProvider.DEFAULT_NUM_SEGMENTS_OPTION_KEY) match {
          case Some(n) => streamConfig.scalingPolicy(ScalingPolicy.fixed(n.toInt))
          case None => streamConfig
        }
        streamManager.createStream(scopeName, streamName, streamConfig.build())
      }
    }
  }
}

object PravegaSourceProvider extends Logging {
  private[spark] val SOURCE_PROVIDER_NAME = "pravega"
  private[spark] val CONTROLLER_OPTION_KEY = "controller"
  private[spark] val SCOPE_OPTION_KEY = "scope"
  private[spark] val STREAM_OPTION_KEY = "stream"
  private[spark] val TRANSACTION_TIMEOUT_MS_OPTION_KEY = "transaction_timeout_ms"
  private[spark] val ENCODING_OPTION_KEY = "encoding"
  private[spark] val START_STREAM_CUT_OPTION_KEY = "start_stream_cut"
  private[spark] val END_STREAM_CUT_OPTION_KEY = "end_stream_cut"
  private[spark] val ALLOW_CREATE_SCOPE_OPTION_KEY = "allow_create_scope"
  private[spark] val ALLOW_CREATE_STREAM_OPTION_KEY = "allow_create_stream"
  private[spark] val DEFAULT_NUM_SEGMENTS_OPTION_KEY = "default_num_segments"
  private[spark] val STREAM_CUT_EARLIEST = "earliest"
  private[spark] val STREAM_CUT_LATEST = "latest"
  private[spark] val STREAM_CUT_UNBOUNDED = "unbounded"
  private[spark] val ROUTING_KEY_ATTRIBUTE_NAME = "routing_key"
  private[spark] val EVENT_ATTRIBUTE_NAME = "event"

  def getPravegaStreamCut(
                                     params: Map[String, String],
                                     streamCutOptionKey: String,
                                     defaultStreamCut: PravegaStreamCut): PravegaStreamCut = {
    params.get(streamCutOptionKey).map(_.trim) match {
      case Some(offset) if offset.toLowerCase(Locale.ROOT) == STREAM_CUT_LATEST =>
        LatestStreamCut
      case Some(offset) if offset.toLowerCase(Locale.ROOT) == STREAM_CUT_EARLIEST =>
        EarliestStreamCut
      case Some(offset) if offset.toLowerCase(Locale.ROOT) == STREAM_CUT_UNBOUNDED =>
        UnboundedStreamCut
      case Some(base64String) => SpecificStreamCut(StreamCut.from(base64String))
      case None => defaultStreamCut
    }
  }
}

object PravegaReader {
  private[spark] val EVENT_FIELD_NAME = "event"
  private[spark] val SCOPE_FIELD_NAME = "scope"
  private[spark] val STREAM_FIELD_NAME = "stream"
  private[spark] val SEGMENT_ID_FIELD_NAME = "segment_id"
  private[spark] val OFFSET_FIELD_NAME = "offset"

  def pravegaSchema: StructType = StructType(Seq(
    StructField(EVENT_FIELD_NAME, BinaryType),
    StructField(SCOPE_FIELD_NAME, StringType),
    StructField(STREAM_FIELD_NAME, StringType),
    StructField(SEGMENT_ID_FIELD_NAME, LongType),
    StructField(OFFSET_FIELD_NAME, LongType)
  ))
}
