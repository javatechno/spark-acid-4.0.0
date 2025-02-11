package com

import com.dimafeng.testcontainers.GenericContainer
import com.dimafeng.testcontainers.GenericContainer.FileSystemBind
import com.qubole.shaded.hadoop.conf.Configuration
import com.qubole.shaded.hadoop.fs.FileSystem
import org.apache.spark.sql._
import org.apache.spark.{SparkConf, SparkContext}
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach, Suite}
import org.testcontainers.containers.BindMode
import org.testcontainers.containers.wait.strategy.Wait

import java.io.{File, FileDescriptor, FileOutputStream, PrintStream}
import java.nio.file.{Files, Paths}
import scala.io.Source

object Utils {
  def deleteRecursively(file: File): Unit = {
    if (file.isDirectory)
      file.listFiles.foreach(deleteRecursively)
    if (file.exists && !file.delete)
      throw new Exception(s"Unable to delete ${file.getAbsolutePath}")
  }
}

trait HMSContainer extends BeforeAndAfterAll {
  self: Suite with Environment =>

  //У hive 4.0.0 нет коннекта к metastore еще https://github.com/apache/iceberg/issues/11928?ysclid=m6n8j58o3k961953079
  //Тест валится
  // [info] com.qubole.spark.hiveacid.HiveAcidTest *** ABORTED ***
  // [info]   java.lang.NoSuchFieldError: METASTOREWAREHOUSE
  lazy val HMSContainer = {
    val container = GenericContainer(dockerImage = "apache/hive:4.0.0",
      exposedPorts = List(9083),
      env = Map[String, String](
        "HIVE_CUSTOM_CONF_DIR" -> "/hive_custom_conf",
        "SERVICE_NAME" -> "metastore",
      ),
      fileSystemBind = List(
        FileSystemBind(s"${(absolutePathSparkWarehouseDir)}/docker_conf", "/hive_custom_conf", BindMode.READ_WRITE)),
      waitStrategy = Wait.forLogMessage(".*Starting Hive Metastore Server.*", 1)
    )

    container.underlyingUnsafeContainer.withCreateContainerCmdModifier(cmd => cmd.withUser("root"))
    container
  }


  override def afterAll(): Unit = {
    HMSContainer.stop()
    super.afterAll()
  }
}


trait BeforeAfterForSpark extends BeforeAndAfterAll with BeforeAndAfterEach with HMSContainer {
  self: Suite with Environment =>

  lazy val absolutePathSparkWarehouseDir: String =
    (new File(".").getCanonicalPath +
      Seq("target", "sparkwarehouse", this.getClass.getCanonicalName.toLowerCase).mkString(sep, sep, ""))
      .replaceAll("\\.", "-")
      .toLowerCase

   lazy val absolutePathSparkWarehouseDirNoDisk = absolutePathSparkWarehouseDir
    .replaceAll("^\\w:", "")
    .replaceAll(raw"\\", raw"/")


   lazy val absoluteHadoopHomeDir: String =
    new File(".").getCanonicalPath +
      Seq("src", "test", "resources", "hadoop").mkString(sep, sep, "")


  @transient var _sc: SparkContext = _
  @transient var _spark: SparkSession = _

  def sc: SparkContext = _sc

  def spark: SparkSession = _spark

  val numCores = "*"

  implicit lazy val spkImpl = spark.implicits

  val sep = File.separator

  System.setProperty("hadoop.home.dir", absoluteHadoopHomeDir)

  System.setErr(new SuppressErrors("org.apache.hadoop.fs"))

  Utils.deleteRecursively(new File(absolutePathSparkWarehouseDir))
  val hadoopConf = new Configuration()
  val hadoopFS = FileSystem.get(hadoopConf)

  val hiveConfTemplate = Source.fromFile(getAbsFilePathFromTestResources("docker_conf/hive-site.xml")).getLines().mkString("\n")

  val hiveConfNew = hiveConfTemplate.replace("{warehouse}", absolutePathSparkWarehouseDirNoDisk)
  val hiveConfDir = Paths.get(absolutePathSparkWarehouseDir + "/docker_conf")
  if (!Files.exists(hiveConfDir)) Files.createDirectories(hiveConfDir)
  val hiveConfNewPath = absolutePathSparkWarehouseDir + "/docker_conf/hive-site.xml"
  writeFile(hiveConfNewPath, hiveConfNew)


  HMSContainer.start()


  val conf = new SparkConf().setMaster(s"local[$numCores]")
    .set("spark.sql.shuffle.partitions", "1")
    .set("hive.exec.scratchdir", s"$absolutePathSparkWarehouseDirNoDisk${sep}scratch")
    .set("hive.exec.dynamic.partition", "true")
    .set("hive.exec.dynamic.partition.mode", "nonstrict")
    .set("spark.sql.warehouse.dir", s"$absolutePathSparkWarehouseDirNoDisk")
    .set("spark.hadoop.hive.metastore.uris", s"thrift://${HMSContainer.host}:${HMSContainer.mappedPort(9083)}")
//    .set("spark.sql.hive.metastore.version", "2.3.9")
//    .set("spark.sql.hive.metastore.jars", "maven")
//У hive 4.0.0 нет коннекта к metastore еще https://github.com/apache/iceberg/issues/11928?ysclid=m6n8j58o3k961953079
//Тест валится
// [info] com.qubole.spark.hiveacid.HiveAcidTest *** ABORTED ***
//[info]   java.lang.NoSuchFieldError: METASTOREWAREHOUSE
        .set("spark.sql.hive.metastore.version", "4.0.0")
        .set("spark.sql.hive.metastore.jars", "maven")

    .set("spark.sql.parquet.writeLegacyFormat", "true")
    .set("spark.driver.host", "127.0.0.1")
    .set("spark.sql.caseSensitive", "false")
    .set("spark.sql.hive.convertMetastoreParquet", "false")
    .set("spark.driver.allowMultipleContexts", "true")
    .set("spark.sql.autoBroadcastJoinThreshold", "-1")
//
    .set("spark.sql.extensions", "org.apache.iceberg.spark.extensions.IcebergSparkSessionExtensions,com.qubole.spark.hiveacid.HiveAcidAutoConvertExtension")
    .set("spark.sql.catalog.spark_catalog", "org.apache.iceberg.spark.SparkSessionCatalog")
    .set("spark.sql.catalog.spark_catalog.type", "hive")
    .set("spark.sql.defaultCatalog", "spark_catalog")

    .set("spark.sql.catalog.spark_catalog.warehouse", s"$absolutePathSparkWarehouseDirNoDisk")

    .set("spark.sql.adaptive.enabled", "false")
    .set("spark.sql.storeAssignmentPolicy", "ANSI")

    .set("spark.io.compression.codec", "zstd")


  override def beforeAll(): Unit = {
    super.beforeAll()
    getOrCreateSparkSession()
  }

  override def beforeEach(): Unit = {
    super.beforeEach()
    getOrCreateSparkSession()
  }

  def getOrCreateSparkSession() = {
    if (_spark == null) {
      println("Create new Spark Context")
      println(s"absolutePathSparkWarehouseDir: $absolutePathSparkWarehouseDir")
      println(s"absolutePathSparkWarehouseDirNoDisk: $absolutePathSparkWarehouseDirNoDisk")
      println(s"absoluteHadoopHomeDir: $absoluteHadoopHomeDir")

      _spark = SparkSession.builder()
        .config(conf)
        .enableHiveSupport()
        .appName("Test")
        .getOrCreate()
      _sc = _spark.sparkContext

      Files.createDirectories(Paths.get(absolutePathSparkWarehouseDir))

    } else {
      println("Get already created Spark Context")
    }
  }



}

class SuppressErrors(packages: String*) extends PrintStream(new FileOutputStream(FileDescriptor.err)) {

  def filter(): Boolean =
    Thread.currentThread()
      .getStackTrace
      .exists(el => packages.exists(el.getClassName.contains))

  override def write(b: Int): Unit = {
    if (!filter()) super.write(b)
  }

  override def write(buf: Array[Byte], off: Int, len: Int): Unit = {
    if (!filter()) super.write(buf, off, len)
  }

  override def write(b: Array[Byte]): Unit = {
    if (!filter()) super.write(b)
  }
}

