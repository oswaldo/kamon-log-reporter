/*
 * =========================================================================================
 * Copyright © 2013-2015 the kamon project <http://kamon.io/>
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific language governing permissions
 * and limitations under the License.
 * =========================================================================================
 */

package kamon.logreporter

import akka.actor._
import kamon.Kamon
import kamon.metric.SubscriptionsDispatcher.TickMetricSnapshot
import kamon.metric._
import kamon.metric.instrument.{ Counter, Histogram }
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import net.logstash.logback.argument.StructuredArguments._
import net.logstash.logback.argument.StructuredArgument
import org.slf4j.MarkerFactory
import org.slf4j.Marker

object LogbackReporter extends ExtensionId[LogbackReporterExtension] with ExtensionIdProvider {
  override def lookup(): ExtensionId[_ <: Extension] = LogbackReporter
  override def createExtension(system: ExtendedActorSystem): LogbackReporterExtension = new LogbackReporterExtension(system)
}

class LogbackReporterExtension(system: ExtendedActorSystem) extends Kamon.Extension {
  val log = LoggerFactory.getLogger(getClass.getName)
  log.info("Starting the Kamon(LogbackReporter) extension")

  val subscriber = system.actorOf(Props[LogbackReporterSubscriber], "kamon-logback-reporter")

  Kamon.metrics.subscribe("trace", "**", subscriber, permanently = true)
  Kamon.metrics.subscribe("akka-actor", "**", subscriber, permanently = true)
  Kamon.metrics.subscribe("akka-actor-group", "**", subscriber, permanently = true)
  Kamon.metrics.subscribe("akka-router", "**", subscriber, permanently = true)
  Kamon.metrics.subscribe("akka-dispatcher", "**", subscriber, permanently = true)
  Kamon.metrics.subscribe("counter", "**", subscriber, permanently = true)
  Kamon.metrics.subscribe("histogram", "**", subscriber, permanently = true)
  Kamon.metrics.subscribe("min-max-counter", "**", subscriber, permanently = true)
  Kamon.metrics.subscribe("gauge", "**", subscriber, permanently = true)
  Kamon.metrics.subscribe("system-metric", "**", subscriber, permanently = true)
  Kamon.metrics.subscribe("executor-service", "**", subscriber, permanently = true)
  Kamon.metrics.subscribe("jdbc-statement", "**", subscriber, permanently = true)
}

class LogbackReporterSubscriber extends Actor {

  import kamon.logreporter.LogbackReporterSubscriber.RichHistogramSnapshot

  val log = LoggerFactory.getLogger(getClass.getName)

  def receive = {
    case tick: TickMetricSnapshot ⇒ printMetricSnapshot(tick)
  }

  def printMetricSnapshot(tick: TickMetricSnapshot): Unit = {
    // Group all the user metrics together.
    val histograms = Map.newBuilder[String, Option[Histogram.Snapshot]]
    val counters = Map.newBuilder[String, Option[Counter.Snapshot]]
    val minMaxCounters = Map.newBuilder[String, Option[Histogram.Snapshot]]
    val gauges = Map.newBuilder[String, Option[Histogram.Snapshot]]

    tick.metrics foreach {
      case (entity, snapshot) if entity.category == "akka-actor"       ⇒ logActorMetrics(entity.name, snapshot)
      case (entity, snapshot) if entity.category == "akka-actor-group" ⇒ logActorGroupMetrics(entity.name, snapshot)
      case (entity, snapshot) if entity.category == "akka-router"      ⇒ logRouterMetrics(entity.name, snapshot)
      case (entity, snapshot) if entity.category == "akka-dispatcher"  ⇒ logDispatcherMetrics(entity, snapshot)
      case (entity, snapshot) if entity.category == "executor-service" ⇒ logExecutorMetrics(entity, snapshot)
      case (entity, snapshot) if entity.category == "trace"            ⇒ logTraceMetrics(entity.name, snapshot)
      case (entity, snapshot) if entity.category == "histogram"        ⇒ histograms += (entity.name -> snapshot.histogram("histogram"))
      case (entity, snapshot) if entity.category == "counter"          ⇒ counters += (entity.name -> snapshot.counter("counter"))
      case (entity, snapshot) if entity.category == "min-max-counter"  ⇒ minMaxCounters += (entity.name -> snapshot.minMaxCounter("min-max-counter"))
      case (entity, snapshot) if entity.category == "gauge"            ⇒ gauges += (entity.name -> snapshot.gauge("gauge"))
      case (entity, snapshot) if entity.category == "system-metric"    ⇒ logSystemMetrics(entity.name, snapshot)
      case (entity, snapshot) if entity.category == "jdbc-statement"   ⇒ logJdbcMetrics(snapshot)
      case ignoreEverythingElse                                        ⇒
    }

    logUserMetrics(histograms.result(), counters.result(), minMaxCounters.result(), gauges.result())
  }

  private def field(key: String, any: Any, padding: Int = 12): StructuredArgument = value(key, s"%-${padding}s".format(any.toString))

  private def logMetrics(markerName: String, formatWithMargin: String, arguments: StructuredArgument*) = log.info(MarkerFactory.getMarker(markerName), formatWithMargin.stripMargin, arguments:_*)

  def logActorMetrics(name: String, actorSnapshot: EntitySnapshot): Unit = {
    for {
      processingTime ← actorSnapshot.histogram("processing-time")
      timeInMailbox ← actorSnapshot.histogram("time-in-mailbox")
      mailboxSize ← actorSnapshot.minMaxCounter("mailbox-size")
      errors ← actorSnapshot.counter("errors")
    } {

      logMetrics("ACTOR_METRICS",
        """
        |+--------------------------------------------------------------------------------------------------+
        ||                                                                                                  |
        ||    Actor: {}    |
        ||                                                                                                  |
        ||   Processing Time (nanoseconds)      Time in Mailbox (nanoseconds)         Mailbox Size          |
        ||    Msg Count: {}               Msg Count: {}             Min: {}       |
        ||          Min: {}                     Min: {}            Avg.: {}       |
        ||    50th Perc: {}               50th Perc: {}             Max: {}       |
        ||    90th Perc: {}               90th Perc: {}                                 |
        ||    95th Perc: {}               95th Perc: {}                                 |
        ||    99th Perc: {}               99th Perc: {}           Error Count: {}   |
        ||  99.9th Perc: {}             99.9th Perc: {}                                 |
        ||          Max: {}                     Max: {}                                 |
        ||                                                                                                  |
        |+--------------------------------------------------------------------------------------------------+""",
            field("actorName", name, 83),

            field("processingTimeMeasurements", processingTime.numberOfMeasurements),
            field("timeInMailboxMeasurements", timeInMailbox.numberOfMeasurements),
            field("mailboxSizeMin", mailboxSize.min, 8),

            field("processingTimeMin", processingTime.min),
            field("timeInMailboxMin", timeInMailbox.min),
            field("mailboxSizeAverage", mailboxSize.average, 8),

            field("processingTimePercentile50", processingTime.percentile(50.0D)),
            field("timeInMailboxPercentile50", timeInMailbox.percentile(50.0D)),
            field("mailboxSizeMax", mailboxSize.max, 8),

            field("processingTimePercentile90", processingTime.percentile(90.0D)),
            field("timeInMailboxPercentile90", timeInMailbox.percentile(90.0D)),

            field("processingTimePercentile95", processingTime.percentile(95.0D)),
            field("timeInMailboxPercentile95", timeInMailbox.percentile(95.0D)),

            field("processingTimePercentile99", processingTime.percentile(99.0D)),
            field("timeInMailboxPercentile99", timeInMailbox.percentile(99.0D)),
            field("errorsCount", errors.count, 6),

            field("processingTimePercentile99_9", processingTime.percentile(99.9D)),
            field("timeInMailboxPercentile99_9", timeInMailbox.percentile(99.9D)),

            field("processingTimeMax", processingTime.max),
            field("timeInMailboxMax", timeInMailbox.max)
        )
    }

  }

  def logActorGroupMetrics(name: String, metricSnapshot: EntitySnapshot): Unit = {
    for {
      processingTime ← metricSnapshot.histogram("processing-time")
      timeInMailbox ← metricSnapshot.histogram("time-in-mailbox")
      mailboxSize ← metricSnapshot.minMaxCounter("mailbox-size")
      actors ← metricSnapshot.minMaxCounter("actors")
      errors ← metricSnapshot.counter("errors")
    } {

      logMetrics("ACTOR_GROUP_METRICS",
        """
        |+--------------------------------------------------------------------------------------------------+
        ||                                                                                                  |
        ||    Actor Group: {}    |
        ||                                                                                                  |
        ||   Processing Time (nanoseconds)      Time in Mailbox (nanoseconds)         Mailbox Size          |
        ||    Msg Count: {}               Msg Count: {}             Min: {}       |
        ||          Min: {}                     Min: {}            Avg.: {}       |
        ||    50th Perc: {}               50th Perc: {}             Max: {}       |
        ||    90th Perc: {}               90th Perc: {}                                 |
        ||    95th Perc: {}               95th Perc: {}             Actor Max: {}   |
        ||    99th Perc: {}               99th Perc: {}           Error Count: {}   |
        ||  99.9th Perc: {}             99.9th Perc: {}                                 |
        ||          Max: {}                     Max: {}                                 |
        ||                                                                                                  |
        |+--------------------------------------------------------------------------------------------------+""",
            field("actorGroupName", name, 77),

            field("processingTimeMeasurements", processingTime.numberOfMeasurements),
            field("timeInMailboxMeasurements", timeInMailbox.numberOfMeasurements),
            field("mailboxSizeMin", mailboxSize.min, 8),

            field("processingTimeMin", processingTime.min),
            field("timeInMailboxMin", timeInMailbox.min),
            field("mailboxSizeAverage", mailboxSize.average, 8),

            field("processingTimePercentile50", processingTime.percentile(50.0D)),
            field("timeInMailboxPercentile50", timeInMailbox.percentile(50.0D)),
            field("mailboxSizeMax", mailboxSize.max, 8),

            field("processingTimePercentile90", processingTime.percentile(90.0D)),
            field("timeInMailboxPercentile90", timeInMailbox.percentile(90.0D)),

            field("processingTimePercentile95", processingTime.percentile(95.0D)),
            field("timeInMailboxPercentile95", timeInMailbox.percentile(95.0D)),
            field("actorsMax", actors.max, 6),

            field("processingTimePercentile99", processingTime.percentile(99.0D)),
            field("timeInMailboxPercentile99", timeInMailbox.percentile(99.0D)),
            field("errorsCount", errors.count, 6),

            field("processingTimePercentile99_9", processingTime.percentile(99.9D)),
            field("timeInMailboxPercentile99_9", timeInMailbox.percentile(99.9D)),

            field("processingTimeMax", processingTime.max),
            field("timeInMailboxMax", timeInMailbox.max)
       )
    }

  }

  def logRouterMetrics(name: String, actorSnapshot: EntitySnapshot): Unit = {
    for {
      processingTime ← actorSnapshot.histogram("processing-time")
      timeInMailbox ← actorSnapshot.histogram("time-in-mailbox")
      routingTime ← actorSnapshot.histogram("routing-time")
      errors ← actorSnapshot.counter("errors")
    } {

      logMetrics("ROUTER_METRICS",
        """
        |+--------------------------------------------------------------------------------------------------+
        ||                                                                                                  |
        ||    Router: {}   |
        ||                                                                                                  |
        ||   Processing Time (nanoseconds)    Time in Mailbox (nanoseconds)    Routing Time (nanoseconds)   |
        ||    Msg Count: {}             Msg Count: {}       Msg Count: {}     |
        ||          Min: {}                   Min: {}             Min: {}     |
        ||    50th Perc: {}             50th Perc: {}       50th Perc: {}     |
        ||    90th Perc: {}             90th Perc: {}       90th Perc: {}     |
        ||    95th Perc: {}             95th Perc: {}       95th Perc: {}     |
        ||    99th Perc: {}             99th Perc: {}       99th Perc: {}     |
        ||  99.9th Perc: {}           99.9th Perc: {}     99.9th Perc: {}     |
        ||          Max: {}                   Max: {}             Max: {}     |
        ||                                                                                                  |
        ||  Error Count: {}                                                                             |
        ||                                                                                                  |
        |+--------------------------------------------------------------------------------------------------+""",
            field("routerName", name, 83),

            field("processingTimeMeasurements", processingTime.numberOfMeasurements),
            field("timeInMailboxMeasurements", timeInMailbox.numberOfMeasurements),
            field("routingTimeMeasurements", routingTime.numberOfMeasurements),

            field("processingTimeMin", processingTime.min),
            field("timeInMailboxMin", timeInMailbox.min),
            field("routingTimeMin", routingTime.min),

            field("processingTimePercentile50", processingTime.percentile(50.0D)),
            field("timeInMailboxPercentile50", timeInMailbox.percentile(50.0D)),
            field("routingTimePercentile50", routingTime.percentile(50.0D)),

            field("processingTimePercentile90", processingTime.percentile(90.0D)),
            field("timeInMailboxPercentile90", timeInMailbox.percentile(90.0D)),
            field("routingTimePercentile90", routingTime.percentile(90.0D)),

            field("processingTimePercentile95", processingTime.percentile(95.0D)),
            field("timeInMailboxPercentile95", timeInMailbox.percentile(95.0D)),
            field("routingTimePercentile95", routingTime.percentile(95.0D)),

            field("processingTimePercentile99", processingTime.percentile(99.0D)),
            field("timeInMailboxPercentile99", timeInMailbox.percentile(99.0D)),
            field("routingTimePercentile99", routingTime.percentile(99.0D)),

            field("processingTimePercentile99_9", processingTime.percentile(99.9D)),
            field("timeInMailboxPercentile99_9", timeInMailbox.percentile(99.9D)),
            field("routingTimePercentile99_9", routingTime.percentile(99.9D)),

            field("processingTimeMax", processingTime.max),
            field("timeInMailboxMax", timeInMailbox.max),
            field("routingTimeMax", routingTime.max),

            field("errorsCount", errors.count, 6)
       )
    }

  }

  def logDispatcherMetrics(entity: Entity, snapshot: EntitySnapshot): Unit = entity.tags.get("dispatcher-type") match {
    case Some("fork-join-pool")       ⇒ logForkJoinPool(entity.name, snapshot)
    case Some("thread-pool-executor") ⇒ logThreadPoolExecutor(entity.name, snapshot)
    case ignoreOthers                 ⇒
  }

  def logExecutorMetrics(entity: Entity, snapshot: EntitySnapshot): Unit = entity.tags.get("executor-type") match {
    case Some("fork-join-pool")       ⇒ logForkJoinPool(entity.name, snapshot)
    case Some("thread-pool-executor") ⇒ logThreadPoolExecutor(entity.name, snapshot)
    case ignoreOthers                 ⇒
  }

  def logForkJoinPool(name: String, forkJoinMetrics: EntitySnapshot): Unit = {
    for {
      paralellism ← forkJoinMetrics.minMaxCounter("parallelism")
      poolSize ← forkJoinMetrics.gauge("pool-size")
      activeThreads ← forkJoinMetrics.gauge("active-threads")
      runningThreads ← forkJoinMetrics.gauge("running-threads")
      queuedTaskCount ← forkJoinMetrics.gauge("queued-task-count")
      queuedSubmissionCount ← forkJoinMetrics.gauge("queued-submission-count")

    } {
      logMetrics("FORK_JOIN_POOL_METRICS",
        """
          |+-------------------------------------------------------------------------------------------------------------------------+
          ||  Fork-Join-Pool                                                                                                         |
          ||                                                                                                                         |
          ||  Dispatcher: {} |
          ||                                                                                                                         |
          ||  Paralellism: {}                                                                                                      |
          ||                                                                                                                         |
          ||                 Pool Size       Active Threads     Running Threads     Queue Task Count     Queued Submission Count     |
          ||      Min           {}              {}                {}                {}                     {}          |
          ||      Avg           {}              {}                {}                {}                     {}          |
          ||      Max           {}              {}                {}                {}                     {}          |
          ||                                                                                                                         |
          |+-------------------------------------------------------------------------------------------------------------------------+""",
            field("dispatcherName", name, 106),

            field("paralellism", paralellism.max, 4),

            field("poolSizeMin", poolSize.min, 4),
            field("activeThreadsMin", activeThreads.min, 4),
            field("runningThreadsMin", runningThreads.min, 4),
            field("queuedTaskCountMin", queuedTaskCount.min, 4),
            field("queuedSubmissionCountMin", queuedSubmissionCount.min, 8),

            field("poolSizeAverage", poolSize.average, 4),
            field("activeThreadsAverage", activeThreads.average, 4),
            field("runningThreadsAverage", runningThreads.average, 4),
            field("queuedTaskCountAverage", queuedTaskCount.average, 4),
            field("queuedSubmissionCountAverage", queuedSubmissionCount.average, 8),

            field("poolSizeMax", poolSize.max, 4),
            field("activeThreadsMax", activeThreads.max, 4),
            field("runningThreadsMax", runningThreads.max, 4),
            field("queuedTaskCountMax", queuedTaskCount.max, 4),
            field("queuedSubmissionCountMax", queuedSubmissionCount.max, 8)
       )
    }
  }

  def logThreadPoolExecutor(name: String, threadPoolMetrics: EntitySnapshot): Unit = {
    for {
      corePoolSize ← threadPoolMetrics.gauge("core-pool-size")
      maxPoolSize ← threadPoolMetrics.gauge("max-pool-size")
      poolSize ← threadPoolMetrics.gauge("pool-size")
      activeThreads ← threadPoolMetrics.gauge("active-threads")
      processedTasks ← threadPoolMetrics.gauge("processed-tasks")
    } {

      logMetrics("THREAD_POOL_EXECUTOR_METRICS",
        """
          |+--------------------------------------------------------------------------------------------------+
          ||  Thread-Pool-Executor                                                                            |
          ||                                                                                                  |
          ||  Dispatcher: {} |
          ||                                                                                                  |
          ||  Core Pool Size: {}                                                                            |
          ||  Max  Pool Size: {}                                                                            |
          ||                                                                                                  |
          ||                                                                                                  |
          ||                         Pool Size        Active Threads          Processed Task                  |
          ||           Min              {}                {}                   {}                       |
          ||           Avg              {}                {}                   {}                       |
          ||           Max              {}                {}                   {}                       |
          ||                                                                                                  |
          |+--------------------------------------------------------------------------------------------------+""",
            field("dispatcherName", name, 83),

            field("corePoolSize", corePoolSize.max, 4),
            field("maxPoolSize", maxPoolSize.max, 4),

            field("poolSizeMin", poolSize.min, 4),
            field("activeThreadsMin", activeThreads.min, 4),
            field("processedTasksMin", processedTasks.min, 4),

            field("poolSizeAverage", poolSize.average, 4),
            field("activeThreadsAverage", activeThreads.average, 4),
            field("processedTasksAverage", processedTasks.average, 4),

            field("poolSizeMax", poolSize.max, 4),
            field("activeThreadsMax", activeThreads.max, 4),
            field("processedTasksMax", processedTasks.max, 4)
       )
    }

  }

  def logSystemMetrics(metric: String, snapshot: EntitySnapshot): Unit = metric match {
    case "cpu"              ⇒ logCpuMetrics(snapshot)
    case "network"          ⇒ logNetworkMetrics(snapshot)
    case "process-cpu"      ⇒ logProcessCpuMetrics(snapshot)
    case "context-switches" ⇒ logContextSwitchesMetrics(snapshot)
    case ignoreOthers       ⇒
  }

  def logCpuMetrics(cpuMetrics: EntitySnapshot): Unit = {
    for {
      user ← cpuMetrics.histogram("cpu-user")
      system ← cpuMetrics.histogram("cpu-system")
      cpuWait ← cpuMetrics.histogram("cpu-wait")
      idle ← cpuMetrics.histogram("cpu-idle")
    } {

      logMetrics("THREAD_POOL_EXECUTOR_METRICS",
        """
        |+--------------------------------------------------------------------------------------------------+
        ||                                                                                                  |
        ||    CPU (ALL)                                                                                     |
        ||                                                                                                  |
        ||    User (percentage)       System (percentage)    Wait (percentage)   Idle (percentage)          |
        ||       Min: {}                   Min: {}               Min: {}           Min: {}              |
        ||       Avg: {}                   Avg: {}               Avg: {}           Avg: {}              |
        ||       Max: {}                   Max: {}               Max: {}           Max: {}              |
        ||                                                                                                  |
        ||                                                                                                  |
        |+--------------------------------------------------------------------------------------------------+""",
            field("userMin", user.min, 3),
            field("systemMin", system.min, 3),
            field("cpuWaitMin", cpuWait.min, 3),
            field("idleMin", idle.min, 3),

            field("userAverage", user.average, 3),
            field("systemAverage", system.average, 3),
            field("cpuWaitAverage", cpuWait.average, 3),
            field("idleAverage", idle.average, 3),

            field("userMax", user.max, 3),
            field("systemMax", system.max, 3),
            field("cpuWaitMax", cpuWait.max, 3),
            field("idleMax", idle.max, 3)
       )
    }

  }

  def logNetworkMetrics(networkMetrics: EntitySnapshot): Unit = {
    for {
      rxBytes ← networkMetrics.histogram("rx-bytes")
      txBytes ← networkMetrics.histogram("tx-bytes")
      rxErrors ← networkMetrics.histogram("rx-errors")
      txErrors ← networkMetrics.histogram("tx-errors")
    } {

      logMetrics("NETWORK_METRICS",
        """
        |+--------------------------------------------------------------------------------------------------+
        ||                                                                                                  |
        ||    Network (ALL)                                                                                 |
        ||                                                                                                  |
        ||     Rx-Bytes (KB)                Tx-Bytes (KB)              Rx-Errors            Tx-Errors       |
        ||      Min: {}                  Min: {}                 Total: {}      Total: {}  |
        ||      Avg: {}                Avg: {}                                                     |
        ||      Max: {}                  Max: {}                                                       |
        ||                                                                                                  |
        |+--------------------------------------------------------------------------------------------------+""",
            field("rxBytesMin", rxBytes.min, 4),
            field("txBytesMin", txBytes.min, 4),
            field("rxErrorsCount", rxErrors.sum, 8),
            field("txErrorsCount", txErrors.sum, 8),

            field("rxBytesAverage", rxBytes.average, 4),
            field("txBytesAverage", txBytes.average, 4),

            field("rxBytesMax", rxBytes.max, 4),
            field("txBytesMax", txBytes.max, 4)
       )
    }
  }

  def logProcessCpuMetrics(processCpuMetrics: EntitySnapshot): Unit = {
    for {
      user ← processCpuMetrics.histogram("process-user-cpu")
      total ← processCpuMetrics.histogram("process-cpu")
    } {

      logMetrics("PROCESS_CPU_METRICS",
        """
        |+--------------------------------------------------------------------------------------------------+
        ||                                                                                                  |
        ||    Process-CPU                                                                                   |
        ||                                                                                                  |
        ||             User-Percentage                           Total-Percentage                           |
        ||                Min: {}                         Min: {}                       |
        ||                Avg: {}                         Avg: {}                       |
        ||                Max: {}                         Max: {}                       |
        ||                                                                                                  |
        |+--------------------------------------------------------------------------------------------------+""",
            field("userMin", user.min),
            field("totalMin", total.min),

            field("userAverage", user.average),
            field("totalAverage", total.average),

            field("userMax", user.max),
            field("totalMax", total.max)
       )
    }

  }

  def logContextSwitchesMetrics(contextSwitchMetrics: EntitySnapshot): Unit = {
    for {
      perProcessVoluntary ← contextSwitchMetrics.histogram("context-switches-process-voluntary")
      perProcessNonVoluntary ← contextSwitchMetrics.histogram("context-switches-process-non-voluntary")
      global ← contextSwitchMetrics.histogram("context-switches-global")
    } {

      logMetrics("CONTEXT_SWITCHES_METRICS",
        """
        |+--------------------------------------------------------------------------------------------------+
        ||                                                                                                  |
        ||    Context-Switches                                                                              |
        ||                                                                                                  |
        ||        Global                Per-Process-Non-Voluntary            Per-Process-Voluntary          |
        ||    Min: {}                   Min: {}                  Min: {}      |
        ||    Avg: {}                   Avg: {}                  Avg: {}      |
        ||    Max: {}                   Max: {}                  Max: {}      |
        ||                                                                                                  |
        |+--------------------------------------------------------------------------------------------------+""",
            field("globalMin", global.min),
            field("perProcessNonVoluntaryMin", perProcessNonVoluntary.min),
            field("perProcessVoluntaryMin", perProcessVoluntary.min),

            field("globalAverage", global.average),
            field("perProcessNonVoluntaryAverage", perProcessNonVoluntary.average),
            field("perProcessVoluntaryAverage", perProcessVoluntary.average),

            field("globalMax", global.max),
            field("perProcessNonVoluntaryMax", perProcessNonVoluntary.max),
            field("perProcessVoluntaryMax", perProcessVoluntary.max)
       )
    }

  }

  def logTraceMetrics(name: String, traceSnapshot: EntitySnapshot): Unit = {
    val traceMetricsData = StringBuilder.newBuilder

    for {
      elapsedTime ← traceSnapshot.histogram("elapsed-time")
    } {

      traceMetricsData.append(
        """
        |+--------------------------------------------------------------------------------------------------+
        ||                                                                                                  |
        ||    Trace: %-83s    |
        ||    Count: %-8s                                                                               |
        ||                                                                                                  |
        ||  Elapsed Time (nanoseconds):                                                                     |
        |"""
          .stripMargin.format(
            name, elapsedTime.numberOfMeasurements))

      traceMetricsData.append(compactHistogramView(elapsedTime))
      traceMetricsData.append(
        """
          ||                                                                                                  |
          |+--------------------------------------------------------------------------------------------------+"""
          .
          stripMargin)

      log.info(traceMetricsData.toString())
    }
  }

  def logUserMetrics(histograms: Map[String, Option[Histogram.Snapshot]],
    counters: Map[String, Option[Counter.Snapshot]], minMaxCounters: Map[String, Option[Histogram.Snapshot]],
    gauges: Map[String, Option[Histogram.Snapshot]]): Unit = {

    if (histograms.isEmpty && counters.isEmpty && minMaxCounters.isEmpty && gauges.isEmpty) {
      log.info("No metrics reported")
      return
    }

    val metricsData = StringBuilder.newBuilder

    metricsData.append(
      """
        |+--------------------------------------------------------------------------------------------------+
        ||                                                                                                  |
        ||                                         Counters                                                 |
        ||                                       -------------                                              |
        |""".stripMargin)

    counters.foreach { case (name, snapshot) ⇒ metricsData.append(userCounterString(name, snapshot.get)) }

    metricsData.append(
      """||                                                                                                  |
        ||                                                                                                  |
        ||                                        Histograms                                                |
        ||                                      --------------                                              |
        |""".stripMargin)

    histograms.foreach {
      case (name, snapshot) ⇒
        metricsData.append("|  %-40s                                                        |\n".format(name))
        metricsData.append(compactHistogramView(snapshot.get))
        metricsData.append("\n|                                                                                                  |\n")
    }

    metricsData.append(
      """||                                                                                                  |
        ||                                      MinMaxCounters                                              |
        ||                                    -----------------                                             |
        |""".stripMargin)

    minMaxCounters.foreach {
      case (name, snapshot) ⇒
        metricsData.append("|  %-40s                                                        |\n".format(name))
        metricsData.append(histogramView(snapshot.get))
        metricsData.append("\n|                                                                                                  |\n")
    }

    metricsData.append(
      """||                                                                                                  |
        ||                                          Gauges                                                  |
        ||                                        ----------                                                |
        |"""
        .stripMargin)

    gauges.foreach {
      case (name, snapshot) ⇒
        metricsData.append("|  %-40s                                                        |\n".format(name))
        metricsData.append(histogramView(snapshot.get))
        metricsData.append("\n|                                                                                                  |\n")
    }

    metricsData.append(
      """||                                                                                                  |
        |+--------------------------------------------------------------------------------------------------+"""
        .stripMargin)

    log.info(metricsData.toString())
  }

  def userCounterString(counterName: String, snapshot: Counter.Snapshot): String = {
    "|             %30s  =>  %-12s                                     |\n"
      .format(counterName, snapshot.count)
  }

  def compactHistogramView(histogram: Histogram.Snapshot): String = {
    val sb = StringBuilder.newBuilder

    sb.append("|    Min: %-11s  50th Perc: %-12s   90th Perc: %-12s   95th Perc: %-12s |\n".format(
      histogram.min, histogram.percentile(50.0D), histogram.percentile(90.0D), histogram.percentile(95.0D)))
    sb.append("|                      99th Perc: %-12s 99.9th Perc: %-12s         Max: %-12s |".format(
      histogram.percentile(99.0D), histogram.percentile(99.9D), histogram.max))

    sb.toString()
  }

  def histogramView(histogram: Histogram.Snapshot): String =
    "|          Min: %-12s           Average: %-12s                Max: %-12s      |"
      .format(histogram.min, histogram.average, histogram.max)

  def logJdbcMetrics(jdbcSnapshot: EntitySnapshot): Unit = {

    val histograms =
      Map(
        "updates" -> jdbcSnapshot.histogram("updates"),
        "queries" -> jdbcSnapshot.histogram("queries"),
        "batches" -> jdbcSnapshot.histogram("batches"),
        "generic-execute" -> jdbcSnapshot.histogram("generic-execute")
      )

    val metricsData = StringBuilder.newBuilder

    metricsData.append(
      """
        |+--------------------------------------------------------------------------------------------------+
        ||                                                                                                  |
        ||                                           JDBC                                                   |
        ||                                       -------------                                              |
        |""".stripMargin)

    histograms.foreach {
      case (name, snapshot) ⇒
        metricsData.append("|  %-40s                                                        |\n".format(name))
        metricsData.append("|  Requests: %-40s                                              |\n".format(snapshot.get.numberOfMeasurements))
        metricsData.append(compactHistogramView(snapshot.get))
        metricsData.append("\n|                                                                                                  |\n")
    }

    metricsData.append(
      """||                                                                                                  |
         |+--------------------------------------------------------------------------------------------------+"""
        .stripMargin)

    log.info(metricsData.toString())

  }
}

object LogbackReporterSubscriber {

  implicit class RichHistogramSnapshot(histogram: Histogram.Snapshot) {
    def average: Double = {
      if (histogram.numberOfMeasurements == 0) 0D
      else histogram.sum / histogram.numberOfMeasurements
    }
  }
}
