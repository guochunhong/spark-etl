package io.frama.parisni.spark.sync.copy

import com.typesafe.scalalogging.LazyLogging
import io.frama.parisni.spark.sync.Sync
import net.jcazevedo.moultingyaml._
import org.apache.spark.sql.SparkSession

import scala.io.Source
import PostgresToDeltaYaml._

object DeltaToPg extends App with LazyLogging {

  val filename = args(0)
  val ymlTxt = Source.fromFile(filename).mkString
  val yaml = ymlTxt.stripMargin.parseYaml
  val database = yaml.convertTo[Database]

  // Spark Session
  val spark = SparkSession.builder()
    .appName(database.jobName)
    .enableHiveSupport()
    .getOrCreate()

  spark.sparkContext.setLogLevel("WARN")

  try {

    val hostPg = database.hostPg.toString
    val portPg = database.portPg.toString
    val databasePg = database.databasePg.toString
    val userPg = database.userPg.toString
    val dateFieldDelta = database.timestampLastColumn.getOrElse("")
    val dateFieldsDelta = database.timestampColumns.getOrElse(List())
    val dateMax = database.dateMax.getOrElse("")

    for (table <- database.tables.getOrElse(Nil)) {
      //if (table.isActive.getOrElse(true)) {

      val schemaPg = table.schemaPg.toString
      val tablePg = table.tablePg.toString
      val pathDelta = table.schemaHive.toString
      val tableDelta = table.tableHive.toString
      val loadType = table.typeLoad.getOrElse("full")
      val pks = table.key

      val config = Map("S_TABLE_NAME" -> tableDelta, "S_TABLE_TYPE" -> "delta",
        "S_DATE_FIELD" -> dateFieldDelta, "PATH" -> pathDelta,

        "T_TABLE_NAME" -> tablePg, "T_TABLE_TYPE" -> "postgres",
        "HOST" -> hostPg, "PORT" -> portPg, "DATABASE" -> databasePg,
        "USER" -> userPg, "SCHEMA" -> schemaPg, "T_LOAD_TYPE" -> loadType,
        "T_DATE_MAX" -> dateMax
      )

      val sync = new Sync()
      sync.syncSourceTarget(spark, config, dateFieldsDelta, pks)

      //}
    }
  }

  spark.close()
}


class DeltaToPg2 extends App with LazyLogging {

  val filename = "io.frama.parisni.spark.sync.copy.DeltaToPg.yaml" //args(0)
  val ymlTxt = Source.fromFile(filename).mkString
  val yaml = ymlTxt.stripMargin.parseYaml
  val database = yaml.convertTo[Database]

  // Spark Session
  val spark = SparkSession.builder()
    .appName(database.jobName)
    .enableHiveSupport()
    .getOrCreate()

  spark.sparkContext.setLogLevel("WARN")

  def sync(spark: SparkSession, database: Database, port: String): Unit = {
    try {

      val hostPg = database.hostPg.toString
      val portPg = port //database.portPg.toString
      val databasePg = database.databasePg.toString
      val userPg = database.userPg.toString
      val dateFieldDelta = database.timestampLastColumn.getOrElse("")
      val dateFieldsDelta = database.timestampColumns.getOrElse(List())
      val dateMax = database.dateMax.getOrElse("")

      for (table <- database.tables.getOrElse(Nil)) {
        //if (table.isActive.getOrElse(true)) {

        val schemaPg = table.schemaPg.toString
        val tablePg = table.tablePg.toString
        val pathDelta = table.schemaHive.toString
        val tableDelta = table.tableHive.toString
        val loadType = table.typeLoad.getOrElse("full")
        val pks = table.key

        val config = Map("S_TABLE_NAME" -> tableDelta, "S_TABLE_TYPE" -> "delta",
          "S_DATE_FIELD" -> dateFieldDelta, "PATH" -> pathDelta,

          "T_TABLE_NAME" -> tablePg, "T_TABLE_TYPE" -> "postgres",
          "HOST" -> hostPg, "PORT" -> portPg, "DATABASE" -> databasePg,
          "USER" -> userPg, "SCHEMA" -> schemaPg, "T_LOAD_TYPE" -> loadType,
          "T_DATE_MAX" -> dateMax
        )

        val sync = new Sync()
        sync.syncSourceTarget(spark, config, dateFieldsDelta, pks)

        //}
      }
    }
  }

  //spark.close()
}

