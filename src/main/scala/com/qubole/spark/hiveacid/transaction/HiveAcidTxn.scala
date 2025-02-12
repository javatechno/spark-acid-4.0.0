/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.qubole.spark.hiveacid.transaction

import com.qubole.shaded.hadoop.hive.common.{ValidTxnList, ValidWriteIdList}

import java.util.concurrent.atomic.AtomicBoolean
import com.qubole.spark.hiveacid.{HiveAcidErrors, HiveAcidOperation, SparkAcidConf}
import com.qubole.spark.hiveacid.hive.{HiveAcidMetadata, HiveConverter}
import org.apache.spark.internal.Logging
import org.apache.spark.sql.SparkSession

/**
  * Hive Acid Transaction object.
  * @param sparkSession: Spark Session
  */
class HiveAcidTxn(sparkSession: SparkSession) extends Logging {

  HiveAcidTxn.setUpTxnManager(sparkSession)

  // txn ID
  protected var id: Long = -1
  protected var validTxnList: ValidTxnList = _
  private [hiveacid] val isClosed: AtomicBoolean = new AtomicBoolean(true)

  private def setTxn(id: Long, txns:ValidTxnList): Unit = {
    this.id = id
    this.validTxnList = txns
    isClosed.set(false)
  }

  private def unsetTxn(): Unit = {
    this.id = -1
    this.validTxnList = null
    isClosed.set(true)
  }

  override def toString: String = s"""{"id":"$id","validTxns":"$validTxnList"}"""

  /**
    * Public API to being transaction.
    */
  def begin(): Unit = synchronized {
    if (!isClosed.get) {
      throw HiveAcidErrors.txnAlreadyOpen(id)
    }
    val newId = HiveAcidTxn.txnManager.beginTxn(this)
    val txnList = HiveAcidTxn.txnManager.getValidTxns(Some(newId))
    setTxn(newId, txnList)
    // Set it for thread for all future references.
    HiveAcidTxn.threadLocal.set(this)
    logDebug(s"Begin transaction $this")
  }

  /**
    * Public API to end transaction
    * @param abort true if transaction is aborted
    */
  def end(abort: Boolean = false): Unit = synchronized {
    if (isClosed.get) {
      throw HiveAcidErrors.txnAlreadyClosed(id)
    }

    logDebug(s"End transaction $this abort = $abort")
    // NB: Unset it for thread proactively invariant of
    //  underlying call fails or succeeds.
    HiveAcidTxn.threadLocal.set(null)
    HiveAcidTxn.txnManager.endTxn(id, abort)
    unsetTxn()
  }

  private[hiveacid] def acquireLocks(hiveAcidMetadata: HiveAcidMetadata,
                                     operationType: HiveAcidOperation.OperationType,
                                     partitionNames: Seq[String],
                                     conf: SparkAcidConf): Unit = {
    if (isClosed.get()) {
      logError(s"Transaction already closed $this")
      throw HiveAcidErrors.txnAlreadyClosed(id)
    }
    HiveAcidTxn.txnManager.acquireLocks(id, hiveAcidMetadata.dbName,
      hiveAcidMetadata.tableName, operationType, partitionNames, hiveAcidMetadata.isPartitioned, conf)
  }

  private[hiveacid] def addDynamicPartitions(writeId: Long,
                                             dbName: String,
                                             tableName: String,
                                             operationType: HiveAcidOperation.OperationType,
                                             partitions: Set[String]) = {
    if (isClosed.get()) {
      logError(s"Transaction already closed $this")
      throw HiveAcidErrors.txnAlreadyClosed(id)
    }
    logDebug(s"Adding dynamic partition txnId: $id writeId: $writeId dbName: $dbName" +
      s" tableName: $tableName partitions: ${partitions.mkString(",")}")
    HiveAcidTxn.txnManager.addDynamicPartitions(id, writeId, dbName,
      tableName, partitions, operationType)
  }
  // Public Interface
  def txnId: Long = id
}

object HiveAcidTxn extends Logging {

  val threadLocal = new ThreadLocal[HiveAcidTxn]

  // Helper function to create snapshot.
  private[hiveacid] def createSnapshot(txn: HiveAcidTxn, hiveAcidMetadata: HiveAcidMetadata): HiveAcidTableSnapshot = {
    val currentWriteId = txnManager.getCurrentWriteId(txn.txnId,
      hiveAcidMetadata.dbName, hiveAcidMetadata.tableName)
    val validWriteIdList: ValidWriteIdList = getValidWriteIds(txn, hiveAcidMetadata)
    HiveAcidTableSnapshot(validWriteIdList, currentWriteId)
  }

  private[hiveacid] def getValidWriteIds(txn: HiveAcidTxn, hiveAcidMetadata: HiveAcidMetadata) = {
    logDebug(s"HiveAcidTxn getValidWriteIdList. HiveAcidMetadata content: " +
      s"dbName=${hiveAcidMetadata.dbName}, " +
      s"isFullAcidTable=${hiveAcidMetadata.isFullAcidTable}, " +
      s"hTable=${hiveAcidMetadata.hTable}, " +
      s"isPartitioned=${hiveAcidMetadata.isPartitioned}, " +
      s"partitionSchema.fields=${hiveAcidMetadata.partitionSchema.fields.toString}, " +
      s"dataSchema.fields=${hiveAcidMetadata.dataSchema.fields.toString}, " +
      s"isFullAcidTable=${hiveAcidMetadata.isFullAcidTable}, " +
      s"fullyQualifiedName=${hiveAcidMetadata.fullyQualifiedName}, " +
      s"rootPath=${hiveAcidMetadata.rootPath.toString}, " +
      s"tableDesc=${hiveAcidMetadata.tableDesc.toString}, "
    )
    val validWriteIdList = if (txn.txnId == -1) {
      logDebug("Called write id request before txn start: ")
      throw HiveAcidErrors.tableWriteIdRequestedBeforeTxnStart(hiveAcidMetadata.fullyQualifiedName)
    } else {
      logDebug("Called write ids from txn manager. validTxnList is: " + txn.validTxnList.writeToString())
      logDebug("Received ids from txn manager. validTxnList is: " + txnManager.getValidWriteIds(txn.txnId, txn.validTxnList, hiveAcidMetadata.fullyQualifiedName).writeToString())
      txnManager.getValidWriteIds(txn.txnId, txn.validTxnList, hiveAcidMetadata.fullyQualifiedName)
    }
    logDebug("Returned write ids from txnManager for table: "+ hiveAcidMetadata.fullyQualifiedName + " . validTxnList is: " + validWriteIdList.writeToString())
    validWriteIdList
  }

  // Txn manager is connection to HMS. Use single instance of it
  var txnManager: HiveAcidTxnManager = _
  private def setUpTxnManager(sparkSession: SparkSession): Unit = synchronized {
    if (txnManager == null) {
      logDebug(s"Initializing HiveAcidTxnManager from session sparkContext: " + sparkSession.sparkContext)
      logDebug(s"Initializing HiveAcidTxnManager from session hiveConf: " + HiveConverter.getHiveConf(sparkSession.sparkContext).toString)
      txnManager = new HiveAcidTxnManager(sparkSession)
    }
  }

  /**
    * Creates read or write transaction based on user request.
    *
    * @param sparkSession Create a new hive Acid transaction
    * @return
    */
  def createTransaction(sparkSession: SparkSession): HiveAcidTxn = {
    setUpTxnManager(sparkSession)
    new HiveAcidTxn(sparkSession)
  }

  /**
    * Given a transaction id return the HiveAcidTxn object. Raise exception if not found.
    * @return
    */
  def currentTxn(): HiveAcidTxn = {
    threadLocal.get()
  }

  /**
    * Check if valid write Ids for `fullyQualifiedTableName` when `txn` was opened
    * is same even now. This should be invoked after `txn` acquires lock, to see
    * if the transaction is still valid and continue.
    */
  def IsTxnStillValid(txn: HiveAcidTxn, fullyQualifiedTableName: String): Boolean = {
    if (txn.txnId == - 1) {
      logWarning(s"Transaction being validated even before it was open")
      false
    } else {
      // Compare the earlier writeIds of fullyQualifiedTableName with the current one.
      logDebug(s"Previous WriteIdList: " + txnManager.getValidWriteIds(txn.txnId, txn.validTxnList, fullyQualifiedTableName).writeToString())
      val previousWriteIdList = txnManager.getValidWriteIds(txn.txnId, txn.validTxnList, fullyQualifiedTableName)
      logDebug(s"Current Valid list: " + txnManager.getValidTxns(Some(txn.txnId)).writeToString())
      val currentValidList = txnManager.getValidTxns(Some(txn.txnId))
      logDebug(s"Current WriteIdList: " + txnManager.getValidWriteIds(txn.txnId, currentValidList, fullyQualifiedTableName).writeToString())
      val currentWriteIdList = txnManager.getValidWriteIds(txn.txnId, currentValidList, fullyQualifiedTableName)
      // Checks if any new write transaction was started and committed
      // after opening transaction and before acquiring locks using HighWaterMark
      if (previousWriteIdList.getHighWatermark == currentWriteIdList.getHighWatermark) {
        // Check all the open transactions when current transaction was opened,
        // are still invalid i.e., either running/open or aborted.
        val prevOpenInvalidWriteIds = previousWriteIdList.getInvalidWriteIds
          .filter(!previousWriteIdList.isWriteIdAborted(_)).toSet
        val currentInvalidWriteIds = currentWriteIdList.getInvalidWriteIds.toSet
        // Previous open transactions should still be invalid
        if (prevOpenInvalidWriteIds.isEmpty ||
          prevOpenInvalidWriteIds.diff(currentInvalidWriteIds).isEmpty) {
          logDebug("All previous open transactions are still invalid! Transaction is valid!")
          true
        } else {
          logWarning("Prev Open transactions: " +  prevOpenInvalidWriteIds.diff(currentInvalidWriteIds).mkString(", ")
            + " have been committed. Transaction " + txn.txnId + " is not valid !")
          false
        }
      } else {
        logWarning("HighWatermark moved from " +
          previousWriteIdList.getHighWatermark + " to " +
          currentWriteIdList.getHighWatermark +
          ". Transaction " + txn.txnId + " is not valid !")
        false
      }
    }
  }
}

private[hiveacid] case class HiveAcidTableSnapshot(validWriteIdList: ValidWriteIdList, currentWriteId: Long)
