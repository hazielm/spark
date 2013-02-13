package spark.streaming

import util.{ManualClock, RecurringTimer, Clock}
import spark.SparkEnv
import spark.Logging

private[streaming]
class Scheduler(ssc: StreamingContext) extends Logging {

  initLogging()

  val concurrentJobs = System.getProperty("spark.streaming.concurrentJobs", "1").toInt
  val jobManager = new JobManager(ssc, concurrentJobs)
  val checkpointWriter = if (ssc.checkpointDuration != null && ssc.checkpointDir != null) {
    new CheckpointWriter(ssc.checkpointDir)
  } else {
    null
  }

  val clockClass = System.getProperty("spark.streaming.clock", "spark.streaming.util.SystemClock")
  val clock = Class.forName(clockClass).newInstance().asInstanceOf[Clock]
  val timer = new RecurringTimer(clock, ssc.graph.batchDuration.milliseconds,
    longTime => generateRDDs(new Time(longTime)))
  val graph = ssc.graph

  def start() = synchronized {
    if (ssc.isCheckpointPresent) {
      restart()
    } else {
      startFirstTime()
    }
    logInfo("Scheduler started")
  }
  
  def stop() = synchronized {
    timer.stop()
    jobManager.stop()
    ssc.graph.stop()
    logInfo("Scheduler stopped")    
  }

  private def startFirstTime() {
    val startTime = new Time(timer.getStartTime())
    graph.start(startTime - graph.batchDuration)
    timer.start(startTime.milliseconds)
    logInfo("Scheduler's timer started at " + startTime)
  }

  private def restart() {

    // If manual clock is being used for testing, then
    // either set the manual clock to the last checkpointed time,
    // or if the property is defined set it to that time
    if (clock.isInstanceOf[ManualClock]) {
      val lastTime = ssc.initialCheckpoint.checkpointTime.milliseconds
      val jumpTime = System.getProperty("spark.streaming.manualClock.jump", "0").toLong
      clock.asInstanceOf[ManualClock].setTime(lastTime + jumpTime)
    }

    val batchDuration = ssc.graph.batchDuration

    // Batches when the master was down, that is,
    // between the checkpoint and current restart time
    val checkpointTime = ssc.initialCheckpoint.checkpointTime
    val restartTime = new Time(timer.getRestartTime(graph.zeroTime.milliseconds))
    val downTimes = checkpointTime.until(restartTime, batchDuration)
    logInfo("Batches during down time: " + downTimes.mkString(", "))

    // Batches that were unprocessed before failure
    val pendingTimes = ssc.initialCheckpoint.pendingTimes
    logInfo("Batches pending processing: " + pendingTimes.mkString(", "))
    // Reschedule jobs for these times
    val timesToReschedule = (pendingTimes ++ downTimes).distinct.sorted(Time.ordering)
    logInfo("Batches to reschedule: " + timesToReschedule.mkString(", "))
    timesToReschedule.foreach(time =>
      graph.generateRDDs(time).foreach(jobManager.runJob)
    )

    // Restart the timer
    timer.start(restartTime.milliseconds)
    logInfo("Scheduler's timer restarted")
  }

  /** Generates the RDDs, clears old metadata and does checkpoint for the given time */
  def generateRDDs(time: Time) {
    SparkEnv.set(ssc.env)
    logInfo("\n-----------------------------------------------------\n")
    graph.generateRDDs(time).foreach(jobManager.runJob)
    doCheckpoint(time)
  }


  def clearOldMetadata(time: Time) {
    ssc.graph.clearOldMetadata(time)
  }

  def doCheckpoint(time: Time) {
    if (ssc.checkpointDuration != null && (time - graph.zeroTime).isMultipleOf(ssc.checkpointDuration)) {
      logInfo("Checkpointing graph for time " + time)
      val startTime = System.currentTimeMillis()
      ssc.graph.updateCheckpointData(time)
      checkpointWriter.write(new Checkpoint(ssc, time))
      val stopTime = System.currentTimeMillis()
      logInfo("Checkpointing the graph took " + (stopTime - startTime) + " ms")
    }
  }
}

