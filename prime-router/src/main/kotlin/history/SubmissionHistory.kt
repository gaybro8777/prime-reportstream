package gov.cdc.prime.router.history

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonInclude.Include
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonPropertyOrder
import com.fasterxml.jackson.annotation.JsonValue
import com.microsoft.azure.functions.HttpStatus
import gov.cdc.prime.router.ActionLogLevel
import gov.cdc.prime.router.ActionLogScope
import gov.cdc.prime.router.ClientSource
import gov.cdc.prime.router.Report
import gov.cdc.prime.router.ReportId
import gov.cdc.prime.router.ReportStreamFilterResult
import gov.cdc.prime.router.Sender
import gov.cdc.prime.router.Topic
import gov.cdc.prime.router.azure.db.enums.TaskAction
import gov.cdc.prime.router.common.BaseEngine
import java.time.OffsetDateTime

/**
 * This class handles ReportFileHistory for Submissions from a sender.
 *
 * @property actionId reference to the `action` table for the action that created this file
 * @property createdAt when the file was created
 * @property externalName actual filename of the file
 * @property reportId unique identifier for this specific report file
 * @property topic the kind of data contained in the report (e.g. "covid-19")
 * @property reportItemCount number of tests (data rows) contained in the report
 * @property sendingOrg the name of the organization that sent this submission
 * @property httpStatus response code for the user fetching this submission
 * @property bodyUrl url used for generating the filename
 * @property schemaName schema used for generating the filename
 * @property bodyFormat filetype, used for generating the filename
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonPropertyOrder(
    value = [
        "submissionId", "timestamp", "sender", "httpStatus",
        "id", "topic", "reportItemCount", "fileName", "fileType"
    ]
)
open class SubmissionHistory(
    @JsonProperty("submissionId")
    actionId: Long,
    @JsonProperty("timestamp")
    createdAt: OffsetDateTime,
    @JsonInclude(Include.NON_NULL)
    externalName: String? = "",
    @JsonProperty("id")
    reportId: String? = null,
    @JsonProperty("topic")
    schemaTopic: Topic? = null,
    @JsonProperty("reportItemCount")
    itemCount: Int? = null,
    @JsonIgnore
    val sendingOrg: String? = "",
    val httpStatus: Int? = null,
    @JsonIgnore
    val sendingOrgClient: String? = "",
    @JsonIgnore
    val bodyUrl: String? = "",
    @JsonIgnore
    val schemaName: String,
    @JsonProperty("fileType")
    val bodyFormat: String,
) : ReportHistory(
    actionId,
    createdAt,
    externalName,
    reportId,
    schemaTopic,
    itemCount,
) {
    /**
     * The actual download path for the file.
     */
    val fileName: String
        get() {
            if (this.reportId != null) {
                return Report.formExternalFilename(
                    this.bodyUrl,
                    ReportId.fromString(this.reportId),
                    this.schemaName,
                    Report.Format.safeValueOf(this.bodyFormat),
                    this.createdAt
                )
            }
            return ""
        }

    /**
     * The sender of the input report.
     */
    var sender: String? = ""

    init {
        sender = when {
            sendingOrg.isNullOrBlank() -> ""
            sendingOrgClient.isNullOrBlank() -> sendingOrg
            else -> ClientSource(sendingOrg, sendingOrgClient).name
        }
    }
}

/**
 * This class provides a detailed view for data in the `report_file` table and data from other related sources.
 * Due to the large amount of data and logic used here, instead use ReportFileHistory for lists.
 *
 * @property actionId reference to the `action` table for the action that created this file
 * @property actionName the type of action that created this report file
 * @property createdAt when the file was created
 * @property httpStatus response code for the user fetching this report file
 * @property reports other reports that are related to this report file's action log
 * @property logs container for logs generated throughout the fetching of this report file
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonPropertyOrder(
    value = [
        "id", "submissionId", "overallStatus", "timestamp", "plannedCompletionAt", "actualCompletionAt",
        "sender", "reportItemCount", "errorCount", "warningCount", "httpStatus", "destinations",
    ]
)
class DetailedSubmissionHistory(
    @JsonProperty("submissionId")
    actionId: Long,
    val actionName: TaskAction,
    @JsonProperty("timestamp")
    createdAt: OffsetDateTime,
    httpStatus: Int? = null,
    @JsonIgnore
    val reports: MutableList<DetailedReport>? = mutableListOf(),
    @JsonIgnore
    var logs: List<DetailedActionLog> = emptyList(),
) : SubmissionHistory(
    actionId,
    createdAt,
    "",
    null,
    null,
    null,
    null,
    httpStatus,
    "",
    "",
    "",
    ""
) {
    /**
     * Alias for the reportId
     * Legacy support needs this older property
     */
    val id: String?
        get() {
            return reportId
        }

    /**
     * Errors logged for this Report File.
     */
    val errors = mutableListOf<ConsolidatedActionLog>()

    /**
     * Warnings logged for this Report File.
     */
    val warnings = mutableListOf<ConsolidatedActionLog>()

    /**
     * The number of warnings.  Note this is not the number of consolidated warnings.
     */
    val warningCount = logs.count { it.type == ActionLogLevel.warning }

    /**
     * The number of errors.  Note this is not the number of consolidated errors.
     */
    val errorCount = logs.count { it.type == ActionLogLevel.error }

    /**
     * The destinations.
     */
    var destinations = mutableListOf<Destination>()

    /**
     * The list of actions that have been performed on the submitted report
     */
    @JsonIgnore
    var actionsPerformed = mutableSetOf<TaskAction>()

    /**
     * Flag to check if there's an action scheduled for a report related to this submission
     */
    @JsonIgnore
    var nextActionScheduled = false

    /**
     * The step in the delivery process for a submission
     * Supported values:
     *     VALID - successfully validated, but not sent
     *     ERROR - error on initial submission
     *     RECEIVED - passed the received step in the pipeline and awaits processing/routing
     *     NOT_DELIVERING - processed but has no intended receivers
     *     WAITING_TO_DELIVER - processed but yet to be sent to/downloaded by any receivers
     *     PARTIALLY_DELIVERED - processed, successfully sent to/downloaded by at least one receiver
     *     DELIVERED - processed, successfully sent to/downloaded by all receivers
     *
     * For now, no "send error" type of state.
     *     If a send error occurs for example,
     *     it'll just sit in the waitingToDeliver or
     *     partiallyDelivered state until someone fixes it.
     */
    enum class Status(private val printableName: String) {
        VALID("Valid"),
        ERROR("Error"),
        RECEIVED("Received"),
        NOT_DELIVERING("Not Delivering"),
        WAITING_TO_DELIVER("Waiting to Deliver"),
        PARTIALLY_DELIVERED("Partially Delivered"),
        DELIVERED("Delivered"),
        ;

        @JsonValue
        override fun toString(): String {
            return printableName
        }
    }

    /**
     * Summary of how far along the submission's process is.
     * The supported values are listed in the Status enum.
     */
    var overallStatus: Status = Status.RECEIVED

    /**
     * When this submission is expected to finish sending.
     * Mirrors the max of all the sendingAt values for this Submission's Destinations
     */
    var plannedCompletionAt: OffsetDateTime? = null

    /**
     * Marks the actual time this submission finished sending.
     * Mirrors the max createdAt of all sent and downloaded reports after it has been sent to all receivers
     */
    var actualCompletionAt: OffsetDateTime? = null

    /**
     * Number of destinations that actually had/will have data sent to.
     */
    val destinationCount: Int
        get() {
            return destinations.filter { it.itemCount != 0 }.size
        }

    init {
        reports?.forEach { report ->
            // For reports sent to a destination
            report.receivingOrg?.let {
                val filterLogs = logs.filter {
                    it.type == ActionLogLevel.filter && it.reportId == report.reportId
                }
                val filteredReportRows = filterLogs.map { it.detail.message }
                val filteredReportItems = filterLogs.map {
                    ReportStreamFilterResultForResponse(it.detail as ReportStreamFilterResult)
                }
                // If there is no transport defined, there will not be a next action so sending at
                // should be null.
                val nextActionTime = if (report.receiverHasTransport) report.nextActionAt else null
                destinations.add(
                    Destination(
                        report.receivingOrg,
                        report.receivingOrgSvc!!,
                        filteredReportRows.toMutableList(),
                        filteredReportItems.toMutableList(),
                        nextActionTime,
                        report.itemCount,
                        report.itemCountBeforeQualFilter,
                    )
                )
            }

            // For the report received from a sender
            if (report.sendingOrg != null) {
                // There can only be one!
                check(reportId == null)
                // Reports with errors do not show an ID
                reportId = if (errorCount == 0) report.reportId.toString() else null
                externalName = report.externalName
                reportItemCount = report.itemCount
                sender = ClientSource(report.sendingOrg, report.sendingOrgClient ?: "").name
                topic = report.schemaTopic
            }

            // if there is ANY action scheduled on this submission history, ensure this flag is true
            if (report.nextActionAt != null) nextActionScheduled = true
        }
        errors.addAll(consolidateLogs(ActionLogLevel.error))
        warnings.addAll(consolidateLogs(ActionLogLevel.warning))
    }

    /**
     * Consolidate the [logs] filtered by an optional [filterBy] action level, so to list similar messages once
     * with a list of items they relate to.
     * @return the consolidated list of logs
     */
    internal fun consolidateLogs(filterBy: ActionLogLevel? = null): List<ConsolidatedActionLog> {
        val consolidatedList = mutableListOf<ConsolidatedActionLog>()

        // First filter the logs and sort by the message.  This first sorting can take care of sorting old messages
        // that contain index numbers like "Report 3: ..."
        val filteredList = when (filterBy) {
            null -> logs
            else -> logs.filter { it.type == filterBy }
        }.filter { it.scope != ActionLogScope.internal }.sortedBy { it.detail.message }
        // Now order the list so that logs contain first non-item messages, then item messages, and item messages
        // are sorted by index.
        val orderedList = (
            filteredList.filter { it.scope != ActionLogScope.item }.sortedBy { it.scope } +
                filteredList.filter { it.scope == ActionLogScope.item }.sortedBy { it.index }
            ).toMutableList()
        // Now consolidate the list
        while (orderedList.isNotEmpty()) {
            // Grab the first log.
            val consolidatedLog = ConsolidatedActionLog(orderedList.first())
            orderedList.removeFirst()
            // Now find similar logs and consolidate it.  Note by using the iterator we can delete on the fly.
            with(orderedList.iterator()) {
                forEach { log ->
                    if (consolidatedLog.canBeConsolidatedWith(log)) {
                        consolidatedLog.add(log)
                        remove()
                    }
                }
            }
            consolidatedList.add(consolidatedLog)
        }

        return consolidatedList
    }

    // TODO: https://github.com/CDCgov/prime-reportstream/issues/14350
    /**
     * Enrich the submission history with various other related bits of history.
     *
     * @param descendants[] the various bits of DetailedSubmissionHistory that will be used to enrich
     */
    fun enrichWithDescendants(descendants: List<DetailedSubmissionHistory>) {
        check(descendants.distinctBy { it.actionId }.size == descendants.size)
        actionsPerformed.addAll(descendants.map { submission -> submission.actionName }.distinct())

        // Enforce an order on the enrichment:  process/translate, send, download
        if (topic?.isUniversalPipeline == true) {
            // logs and destinations are handled very differently for UP
            // both routing and translate are populated at different times,
            // so we need to do special logic to handle them
            descendants.filter { it.actionName == TaskAction.translate }.forEach { descendant ->
                enrichWithTranslateAction(descendant)
            }
            descendants.filter { it.actionName == TaskAction.route }.forEach { descendant ->
                enrichWithRouteAction(descendant)
            }
            descendants.filter { it.actionName == TaskAction.convert }.forEach { descendant ->
                enrichWithConvertAction(descendant)
            }
        } else {
            descendants.filter {
                it.actionName == TaskAction.process
            }.forEach { descendant ->
                enrichWithProcessAction(descendant)
            }
        }

        // note: we do not use any data from the batch action at this time.
        descendants.filter { it.actionName == TaskAction.send }.forEach { descendant ->
            enrichWithSendAction(descendant)
        }
        descendants.filter { it.actionName == TaskAction.download }.forEach { descendant ->
            enrichWithDownloadAction(descendant)
        }
    }

    /**
     * Enrich a parent detailed history with details from translate actions.
     * Add destinations, errors, and warnings, to the history details.
     * Note: Route/Translate is exclusive to the Universal pipeline
     * See enrichWithProcessAction for the TopicReceiver pipeline counterpart
     *
     * @param descendant translate action that will be used to enrich
     */
    private fun enrichWithTranslateAction(descendant: DetailedSubmissionHistory) {
        require(
            topic?.isUniversalPipeline == true &&
                descendant.actionName == TaskAction.translate
        ) {
            "Must be translate action. Enrichment is only available for the Universal Pipeline"
        }

        // Grab destinations from the "translate" action
        descendant.destinations.forEach { descendantDest ->
            // Check if destination has already been added
            // if it is increase item counts
            // otherwise add it to destinations
            destinations.firstOrNull {
                it.organizationId == descendantDest.organizationId && it.service == descendantDest.service
            }?.let { existingDestination ->
                existingDestination.itemCount += descendantDest.itemCount
                existingDestination.itemCountBeforeQualFilter =
                    existingDestination.itemCountBeforeQualFilter?.plus(
                        descendantDest.itemCountBeforeQualFilter ?: 0
                    ) ?: descendantDest.itemCountBeforeQualFilter
            } ?: run {
                destinations += descendantDest
            }
        }
    }

    /**
     * Enrich a parent detailed history with details from the convert action.
     * Add errors, and warnings, to the history details.
     *
     * @param descendant route action that will be used to enrich
     */
    private fun enrichWithConvertAction(descendant: DetailedSubmissionHistory) {
        require(
            topic?.isUniversalPipeline == true &&
                descendant.actionName == TaskAction.convert
        ) {
            "Must be route action. Enrichment is only available for the Universal Pipeline"
        }
        errors += descendant.errors
        warnings += descendant.warnings
    }

    /**
     * Enrich a parent detailed history with details from the route action.
     * Add destinations, errors, and warnings, to the history details.
     * Note: Route/Translate is exclusive to the Universal pipeline
     * See enrichWithProcessAction for the TopicReceiver pipeline counterpart
     *
     * @param descendant route action that will be used to enrich
     */
    private fun enrichWithRouteAction(descendant: DetailedSubmissionHistory) {
        require(
            topic?.isUniversalPipeline == true &&
                descendant.actionName == TaskAction.route
        ) {
            "Must be route action. Enrichment is only available for the Universal Pipeline"
        }
        // Grab the filter logs generated during the "route" action, as well as errors and warnings
        val filterLogs = descendant.logs.filter { log -> log.type == ActionLogLevel.filter }
        errors += descendant.errors
        warnings += descendant.warnings

        // add filter logs to its respective destination otherwise add new destination
        if (filterLogs.isNotEmpty()) {
            filterLogs.forEach { log ->
                check(log.detail is ReportStreamFilterResult) { "Filter result not of type ReportStreamFilterResult" }
                val filterResult = log.detail
                val filterReport = log.detail.message
                val receiverNameSegments = filterResult.receiverName.split(Sender.fullNameSeparator)
                val filterResultResponse = ReportStreamFilterResultForResponse(filterResult)

                destinations.firstOrNull {
                    it.organizationId == receiverNameSegments[0] && it.service == receiverNameSegments[1]
                }?.let { existingDestination ->
                    // filteredReportRows and filteredReportItems are initialized
                    // when a DetailedSubmissionHistory is created, so they shouldn't be null
                    existingDestination.filteredReportRows!!.add(filterReport)
                    existingDestination.filteredReportItems!!.add(filterResultResponse)
                    existingDestination.itemCountBeforeQualFilter = existingDestination.itemCountBeforeQualFilter?.plus(
                        filterResult.originalCount
                    ) ?: filterResult.originalCount
                } ?: run {
                    destinations.add(
                        Destination(
                            receiverNameSegments[0],
                            receiverNameSegments[1],
                            mutableListOf(filterReport),
                            mutableListOf(ReportStreamFilterResultForResponse(filterResult)),
                            null,
                            0,
                            filterResult.originalCount,
                        )
                    )
                }
            }
        }
    }

    /**
     * Enrich a parent detailed history with details from the process action.
     * Add destinations, errors, and warnings, to the history details.
     * Note: Process is exclusive to the COVID pipeline
     * See enrichWithRoutingAndTranslationActions for the Universal pipeline counterpart
     *
     * @param descendant the history used for enriching
     */
    private fun enrichWithProcessAction(descendant: DetailedSubmissionHistory) {
        require(descendant.actionName == TaskAction.process) {
            "Must be a process action"
        }
        destinations += descendant.destinations
        errors += descendant.errors
        warnings += descendant.warnings
    }

    /**
     * Enrich a parent detailed history with details from the send action.
     * Add sent report information to each destination present in the parent's historical details.
     *
     * @param descendant the history used for enriching
     */
    private fun enrichWithSendAction(descendant: DetailedSubmissionHistory) {
        require(descendant.actionName == TaskAction.send) { "Must be a send action" }
        descendant.reports?.let { it ->
            it.forEach { report ->
                destinations.find {
                    it.organizationId == report.receivingOrg && it.service == report.receivingOrgSvc
                }?.let {
                    it.sentReports.add(report)
                } ?: run {
                    if (report.receivingOrg != null && report.receivingOrgSvc != null) {
                        destinations.add(
                            Destination(
                                report.receivingOrg,
                                report.receivingOrgSvc,
                                null,
                                null,
                                null,
                                report.itemCount,
                                report.itemCountBeforeQualFilter,
                            )
                        )
                    }
                }
            }
        }
    }

    /**
     * Enrich a parent detailed history with details from the download action
     * Add download report information to each destination present in the parent's historical details.
     *
     * @param descendant the history used for enriching
     */
    private fun enrichWithDownloadAction(descendant: DetailedSubmissionHistory) {
        require(descendant.actionName == TaskAction.download) { "Must be a download action" }

        descendant.reports?.let { it ->
            it.forEach { report ->
                destinations.find {
                    it.organizationId == report.receivingOrg && it.service == report.receivingOrgSvc
                }?.let {
                    it.downloadedReports.add(report)
                } ?: run {
                    if (report.receivingOrg != null && report.receivingOrgSvc != null) {
                        val dest = Destination(
                            report.receivingOrg,
                            report.receivingOrgSvc,
                            null,
                            null,
                            null,
                            report.itemCount,
                            report.itemCountBeforeQualFilter,
                        )

                        destinations.add(dest)
                        dest.downloadedReports.add(report)
                    }
                }
            }
        }
    }

    /**
     *  Update the summary fields for this Submission report based on the destinations that
     *  will be receiving reports.
     */
    fun enrichWithSummary() {
        val realDestinations = destinations.filter { it.itemCount != 0 }

        overallStatus = calculateStatus(realDestinations)
        plannedCompletionAt = calculatePlannedCompletionAt(realDestinations)
        actualCompletionAt = calculateActualCompletionAt(realDestinations)
    }

    /**
     * Runs the calculations for the overallStatus field so that it can be done during init.
     *
     * @param realDestinations[] destinations where items have gone through and thus should be calculated
     * @return The status from the Status enum that matches the current Submission state.
     */
    private fun calculateStatus(realDestinations: List<Destination>): Status {
        if (httpStatus != HttpStatus.OK.value() && httpStatus != HttpStatus.CREATED.value()) {
            return Status.ERROR
        }

        if (destinations.size == 0) {
            /**
             * This conditional serves to differentiate where a report was submitted async and therefore hasn't
             * been processed yet vs. a report that has been processed, but did not have any eligible receivers.
             *
             * The most likely scenario for that is when the item does not pass the jurisdictional filter for any of
             * the receivers.
             */
            return if (
                (actionsPerformed.contains(TaskAction.route) || actionsPerformed.contains(TaskAction.convert)) &&
                !nextActionScheduled
            ) {
                Status.NOT_DELIVERING
            } else {
                Status.RECEIVED
            }
        } else if (realDestinations.isEmpty()) {
            return if (nextActionScheduled) {
                Status.RECEIVED
            } else {
                Status.NOT_DELIVERING
            }
        }

        var finishedDestinations = 0
        realDestinations.forEach {
            var sentItemCount = 0
            it.sentReports.forEach { sentReport ->
                sentItemCount += sentReport.itemCount
            }

            var downloadedItemCount = 0
            it.downloadedReports.forEach { downloadedReport ->
                downloadedItemCount += downloadedReport.itemCount
            }

            if (sentItemCount >= it.itemCount || downloadedItemCount >= it.itemCount) {
                finishedDestinations++
            }
        }

        // Were items delivered to destinations?
        if (finishedDestinations >= destinations.size) {
            // ALL destinations received items and are finished
            return Status.DELIVERED
        } else if (finishedDestinations >= realDestinations.size) {
            // SOME destinations received items and are finished
            return Status.PARTIALLY_DELIVERED
        }
        // Destinations have not received the items yet
        // If adding additional Status states, consider adding one to distinguish between
        // "Waiting to Deliver" vs "Delivery in Progress"
        return Status.WAITING_TO_DELIVER
    }

    /**
     * Runs the calculations for the plannedCompletionAt field so that it can be done during init.
     *
     * @param realDestinations[] destinations where items have gone through and thus should be calculated
     * @return The timestamp that equals the max of all the sendingAt values for this Submission's Destinations
     */
    private fun calculatePlannedCompletionAt(realDestinations: List<Destination>): OffsetDateTime? {
        if (overallStatus == Status.ERROR ||
            overallStatus == Status.RECEIVED ||
            overallStatus == Status.NOT_DELIVERING
        ) {
            return null
        }

        return realDestinations.maxWithOrNull(compareBy { it.sendingAt })?.sendingAt
    }

    /**
     * Runs the calculations for the actualCompletedAt field so that it can be done during init.
     *
     * @param realDestinations[] destinations where items have gone through and thus should be calculated
     * @return The timestamp that equals the max createdAt of all sent and downloaded reports
     *     after it has been sent to all receivers
     */
    private fun calculateActualCompletionAt(realDestinations: List<Destination>): OffsetDateTime? {
        if (overallStatus != Status.DELIVERED) {
            return null
        }

        val sentReports = realDestinations.filter { it.sentReports.size > 0 }
            .flatMap { it.sentReports }

        val downloadedReports = realDestinations.filter { it.downloadedReports.size > 0 }
            .flatMap { it.downloadedReports }

        return sentReports.plus(downloadedReports).maxWithOrNull(compareBy { it.createdAt })?.createdAt
    }
}

/**
 * Represents the organizations that receive submitted reports from the point of view of a Submission.
 *
 * @property organizationId identifier for the organization that owns this destination
 * @property service the service used by the organization (e.g. elr)
 * @property filteredReportRows filters that were triggered by the contents of the report
 * @property filteredReportItems more structured version of filteredReportRows
 * @property sendingAt the time that this destination is next expecting to receive a report
 * @property itemCount final number of tests available in the report received by the destination
 * @property itemCountBeforeQualFilter total number of tests that were in the submitted report before any filtering
 * @property sentReports logs of reports for this submission sent to this destination
 * @property downloadedReports logs of reports for this submission downloaded for this destination
 */
@JsonPropertyOrder(
    value = [
        "organization", "organizationId", "service", "itemCount", "itemCountBeforeQualFilter", "sendingAt"
    ]
)
@JsonIgnoreProperties(ignoreUnknown = true)
data class Destination(
    @JsonProperty("organization_id")
    val organizationId: String,
    val service: String,
    val filteredReportRows: MutableList<String>?,
    val filteredReportItems: MutableList<ReportStreamFilterResultForResponse>?,
    @JsonProperty("sending_at")
    @JsonInclude(Include.NON_NULL)
    val sendingAt: OffsetDateTime?,
    var itemCount: Int,
    @JsonProperty("itemCountBeforeQualityFiltering")
    var itemCountBeforeQualFilter: Int?,
    var sentReports: MutableList<DetailedReport> = mutableListOf(),
    var downloadedReports: MutableList<DetailedReport> = mutableListOf(),
) {
    /**
     * Finds the name for the organization based on the id provided.
     */
    val organization: String?
        get() = BaseEngine.settingsProviderSingleton.findOrganizationAndReceiver(
            "$organizationId.$service"
        )?.let { (org, _) ->
            org.description
        }
}

/**
 * Response use for the API for the filtered report items. This removes unneeded properties that exist in
 * ReportStreamFilterResult. ReportStreamFilterResult is used to serialize and deserialize to/from the database.
 *
 * @param filterResult the filter result to use
 */
data class ReportStreamFilterResultForResponse(@JsonIgnore private val filterResult: ReportStreamFilterResult) {
    /**
     * What kind of filter was triggered.
     */
    val filterType = filterResult.filterType

    /**
     * What was the actual filter triggered.
     */
    val filterName = filterResult.filterName

    /**
     * The name of the test that was filtered out.
     */
    val filteredTrackingElement = filterResult.filteredTrackingElement

    /**
     * What specifically in the filter was triggered.
     */
    val filterArgs = filterResult.filterArgs

    /**
     * Readable version of the various other properties this object has.
     */
    val message = filterResult.message
}