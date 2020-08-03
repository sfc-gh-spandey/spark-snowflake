package net.snowflake.spark.snowflake.pushdowns.querygeneration

import net.snowflake.spark.snowflake.{ConstantString, SnowflakeSQLStatement}
import org.apache.spark.sql.catalyst.expressions._
import org.apache.spark.sql.types._

/** Extractor for boolean expressions (return true or false). */
private[querygeneration] object DateStatement {
  // DateAdd's pretty name in Spark is "date_add",
  // the counterpart's name in SF is "DATEADD".
  // And the syntax is some different.
  val SNOWFLAKE_DATEADD = "DATEADD"

  def unapply(
    expAttr: (Expression, Seq[Attribute])
  ): Option[SnowflakeSQLStatement] = {
    val expr = expAttr._1
    val fields = expAttr._2

    Option(expr match {
      case DateAdd(startDate, days) =>
        ConstantString(SNOWFLAKE_DATEADD) +
          blockStatement(
            ConstantString("day,") +
              convertStatement(days, fields) + "," +
              convertStatement(startDate, fields)
          )

      // Snowflake has no direct DateSub function,
      // it is pushdown by DATEADD with negative days
      case DateSub(startDate, days) =>
        ConstantString(SNOWFLAKE_DATEADD) +
          blockStatement(
            ConstantString("day, (0 - (") +
              convertStatement(days, fields) + ") )," +
              convertStatement(startDate, fields)
          )

      case AddMonths(startDate, days) =>
        ConstantString(expr.prettyName.toUpperCase) +
          blockStatement(convertStatement(startDate, fields) + "," +
            convertStatement(days, fields))

      case _: Month | _: Quarter | _: Year |
           _: TruncDate | _: TruncTimestamp =>
        ConstantString(expr.prettyName.toUpperCase) +
          blockStatement(convertStatements(fields, expr.children: _*))

      case UnixTimestamp(timeExp, format, _) =>
        // There is no direct equivalent function for Spark unix_timestamp()
        // in Snowflake. But, the equivalent functionality can be achieved by
        // DATE_PART( <date_or_time_part> , <date_or_time_expr> ).
        // Some highlights to using DATE_PART():
        // 1. Spark unix_timestamp() is used to convert timestamp in seconds.
        //    So Snowflake needs to use 'EPOCH_SECOND' for <date_or_time_part>.
        // 2. Spark unix_timestamp() supports column type: Date, Timestamp
        //    and String. SF DATE_PART() only supports the column type: Date
        //    and Timestamp.
        //    a) If datatype is DateType or TimestampType, we can use
        //       "DATE_PART('EPOCH_SECOND', <Col>)" directly.
        //    b) If datatype is String, Spark requires customer to provide
        //       format (the default format is "yyyy-MM-dd HH:mm:ss").
        //       So, we can use DATE_PART() + TO_TIMESTAMP(). For example,
        //       "DATE_PART('EPOCH_SECOND', TO_TIMESTAMP(<Col>, format))"
        // 3. Spark also supports unix_timestamp() to get current timestamp.
        //    For this case, Spark has calculated it as a CONST value.
        //    Pushdown doesn't need to care about it.
        timeExp.dataType match {
          case _: DateType | _: TimestampType =>
            ConstantString("DATE_PART('EPOCH_SECOND',") +
              blockStatement(convertStatement(timeExp, fields)) + ")"
          case StringType =>
            // Spark uses Java simpleDateFormat pattern for 'format'.
            // https://docs.oracle.com/javase/tutorial/i18n/format/simpleDateFormat.html
            // The format is some different to Snowflake's timestamp format.
            // One different is that Spark uses 'mm' stand for minutes,
            // but Snowflake uses 'MI', so it needs to be converted before
            // passing to snowflake.
            val sfFormat = format.toString().replaceAll("mm", "MI")
            ConstantString("DATE_PART('EPOCH_SECOND', TO_TIMESTAMP") +
              blockStatement(convertStatement(timeExp, fields) +
                s",'$sfFormat'") + ")"
        }

      case _ => null
    })
  }
}
