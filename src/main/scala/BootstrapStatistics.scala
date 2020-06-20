import org.apache.spark.sql.SparkSession
import org.apache.spark.SparkContext._
import org.apache.spark.rdd.RDD

object BootstrapStatistics {
  def main(args: Array[String]) {
    // set up spark session
    val spark = SparkSession.builder
      .appName("Bootstrap Statistics")
      .config("spark.master", "local") // development only
      .getOrCreate()

    // load up data file
    // US Births in 1969 - 1988
    val dataFile = "input/Birthdays.csv"
    val birthdayData = spark.read.textFile(dataFile).cache()
    val populationRDD = birthdayData.rdd
    val dataHeader = birthdayData.take(1) // skip header

    // reduce data to year-month-day and births for that month
    val population = populationRDD.filter(_ != dataHeader(0))
      .map(line => line.split(","))
      .map(line => (line(2), line(7).replace("\"", "").toInt))
      .cache()

    val populationFormatData = formatData(population)

    // Compute Category, Mean, Variance by year of US Births
    val populationStatistics = populationFormatData
      .sortByKey()
      .map(summariseStatisticsV(population))

    // Print results - statistics are for daily rates
    //println("Year | Mean | Variance")
    populationStatistics.collect()
      .foreach(printSummaryWindow)

    // Creating sample for bootstrapping
    val bootstrapSample = population.sample(false, 0.25)
    var bootstrapSampleAggregate = collection.mutable.Map[String, (Double, Double)]()

    // Create 1000 samples with replacement for bootstrapping
    val numberOfSamplingIterations = 10
    for ( n <- 0 to numberOfSamplingIterations) {
      val resampledData = formatData(bootstrapSample.sample(true, 1))
        .map(summariseStatistics)

      resampledData.collect().map(a => {
        //bootstrapSampleAggregate = bootstrapSampleAggregate :+ a
//        if (bootstrapSampleAggregate.contains(a._1)) {
//          val valToUpdate = bootstrapSampleAggregate.get(a._1) match {
//            case Some(e) => bootstrapSampleAggregate.update(a._1, )
//          }
//          //bootstrapSampleAggregate = bootstrapSampleAggregate + (a._1 -> (a._2 + valToUpdate.getOrElse(0), a._3))
//        } else {
//          bootstrapSampleAggregate = bootstrapSampleAggregate + (a._1 -> (a._2, a._3))
//        }
        //println(a._3)
        bootstrapSampleAggregate.get(a._1) match {
          case Some(e) => bootstrapSampleAggregate.update(a._1, (e._1 + a._2, e._2 + a._3))
          case None => bootstrapSampleAggregate += (a._1 -> (a._2, a._3))
        }
      })
    }

    //bootstrapSampleAggregate.map(println)
      //.foreach(printSummaryWindow)

    spark.stop()
  }

  def formatData(dataset: RDD[(String, Int)]): RDD[(String, (Int, Int))] = {
    dataset.map(line => (line._1, (line._2, 1)))
      .reduceByKey((a, b) => (a._1 + b._1, a._2 + b._2))
  }

  def summariseStatistics(yearBirthsData: (String, (Int, Int))) = {
    // calc mean and variance
    // TODO: fix results to two decimal places
    val year = yearBirthsData._1
    val mean = yearBirthsData._2._1.toDouble / yearBirthsData._2._2 // u = total/count
    val variance = Math.pow(yearBirthsData._2._1 - mean, 2) / yearBirthsData._2._2 // var(x) = E[(x-u)^2]
    (year, mean, variance)
  }

  def summariseStatisticsV(rawDataPoints: RDD[(String, Int)])(yearBirthsData: (String, (Int, Int))) = {
    // calc mean and variance
    // TODO: fix results to two decimal places
    val year = yearBirthsData._1
    val mean = yearBirthsData._2._1.toDouble / yearBirthsData._2._2 // u = total/count
    val getVariance = (x: Double) => Math.pow(x - mean, 2) / yearBirthsData._2._2 // var(x) = E[(x-u)^2]
    val pointsToTry = rawDataPoints
    val variance = pointsToTry.map(_._2.toDouble).map(getVariance).reduce(_+_)

    (year, mean, variance)
  }

  def printSummaryWindow(result: (String, Double, Double)) = {
    val year = result._1
    val mean = result._2
    val variance = result._3
    println(year + " | " + mean + " | " + variance)
  }
}
