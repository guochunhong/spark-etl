package io.frama.parisni.spark.hive

import com.typesafe.scalalogging.LazyLogging
import io.delta.tables._
import io.frama.parisni.spark.dataframe.DFTool
import io.frama.parisni.spark.hive.PostgresToHiveYaml._
import io.frama.parisni.spark.postgres.PGTool
//import io.frama.parisni.spark.sink.Sync
import net.jcazevedo.moultingyaml._
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.{FileSystem, Path}
import org.apache.spark.sql.SaveMode._
import org.apache.spark.sql.SparkSession

import scala.io.Source

/*
 * Principle
 *
 *
 */
object PostgresToDelta extends App with LazyLogging {

  val filename = args(0)
  //	  val filename = "test.yaml"

  val ymlTxt = Source.fromFile(filename).mkString
  val yaml = ymlTxt.stripMargin.parseYaml
  val database = yaml.convertTo[Database]

  //
  // SPARK PART
  //
  val spark = SparkSession.builder()
    .appName(database.jobName)
    .enableHiveSupport()
    .getOrCreate()

  spark.sparkContext.setLogLevel("WARN")

  //val url = f"jdbc:postgresql://${database.hostPg}:${database.portPg}/${database.databasePg}?user=${database.userPg}&currentSchema=${database.schemaPg}"
  val url = f"jdbc:postgresql://${database.hostPg}:${database.portPg}/${database.databasePg}?user=${database.userPg}"
  val pgClient = PGTool(spark, url, "spark-postgres")

  try {

    // retrieve last timestamp an build filter query filter
    /*var timestampFilter = ""
    if (database.timestampTable.isDefined) {
      try {
        val query =
          s"""select date_format(max(${database.timestampLastColumn.get}) - interval 2 hours, 'YYYYMMdd HHmmss') as last_timestamp
             |from ${database.databaseHive.get}.${database.timestampTable.get}""".stripMargin
        logger.warn(query)
        val timestampLast = spark.sql(query)
          .take(1).map(x => x.getString(0)).toList(0)
        logger.warn(s"fetching data created after $timestampLast")
        timestampFilter = database.timestampColumns.get.map(x => x + s" >= '$timestampLast'").mkString(" WHERE ", " OR ", "")
      } catch {
        case e: Exception => logger.warn(s"${database.databaseHive.get}.${database.timestampTable.get} does not yet exists. Loading from scratch")
      }
    }*/

    val hostPg = database.hostPg
    val portPg = database.portPg
    val databasePg = database.databasePg
    val userPg = database.userPg
    val dateFieldPg = database.timestampLastColumn.getOrElse("")
    val dateFildsDelta = database.timestampColumns.getOrElse("")
    val dateMax = database.dateMax.getOrElse("2018-10-16 23:16:16")

    for(table <- database.tables.getOrElse(Nil)) {
      if (table.isActive.getOrElse(true)) {

        val schemaPg = table.schemaPg
        val tablePg = table.tablePg
        val pathDelta = table.schemaHive
        val tableDelta = table.tableHive
        val loadType = table.typeLoad.getOrElse("full")
        val pks = table.key

        val config = Map("S_TABLE_NAME" -> tablePg, "S_TABLE_TYPE" -> "postgres",
          "S_DATE_FIELD" -> dateFieldPg, "HOST" -> hostPg, "PORT" -> portPg,
          "DATABASE" -> databasePg, "USER" -> userPg, "SCHEMA" -> schemaPg,

          "T_TABLE_NAME" -> tableDelta, "T_TABLE_TYPE" -> "delta",
          "PATH" -> pathDelta, "T_LOAD_TYPE" -> loadType, "T_DATE_MAX" -> dateMax
        )

        /*val sync = new Sync()
        sync.syncSourceTarget(spark, config, dates, pks)*/

      }
    }


    for (table <- database.tables.getOrElse(Nil)) {
      if (table.isActive.getOrElse(true)) {

        val query = f"select * from ${table.schemaPg}.${table.tablePg} "  //+ timestampFilter
        logger.warn(query)
        val pgTable = DFTool.dfAddHash(pgClient.inputBulk(query = query, isMultiline = table.isMultiline, numPartitions = if (database.timestampTable.isEmpty) {
          table.numThread
        } else {
          Some(1)
        }, splitFactor = table.splitFactor, partitionColumn = table.key))

        table.format.getOrElse("orc") match {
          case "delta" => {

            val deltaPath = "%s/%s".format(table.schemaHive, table.tableHive)
            if (table.delta.isDefined) {
              if (!tableExists(spark, table.schemaHive, table.tableHive)) {
                logger.warn("creating delta table from scratch")
                pgTable.write.format("delta").save(deltaPath)
              } else {
                logger.warn("merging delta table")
                val deltaTable = DeltaTable.forPath(spark, deltaPath)
                deltaTable
                  .as("t")
                  .merge(
                    pgTable.as("s"),
                    "s.%s = t.%s".format(table.key, table.key))
                  .whenMatched("s.hash <> t.hash")
                  .updateAll()
                  .whenNotMatched()
                  .insertAll()
                  .execute()
              }
            } else {
              // full mode
              logger.warn("writing from scatch")
              pgTable.write.format("delta").mode(Overwrite).save(deltaPath)
            }
          }
          case format => pgTable.write.format(format).mode(Overwrite).saveAsTable(f"${table.schemaHive}.${table.tableHive}")
        }
      }
    }
  } finally {
    pgClient.purgeTmp()
  }

  spark.close()

  def tableExists(spark: SparkSession, deltaPath: String, tablePath: String): Boolean = {
    val defaultFSConf = spark.sessionState.newHadoopConf().get("fs.defaultFS")
    val fsConf = if (deltaPath.startsWith("file:")) {
      "file:///"
    } else {
      defaultFSConf
    }
    val conf = new Configuration()
    conf.set("fs.defaultFS", fsConf)
    val fs = FileSystem.get(conf)

    fs.exists(new Path(deltaPath + tablePath))
  }

  //
  // HIVE PART
  //
  /*
  val user = System.getProperty("user.name")
  val hiveClient = HIVETool(f"$user@<REALM>", f"/export/home/$user/$user.keytab", "jdbc:hive2://<host>:<port>/default;principal=hive/_HOST@<REALM>")

  try {
    for (table <- database.tables.getOrElse(Nil)) {
      if (table.isAnalyse.getOrElse(false)) {
        hiveClient.execute(f"analyze table ${table.schemaHive}.${table.tableHive} compute statistics for columns")
        hiveClient.execute(f"analyze table ${table.schemaHive}.${table.tableHive} compute statistics")
      }
    }
  } finally {
    hiveClient.close()
  }

   */

}

