package io.frama.parisni.spark.sync.conf

import org.apache.spark.sql.{DataFrame, SparkSession}

class PostgresConf(config: Map[String, String], dates: List[String], pks: List[String])
  extends SourceAndTarget { //io.frama.parisni.spark.sync.conf.TargetConf with io.frama.parisni.spark.sync.conf.SourceConf with LazyLogging{

  checkTargetParams(config)
  checkSourceParams(config)

  //val URL: String = "URL"
  val HOST: String = "HOST"
  val PORT: String = "PORT"
  val DATABASE: String = "DATABASE"
  val USER: String = "USER"
  val SCHEMA: String = "SCHEMA"

  def getHost: Option[String] = config.get(HOST)

  def getPort: Option[String] = config.get(PORT)

  def getDB: Option[String] = config.get(DATABASE)

  def getUser: Option[String] = config.get(USER)

  def getSchema: Option[String] = config.get(SCHEMA)

  // SourceTable methods
  def readSource(spark: SparkSession, host: String, port: String,
                 db: String, user: String, schema: String,
                 s_table: String, s_date_field: String,
                 date_Max: String, load_type: String, pks: List[String]): DataFrame = {

    try {
      logger.warn("Reading data from Postgres table ---------")
      val url = f"jdbc:postgresql://${host}:${port}/${db}?user=${user}&currentSchema=${schema}"

      //if (!checkTableExists(spark, url, schema, s_table)) {
      //  logger.warn(s"Postgres Table ${s_table} doesn't exist")
      //  return spark.emptyDataFrame
      //}
      val dateFilter = dates.map(x => s""" "${x}" >= '${date_Max}'""").mkString(" OR ")

      var query = f"select * from ${s_table}"
      if (load_type != "full" && date_Max != "")
        query += f""" where $dateFilter"""

      logger.warn("query: " + query)

      val dfPG = spark.read.format("postgres")
        .option("url", url)
        .option("query", query)
        .option("partitions", 4)
        .option("multiline", true)
        .option("numSplits", "40")
        .option("partitionColumn", pks.head)
        .load

      dfPG
    } catch {
      case re: RuntimeException => throw re
      case e: Exception => throw new RuntimeException(e)
    }
  }

  override def getSourceTableName = config.get(S_TABLE_NAME)

  override def getSourceTableType = config.get(S_TABLE_TYPE)

  override def getSourceDateField = config.get(S_DATE_FIELD)

  def getSourcePK = pks

  // TargetTable methods
  override def getTargetTableName = config.get(T_TABLE_NAME)

  override def getTargetTableType = config.get(T_TABLE_TYPE)

  override def getLoadType = config.get(T_LOAD_TYPE)

  def getDateFields = dates

  override def getDateMax(spark: SparkSession): String = {

    val url = f"jdbc:postgresql://${getHost.getOrElse("localhost")}:${getPort.getOrElse("5432")}/" +
      f"${getDB.getOrElse("postgres")}?user=${getUser.getOrElse("postgres")}&currentSchema=${getSchema.getOrElse("public")}/"

    val result = config.get(T_DATE_MAX) match {
      case Some("") => calculDateMax(spark, url, getTargetTableType.getOrElse(""), getTargetTableName.getOrElse(""), getDateFields)
      case Some(_) => config.get(T_DATE_MAX).get
    }
    logger.warn(s"getting the maxdate : ${result}")
    result
  }

  def writeSource(spark: SparkSession, s_df: DataFrame, host: String, port: String,
                  db: String, user: String, schema: String, t_table: String,
                  load_type: String, hash_field: String = "", pw: String = ""): Unit = {

    try {
      logger.warn("Writing data into Postgres table ---------")
      val url = f"jdbc:postgresql://${host}:${port}/${db}?user=${user}&currentSchema=${schema}"

      if (!checkTableExists(spark, url, schema, t_table)) {
        logger.warn(s"Creating Postgres Table ${t_table} from scratch")

        s_df.write.format("postgres")
          .option("type", "full")
          .option("partitions", 4)
          .option("url", url)
          .option("table", t_table)
          .save

        return
      }

      load_type match {
        case "full" => {
          s_df.write.format("postgres")
            .option("type", load_type)
            .option("partitions", 4)
            .option("url", url)
            .option("table", t_table)
            .mode(org.apache.spark.sql.SaveMode.Overwrite)
            .save
        }
        case "scd1" => {
          s_df.write.format("postgres")
            .option("type", load_type)
            .option("JoinKey", hash_field)
            .option("partitions", 4)
            .option("url", url)
            .option("table", t_table)
            .save
        }
      }
    } catch {
      case re: RuntimeException => throw re
      case e: Exception => throw new RuntimeException(e)
    }
  }

  def checkTableExists(spark: SparkSession, url: String, schema: String, table: String): Boolean = {

    val q1 = "SELECT EXISTS (SELECT FROM pg_tables " +
      f"WHERE schemaname = '${schema}' AND tablename = '${table}')"

    val res = spark.read.format("postgres")
      .option("url", url)
      .option("query", q1)
      .load.first.get(0).equals(true)

    logger.warn(s"Table ${table} exists = " + res)
    res
  }
}
