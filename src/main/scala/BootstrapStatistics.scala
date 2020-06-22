import org.apache.spark.sql.SparkSession
import org.apache.spark.rdd.RDD
import org.apache.log4j.Logger
import org.apache.log4j.Level
import scala.math.BigDecimal

object BootstrapStatistics {
  def main(args: Array[String]) = {
    val numberOfIterations = 1000

    // set up spark session
    val spark = SparkSession.builder
      .appName("Bootstrap Statistics")
      .config("spark.master", "local")
      .getOrCreate()

    // Suppress LOG messages
    Logger.getLogger("org").setLevel(Level.ERROR)
    Logger.getLogger("akka").setLevel(Level.ERROR)

    // load up data file
    // US Births in 1969 - 1988
    val dataFile = "input/Birthdays.csv"
    val birthdayData = spark.read.textFile(dataFile).cache()
    val populationRDD = birthdayData.rdd
    val dataHeader = birthdayData.take(1)

    // reduce data to year-month-day and births for that day
    val population = populationRDD.filter(_ != dataHeader(0))
      .map(line => line.split(","))
      .map(line => (line(2), line(7).replace("\"", "").toInt))
      .cache()

    // Compute mean, variance by accumulating totals.
    println("Year \t| Mean \t\t| Variance")
    prepareForSummary(population)
      .map(summarizeStatistics)
      .sortBy(a => a._1)
      //.saveAsTextFile("output")
      .collect()
      .foreach(printSummaryWindow)

    // Set up bootstrapping
    val bootstrapSample = population.sample(false, 0.25)
    var bootstrapSampleAggregate = collection.mutable.TreeMap[String, (Double, Double)]()

    for (n <- 0 to numberOfIterations) {
      prepareForSummary(bootstrapSample.sample(true, 1))
        .map(summarizeStatistics)
        .collect()
        .map(x => {
          bootstrapSampleAggregate.get(x._1) match {
            case Some(e) => bootstrapSampleAggregate.update(x._1, (e._1 + x._2, e._2 + x._3))
            case None => bootstrapSampleAggregate += (x._1 -> (x._2, x._3))
          }
          x
        })
      //.collect()
      //.foreach(printSummaryWindow)
    }
    println("===============Bootstrap====================")
    println("Year \t| Mean \t\t| Variance")
    bootstrapSampleAggregate
      .map(stat => (stat._1, stat._2._1 / numberOfIterations, stat._2._2 / numberOfIterations))
      .map(stat => printSummaryWindow(stat))
    println("============================================")
    spark.stop()
  }

  def prepareForSummary(dataPoints: RDD[(String, Int)]) = {
    dataPoints
      .map(line => (line._1, (line._2, 1)))
      .groupByKey()
      .map(yearDataPoint => (yearDataPoint._1, yearDataPoint._2.reduce((a, b) => (a._1 + b._1, a._2 + b._2)), yearDataPoint._2.map(a => a._1)))
  }

  def summarizeStatistics(dataPoint: (String, (Int, Int), Iterable[Int])): (String, Double, Double) = {
    val year = dataPoint._1
    val mean = dataPoint._2._1.toDouble / dataPoint._2._2
    val count = dataPoint._2._2
    val dailyDataPoints = dataPoint._3

    def getVariance(mean: Double) = (x: Double) => Math.pow(x - mean, 2).toDouble // var(x) = E[(x-u)^2]
    (year, mean, dailyDataPoints.map(x => getVariance(mean)(x)).reduce(_ + _).toDouble / (count - 1));
  }

  def printSummaryWindow(result: (String, Double, Double)) = {
    val year = result._1
    val mean = BigDecimal(result._2).setScale(2, BigDecimal.RoundingMode.HALF_UP)
    val variance = BigDecimal(result._3).setScale(2, BigDecimal.RoundingMode.HALF_UP)
    println(year + " \t| " + mean + " \t| " + variance)
  }
}





