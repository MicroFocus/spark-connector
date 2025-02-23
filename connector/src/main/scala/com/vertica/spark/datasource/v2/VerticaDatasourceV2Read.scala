// (c) Copyright [2020-2021] Micro Focus or one of its affiliates.
// Licensed under the Apache License, Version 2.0 (the "License");
// You may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.vertica.spark.datasource.v2

import com.typesafe.scalalogging.Logger
import org.apache.spark.sql.connector.read._
import org.apache.spark.sql.types._
import org.apache.spark.sql.catalyst.InternalRow
import com.vertica.spark.config.{LogProvider, ReadConfig}
import com.vertica.spark.datasource.core.{DSConfigSetupInterface, DSReader, DSReaderInterface}
import com.vertica.spark.util.error.{ConnectorError, ErrorHandling, InitialSetupPartitioningError}
import com.vertica.spark.util.pushdown.PushdownUtils
import org.apache.spark.sql.sources.Filter

trait PushdownFilter {
  def getFilterString: String
}

case class PushFilter(filter: Filter, filterString: String) extends PushdownFilter {
  def getFilterString: String = this.filterString
}

case class NonPushFilter(filter: Filter) extends AnyVal

case class ExpectedRowDidNotExistError() extends ConnectorError {
  def getFullContext: String = "Fatal error: expected row did not exist"
}

/**
  * Builds the scan class for use in reading of Vertica
  */
class VerticaScanBuilder(config: ReadConfig, readConfigSetup: DSConfigSetupInterface[ReadConfig]) extends ScanBuilder with
  SupportsPushDownFilters with SupportsPushDownRequiredColumns {
  private var pushFilters: List[PushFilter] = Nil

  private var requiredSchema: StructType = StructType(Nil)

/**
  * Builds the class representing a scan of a Vertica table
  *
  * @return [[VerticaScan]]
  */
  override def build(): Scan = {
    val cfg = config.copyConfig()
    cfg.setPushdownFilters(this.pushFilters)
    cfg.setRequiredSchema(this.requiredSchema)

    new VerticaScan(cfg, readConfigSetup)
  }

  override def pushFilters(filters: Array[Filter]): Array[Filter] = {
    val initialLists: (List[NonPushFilter], List[PushFilter]) = (List(), List())
    val (nonPushFilters, pushFilters): (List[NonPushFilter], List[PushFilter]) = filters
      .map(PushdownUtils.genFilter)
      .foldLeft(initialLists)((acc, filter) => {
        val (nonPushFilters, pushFilters) = acc
        filter match {
          case Left(nonPushFilter) => (nonPushFilter :: nonPushFilters, pushFilters)
          case Right(pushFilter) => (nonPushFilters, pushFilter :: pushFilters)
        }
      })

    this.pushFilters = pushFilters

    nonPushFilters.map(_.filter).toArray
  }

  override def pushedFilters(): Array[Filter] = {
    this.pushFilters.map(_.filter).toArray
  }

  override def pruneColumns(requiredSchema: StructType): Unit = {
    this.requiredSchema = requiredSchema
  }
}


/**
  * Represents a scan of a Vertica table.
  *
  * Extends mixin class to represent type of read. Options are Batch or Stream, we are doing a batch read.
  */
class VerticaScan(config: ReadConfig, readConfigSetup: DSConfigSetupInterface[ReadConfig]) extends Scan with Batch {

  private val logger: Logger = LogProvider.getLogger(classOf[VerticaScan])

  def getConfig: ReadConfig = config

  /**
  * Schema of scan (can be different than full table schema)
  */
  override def readSchema(): StructType = {
    (readConfigSetup.getTableSchema(config), config.getRequiredSchema) match {
      case (Right(schema), requiredSchema) => if (requiredSchema.nonEmpty) { requiredSchema } else { schema }
      case (Left(err), _) => ErrorHandling.logAndThrowError(logger, err)
    }
  }

/**
  * Returns this object as an instance of the Batch interface
  */
  override def toBatch: Batch = this


/**
  * Returns an array of partitions. These contain the information necesary for each reader to read it's portion of the data
  */
  override def planInputPartitions(): Array[InputPartition] = {
   readConfigSetup
      .performInitialSetup(config) match {
      case Left(err) => ErrorHandling.logAndThrowError(logger, err)
      case Right(opt) => opt match {
        case None => ErrorHandling.logAndThrowError(logger, InitialSetupPartitioningError())
        case Some(partitionInfo) => partitionInfo.partitionSeq
      }
    }
  }


/**
  * Creates the reader factory which will be serialized and sent to workers
  *
  * @return [[VerticaReaderFactory]]
  */
  override def createReaderFactory(): PartitionReaderFactory = {
    new VerticaReaderFactory(config)
  }
}

/**
  * Factory class for creating the Vertica reader
  *
  * This class is seriazlized and sent to each worker node. On the worker, createReader will be called with the given partition of data for that worker.
  */
class VerticaReaderFactory(config: ReadConfig) extends PartitionReaderFactory {
/**
  * Called from the worker node to get the reader for that node
  *
  * @return [[VerticaBatchReader]]
  */
  override def createReader(partition: InputPartition): PartitionReader[InternalRow] =
  {
    new VerticaBatchReader(config, new DSReader(config, partition))
  }

}

/**
  * Reader class that reads rows from the underlying datasource
  */
class VerticaBatchReader(config: ReadConfig, reader: DSReaderInterface) extends PartitionReader[InternalRow] {
  private val logger: Logger = LogProvider.getLogger(classOf[VerticaBatchReader])

  // Open the read
  reader.openRead() match {
    case Right(_) => ()
    case Left(err) => ErrorHandling.logAndThrowError(logger, err)
  }

  var row: Option[InternalRow] = None

/**
  * Returns true if there are more rows to read
  */
  override def next: Boolean =
  {
    reader.readRow() match {
      case Left(err) => ErrorHandling.logAndThrowError(logger, err)
      case Right(r) =>
        row = r
    }
    row match {
      case Some(_) => true
      case None => false
    }
  }

/**
  * Return the current row
  */
  override def get: InternalRow = {
    row match {
      case None => ErrorHandling.logAndThrowError(logger, ExpectedRowDidNotExistError())
      case Some(v) => v
    }
  }

/**
  * Calls underlying datasource to do any needed cleanup
  */
  def close(): Unit = {
    reader.closeRead() match {
      case Right(_) => ()
      case Left(e) => ErrorHandling.logAndThrowError(logger, e)
    }
  }
}
