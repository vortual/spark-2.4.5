/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.deploy.yarn

import java.util.Collections
import java.util.concurrent._
import java.util.concurrent.atomic.AtomicInteger
import java.util.regex.Pattern

import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.collection.mutable.{ArrayBuffer, HashMap, HashSet}
import scala.util.control.NonFatal

import org.apache.hadoop.yarn.api.records._
import org.apache.hadoop.yarn.client.api.AMRMClient
import org.apache.hadoop.yarn.client.api.AMRMClient.ContainerRequest
import org.apache.hadoop.yarn.conf.YarnConfiguration

import org.apache.spark.{SecurityManager, SparkConf, SparkException}
import org.apache.spark.deploy.yarn.YarnSparkHadoopUtil._
import org.apache.spark.deploy.yarn.config._
import org.apache.spark.internal.Logging
import org.apache.spark.internal.config._
import org.apache.spark.rpc.{RpcCallContext, RpcEndpointRef}
import org.apache.spark.scheduler.{ExecutorExited, ExecutorLossReason}
import org.apache.spark.scheduler.cluster.CoarseGrainedClusterMessages.RemoveExecutor
import org.apache.spark.scheduler.cluster.CoarseGrainedClusterMessages.RetrieveLastAllocatedExecutorId
import org.apache.spark.scheduler.cluster.SchedulerBackendUtils
import org.apache.spark.util.{Clock, SystemClock, ThreadUtils}

/**
 * YarnAllocator is charged with requesting containers from the YARN ResourceManager and deciding
 * what to do with containers when YARN fulfills these requests.
 *
 * This class makes use of YARN's AMRMClient APIs. We interact with the AMRMClient in three ways:
 * * Making our resource needs known, which updates local bookkeeping about containers requested.
 * * Calling "allocate", which syncs our local container requests with the RM, and returns any
 *   containers that YARN has granted to us.  This also functions as a heartbeat.
 * * Processing the containers granted to us to possibly launch executors inside of them.
 *
 * The public methods of this class are thread-safe.  All methods that mutate state are
 * synchronized.
 */
private[yarn] class YarnAllocator(
    driverUrl: String,
    driverRef: RpcEndpointRef,
    conf: YarnConfiguration,
    sparkConf: SparkConf,
    amClient: AMRMClient[ContainerRequest],
    appAttemptId: ApplicationAttemptId,
    securityMgr: SecurityManager,
    localResources: Map[String, LocalResource],
    resolver: SparkRackResolver,
    clock: Clock = new SystemClock)
  extends Logging {

  import YarnAllocator._

  // Visible for testing.
  val allocatedHostToContainersMap = new HashMap[String, collection.mutable.Set[ContainerId]]
  val allocatedContainerToHostMap = new HashMap[ContainerId, String]

  // Containers that we no longer care about. We've either already told the RM to release them or
  // will on the next heartbeat. Containers get removed from this map after the RM tells us they've
  // completed.
  private val releasedContainers = Collections.newSetFromMap[ContainerId](
    new ConcurrentHashMap[ContainerId, java.lang.Boolean])

  private val runningExecutors = Collections.newSetFromMap[String](
    new ConcurrentHashMap[String, java.lang.Boolean]())

  private val numExecutorsStarting = new AtomicInteger(0)

  /**
   * Used to generate a unique ID per executor
   *
   * Init `executorIdCounter`. when AM restart, `executorIdCounter` will reset to 0. Then
   * the id of new executor will start from 1, this will conflict with the executor has
   * already created before. So, we should initialize the `executorIdCounter` by getting
   * the max executorId from driver.
   *
   * And this situation of executorId conflict is just in yarn client mode, so this is an issue
   * in yarn client mode. For more details, can check in jira.
   *
   * @see SPARK-12864
   */
  private var executorIdCounter: Int =
    driverRef.askSync[Int](RetrieveLastAllocatedExecutorId)

  private[spark] val failureTracker = new FailureTracker(sparkConf, clock)

  private val allocatorBlacklistTracker =
    new YarnAllocatorBlacklistTracker(sparkConf, amClient, failureTracker)

  @volatile private var targetNumExecutors =
    SchedulerBackendUtils.getInitialTargetExecutorNumber(sparkConf)


  // Executor loss reason requests that are pending - maps from executor ID for inquiry to a
  // list of requesters that should be responded to once we find out why the given executor
  // was lost.
  private val pendingLossReasonRequests = new HashMap[String, mutable.Buffer[RpcCallContext]]

  // Maintain loss reasons for already released executors, it will be added when executor loss
  // reason is got from AM-RM call, and be removed after querying this loss reason.
  private val releasedExecutorLossReasons = new HashMap[String, ExecutorLossReason]

  // Keep track of which container is running which executor to remove the executors later
  // Visible for testing.
  private[yarn] val executorIdToContainer = new HashMap[String, Container]

  private var numUnexpectedContainerRelease = 0L
  private val containerIdToExecutorId = new HashMap[ContainerId, String]

  // Executor memory in MB.
  protected val executorMemory = sparkConf.get(EXECUTOR_MEMORY).toInt
  // Additional memory overhead.
  protected val memoryOverhead: Int = sparkConf.get(EXECUTOR_MEMORY_OVERHEAD).getOrElse(
    math.max((MEMORY_OVERHEAD_FACTOR * executorMemory).toInt, MEMORY_OVERHEAD_MIN)).toInt
  protected val pysparkWorkerMemory: Int = if (sparkConf.get(IS_PYTHON_APP)) {
    sparkConf.get(PYSPARK_EXECUTOR_MEMORY).map(_.toInt).getOrElse(0)
  } else {
    0
  }
  // Number of cores per executor.
  protected val executorCores = sparkConf.get(EXECUTOR_CORES)
  // Resource capability requested for each executors
  private[yarn] val resource = Resource.newInstance(
    executorMemory + memoryOverhead + pysparkWorkerMemory,
    executorCores)

  private val launcherPool = ThreadUtils.newDaemonCachedThreadPool(
    "ContainerLauncher", sparkConf.get(CONTAINER_LAUNCH_MAX_THREADS))

  // For testing
  private val launchContainers = sparkConf.getBoolean("spark.yarn.launchContainers", true)

  private val labelExpression = sparkConf.get(EXECUTOR_NODE_LABEL_EXPRESSION)

  // A map to store preferred hostname and possible task numbers running on it.
  private var hostToLocalTaskCounts: Map[String, Int] = Map.empty

  // Number of tasks that have locality preferences in active stages
  private[yarn] var numLocalityAwareTasks: Int = 0

  // A container placement strategy based on pending tasks' locality preference
  private[yarn] val containerPlacementStrategy =
    new LocalityPreferredContainerPlacementStrategy(sparkConf, conf, resource, resolver)

  def getNumExecutorsRunning: Int = runningExecutors.size()

  def getNumReleasedContainers: Int = releasedContainers.size()

  def getNumExecutorsFailed: Int = failureTracker.numFailedExecutors

  def isAllNodeBlacklisted: Boolean = allocatorBlacklistTracker.isAllNodeBlacklisted

  /**
   * A sequence of pending container requests that have not yet been fulfilled.
   */
  def getPendingAllocate: Seq[ContainerRequest] = getPendingAtLocation(ANY_HOST)

  def numContainersPendingAllocate: Int = synchronized {
    getPendingAllocate.size
  }

  /**
   * A sequence of pending container requests at the given location that have not yet been
   * fulfilled.
   */
  private def getPendingAtLocation(location: String): Seq[ContainerRequest] = {
    amClient.getMatchingRequests(RM_REQUEST_PRIORITY, location, resource).asScala
      .flatMap(_.asScala)
      .toSeq
  }

  /**
   * Request as many executors from the ResourceManager as needed to reach the desired total. If
   * the requested total is smaller than the current number of running executors, no executors will
   * be killed.
   * @param requestedTotal total number of containers requested
   * @param localityAwareTasks number of locality aware tasks to be used as container placement hint
   * @param hostToLocalTaskCount a map of preferred hostname to possible task counts to be used as
   *                             container placement hint.
   * @param nodeBlacklist blacklisted nodes, which is passed in to avoid allocating new containers
   *                      on them. It will be used to update the application master's blacklist.
   * @return Whether the new requested total is different than the old value.
   */
  def requestTotalExecutorsWithPreferredLocalities(
      requestedTotal: Int,
      localityAwareTasks: Int,
      hostToLocalTaskCount: Map[String, Int],
      nodeBlacklist: Set[String]): Boolean = synchronized {
    this.numLocalityAwareTasks = localityAwareTasks
    this.hostToLocalTaskCounts = hostToLocalTaskCount

    if (requestedTotal != targetNumExecutors) {
      logInfo(s"Driver requested a total number of $requestedTotal executor(s).")
      targetNumExecutors = requestedTotal
      allocatorBlacklistTracker.setSchedulerBlacklistedNodes(nodeBlacklist)
      true
    } else {
      false
    }
  }

  /**
   * Request that the ResourceManager release the container running the specified executor.
   */
  def killExecutor(executorId: String): Unit = synchronized {
    executorIdToContainer.get(executorId) match {
      case Some(container) if !releasedContainers.contains(container.getId) =>
        internalReleaseContainer(container)
        runningExecutors.remove(executorId)
      case _ => logWarning(s"Attempted to kill unknown executor $executorId!")
    }
  }

  /**
   * Request resources such that, if YARN gives us all we ask for, we'll have a number of containers
   * equal to maxExecutors.
   *
   * Deal with any containers YARN has granted to us by possibly launching executors in them.
   *
   * This must be synchronized because variables read in this method are mutated by other methods.
   */
  def allocateResources(): Unit = synchronized {
    // 更新要申请的 executor 数量，主要改的是 ask 变量： org.apache.hadoop.yarn.client.api.impl.AMRMClientImpl.ask
    updateResourceRequests()

    val progressIndicator = 0.1f
    // Poll the ResourceManager. This doubles as a heartbeat if there are no pending container
    // requests.
    val allocateResponse = amClient.allocate(progressIndicator)

    val allocatedContainers = allocateResponse.getAllocatedContainers()
    allocatorBlacklistTracker.setNumClusterNodes(allocateResponse.getNumClusterNodes)

    if (allocatedContainers.size > 0) {
      logDebug(("Allocated containers: %d. Current executor count: %d. " +
        "Launching executor count: %d. Cluster resources: %s.")
        .format(
          allocatedContainers.size,
          runningExecutors.size,
          numExecutorsStarting.get,
          allocateResponse.getAvailableResources))

      handleAllocatedContainers(allocatedContainers.asScala)
    }

    val completedContainers = allocateResponse.getCompletedContainersStatuses()
    if (completedContainers.size > 0) {
      logDebug("Completed %d containers".format(completedContainers.size))
      processCompletedContainers(completedContainers.asScala)
      logDebug("Finished processing %d completed containers. Current running executor count: %d."
        .format(completedContainers.size, runningExecutors.size))
    }
  }

  /**
   * Update the set of container requests that we will sync with the RM based on the number of
   * executors we have currently running and our target number of executors.
   *
   * Visible for testing.
   */
  def updateResourceRequests(): Unit = {
    val pendingAllocate = getPendingAllocate
    val numPendingAllocate = pendingAllocate.size
    val missing = targetNumExecutors - numPendingAllocate -
      numExecutorsStarting.get - runningExecutors.size
    logDebug(s"Updating resource requests, target: $targetNumExecutors, " +
      s"pending: $numPendingAllocate, running: ${runningExecutors.size}, " +
      s"executorsStarting: ${numExecutorsStarting.get}")

    // Split the pending container request into three groups: locality matched list, locality
    // unmatched list and non-locality list. Take the locality matched container request into
    // consideration of container placement, treat as allocated containers.
    // For locality unmatched and locality free container requests, cancel these container
    // requests, since required locality preference has been changed, recalculating using
    // container placement strategy.
    val (localRequests, staleRequests, anyHostRequests) = splitPendingAllocationsByLocality(
      hostToLocalTaskCounts, pendingAllocate)

    if (missing > 0) {
      logInfo(s"Will request $missing executor container(s), each with " +
        s"${resource.getVirtualCores} core(s) and " +
        s"${resource.getMemory} MB memory (including $memoryOverhead MB of overhead)")

      // cancel "stale" requests for locations that are no longer needed
      staleRequests.foreach { stale =>
        amClient.removeContainerRequest(stale)
      }
      val cancelledContainers = staleRequests.size
      if (cancelledContainers > 0) {
        logInfo(s"Canceled $cancelledContainers container request(s) (locality no longer needed)")
      }

      // consider the number of new containers and cancelled stale containers available
      val availableContainers = missing + cancelledContainers

      // to maximize locality, include requests with no locality preference that can be cancelled
      val potentialContainers = availableContainers + anyHostRequests.size

      val containerLocalityPreferences = containerPlacementStrategy.localityOfRequestedContainers(
        potentialContainers, numLocalityAwareTasks, hostToLocalTaskCounts,
          allocatedHostToContainersMap, localRequests)

      val newLocalityRequests = new mutable.ArrayBuffer[ContainerRequest]
      containerLocalityPreferences.foreach {
        case ContainerLocalityPreferences(nodes, racks) if nodes != null =>
          newLocalityRequests += createContainerRequest(resource, nodes, racks)
        case _ =>
      }

      if (availableContainers >= newLocalityRequests.size) {
        // more containers are available than needed for locality, fill in requests for any host
        for (i <- 0 until (availableContainers - newLocalityRequests.size)) {
          newLocalityRequests += createContainerRequest(resource, null, null)
        }
      } else {
        val numToCancel = newLocalityRequests.size - availableContainers
        // cancel some requests without locality preferences to schedule more local containers
        anyHostRequests.slice(0, numToCancel).foreach { nonLocal =>
          amClient.removeContainerRequest(nonLocal)
        }
        if (numToCancel > 0) {
          logInfo(s"Canceled $numToCancel unlocalized container requests to resubmit with locality")
        }
      }

      newLocalityRequests.foreach { request =>
        amClient.addContainerRequest(request)
      }

      if (log.isInfoEnabled()) {
        val (localized, anyHost) = newLocalityRequests.partition(_.getNodes() != null)
        if (anyHost.nonEmpty) {
          logInfo(s"Submitted ${anyHost.size} unlocalized container requests.")
        }
        localized.foreach { request =>
          logInfo(s"Submitted container request for host ${hostStr(request)}.")
        }
      }
    } else if (numPendingAllocate > 0 && missing < 0) {
      val numToCancel = math.min(numPendingAllocate, -missing)
      logInfo(s"Canceling requests for $numToCancel executor container(s) to have a new desired " +
        s"total $targetNumExecutors executors.")
      // cancel pending allocate requests by taking locality preference into account
      val cancelRequests = (staleRequests ++ anyHostRequests ++ localRequests).take(numToCancel)
      cancelRequests.foreach(amClient.removeContainerRequest)
    }
  }

  def stop(): Unit = {
    // Forcefully shut down the launcher pool, in case this is being called in the middle of
    // container allocation. This will prevent queued executors from being started - and
    // potentially interrupt active ExecutorRunnable instaces too.
    launcherPool.shutdownNow()
  }

  private def hostStr(request: ContainerRequest): String = {
    Option(request.getNodes) match {
      case Some(nodes) => nodes.asScala.mkString(",")
      case None => "Any"
    }
  }

  /**
   * Creates a container request, handling the reflection required to use YARN features that were
   * added in recent versions.
   */
  private def createContainerRequest(
      resource: Resource,
      nodes: Array[String],
      racks: Array[String]): ContainerRequest = {
    new ContainerRequest(resource, nodes, racks, RM_REQUEST_PRIORITY, true, labelExpression.orNull)
  }

  /**
   * Handle containers granted by the RM by launching executors on them.
   *
   * Due to the way the YARN allocation protocol works, certain healthy race conditions can result
   * in YARN granting containers that we no longer need. In this case, we release them.
   *
   * Visible for testing.
   */
  def handleAllocatedContainers(allocatedContainers: Seq[Container]): Unit = {
    val containersToUse = new ArrayBuffer[Container](allocatedContainers.size)

    // Match incoming requests by host
    val remainingAfterHostMatches = new ArrayBuffer[Container]
    for (allocatedContainer <- allocatedContainers) {
      matchContainerToRequest(allocatedContainer, allocatedContainer.getNodeId.getHost,
        containersToUse, remainingAfterHostMatches)
    }

    // Match remaining by rack. Because YARN's RackResolver swallows thread interrupts
    // (see SPARK-27094), which can cause this code to miss interrupts from the AM, use
    // a separate thread to perform the operation.
    val remainingAfterRackMatches = new ArrayBuffer[Container]
    if (remainingAfterHostMatches.nonEmpty) {
      var exception: Option[Throwable] = None
      val thread = new Thread("spark-rack-resolver") {
        override def run(): Unit = {
          try {
            for (allocatedContainer <- remainingAfterHostMatches) {
              val rack = resolver.resolve(conf, allocatedContainer.getNodeId.getHost)
              matchContainerToRequest(allocatedContainer, rack, containersToUse,
                remainingAfterRackMatches)
            }
          } catch {
            case e: Throwable =>
              exception = Some(e)
          }
        }
      }
      thread.setDaemon(true)
      thread.start()

      try {
        thread.join()
      } catch {
        case e: InterruptedException =>
          thread.interrupt()
          throw e
      }

      if (exception.isDefined) {
        throw exception.get
      }
    }

    // Assign remaining that are neither node-local nor rack-local
    val remainingAfterOffRackMatches = new ArrayBuffer[Container]
    for (allocatedContainer <- remainingAfterRackMatches) {
      matchContainerToRequest(allocatedContainer, ANY_HOST, containersToUse,
        remainingAfterOffRackMatches)
    }

    if (!remainingAfterOffRackMatches.isEmpty) {
      logDebug(s"Releasing ${remainingAfterOffRackMatches.size} unneeded containers that were " +
        s"allocated to us")
      for (container <- remainingAfterOffRackMatches) {
        internalReleaseContainer(container)
      }
    }

    runAllocatedContainers(containersToUse)

    logInfo("Received %d containers from YARN, launching executors on %d of them."
      .format(allocatedContainers.size, containersToUse.size))
  }

  /**
   * Looks for requests for the given location that match the given container allocation. If it
   * finds one, removes the request so that it won't be submitted again. Places the container into
   * containersToUse or remaining.
   *
   * @param allocatedContainer container that was given to us by YARN
   * @param location resource name, either a node, rack, or *
   * @param containersToUse list of containers that will be used
   * @param remaining list of containers that will not be used
   */
  private def matchContainerToRequest(
      allocatedContainer: Container,
      location: String,
      containersToUse: ArrayBuffer[Container],
      remaining: ArrayBuffer[Container]): Unit = {
    // SPARK-6050: certain Yarn configurations return a virtual core count that doesn't match the
    // request; for example, capacity scheduler + DefaultResourceCalculator. So match on requested
    // memory, but use the asked vcore count for matching, effectively disabling matching on vcore
    // count.
    val matchingResource = Resource.newInstance(allocatedContainer.getResource.getMemory,
          resource.getVirtualCores)
    val matchingRequests = amClient.getMatchingRequests(allocatedContainer.getPriority, location,
      matchingResource)

    // Match the allocation to a request
    if (!matchingRequests.isEmpty) {
      val containerRequest = matchingRequests.get(0).iterator.next
      amClient.removeContainerRequest(containerRequest)
      containersToUse += allocatedContainer
    } else {
      remaining += allocatedContainer
    }
  }

  /**
   * Launches executors in the allocated containers.
   */
  private def runAllocatedContainers(containersToUse: ArrayBuffer[Container]): Unit = {
    for (container <- containersToUse) {
      executorIdCounter += 1
      val executorHostname = container.getNodeId.getHost
      val containerId = container.getId
      val executorId = executorIdCounter.toString
      assert(container.getResource.getMemory >= resource.getMemory)
      logInfo(s"Launching container $containerId on host $executorHostname " +
        s"for executor with ID $executorId")

      def updateInternalState(): Unit = synchronized {
        runningExecutors.add(executorId)
        numExecutorsStarting.decrementAndGet()
        executorIdToContainer(executorId) = container
        containerIdToExecutorId(container.getId) = executorId

        val containerSet = allocatedHostToContainersMap.getOrElseUpdate(executorHostname,
          new HashSet[ContainerId])
        containerSet += containerId
        allocatedContainerToHostMap.put(containerId, executorHostname)
      }

      if (runningExecutors.size() < targetNumExecutors) {
        numExecutorsStarting.incrementAndGet()
        if (launchContainers) {
          launcherPool.execute(new Runnable {
            override def run(): Unit = {
              try {
                new ExecutorRunnable(
                  Some(container),
                  conf,
                  sparkConf,
                  driverUrl,
                  executorId,
                  executorHostname,
                  executorMemory,
                  executorCores,
                  appAttemptId.getApplicationId.toString,
                  securityMgr,
                  localResources
                ).run()
                updateInternalState()
              } catch {
                case e: Throwable =>
                  numExecutorsStarting.decrementAndGet()
                  if (NonFatal(e)) {
                    logError(s"Failed to launch executor $executorId on container $containerId", e)
                    // Assigned container should be released immediately
                    // to avoid unnecessary resource occupation.
                    amClient.releaseAssignedContainer(containerId)
                  } else {
                    throw e
                  }
              }
            }
          })
        } else {
          // For test only
          updateInternalState()
        }
      } else {
        logInfo(("Skip launching executorRunnable as running executors count: %d " +
          "reached target executors count: %d.").format(
          runningExecutors.size, targetNumExecutors))
      }
    }
  }

  // Visible for testing.
  private[yarn] def processCompletedContainers(completedContainers: Seq[ContainerStatus]): Unit = {
    for (completedContainer <- completedContainers) {
      val containerId = completedContainer.getContainerId
      val alreadyReleased = releasedContainers.remove(containerId)
      val hostOpt = allocatedContainerToHostMap.get(containerId)
      val onHostStr = hostOpt.map(host => s" on host: $host").getOrElse("")
      val exitReason = if (!alreadyReleased) {
        // Decrement the number of executors running. The next iteration of
        // the ApplicationMaster's reporting thread will take care of allocating.
        containerIdToExecutorId.get(containerId) match {
          case Some(executorId) => runningExecutors.remove(executorId)
          case None => logWarning(s"Cannot find executorId for container: ${containerId.toString}")
        }

        logInfo("Completed container %s%s (state: %s, exit status: %s)".format(
          containerId,
          onHostStr,
          completedContainer.getState,
          completedContainer.getExitStatus))
        // Hadoop 2.2.X added a ContainerExitStatus we should switch to use
        // there are some exit status' we shouldn't necessarily count against us, but for
        // now I think its ok as none of the containers are expected to exit.
        val exitStatus = completedContainer.getExitStatus
        val (exitCausedByApp, containerExitReason) = exitStatus match {
          case ContainerExitStatus.SUCCESS =>
            (false, s"Executor for container $containerId exited because of a YARN event (e.g., " +
              "pre-emption) and not because of an error in the running job.")
          case ContainerExitStatus.PREEMPTED =>
            // Preemption is not the fault of the running tasks, since YARN preempts containers
            // merely to do resource sharing, and tasks that fail due to preempted executors could
            // just as easily finish on any other executor. See SPARK-8167.
            (false, s"Container ${containerId}${onHostStr} was preempted.")
          // Should probably still count memory exceeded exit codes towards task failures
          case VMEM_EXCEEDED_EXIT_CODE =>
            (true, memLimitExceededLogMessage(
              completedContainer.getDiagnostics,
              VMEM_EXCEEDED_PATTERN))
          case PMEM_EXCEEDED_EXIT_CODE =>
            (true, memLimitExceededLogMessage(
              completedContainer.getDiagnostics,
              PMEM_EXCEEDED_PATTERN))
          case other_exit_status =>
            // SPARK-26269: follow YARN's blacklisting behaviour(see https://github
            // .com/apache/hadoop/blob/228156cfd1b474988bc4fedfbf7edddc87db41e3/had
            // oop-yarn-project/hadoop-yarn/hadoop-yarn-common/src/main/java/org/ap
            // ache/hadoop/yarn/util/Apps.java#L273 for details)
            if (NOT_APP_AND_SYSTEM_FAULT_EXIT_STATUS.contains(other_exit_status)) {
              (false, s"Container marked as failed: $containerId$onHostStr" +
                s". Exit status: ${completedContainer.getExitStatus}" +
                s". Diagnostics: ${completedContainer.getDiagnostics}.")
            } else {
              // completed container from a bad node
              allocatorBlacklistTracker.handleResourceAllocationFailure(hostOpt)
              (true, s"Container from a bad node: $containerId$onHostStr" +
                s". Exit status: ${completedContainer.getExitStatus}" +
                s". Diagnostics: ${completedContainer.getDiagnostics}.")
            }


        }
        if (exitCausedByApp) {
          logWarning(containerExitReason)
        } else {
          logInfo(containerExitReason)
        }
        ExecutorExited(exitStatus, exitCausedByApp, containerExitReason)
      } else {
        // If we have already released this container, then it must mean
        // that the driver has explicitly requested it to be killed
        ExecutorExited(completedContainer.getExitStatus, exitCausedByApp = false,
          s"Container $containerId exited from explicit termination request.")
      }

      for {
        host <- hostOpt
        containerSet <- allocatedHostToContainersMap.get(host)
      } {
        containerSet.remove(containerId)
        if (containerSet.isEmpty) {
          allocatedHostToContainersMap.remove(host)
        } else {
          allocatedHostToContainersMap.update(host, containerSet)
        }

        allocatedContainerToHostMap.remove(containerId)
      }

      containerIdToExecutorId.remove(containerId).foreach { eid =>
        executorIdToContainer.remove(eid)
        pendingLossReasonRequests.remove(eid) match {
          case Some(pendingRequests) =>
            // Notify application of executor loss reasons so it can decide whether it should abort
            pendingRequests.foreach(_.reply(exitReason))

          case None =>
            // We cannot find executor for pending reasons. This is because completed container
            // is processed before querying pending result. We should store it for later query.
            // This is usually happened when explicitly killing a container, the result will be
            // returned in one AM-RM communication. So query RPC will be later than this completed
            // container process.
            releasedExecutorLossReasons.put(eid, exitReason)
        }
        if (!alreadyReleased) {
          // The executor could have gone away (like no route to host, node failure, etc)
          // Notify backend about the failure of the executor
          numUnexpectedContainerRelease += 1
          driverRef.send(RemoveExecutor(eid, exitReason))
        }
      }
    }
  }

  /**
   * Register that some RpcCallContext has asked the AM why the executor was lost. Note that
   * we can only find the loss reason to send back in the next call to allocateResources().
   */
  private[yarn] def enqueueGetLossReasonRequest(
      eid: String,
      context: RpcCallContext): Unit = synchronized {
    if (executorIdToContainer.contains(eid)) {
      pendingLossReasonRequests
        .getOrElseUpdate(eid, new ArrayBuffer[RpcCallContext]) += context
    } else if (releasedExecutorLossReasons.contains(eid)) {
      // Executor is already released explicitly before getting the loss reason, so directly send
      // the pre-stored lost reason
      context.reply(releasedExecutorLossReasons.remove(eid).get)
    } else {
      logWarning(s"Tried to get the loss reason for non-existent executor $eid")
      context.sendFailure(
        new SparkException(s"Fail to find loss reason for non-existent executor $eid"))
    }
  }

  private def internalReleaseContainer(container: Container): Unit = {
    releasedContainers.add(container.getId())
    amClient.releaseAssignedContainer(container.getId())
  }

  private[yarn] def getNumUnexpectedContainerRelease = numUnexpectedContainerRelease

  private[yarn] def getNumPendingLossReasonRequests: Int = synchronized {
    pendingLossReasonRequests.size
  }

  /**
   * Split the pending container requests into 3 groups based on current localities of pending
   * tasks.
   * @param hostToLocalTaskCount a map of preferred hostname to possible task counts to be used as
   *                             container placement hint.
   * @param pendingAllocations A sequence of pending allocation container request.
   * @return A tuple of 3 sequences, first is a sequence of locality matched container
   *         requests, second is a sequence of locality unmatched container requests, and third is a
   *         sequence of locality free container requests.
   */
  private def splitPendingAllocationsByLocality(
      hostToLocalTaskCount: Map[String, Int],
      pendingAllocations: Seq[ContainerRequest]
    ): (Seq[ContainerRequest], Seq[ContainerRequest], Seq[ContainerRequest]) = {
    val localityMatched = ArrayBuffer[ContainerRequest]()
    val localityUnMatched = ArrayBuffer[ContainerRequest]()
    val localityFree = ArrayBuffer[ContainerRequest]()

    val preferredHosts = hostToLocalTaskCount.keySet
    pendingAllocations.foreach { cr =>
      val nodes = cr.getNodes
      if (nodes == null) {
        localityFree += cr
      } else if (nodes.asScala.toSet.intersect(preferredHosts).nonEmpty) {
        localityMatched += cr
      } else {
        localityUnMatched += cr
      }
    }

    (localityMatched.toSeq, localityUnMatched.toSeq, localityFree.toSeq)
  }

}

private object YarnAllocator {
  val MEM_REGEX = "[0-9.]+ [KMG]B"
  val PMEM_EXCEEDED_PATTERN =
    Pattern.compile(s"$MEM_REGEX of $MEM_REGEX physical memory used")
  val VMEM_EXCEEDED_PATTERN =
    Pattern.compile(s"$MEM_REGEX of $MEM_REGEX virtual memory used")
  val VMEM_EXCEEDED_EXIT_CODE = -103
  val PMEM_EXCEEDED_EXIT_CODE = -104

  def memLimitExceededLogMessage(diagnostics: String, pattern: Pattern): String = {
    val matcher = pattern.matcher(diagnostics)
    val diag = if (matcher.find()) " " + matcher.group() + "." else ""
    s"Container killed by YARN for exceeding memory limits. $diag " +
      "Consider boosting spark.yarn.executor.memoryOverhead or " +
      "disabling yarn.nodemanager.vmem-check-enabled because of YARN-4714."
  }
  val NOT_APP_AND_SYSTEM_FAULT_EXIT_STATUS = Set(
    ContainerExitStatus.KILLED_BY_RESOURCEMANAGER,
    ContainerExitStatus.KILLED_BY_APPMASTER,
    ContainerExitStatus.KILLED_AFTER_APP_COMPLETION,
    ContainerExitStatus.ABORTED,
    ContainerExitStatus.DISKS_FAILED
  )
}
