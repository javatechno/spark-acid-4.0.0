package com.qubole.shaded.hadoop.hive.ql.io.orc

import java.util.regex.Pattern

object OrcAcidUtil {
  val BUCKET_PATTERN = Pattern.compile("bucket_[0-9]{5}$")

//  def getDeleteDeltaPaths(orcSplit: OrcSplit): Array[Path] = {
//    assert(BUCKET_PATTERN.matcher(orcSplit.getPath.getName).matches())
//    val bucket = AcidUtils.parseBucketId(orcSplit.getPath)
//    assert(bucket != -1)
//    val deleteDeltaDirPaths = VectorizedOrcAcidRowBatchReader.getDeleteDeltaDirsFromSplit(orcSplit);
//    deleteDeltaDirPaths.map(deleteDir => AcidUtils.createBucketFile(deleteDir, bucket))
//  }
}
