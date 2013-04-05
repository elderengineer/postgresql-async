package com.github.mauricio.postgresql

import column.{TimeEncoderDecoder, DateEncoderDecoder, TimestampEncoderDecoder}
import exceptions.{DatabaseException, UnsupportedAuthenticationMethodException}
import messages.backend.InformationMessage
import org.specs2.mutable.Specification
import scala.concurrent.duration._
import concurrent.{Future, Await}
import scala.concurrent.ExecutionContext.Implicits.global

/**
 * User: Maurício Linhares
 * Date: 3/1/12
 * Time: 12:38 AM
 */

class DatabaseConnectionHandlerSpec extends Specification {

  val create = """create temp table type_test_table (
            bigserial_column bigserial not null,
            smallint_column smallint not null,
            integer_column integer not null,
            decimal_column decimal(10,4),
            real_column real,
            double_column double precision,
            serial_column serial not null,
            varchar_column varchar(255),
            text_column text,
            timestamp_column timestamp,
            date_column date,
            time_column time,
            boolean_column boolean,
            constraint bigserial_column_pkey primary key (bigserial_column)
          )"""

  val insert = """insert into type_test_table (
            smallint_column,
            integer_column,
            decimal_column,
            real_column,
            double_column,
            varchar_column,
            text_column,
            timestamp_column,
            date_column,
            time_column,
            boolean_column
            )
            VALUES (
            10,
            11,
            14.999,
            78.34,
            15.68,
            'this is a varchar field',
            'this is a long text field',
            '1984-08-06 22:13:45.888888',
            '1984-08-06',
            '22:13:45.888888',
            TRUE
            )
               """

  val DatabaseName = Some("netty_driver_test")
  val DatabasePort = 5433
  val DefaultConfiguration = new Configuration(
    port = DatabasePort,
    username = "postgres",
    database = DatabaseName)

  val select = "select * from type_test_table"

  val preparedStatementCreate = """create temp table prepared_statement_test (
    id bigserial not null,
    name varchar(255) not null,
    constraint bigserial_column_pkey primary key (id)
  )"""

  val preparedStatementInsert = " insert into prepared_statement_test (name) values ('John Doe')"
  val preparedStatementInsert2 = " insert into prepared_statement_test (name) values ('Mary Jane')"
  val preparedStatementInsert3 = " insert into prepared_statement_test (name) values ('Peter Parker')"
  val preparedStatementSelect = "select * from prepared_statement_test"

  def withHandler[T](fn: (DatabaseConnectionHandler) => T): T = {
    withHandler( DefaultConfiguration, fn )
  }

  def withHandler[T]( configuration : Configuration, fn: (DatabaseConnectionHandler) => T): T = {

    val handler = new DatabaseConnectionHandler(configuration)

    try {
      Await.result(handler.connect, Duration(5, SECONDS))
      fn(handler)
    } finally {
      handler.disconnect
    }

  }

  def executeDdl( handler : DatabaseConnectionHandler, data : String, count : Int = 0 ) = {
    Await.result(handler.sendQuery(data), Duration(5, SECONDS)).rowsAffected === count
  }

  def executeQuery( handler : DatabaseConnectionHandler, data : String ) = {
    Await.result(handler.sendQuery(data), Duration(5, SECONDS))
  }

  def executePreparedStatement(
                                handler : DatabaseConnectionHandler,
                                statement : String,
                                values : Array[Any] = Array.empty[Any] ) =  {
    Await.result( handler.sendPreparedStatement(statement, values), Duration(5, SECONDS) )
  }

  "handler" should {

    "connect to the database" in {

      withHandler {
        handler =>
          handler.isReadyForQuery must beTrue
      }

    }

    "create a table in the database" in {

      withHandler {
        handler =>
          executeDdl(handler, this.create)
      }

    }

    "insert a row in the database" in {

      withHandler {
        handler =>
          executeDdl(handler, this.create)
          executeDdl(handler, this.insert, 1)

      }

    }

    "select rows in the database" in {

      withHandler {
        handler =>
          executeDdl(handler, this.create)
          executeDdl(handler, this.insert, 1)
          val result = executeQuery(handler, this.select)

          val rows = result.rows.get

          List(
            rows(0, 0) === 1,
            rows(1, 0) === 10,
            rows(2, 0) === 11,
            rows(3, 0).toString === "14.9990",
            rows(4, 0).toString === 78.34.toString,
            rows(5, 0) === 15.68,
            rows(6, 0) === 1,
            rows(7, 0) === "this is a varchar field",
            rows(8, 0) === "this is a long text field",
            rows(9, 0) === TimestampEncoderDecoder.Instance.decode("1984-08-06 22:13:45.888888"),
            rows(10, 0) === DateEncoderDecoder.decode("1984-08-06"),
            rows(11, 0) === TimeEncoderDecoder.Instance.decode("22:13:45.888888"),
            rows(12, 0) === true
          )


      }

    }

    "execute a prepared statement" in {

      withHandler {
        handler =>
          executeDdl(handler, this.preparedStatementCreate)
          executeDdl(handler, this.preparedStatementInsert, 1)
          val result = executePreparedStatement(handler, this.preparedStatementSelect)

          val rows = result.rows.get

          List(
            rows(0, 0) === 1,
            rows(1, 0) === "John Doe"
          )

      }

    }

    "execute a prepared statement with parameters" in {

      withHandler {
        handler =>
          executeDdl(handler, this.preparedStatementCreate)
          executeDdl(handler, this.preparedStatementInsert, 1)
          executeDdl(handler, this.preparedStatementInsert2, 1)
          executeDdl(handler, this.preparedStatementInsert3, 1)

          val select = "select * from prepared_statement_test where name like ?"

          val queryResult = executePreparedStatement(handler, select, Array("Peter Parker") )
          val rows = queryResult.rows.get

          val queryResult2 = executePreparedStatement(handler, select, Array("Mary Jane") )
          val rows2 = queryResult2.rows.get

          List(
            rows(0, 0) === 3,
            rows(1, 0) === "Peter Parker",
            rows.length === 1,
            rows2.length === 1,
            rows2(0, 0) === 2,
            rows2(1, 0) === "Mary Jane"
          )
      }

    }

    "login using MD5 authentication" in {

      val configuration = new Configuration(
        username = "postgres_md5",
        password = Some("postgres_md5"),
        port = DatabasePort,
        database = DatabaseName
      )

      withHandler(configuration, {
        handler =>
          val result = executeQuery(handler, "SELECT 0")
          result.rows.get.apply(0, 0) === 0
      })

    }

    "login using cleartext authentication" in {

      val configuration = new Configuration(
        username = "postgres_cleartext",
        password = Some("postgres_cleartext"),
        port = DatabasePort,
        database = DatabaseName
      )

      withHandler(configuration, {
        handler =>
          val result = executeQuery(handler, "SELECT 0")
          result.rows.get.apply(0, 0) === 0
      })

    }

    "fail login using kerberos authentication" in {

      val configuration = new Configuration(
        username = "postgres_kerberos",
        password = Some("postgres_kerberos"),
        port = DatabasePort,
        database = DatabaseName
      )

      withHandler(configuration, {
        handler =>
          executeQuery(handler, "SELECT 0")
      }) must throwAn[UnsupportedAuthenticationMethodException]

    }

    "fail login using with an invalid credential exception" in {

      val configuration = new Configuration(
        username = "postgres_md5",
        password = Some("postgres_kerberos"),
        port = DatabasePort,
        database = DatabaseName
      )
      try {
        withHandler(configuration, {
          handler =>
            executeQuery(handler, "SELECT 0")
            failure("should not have come here")
        })
      } catch {
        case e : DatabaseException => {
          e.errorMessage.fields(InformationMessage.Routine) === "auth_failed"
        }
        case e : Exception => {
          failure("should not have come here")
        }
      }

    }

    "transaction and flatmap example" in {

      val handler : Connection = new DatabaseConnectionHandler( DefaultConfiguration )
      val result: Future[QueryResult] = handler.connect
        .map( parameters => handler )
        .flatMap( connection => connection.sendQuery("BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ") )
        .flatMap( query => handler.sendQuery("SELECT 0") )
        .flatMap( query => handler.sendQuery("COMMIT").map( value => query ) )

      val queryResult: QueryResult = Await.result(result, Duration(5, SECONDS))

      queryResult.rows.get(0, 0) === 0

    }

  }

}
