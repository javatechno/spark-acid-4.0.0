package com.qubole.shaded.hadoop.hive.ql.io.orc

import org.apache.hadoop.fs.Path
import org.apache.hadoop.hive.ql.io.AcidUtils

import org.apache.hadoop.hive.ql.io.orc.{OrcSplit, VectorizedOrcAcidRowBatchReader}

//import org.apache.hadoop.hive.ql.io.orc.{OrcSplit, VectorizedOrcAcidRowBatchReader}

import java.util.regex.Pattern

object OrcAcidUtil {
  val BUCKET_PATTERN = Pattern.compile("bucket_[0-9]{5}$")

  def getDeleteDeltaPaths(orcSplit: OrcSplit): Array[Path] = {
    assert(BUCKET_PATTERN.matcher(orcSplit.getPath.getName).matches())
    val bucket = AcidUtils.parseBucketId(orcSplit.getPath)
    assert(bucket != -1)
    val deleteDeltaDirPaths = {
      val method = classOf[VectorizedOrcAcidRowBatchReader].getDeclaredMethod("getDeleteDeltaDirsFromSplit", classOf[OrcSplit])
      method.setAccessible(true)
      method.invoke(null, orcSplit).asInstanceOf[Array[Path]]
    }
    deleteDeltaDirPaths.map(deleteDir => AcidUtils.createBucketFile(deleteDir, bucket))
  }
}
