package fhirengine.azure

import assertk.assertThat
import assertk.assertions.containsOnly
import assertk.assertions.each
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import assertk.assertions.isNotEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.matchesPredicate
import gov.cdc.prime.router.ActionLogger
import gov.cdc.prime.router.ClientSource
import gov.cdc.prime.router.CustomerStatus
import gov.cdc.prime.router.DeepOrganization
import gov.cdc.prime.router.FileSettings
import gov.cdc.prime.router.Metadata
import gov.cdc.prime.router.Options
import gov.cdc.prime.router.Organization
import gov.cdc.prime.router.Receiver
import gov.cdc.prime.router.Report
import gov.cdc.prime.router.SettingsProvider
import gov.cdc.prime.router.Topic
import gov.cdc.prime.router.azure.ActionHistory
import gov.cdc.prime.router.azure.BlobAccess
import gov.cdc.prime.router.azure.DatabaseAccess
import gov.cdc.prime.router.azure.Event
import gov.cdc.prime.router.azure.ProcessEvent
import gov.cdc.prime.router.azure.QueueAccess
import gov.cdc.prime.router.azure.WorkflowEngine
import gov.cdc.prime.router.azure.db.Tables.ACTION_LOG
import gov.cdc.prime.router.azure.db.enums.ActionLogType
import gov.cdc.prime.router.azure.db.enums.TaskAction
import gov.cdc.prime.router.azure.db.tables.ActionLog
import gov.cdc.prime.router.azure.db.tables.Task
import gov.cdc.prime.router.azure.db.tables.pojos.Action
import gov.cdc.prime.router.azure.db.tables.pojos.ReportFile
import gov.cdc.prime.router.azure.db.tables.pojos.ReportLineage
import gov.cdc.prime.router.cli.tests.CompareData
import gov.cdc.prime.router.common.TestcontainersUtils
import gov.cdc.prime.router.db.ReportStreamTestDatabaseContainer
import gov.cdc.prime.router.db.ReportStreamTestDatabaseSetupExtension
import gov.cdc.prime.router.fhirengine.azure.FHIRFunctions
import gov.cdc.prime.router.fhirengine.engine.FHIRConverter
import gov.cdc.prime.router.fhirengine.engine.FHIRRouter
import gov.cdc.prime.router.fhirengine.engine.FHIRTranslator
import gov.cdc.prime.router.fhirengine.engine.QueueMessage
import gov.cdc.prime.router.fhirengine.engine.elrRoutingQueueName
import gov.cdc.prime.router.fhirengine.engine.elrTranslationQueueName
import gov.cdc.prime.router.history.DetailedActionLog
import gov.cdc.prime.router.history.db.ReportGraph
import gov.cdc.prime.router.metadata.LookupTable
import gov.cdc.prime.router.report.ReportService
import gov.cdc.prime.router.unittest.UnitTestUtils
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.spyk
import io.mockk.unmockkAll
import io.mockk.verify
import org.jooq.impl.DSL
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.io.File
import java.nio.charset.Charset
import java.time.OffsetDateTime
import java.util.UUID

private const val MULTIPLE_TARGETS_FHIR_PATH = "src/test/resources/fhirengine/engine/valid_data_multiple_targets.fhir"

private const val VALID_FHIR_PATH = "src/test/resources/fhirengine/engine/valid_data.fhir"

@Suppress("ktlint:standard:max-line-length")
private const val fhirRecord =
    """{"resourceType":"Bundle","id":"1667861767830636000.7db38d22-b713-49fc-abfa-2edba9c12347","meta":{"lastUpdated":"2022-11-07T22:56:07.832+00:00"},"identifier":{"value":"1234d1d1-95fe-462c-8ac6-46728dba581c"},"type":"message","timestamp":"2021-08-03T13:15:11.015+00:00","entry":[{"fullUrl":"Observation/d683b42a-bf50-45e8-9fce-6c0531994f09","resource":{"resourceType":"Observation","id":"d683b42a-bf50-45e8-9fce-6c0531994f09","status":"final","code":{"coding":[{"system":"http://loinc.org","code":"80382-5"}],"text":"Flu A"},"subject":{"reference":"Patient/9473889b-b2b9-45ac-a8d8-191f27132912"},"performer":[{"reference":"Organization/1a0139b9-fc23-450b-9b6c-cd081e5cea9d"}],"valueCodeableConcept":{"coding":[{"system":"http://snomed.info/sct","code":"260373001","display":"Detected"}]},"interpretation":[{"coding":[{"system":"http://terminology.hl7.org/CodeSystem/v2-0078","code":"A","display":"Abnormal"}]}],"method":{"extension":[{"url":"https://reportstream.cdc.gov/fhir/StructureDefinition/testkit-name-id","valueCoding":{"code":"BD Veritor System for Rapid Detection of SARS-CoV-2 & Flu A+B_Becton, Dickinson and Company (BD)"}},{"url":"https://reportstream.cdc.gov/fhir/StructureDefinition/equipment-uid","valueCoding":{"code":"BD Veritor System for Rapid Detection of SARS-CoV-2 & Flu A+B_Becton, Dickinson and Company (BD)"}}],"coding":[{"display":"BD Veritor System for Rapid Detection of SARS-CoV-2 & Flu A+B*"}]},"specimen":{"reference":"Specimen/52a582e4-d389-42d0-b738-bee51cf5244d"},"device":{"reference":"Device/78dc4d98-2958-43a3-a445-76ceef8c0698"}}}]}"""

@Suppress("ktlint:standard:max-line-length")
private const val codelessFhirRecord =
    """{"resourceType":"Bundle","id":"1667861767830636000.7db38d22-b713-49fc-abfa-2edba9c12347","meta":{"lastUpdated":"2022-11-07T22:56:07.832+00:00"},"identifier":{"value":"1234d1d1-95fe-462c-8ac6-46728dba581c"},"type":"message","timestamp":"2021-08-03T13:15:11.015+00:00","entry":[{"fullUrl":"Observation/d683b42a-bf50-45e8-9fce-6c0531994f09","resource":{"resourceType":"Observation","id":"d683b42a-bf50-45e8-9fce-6c0531994f09","status":"final","code":{"coding":[],"text":"Flu A"},"subject":{"reference":"Patient/9473889b-b2b9-45ac-a8d8-191f27132912"},"performer":[{"reference":"Organization/1a0139b9-fc23-450b-9b6c-cd081e5cea9d"}],"valueCodeableConcept":{"coding":[]},"interpretation":[{"coding":[{"system":"http://terminology.hl7.org/CodeSystem/v2-0078","code":"A","display":"Abnormal"}]}],"method":{"extension":[{"url":"https://reportstream.cdc.gov/fhir/StructureDefinition/testkit-name-id","valueCoding":{"code":"BD Veritor System for Rapid Detection of SARS-CoV-2 & Flu A+B_Becton, Dickinson and Company (BD)"}},{"url":"https://reportstream.cdc.gov/fhir/StructureDefinition/equipment-uid","valueCoding":{"code":"BD Veritor System for Rapid Detection of SARS-CoV-2 & Flu A+B_Becton, Dickinson and Company (BD)"}}],"coding":[{"display":"BD Veritor System for Rapid Detection of SARS-CoV-2 & Flu A+B*"}]},"specimen":{"reference":"Specimen/52a582e4-d389-42d0-b738-bee51cf5244d"},"device":{"reference":"Device/78dc4d98-2958-43a3-a445-76ceef8c0698"}}}]}"""

@Suppress("ktlint:standard:max-line-length")
private const val bulkFhirRecord =
    """{"resourceType":"Bundle","id":"1667861767830636000.7db38d22-b713-49fc-abfa-2edba9c12347","meta":{"lastUpdated":"2022-11-07T22:56:07.832+00:00"},"identifier":{"value":"1234d1d1-95fe-462c-8ac6-46728dba581c"},"type":"message","timestamp":"2021-08-03T13:15:11.015+00:00","entry":[{"fullUrl":"Observation/d683b42a-bf50-45e8-9fce-6c0531994f09","resource":{"resourceType":"Observation","id":"d683b42a-bf50-45e8-9fce-6c0531994f09","status":"final","code":{"coding":[{"system":"http://loinc.org","code":"80382-5"}],"text":"Flu A"},"subject":{"reference":"Patient/9473889b-b2b9-45ac-a8d8-191f27132912"},"performer":[{"reference":"Organization/1a0139b9-fc23-450b-9b6c-cd081e5cea9d"}],"valueCodeableConcept":{"coding":[{"system":"http://snomed.info/sct","code":"260373001","display":"Detected"}]},"interpretation":[{"coding":[{"system":"http://terminology.hl7.org/CodeSystem/v2-0078","code":"A","display":"Abnormal"}]}],"method":{"extension":[{"url":"https://reportstream.cdc.gov/fhir/StructureDefinition/testkit-name-id","valueCoding":{"code":"BD Veritor System for Rapid Detection of SARS-CoV-2 & Flu A+B_Becton, Dickinson and Company (BD)"}},{"url":"https://reportstream.cdc.gov/fhir/StructureDefinition/equipment-uid","valueCoding":{"code":"BD Veritor System for Rapid Detection of SARS-CoV-2 & Flu A+B_Becton, Dickinson and Company (BD)"}}],"coding":[{"display":"BD Veritor System for Rapid Detection of SARS-CoV-2 & Flu A+B*"}]},"specimen":{"reference":"Specimen/52a582e4-d389-42d0-b738-bee51cf5244d"},"device":{"reference":"Device/78dc4d98-2958-43a3-a445-76ceef8c0698"}}}]}
    {"resourceType":"Bundle","id":"1667861767830636000.7db38d22-b713-49fc-abfa-2edba9c09876","meta":{"lastUpdated":"2022-11-07T22:56:07.832+00:00"},"identifier":{"value":"1234d1d1-95fe-462c-8ac6-46728dbau8cd"},"type":"message","timestamp":"2021-08-03T13:15:11.015+00:00","entry":[{"fullUrl":"Observation/d683b42a-bf50-45e8-9fce-6c0531994f09","resource":{"resourceType":"Observation","id":"d683b42a-bf50-45e8-9fce-6c0531994f09","status":"final","code":{"coding":[{"system":"http://loinc.org","code":"80382-5"}],"text":"Flu A"},"subject":{"reference":"Patient/9473889b-b2b9-45ac-a8d8-191f27132912"},"performer":[{"reference":"Organization/1a0139b9-fc23-450b-9b6c-cd081e5cea9d"}],"valueCodeableConcept":{"coding":[{"system":"http://snomed.info/sct","code":"260373001","display":"Detected"}]},"interpretation":[{"coding":[{"system":"http://terminology.hl7.org/CodeSystem/v2-0078","code":"A","display":"Abnormal"}]}],"method":{"extension":[{"url":"https://reportstream.cdc.gov/fhir/StructureDefinition/testkit-name-id","valueCoding":{"code":"BD Veritor System for Rapid Detection of SARS-CoV-2 & Flu A+B_Becton, Dickinson and Company (BD)"}},{"url":"https://reportstream.cdc.gov/fhir/StructureDefinition/equipment-uid","valueCoding":{"code":"BD Veritor System for Rapid Detection of SARS-CoV-2 & Flu A+B_Becton, Dickinson and Company (BD)"}}],"coding":[{"display":"BD Veritor System for Rapid Detection of SARS-CoV-2 & Flu A+B*"}]},"specimen":{"reference":"Specimen/52a582e4-d389-42d0-b738-bee51cf5244d"},"device":{"reference":"Device/78dc4d98-2958-43a3-a445-76ceef8c0698"}}}]}
    {}
    {"resourceType":"Bund}"""

@Suppress("ktlint:standard:max-line-length")
private const val validFHIRRecord1 =
    """{"resourceType":"Bundle","id":"1667861767830636000.7db38d22-b713-49fc-abfa-2edba9c12347","meta":{"lastUpdated":"2022-11-07T22:56:07.832+00:00"},"identifier":{"value":"1234d1d1-95fe-462c-8ac6-46728dba581c"},"type":"message","timestamp":"2021-08-03T13:15:11.015+00:00","entry":[{"fullUrl":"Observation/d683b42a-bf50-45e8-9fce-6c0531994f09","resource":{"resourceType":"Observation","id":"d683b42a-bf50-45e8-9fce-6c0531994f09","status":"final","code":{"coding":[{"system":"http://loinc.org","code":"80382-5"}],"text":"Flu A"},"subject":{"reference":"Patient/9473889b-b2b9-45ac-a8d8-191f27132912"},"performer":[{"reference":"Organization/1a0139b9-fc23-450b-9b6c-cd081e5cea9d"}],"valueCodeableConcept":{"coding":[{"system":"http://snomed.info/sct","code":"260373001","display":"Detected"}]},"interpretation":[{"coding":[{"system":"http://terminology.hl7.org/CodeSystem/v2-0078","code":"A","display":"Abnormal"}]}],"method":{"extension":[{"url":"https://reportstream.cdc.gov/fhir/StructureDefinition/testkit-name-id","valueCoding":{"code":"BD Veritor System for Rapid Detection of SARS-CoV-2 & Flu A+B_Becton, Dickinson and Company (BD)"}},{"url":"https://reportstream.cdc.gov/fhir/StructureDefinition/equipment-uid","valueCoding":{"code":"BD Veritor System for Rapid Detection of SARS-CoV-2 & Flu A+B_Becton, Dickinson and Company (BD)"}}],"coding":[{"display":"BD Veritor System for Rapid Detection of SARS-CoV-2 & Flu A+B*"}]},"specimen":{"reference":"Specimen/52a582e4-d389-42d0-b738-bee51cf5244d"},"device":{"reference":"Device/78dc4d98-2958-43a3-a445-76ceef8c0698"}}}]}"""

@Suppress("ktlint:standard:max-line-length")
private const val validFHIRRecord2 =
    """{"resourceType":"Bundle","id":"1667861767830636000.7db38d22-b713-49fc-abfa-2edba9c09876","meta":{"lastUpdated":"2022-11-07T22:56:07.832+00:00"},"identifier":{"value":"1234d1d1-95fe-462c-8ac6-46728dbau8cd"},"type":"message","timestamp":"2021-08-03T13:15:11.015+00:00","entry":[{"fullUrl":"Observation/d683b42a-bf50-45e8-9fce-6c0531994f09","resource":{"resourceType":"Observation","id":"d683b42a-bf50-45e8-9fce-6c0531994f09","status":"final","code":{"coding":[{"system":"http://loinc.org","code":"80382-5"}],"text":"Flu A"},"subject":{"reference":"Patient/9473889b-b2b9-45ac-a8d8-191f27132912"},"performer":[{"reference":"Organization/1a0139b9-fc23-450b-9b6c-cd081e5cea9d"}],"valueCodeableConcept":{"coding":[{"system":"http://snomed.info/sct","code":"260373001","display":"Detected"}]},"interpretation":[{"coding":[{"system":"http://terminology.hl7.org/CodeSystem/v2-0078","code":"A","display":"Abnormal"}]}],"method":{"extension":[{"url":"https://reportstream.cdc.gov/fhir/StructureDefinition/testkit-name-id","valueCoding":{"code":"BD Veritor System for Rapid Detection of SARS-CoV-2 & Flu A+B_Becton, Dickinson and Company (BD)"}},{"url":"https://reportstream.cdc.gov/fhir/StructureDefinition/equipment-uid","valueCoding":{"code":"BD Veritor System for Rapid Detection of SARS-CoV-2 & Flu A+B_Becton, Dickinson and Company (BD)"}}],"coding":[{"display":"BD Veritor System for Rapid Detection of SARS-CoV-2 & Flu A+B*"}]},"specimen":{"reference":"Specimen/52a582e4-d389-42d0-b738-bee51cf5244d"},"device":{"reference":"Device/78dc4d98-2958-43a3-a445-76ceef8c0698"}}}]}"""

private const val invalidEmptyFHIRRecord = "{}"

private const val invalidMalformedFHIRRecord = """{"resourceType":"Bund}"""

@Suppress("ktlint:standard:max-line-length")
private const val cleanHL7Record =
    """MSH|^~\&|CDC PRIME - Atlanta, Georgia (Dekalb)^2.16.840.1.114222.4.1.237821^ISO|Avante at Ormond Beach^10D0876999^CLIA|PRIME_DOH|Prime ReportStream|20210210170737||ORU^R01^ORU_R01|371784|P|2.5.1|||NE|NE|USA||||PHLabReportNoAck^ELR_Receiver^2.16.840.1.113883.9.99^ISO
SFT|Centers for Disease Control and Prevention|0.1-SNAPSHOT|PRIME ReportStream|0.1-SNAPSHOT||20210210
PID|1||2a14112c-ece1-4f82-915c-7b3a8d152eda^^^Avante at Ormond Beach^PI||Buckridge^Kareem^Millie^^^^L||19580810|F||2106-3^White^HL70005^^^^2.5.1|688 Leighann Inlet^^South Rodneychester^TX^67071^^^^48077||7275555555:1:^PRN^^roscoe.wilkinson@email.com^1^211^2240784|||||||||U^Unknown^HL70189||||||||N
ORC|RE|73a6e9bd-aaec-418e-813a-0ad33366ca85|73a6e9bd-aaec-418e-813a-0ad33366ca85|||||||||1629082607^Eddin^Husam^^^^^^CMS&2.16.840.1.113883.3.249&ISO^^^^NPI||^WPN^^^1^386^6825220|20210209||||||Avante at Ormond Beach|170 North King Road^^Ormond Beach^FL^32174^^^^12127|^WPN^^jbrush@avantecenters.com^1^407^7397506|^^^^32174
OBR|1|73a6e9bd-aaec-418e-813a-0ad33366ca85|0cba76f5-35e0-4a28-803a-2f31308aae9b|94558-4^SARS-CoV-2 (COVID-19) Ag [Presence] in Respiratory specimen by Rapid immunoassay^LN|||202102090000-0600|202102090000-0600||||||||1629082607^Eddin^Husam^^^^^^CMS&2.16.840.1.113883.3.249&ISO^^^^NPI|^WPN^^^1^386^6825220|||||202102090000-0600|||F
OBX|1|CWE|94558-4^SARS-CoV-2 (COVID-19) Ag [Presence] in Respiratory specimen by Rapid immunoassay^LN||260415000^Not detected^SCT|||N^Normal (applies to non-numeric results)^HL70078|||F|||202102090000-0600|||CareStart COVID-19 Antigen test_Access Bio, Inc._EUA^^99ELR||202102090000-0600||||Avante at Ormond Beach^^^^^CLIA&2.16.840.1.113883.4.7&ISO^^^^10D0876999^CLIA|170 North King Road^^Ormond Beach^FL^32174^^^^12127
OBX|2|CWE|95418-0^Whether patient is employed in a healthcare setting^LN||Y^Yes^HL70136||||||F|||202102090000-0600|||||||||||||||QST
OBX|3|CWE|95417-2^First test for condition of interest^LN||Y^Yes^HL70136||||||F|||202102090000-0600|||||||||||||||QST
OBX|4|CWE|95421-4^Resides in a congregate care setting^LN||N^No^HL70136||||||F|||202102090000-0600|||||||||||||||QST
OBX|5|CWE|95419-8^Has symptoms related to condition of interest^LN||N^No^HL70136||||||F|||202102090000-0600|||||||||||||||QST
SPM|1|0cba76f5-35e0-4a28-803a-2f31308aae9b||258500001^Nasopharyngeal swab^SCT||||71836000^Nasopharyngeal structure (body structure)^SCT^^^^2020-09-01|||||||||202102090000-0600|202102090000-0600"""

@Suppress("ktlint:standard:max-line-length")
private const val cleanHL7RecordConverted =
    """{"resourceType" : "Bundle","id" : "1713967725033496000.ddda2a58-5e5f-4aba-8df5-75a653ba2acf","meta" : {  "lastUpdated" : "2024-04-24T10:08:45.039-04:00"},"identifier" : {  "system" : "https://reportstream.cdc.gov/prime-router",  "value" : "371784"},"type" : "message","timestamp" : "2021-02-10T17:07:37.000-05:00","entry" : [ {  "fullUrl" : "MessageHeader/4aeed951-99a9-3152-8885-6b0acc6dd35e",  "resource" : {"resourceType" : "MessageHeader","id" : "4aeed951-99a9-3152-8885-6b0acc6dd35e","meta" : {  "tag" : [ {"system" : "http://terminology.hl7.org/CodeSystem/v2-0103","code" : "P"  } ]},"extension" : [ {  "url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/msh-message-header",  "extension" : [ {"url" : "MSH.7","valueString" : "20210210170737"  }, {"url" : "MSH.15","valueString" : "NE"  }, {"url" : "MSH.16","valueString" : "NE"  }, {"url" : "MSH.21","valueIdentifier" : {  "extension" : [ {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/assigning-authority","extension" : [ {  "url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/namespace-id",  "valueString" : "ELR_Receiver"}, {  "url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/universal-id",  "valueString" : "2.16.840.1.113883.9.99"}, {  "url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/universal-id-type",  "valueCode" : "ISO"} ]  } ],  "value" : "PHLabReportNoAck"}  } ]} ],"eventCoding" : {  "system" : "http://terminology.hl7.org/CodeSystem/v2-0003",  "code" : "R01",  "display" : "ORU^R01^ORU_R01"},"destination" : [ {  "extension" : [ {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/hl7v2Field","valueString" : "MSH.5"  } ],  "name" : "PRIME_DOH",  "_endpoint" : {"extension" : [ {  "url" : "http://hl7.org/fhir/StructureDefinition/data-absent-reason",  "valueCode" : "unknown"} ]  },  "receiver" : {"reference" : "Organization/1713967725107573000.28b04a7c-ada7-441f-9d42-3e9e25719713"  }} ],"sender" : {  "reference" : "Organization/1713967725083787000.c3a617fb-0b19-49f0-ba16-bfdaca09a2d8"},"source" : {  "extension" : [ {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/namespace-id","valueString" : "CDC PRIME - Atlanta, Georgia (Dekalb)"  }, {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/universal-id","valueString" : "2.16.840.1.114222.4.1.237821"  }, {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/universal-id-type","valueString" : "ISO"  }, {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/hl7v2Field","valueString" : "MSH.3"  } ],  "software" : "PRIME ReportStream",  "version" : "0.1-SNAPSHOT",  "endpoint" : "urn:oid:2.16.840.1.114222.4.1.237821"}  }}, {  "fullUrl" : "Organization/1713967725083787000.c3a617fb-0b19-49f0-ba16-bfdaca09a2d8",  "resource" : {"resourceType" : "Organization","id" : "1713967725083787000.c3a617fb-0b19-49f0-ba16-bfdaca09a2d8","identifier" : [ {  "extension" : [ {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/hl7v2Field","valueString" : "HD.1"  } ],  "value" : "Avante at Ormond Beach"}, {  "extension" : [ {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/hl7v2Field","valueString" : "HD.2,HD.3"  } ],  "type" : {"coding" : [ {  "system" : "http://terminology.hl7.org/CodeSystem/v2-0301",  "code" : "CLIA"} ]  },  "value" : "10D0876999"} ],"address" : [ {  "country" : "USA"} ]  }}, {  "fullUrl" : "Organization/1713967725107573000.28b04a7c-ada7-441f-9d42-3e9e25719713",  "resource" : {"resourceType" : "Organization","id" : "1713967725107573000.28b04a7c-ada7-441f-9d42-3e9e25719713","extension" : [ {  "url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/hl7v2Field",  "valueString" : "MSH.6"} ],"identifier" : [ {  "extension" : [ {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/hl7v2Field","valueString" : "HD.1"  } ],  "value" : "Prime ReportStream"} ]  }}, {  "fullUrl" : "Provenance/1713967725404044000.8270c3a7-0ff7-4a13-a09d-850a750a9087",  "resource" : {"resourceType" : "Provenance","id" : "1713967725404044000.8270c3a7-0ff7-4a13-a09d-850a750a9087","target" : [ {  "reference" : "MessageHeader/4aeed951-99a9-3152-8885-6b0acc6dd35e"}, {  "reference" : "DiagnosticReport/1713967725618013000.7fdfae74-683d-4bd5-a0f9-a829ad205f64"} ],"recorded" : "2021-02-10T17:07:37Z","activity" : {  "coding" : [ {"display" : "ORU^R01^ORU_R01"  } ]},"agent" : [ {  "type" : {"coding" : [ {  "system" : "http://terminology.hl7.org/CodeSystem/provenance-participant-type",  "code" : "author"} ]  },  "who" : {"reference" : "Organization/1713967725403503000.d479c1ee-7d2c-4dc1-b0e9-5c1cc2edfd70"  }} ],"entity" : [ {  "role" : "source",  "what" : {"reference" : "Device/1713967725406901000.c04f5dd3-9f14-41ef-8bc5-4640f89b4941"  }} ]  }}, {  "fullUrl" : "Organization/1713967725403503000.d479c1ee-7d2c-4dc1-b0e9-5c1cc2edfd70",  "resource" : {"resourceType" : "Organization","id" : "1713967725403503000.d479c1ee-7d2c-4dc1-b0e9-5c1cc2edfd70","identifier" : [ {  "extension" : [ {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/hl7v2Field","valueString" : "HD.1"  } ],  "value" : "Avante at Ormond Beach"}, {  "extension" : [ {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/hl7v2Field","valueString" : "HD.2,HD.3"  } ],  "type" : {"coding" : [ {  "system" : "http://terminology.hl7.org/CodeSystem/v2-0301",  "code" : "CLIA"} ]  },  "value" : "10D0876999"} ]  }}, {  "fullUrl" : "Organization/1713967725406689000.fa6e0f06-6803-4594-b262-5476514d1818",  "resource" : {"resourceType" : "Organization","id" : "1713967725406689000.fa6e0f06-6803-4594-b262-5476514d1818","name" : "Centers for Disease Control and Prevention"  }}, {  "fullUrl" : "Device/1713967725406901000.c04f5dd3-9f14-41ef-8bc5-4640f89b4941",  "resource" : {"resourceType" : "Device","id" : "1713967725406901000.c04f5dd3-9f14-41ef-8bc5-4640f89b4941","extension" : [ {  "url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/software-vendor-org",  "valueReference" : {"reference" : "Organization/1713967725406689000.fa6e0f06-6803-4594-b262-5476514d1818"  }} ],"manufacturer" : "Centers for Disease Control and Prevention","deviceName" : [ {  "name" : "PRIME ReportStream",  "type" : "manufacturer-name"} ],"modelNumber" : "0.1-SNAPSHOT","version" : [ {  "extension" : [ {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/software-install-date","valueDateTime" : "2021-02-10","_valueDateTime" : {  "extension" : [ {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/hl7v2-date-time","valueString" : "20210210"  } ]}  } ],  "value" : "0.1-SNAPSHOT"} ]  }}, {  "fullUrl" : "Provenance/1713967725413638000.77085fd5-1aec-444d-925a-5cf9bc5eadc0",  "resource" : {"resourceType" : "Provenance","id" : "1713967725413638000.77085fd5-1aec-444d-925a-5cf9bc5eadc0","recorded" : "2024-04-24T10:08:45Z","policy" : [ "http://hl7.org/fhir/uv/v2mappings/message-oru-r01-to-bundle" ],"activity" : {  "coding" : [ {"code" : "v2-FHIR transformation"  } ]},"agent" : [ {  "type" : {"coding" : [ {  "system" : "http://terminology.hl7.org/CodeSystem/provenance-participant-type",  "code" : "assembler"} ]  },  "who" : {"reference" : "Organization/1713967725413287000.d54ffd58-97bf-4976-8e97-aaa9e01546a1"  }} ]  }}, {  "fullUrl" : "Organization/1713967725413287000.d54ffd58-97bf-4976-8e97-aaa9e01546a1",  "resource" : {"resourceType" : "Organization","id" : "1713967725413287000.d54ffd58-97bf-4976-8e97-aaa9e01546a1","identifier" : [ {  "value" : "CDC PRIME - Atlanta"}, {  "type" : {"coding" : [ {  "system" : "http://terminology.hl7.org/CodeSystem/v2-0301"} ]  },  "system" : "urn:ietf:rfc:3986",  "value" : "2.16.840.1.114222.4.1.237821"} ]  }}, {  "fullUrl" : "Patient/1713967725440022000.0871b09b-abf8-410b-afb1-a43db09ee0d5",  "resource" : {"resourceType" : "Patient","id" : "1713967725440022000.0871b09b-abf8-410b-afb1-a43db09ee0d5","extension" : [ {  "url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/pid-patient",  "extension" : [ {"url" : "PID.8","valueCodeableConcept" : {  "coding" : [ {"extension" : [ {  "url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/cwe-coding",  "valueString" : "coding"} ],"code" : "F"  } ]}  }, {"url" : "PID.30","valueString" : "N"  } ]}, {  "url" : "http://ibm.com/fhir/cdm/StructureDefinition/local-race-cd",  "valueCodeableConcept" : {"coding" : [ {  "extension" : [ {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/cwe-coding","valueString" : "coding"  }, {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/cwe-coding-system","valueString" : "HL70005"  } ],  "system" : "http://terminology.hl7.org/CodeSystem/v3-Race",  "version" : "2.5.1",  "code" : "2106-3",  "display" : "White"} ]  }}, {  "url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/ethnic-group",  "valueCodeableConcept" : {"coding" : [ {  "extension" : [ {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/cwe-coding","valueString" : "coding"  }, {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/cwe-coding-system","valueString" : "HL70189"  } ],  "system" : "http://terminology.hl7.org/CodeSystem/v2-0189",  "code" : "U",  "display" : "Unknown"} ]  }} ],"identifier" : [ {  "extension" : [ {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/cx-identifier","extension" : [ {  "url" : "CX.5",  "valueString" : "PI"} ]  }, {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/hl7v2Field","valueString" : "PID.3"  } ],  "type" : {"coding" : [ {  "code" : "PI"} ]  },  "value" : "2a14112c-ece1-4f82-915c-7b3a8d152eda",  "assigner" : {"reference" : "Organization/1713967725419956000.ea2ccde2-b762-479d-9059-8c942661b295"  }} ],"name" : [ {  "extension" : [ {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/xpn-human-name","extension" : [ {  "url" : "XPN.2",  "valueString" : "Kareem"}, {  "url" : "XPN.3",  "valueString" : "Millie"}, {  "url" : "XPN.7",  "valueString" : "L"} ]  } ],  "use" : "official",  "family" : "Buckridge",  "given" : [ "Kareem", "Millie" ]} ],"telecom" : [ {  "extension" : [ {"url" : "http://hl7.org/fhir/StructureDefinition/contactpoint-country","valueString" : "1"  }, {"url" : "http://hl7.org/fhir/StructureDefinition/contactpoint-area","valueString" : "211"  }, {"url" : "http://hl7.org/fhir/StructureDefinition/contactpoint-local","valueString" : "2240784"  }, {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/xtn-contact-point","extension" : [ {  "url" : "XTN.1",  "valueString" : "7275555555:1:"}, {  "url" : "XTN.2",  "valueString" : "PRN"}, {  "url" : "XTN.4",  "valueString" : "roscoe.wilkinson@email.com"}, {  "url" : "XTN.7",  "valueString" : "2240784"} ]  } ],  "system" : "email",  "use" : "home"} ],"gender" : "female","birthDate" : "1958-08-10","_birthDate" : {  "extension" : [ {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/hl7v2-date-time","valueString" : "19580810"  } ]},"deceasedBoolean" : false,"address" : [ {  "extension" : [ {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/xad-address","extension" : [ {  "url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/sad-address-line",  "extension" : [ {"url" : "SAD.1","valueString" : "688 Leighann Inlet"  } ]} ]  } ],  "line" : [ "688 Leighann Inlet" ],  "city" : "South Rodneychester",  "district" : "48077",  "state" : "TX",  "postalCode" : "67071"} ]  }}, {  "fullUrl" : "Organization/1713967725419956000.ea2ccde2-b762-479d-9059-8c942661b295",  "resource" : {"resourceType" : "Organization","id" : "1713967725419956000.ea2ccde2-b762-479d-9059-8c942661b295","identifier" : [ {  "extension" : [ {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/hl7v2Field","valueString" : "HD.1"  } ],  "value" : "Avante at Ormond Beach"} ]  }}, {  "fullUrl" : "Provenance/1713967725443912000.440242ec-d3f6-4283-8996-5cc08ffc0a82",  "resource" : {"resourceType" : "Provenance","id" : "1713967725443912000.440242ec-d3f6-4283-8996-5cc08ffc0a82","target" : [ {  "reference" : "Patient/1713967725440022000.0871b09b-abf8-410b-afb1-a43db09ee0d5"} ],"recorded" : "2024-04-24T10:08:45Z","activity" : {  "coding" : [ {"system" : "https://terminology.hl7.org/CodeSystem/v3-DataOperation","code" : "UPDATE"  } ]}  }}, {  "fullUrl" : "Observation/1713967725447581000.2a7c6684-489e-4a99-89f2-404e1902306a",  "resource" : {"resourceType" : "Observation","id" : "1713967725447581000.2a7c6684-489e-4a99-89f2-404e1902306a","extension" : [ {  "url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/analysis-date-time",  "valueDateTime" : "2021-02-09T00:00:00-06:00",  "_valueDateTime" : {"extension" : [ {  "url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/hl7v2-date-time",  "valueString" : "202102090000-0600"} ]  }}, {  "url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/obx-observation",  "extension" : [ {"url" : "OBX.2","valueId" : "CWE"  }, {"url" : "OBX.11","valueString" : "F"  }, {"url" : "OBX.17","valueCodeableConcept" : {  "coding" : [ {"extension" : [ {  "url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/cwe-coding",  "valueString" : "coding"}, {  "url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/cwe-coding-system",  "valueString" : "99ELR"} ],"code" : "CareStart COVID-19 Antigen test_Access Bio, Inc._EUA"  } ]}  } ]} ],"status" : "final","code" : {  "coding" : [ {"extension" : [ {  "url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/cwe-coding",  "valueString" : "coding"}, {  "url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/cwe-coding-system",  "valueString" : "LN"} ],"system" : "http://loinc.org","code" : "94558-4","display" : "SARS-CoV-2 (COVID-19) Ag [Presence] in Respiratory specimen by Rapid immunoassay"  } ]},"subject" : {  "reference" : "Patient/1713967725440022000.0871b09b-abf8-410b-afb1-a43db09ee0d5"},"effectiveDateTime" : "2021-02-09T00:00:00-06:00","_effectiveDateTime" : {  "extension" : [ {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/hl7v2-date-time","valueString" : "202102090000-0600"  } ]},"performer" : [ {  "reference" : "Organization/1713967725448670000.5835a6a0-9e5d-4878-a2cc-6044228d3cdd"} ],"valueCodeableConcept" : {  "coding" : [ {"extension" : [ {  "url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/cwe-coding",  "valueString" : "coding"}, {  "url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/cwe-coding-system",  "valueString" : "SCT"} ],"system" : "http://snomed.info/sct","code" : "260415000","display" : "Not detected"  } ]},"interpretation" : [ {  "coding" : [ {"extension" : [ {  "url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/cwe-coding",  "valueString" : "coding"}, {  "url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/cwe-coding-system",  "valueString" : "HL70078"} ],"code" : "N","display" : "Normal (applies to non-numeric results)"  } ]} ],"method" : {  "coding" : [ {"extension" : [ {  "url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/cwe-coding",  "valueString" : "coding"}, {  "url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/cwe-coding-system",  "valueString" : "99ELR"} ],"code" : "CareStart COVID-19 Antigen test_Access Bio, Inc._EUA"  } ]}  }}, {  "fullUrl" : "Organization/1713967725448670000.5835a6a0-9e5d-4878-a2cc-6044228d3cdd",  "resource" : {"resourceType" : "Organization","id" : "1713967725448670000.5835a6a0-9e5d-4878-a2cc-6044228d3cdd","extension" : [ {  "url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/xon-organization",  "extension" : [ {"url" : "XON.10","valueString" : "10D0876999"  } ]}, {  "url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/hl7v2Field",  "valueString" : "OBX.25"} ],"identifier" : [ {  "extension" : [ {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/assigning-authority","extension" : [ {  "url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/namespace-id",  "valueString" : "CLIA"}, {  "url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/universal-id",  "valueString" : "2.16.840.1.113883.4.7"}, {  "url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/universal-id-type",  "valueCode" : "ISO"} ]  } ],  "value" : "10D0876999"} ],"name" : "Avante at Ormond Beach","address" : [ {  "extension" : [ {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/xad-address","extension" : [ {  "url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/sad-address-line",  "extension" : [ {"url" : "SAD.1","valueString" : "170 North King Road"  } ]} ]  } ],  "line" : [ "170 North King Road" ],  "city" : "Ormond Beach",  "district" : "12127",  "state" : "FL",  "postalCode" : "32174"} ]  }}, {  "fullUrl" : "Observation/1713967725451712000.3025c3ff-a099-4bff-a233-dfb1b8f5726f",  "resource" : {"resourceType" : "Observation","id" : "1713967725451712000.3025c3ff-a099-4bff-a233-dfb1b8f5726f","extension" : [ {  "url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/obx-observation",  "extension" : [ {"url" : "OBX.2","valueId" : "CWE"  }, {"url" : "OBX.11","valueString" : "F"  } ]} ],"status" : "final","code" : {  "coding" : [ {"extension" : [ {  "url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/cwe-coding",  "valueString" : "coding"}, {  "url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/cwe-coding-system",  "valueString" : "LN"} ],"system" : "http://loinc.org","code" : "95418-0","display" : "Whether patient is employed in a healthcare setting"  } ]},"subject" : {  "reference" : "Patient/1713967725440022000.0871b09b-abf8-410b-afb1-a43db09ee0d5"},"effectiveDateTime" : "2021-02-09T00:00:00-06:00","_effectiveDateTime" : {  "extension" : [ {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/hl7v2-date-time","valueString" : "202102090000-0600"  } ]},"valueCodeableConcept" : {  "coding" : [ {"extension" : [ {  "url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/cwe-coding",  "valueString" : "coding"}, {  "url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/cwe-coding-system",  "valueString" : "HL70136"} ],"code" : "Y","display" : "Yes"  } ]}  }}, {  "fullUrl" : "Observation/1713967725453742000.97376236-a905-45d3-bc27-e4650947fc98",  "resource" : {"resourceType" : "Observation","id" : "1713967725453742000.97376236-a905-45d3-bc27-e4650947fc98","extension" : [ {  "url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/obx-observation",  "extension" : [ {"url" : "OBX.2","valueId" : "CWE"  }, {"url" : "OBX.11","valueString" : "F"  } ]} ],"status" : "final","code" : {  "coding" : [ {"extension" : [ {  "url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/cwe-coding",  "valueString" : "coding"}, {  "url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/cwe-coding-system",  "valueString" : "LN"} ],"system" : "http://loinc.org","code" : "95417-2","display" : "First test for condition of interest"  } ]},"subject" : {  "reference" : "Patient/1713967725440022000.0871b09b-abf8-410b-afb1-a43db09ee0d5"},"effectiveDateTime" : "2021-02-09T00:00:00-06:00","_effectiveDateTime" : {  "extension" : [ {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/hl7v2-date-time","valueString" : "202102090000-0600"  } ]},"valueCodeableConcept" : {  "coding" : [ {"extension" : [ {  "url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/cwe-coding",  "valueString" : "coding"}, {  "url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/cwe-coding-system",  "valueString" : "HL70136"} ],"code" : "Y","display" : "Yes"  } ]}  }}, {  "fullUrl" : "Observation/1713967725455766000.9ed3578f-8979-430d-ad47-688fd93bd06f",  "resource" : {"resourceType" : "Observation","id" : "1713967725455766000.9ed3578f-8979-430d-ad47-688fd93bd06f","extension" : [ {  "url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/obx-observation",  "extension" : [ {"url" : "OBX.2","valueId" : "CWE"  }, {"url" : "OBX.11","valueString" : "F"  } ]} ],"status" : "final","code" : {  "coding" : [ {"extension" : [ {  "url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/cwe-coding",  "valueString" : "coding"}, {  "url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/cwe-coding-system",  "valueString" : "LN"} ],"system" : "http://loinc.org","code" : "95421-4","display" : "Resides in a congregate care setting"  } ]},"subject" : {  "reference" : "Patient/1713967725440022000.0871b09b-abf8-410b-afb1-a43db09ee0d5"},"effectiveDateTime" : "2021-02-09T00:00:00-06:00","_effectiveDateTime" : {  "extension" : [ {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/hl7v2-date-time","valueString" : "202102090000-0600"  } ]},"valueCodeableConcept" : {  "coding" : [ {"extension" : [ {  "url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/cwe-coding",  "valueString" : "coding"}, {  "url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/cwe-coding-system",  "valueString" : "HL70136"} ],"code" : "N","display" : "No"  } ]}  }}, {  "fullUrl" : "Observation/1713967725458002000.3181208f-314a-4b19-abe2-29acaabc5558",  "resource" : {"resourceType" : "Observation","id" : "1713967725458002000.3181208f-314a-4b19-abe2-29acaabc5558","extension" : [ {  "url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/obx-observation",  "extension" : [ {"url" : "OBX.2","valueId" : "CWE"  }, {"url" : "OBX.11","valueString" : "F"  } ]} ],"status" : "final","code" : {  "coding" : [ {"extension" : [ {  "url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/cwe-coding",  "valueString" : "coding"}, {  "url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/cwe-coding-system",  "valueString" : "LN"} ],"system" : "http://loinc.org","code" : "95419-8","display" : "Has symptoms related to condition of interest"  } ]},"subject" : {  "reference" : "Patient/1713967725440022000.0871b09b-abf8-410b-afb1-a43db09ee0d5"},"effectiveDateTime" : "2021-02-09T00:00:00-06:00","_effectiveDateTime" : {  "extension" : [ {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/hl7v2-date-time","valueString" : "202102090000-0600"  } ]},"valueCodeableConcept" : {  "coding" : [ {"extension" : [ {  "url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/cwe-coding",  "valueString" : "coding"}, {  "url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/cwe-coding-system",  "valueString" : "HL70136"} ],"code" : "N","display" : "No"  } ]}  }}, {  "fullUrl" : "Specimen/1713967725603004000.b722aed5-2c6a-43f9-b1d4-9da08821415e",  "resource" : {"resourceType" : "Specimen","id" : "1713967725603004000.b722aed5-2c6a-43f9-b1d4-9da08821415e","extension" : [ {  "url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/hl7v2Segment",  "valueString" : "OBR"} ]  }}, {  "fullUrl" : "Specimen/1713967725605160000.82d836ec-27a6-4725-9e50-291e18a9c10d",  "resource" : {"resourceType" : "Specimen","id" : "1713967725605160000.82d836ec-27a6-4725-9e50-291e18a9c10d","extension" : [ {  "url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/hl7v2Segment",  "valueString" : "SPM"} ],"identifier" : [ {  "extension" : [ {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/hl7v2Component","valueString" : "SPM.2.1"  } ],  "type" : {"coding" : [ {  "system" : "http://terminology.hl7.org/CodeSystem/v2-0203",  "code" : "PGN"} ]  },  "value" : "0cba76f5-35e0-4a28-803a-2f31308aae9b"} ],"type" : {  "coding" : [ {"extension" : [ {  "url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/cwe-coding",  "valueString" : "coding"}, {  "url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/cwe-coding-system",  "valueString" : "SCT"} ],"system" : "http://snomed.info/sct","code" : "258500001","display" : "Nasopharyngeal swab"  } ]},"receivedTime" : "2021-02-09T00:00:00-06:00","_receivedTime" : {  "extension" : [ {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/hl7v2-date-time","valueString" : "202102090000-0600"  } ]},"collection" : {  "collectedDateTime" : "2021-02-09T00:00:00-06:00",  "_collectedDateTime" : {"extension" : [ {  "url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/hl7v2-date-time",  "valueString" : "202102090000-0600"} ]  },  "bodySite" : {"coding" : [ {  "extension" : [ {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/cwe-coding","valueString" : "coding"  }, {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/cwe-coding-system","valueString" : "SCT"  } ],  "system" : "http://snomed.info/sct",  "version" : "2020-09-01",  "code" : "71836000",  "display" : "Nasopharyngeal structure (body structure)"} ]  }}  }}, {  "fullUrl" : "ServiceRequest/1713967725613527000.38e79167-d2bb-48ac-8af3-37132009751a",  "resource" : {"resourceType" : "ServiceRequest","id" : "1713967725613527000.38e79167-d2bb-48ac-8af3-37132009751a","extension" : [ {  "url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/business-event",  "valueCode" : "RE"}, {  "url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/orc-common-order",  "extension" : [ {"url" : "orc-21-ordering-facility-name","valueReference" : {  "reference" : "Organization/1713967725610264000.9536ff14-baf8-46ba-a590-ed763cf6e2d5"}  }, {"url" : "orc-22-ordering-facility-address","valueAddress" : {  "extension" : [ {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/xad-address","extension" : [ {  "url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/sad-address-line",  "extension" : [ {"url" : "SAD.1","valueString" : "170 North King Road"  } ]} ]  } ],  "line" : [ "170 North King Road" ],  "city" : "Ormond Beach",  "district" : "12127",  "state" : "FL",  "postalCode" : "32174"}  }, {"url" : "orc-24-ordering-provider-address","valueAddress" : {  "postalCode" : "32174"}  }, {"url" : "orc-12-ordering-provider","valueReference" : {  "reference" : "Practitioner/1713967725611549000.aab029ea-3516-42c1-8373-0cd467e647aa"}  }, {"url" : "ORC.15","valueString" : "20210209"  } ]}, {  "url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/obr-observation-request",  "extension" : [ {"url" : "OBR.2","valueIdentifier" : {  "value" : "73a6e9bd-aaec-418e-813a-0ad33366ca85"}  }, {"url" : "OBR.3","valueIdentifier" : {  "value" : "0cba76f5-35e0-4a28-803a-2f31308aae9b"}  }, {"url" : "OBR.22","valueString" : "202102090000-0600"  }, {"url" : "OBR.16","valueReference" : {  "reference" : "Practitioner/1713967725612494000.5a114a3c-a0b4-422b-aa82-d4962c0968ee"}  }, {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/callback-number","valueContactPoint" : {  "extension" : [ {"url" : "http://hl7.org/fhir/StructureDefinition/contactpoint-country","valueString" : "1"  }, {"url" : "http://hl7.org/fhir/StructureDefinition/contactpoint-area","valueString" : "386"  }, {"url" : "http://hl7.org/fhir/StructureDefinition/contactpoint-local","valueString" : "6825220"  }, {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/xtn-contact-point","extension" : [ {  "url" : "XTN.2",  "valueString" : "WPN"}, {  "url" : "XTN.7",  "valueString" : "6825220"} ]  }, {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/hl7v2Field","valueString" : "OBR.17"  } ],  "_system" : {"extension" : [ {  "url" : "http://hl7.org/fhir/StructureDefinition/data-absent-reason",  "valueCode" : "unknown"} ]  },  "use" : "work"}  } ]} ],"identifier" : [ {  "extension" : [ {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/hl7v2Field","valueString" : "ORC.2"  } ],  "type" : {"coding" : [ {  "system" : "http://terminology.hl7.org/CodeSystem/v2-0203",  "code" : "PLAC"} ]  },  "value" : "73a6e9bd-aaec-418e-813a-0ad33366ca85"}, {  "extension" : [ {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/hl7v2Field","valueString" : "ORC.3"  } ],  "type" : {"coding" : [ {  "system" : "http://terminology.hl7.org/CodeSystem/v2-0203",  "code" : "FILL"} ]  },  "value" : "73a6e9bd-aaec-418e-813a-0ad33366ca85"} ],"status" : "unknown","code" : {  "coding" : [ {"extension" : [ {  "url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/cwe-coding",  "valueString" : "coding"}, {  "url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/cwe-coding-system",  "valueString" : "LN"} ],"system" : "http://loinc.org","code" : "94558-4","display" : "SARS-CoV-2 (COVID-19) Ag [Presence] in Respiratory specimen by Rapid immunoassay"  } ]},"subject" : {  "reference" : "Patient/1713967725440022000.0871b09b-abf8-410b-afb1-a43db09ee0d5"},"requester" : {  "extension" : [ {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/callback-number","valueContactPoint" : {  "extension" : [ {"url" : "http://hl7.org/fhir/StructureDefinition/contactpoint-country","valueString" : "1"  }, {"url" : "http://hl7.org/fhir/StructureDefinition/contactpoint-area","valueString" : "386"  }, {"url" : "http://hl7.org/fhir/StructureDefinition/contactpoint-local","valueString" : "6825220"  }, {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/xtn-contact-point","extension" : [ {  "url" : "XTN.2",  "valueString" : "WPN"}, {  "url" : "XTN.7",  "valueString" : "6825220"} ]  }, {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/hl7v2Field","valueString" : "ORC.14"  } ],  "_system" : {"extension" : [ {  "url" : "http://hl7.org/fhir/StructureDefinition/data-absent-reason",  "valueCode" : "unknown"} ]  },  "use" : "work"}  } ],  "reference" : "PractitionerRole/1713967725606323000.2e00cfe9-c772-4aba-aa4c-3202e5a65262"}  }}, {  "fullUrl" : "Practitioner/1713967725607580000.71b2e562-cd10-4af0-be62-ba4ac14e8bc8",  "resource" : {"resourceType" : "Practitioner","id" : "1713967725607580000.71b2e562-cd10-4af0-be62-ba4ac14e8bc8","extension" : [ {  "url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/assigning-authority",  "extension" : [ {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/namespace-id","valueString" : "CMS"  }, {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/universal-id","valueString" : "2.16.840.1.113883.3.249"  }, {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/universal-id-type","valueCode" : "ISO"  } ]}, {  "url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/xcn-practitioner",  "extension" : [ {"url" : "XCN.3","valueString" : "Husam"  } ]}, {  "url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/hl7v2Field",  "valueString" : "ORC.12"} ],"identifier" : [ {  "type" : {"coding" : [ {  "extension" : [ {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/codeable-concept-id","valueBoolean" : true  } ],  "code" : "NPI"} ]  },  "system" : "CMS",  "value" : "1629082607"} ],"name" : [ {  "family" : "Eddin",  "given" : [ "Husam" ]} ],"address" : [ {  "postalCode" : "32174"} ]  }}, {  "fullUrl" : "Organization/1713967725608639000.45e7b3e4-ffa4-4c39-926b-fe50a6cca085",  "resource" : {"resourceType" : "Organization","id" : "1713967725608639000.45e7b3e4-ffa4-4c39-926b-fe50a6cca085","name" : "Avante at Ormond Beach","telecom" : [ {  "extension" : [ {"url" : "http://hl7.org/fhir/StructureDefinition/contactpoint-country","valueString" : "1"  }, {"url" : "http://hl7.org/fhir/StructureDefinition/contactpoint-area","valueString" : "407"  }, {"url" : "http://hl7.org/fhir/StructureDefinition/contactpoint-local","valueString" : "7397506"  }, {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/xtn-contact-point","extension" : [ {  "url" : "XTN.2",  "valueString" : "WPN"}, {  "url" : "XTN.4",  "valueString" : "jbrush@avantecenters.com"}, {  "url" : "XTN.7",  "valueString" : "7397506"} ]  } ],  "system" : "email",  "use" : "work"} ],"address" : [ {  "extension" : [ {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/xad-address","extension" : [ {  "url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/sad-address-line",  "extension" : [ {"url" : "SAD.1","valueString" : "170 North King Road"  } ]} ]  } ],  "line" : [ "170 North King Road" ],  "city" : "Ormond Beach",  "district" : "12127",  "state" : "FL",  "postalCode" : "32174"} ]  }}, {  "fullUrl" : "PractitionerRole/1713967725606323000.2e00cfe9-c772-4aba-aa4c-3202e5a65262",  "resource" : {"resourceType" : "PractitionerRole","id" : "1713967725606323000.2e00cfe9-c772-4aba-aa4c-3202e5a65262","practitioner" : {  "reference" : "Practitioner/1713967725607580000.71b2e562-cd10-4af0-be62-ba4ac14e8bc8"},"organization" : {  "reference" : "Organization/1713967725608639000.45e7b3e4-ffa4-4c39-926b-fe50a6cca085"}  }}, {  "fullUrl" : "Organization/1713967725610264000.9536ff14-baf8-46ba-a590-ed763cf6e2d5",  "resource" : {"resourceType" : "Organization","id" : "1713967725610264000.9536ff14-baf8-46ba-a590-ed763cf6e2d5","name" : "Avante at Ormond Beach"  }}, {  "fullUrl" : "Practitioner/1713967725611549000.aab029ea-3516-42c1-8373-0cd467e647aa",  "resource" : {"resourceType" : "Practitioner","id" : "1713967725611549000.aab029ea-3516-42c1-8373-0cd467e647aa","extension" : [ {  "url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/assigning-authority",  "extension" : [ {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/namespace-id","valueString" : "CMS"  }, {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/universal-id","valueString" : "2.16.840.1.113883.3.249"  }, {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/universal-id-type","valueCode" : "ISO"  } ]}, {  "url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/xcn-practitioner",  "extension" : [ {"url" : "XCN.3","valueString" : "Husam"  } ]} ],"identifier" : [ {  "type" : {"coding" : [ {  "extension" : [ {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/codeable-concept-id","valueBoolean" : true  } ],  "code" : "NPI"} ]  },  "system" : "CMS",  "value" : "1629082607"} ],"name" : [ {  "family" : "Eddin",  "given" : [ "Husam" ]} ]  }}, {  "fullUrl" : "Practitioner/1713967725612494000.5a114a3c-a0b4-422b-aa82-d4962c0968ee",  "resource" : {"resourceType" : "Practitioner","id" : "1713967725612494000.5a114a3c-a0b4-422b-aa82-d4962c0968ee","extension" : [ {  "url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/assigning-authority",  "extension" : [ {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/namespace-id","valueString" : "CMS"  }, {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/universal-id","valueString" : "2.16.840.1.113883.3.249"  }, {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/universal-id-type","valueCode" : "ISO"  } ]}, {  "url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/xcn-practitioner",  "extension" : [ {"url" : "XCN.3","valueString" : "Husam"  } ]} ],"identifier" : [ {  "type" : {"coding" : [ {  "extension" : [ {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/codeable-concept-id","valueBoolean" : true  } ],  "code" : "NPI"} ]  },  "system" : "CMS",  "value" : "1629082607"} ],"name" : [ {  "family" : "Eddin",  "given" : [ "Husam" ]} ]  }}, {  "fullUrl" : "DiagnosticReport/1713967725618013000.7fdfae74-683d-4bd5-a0f9-a829ad205f64",  "resource" : {"resourceType" : "DiagnosticReport","id" : "1713967725618013000.7fdfae74-683d-4bd5-a0f9-a829ad205f64","identifier" : [ {  "extension" : [ {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/hl7v2Field","valueString" : "ORC.2"  } ],  "type" : {"coding" : [ {  "system" : "http://terminology.hl7.org/CodeSystem/v2-0203",  "code" : "PLAC"} ]  },  "value" : "73a6e9bd-aaec-418e-813a-0ad33366ca85"}, {  "type" : {"coding" : [ {  "system" : "http://terminology.hl7.org/CodeSystem/v2-0203",  "code" : "FILL"} ]  },  "value" : "73a6e9bd-aaec-418e-813a-0ad33366ca85"} ],"basedOn" : [ {  "reference" : "ServiceRequest/1713967725613527000.38e79167-d2bb-48ac-8af3-37132009751a"} ],"status" : "final","code" : {  "coding" : [ {"extension" : [ {  "url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/cwe-coding",  "valueString" : "coding"}, {  "url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/cwe-coding-system",  "valueString" : "LN"} ],"system" : "http://loinc.org","code" : "94558-4","display" : "SARS-CoV-2 (COVID-19) Ag [Presence] in Respiratory specimen by Rapid immunoassay"  } ]},"subject" : {  "reference" : "Patient/1713967725440022000.0871b09b-abf8-410b-afb1-a43db09ee0d5"},"effectivePeriod" : {  "start" : "2021-02-09T00:00:00-06:00",  "_start" : {"extension" : [ {  "url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/hl7v2-date-time",  "valueString" : "202102090000-0600"} ]  },  "end" : "2021-02-09T00:00:00-06:00",  "_end" : {"extension" : [ {  "url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/hl7v2-date-time",  "valueString" : "202102090000-0600"} ]  }},"issued" : "2021-02-09T00:00:00-06:00","_issued" : {  "extension" : [ {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/hl7v2-date-time","valueString" : "202102090000-0600"  } ]},"specimen" : [ {  "reference" : "Specimen/1713967725605160000.82d836ec-27a6-4725-9e50-291e18a9c10d"}, {  "reference" : "Specimen/1713967725603004000.b722aed5-2c6a-43f9-b1d4-9da08821415e"} ],"result" : [ {  "reference" : "Observation/1713967725447581000.2a7c6684-489e-4a99-89f2-404e1902306a"}, {  "reference" : "Observation/1713967725451712000.3025c3ff-a099-4bff-a233-dfb1b8f5726f"}, {  "reference" : "Observation/1713967725453742000.97376236-a905-45d3-bc27-e4650947fc98"}, {  "reference" : "Observation/1713967725455766000.9ed3578f-8979-430d-ad47-688fd93bd06f"}, {  "reference" : "Observation/1713967725458002000.3181208f-314a-4b19-abe2-29acaabc5558"} ]  }} ]  }"""

// This message will be parsed and successfully passed through the convert step
// despite having a nonexistent NNN segement and an SFT.2 that is not an ST
@Suppress("ktlint:standard:max-line-length")
private const val invalidHL7Record =
    """MSH|^~\&|CDC PRIME - Atlanta, Georgia (Dekalb)^2.16.840.1.114222.4.1.237821^ISO|Avante at Ormond Beach^10D0876999^CLIA|PRIME_DOH|Prime ReportStream|20210210170737||ORU^R01^ORU_R01|371784|P|2.5.1|||NE|NE|USA||||PHLabReportNoAck^ELR_Receiver^2.16.840.1.113883.9.99^ISO
SFT|Centers for Disease Control and Prevention|0.1-SNAPSHOT^4^NH|PRIME ReportStream|0.1-SNAPSHOT||20210210
PID|1||2a14112c-ece1-4f82-915c-7b3a8d152eda^^^Avante at Ormond Beach^PI||Buckridge^Kareem^Millie^^^^L||19580810|F||2106-3^White^HL70005^^^^2.5.1|688 Leighann Inlet^^South Rodneychester^TX^67071^^^^48077||7275555555:1:^PRN^^roscoe.wilkinson@email.com^1^211^2240784|||||||||U^Unknown^HL70189||||||||N
ORC|RE|73a6e9bd-aaec-418e-813a-0ad33366ca85|73a6e9bd-aaec-418e-813a-0ad33366ca85|||||||||1629082607^Eddin^Husam^^^^^^CMS&2.16.840.1.113883.3.249&ISO^^^^NPI||^WPN^^^1^386^6825220|20210209||||||Avante at Ormond Beach|170 North King Road^^Ormond Beach^FL^32174^^^^12127|^WPN^^jbrush@avantecenters.com^1^407^7397506|^^^^32174
OBR|1|73a6e9bd-aaec-418e-813a-0ad33366ca85|0cba76f5-35e0-4a28-803a-2f31308aae9b|94558-4^SARS-CoV-2 (COVID-19) Ag [Presence] in Respiratory specimen by Rapid immunoassay^LN|||202102090000-0600|202102090000-0600||||||||1629082607^Eddin^Husam^^^^^^CMS&2.16.840.1.113883.3.249&ISO^^^^NPI|^WPN^^^1^386^6825220|||||202102090000-0600|||F
OBX|1|CWE|94558-4^SARS-CoV-2 (COVID-19) Ag [Presence] in Respiratory specimen by Rapid immunoassay^LN||260415000^Not detected^SCT|||N^Normal (applies to non-numeric results)^HL70078|||F|||202102090000-0600|||CareStart COVID-19 Antigen test_Access Bio, Inc._EUA^^99ELR||202102090000-0600||||Avante at Ormond Beach^^^^^CLIA&2.16.840.1.113883.4.7&ISO^^^^10D0876999^CLIA|170 North King Road^^Ormond Beach^FL^32174^^^^12127
OBX|2|CWE|95418-0^Whether patient is employed in a healthcare setting^LN||Y^Yes^HL70136||||||F|||202102090000-0600|||||||||||||||QST
OBX|3|CWE|95417-2^First test for condition of interest^LN||Y^Yes^HL70136||||||F|||202102090000-0600|||||||||||||||QST
OBX|4|CWE|95421-4^Resides in a congregate care setting^LN||N^No^HL70136||||||F|||202102090000-0600|||||||||||||||QST
NNN|5|CWE|95419-8^Has symptoms related to condition of interest^LN||N^No^HL70136||||||F|||202102090000-0600|||||||||||||||QST
SPM|1|0cba76f5-35e0-4a28-803a-2f31308aae9b||258500001^Nasopharyngeal swab^SCT||||71836000^Nasopharyngeal structure (body structure)^SCT^^^^2020-09-01|||||||||202102090000-0600|202102090000-0600"""

@Suppress("ktlint:standard:max-line-length")
private const val invalidHL7RecordConverted =
    """{"resourceType" : "Bundle","id" : "1713967817335173000.9f2076bb-261c-4c5b-b0ba-14480e4c310a","meta" : {"lastUpdated" : "2024-04-24T10:10:17.343-04:00"},"identifier" : {"system" : "https://reportstream.cdc.gov/prime-router","value" : "371784"},"type" : "message","timestamp" : "2021-02-10T17:07:37.000-05:00","entry" : [ {"fullUrl" : "MessageHeader/4aeed951-99a9-3152-8885-6b0acc6dd35e","resource" : {"resourceType" : "MessageHeader","id" : "4aeed951-99a9-3152-8885-6b0acc6dd35e","meta" : {"tag" : [ {"system" : "http://terminology.hl7.org/CodeSystem/v2-0103","code" : "P"} ]},"extension" : [ {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/msh-message-header","extension" : [ {"url" : "MSH.7","valueString" : "20210210170737"}, {"url" : "MSH.15","valueString" : "NE"}, {"url" : "MSH.16","valueString" : "NE"}, {"url" : "MSH.21","valueIdentifier" : {"extension" : [ {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/assigning-authority","extension" : [ {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/namespace-id","valueString" : "ELR_Receiver"}, {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/universal-id","valueString" : "2.16.840.1.113883.9.99"}, {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/universal-id-type","valueCode" : "ISO"} ]} ],"value" : "PHLabReportNoAck"}} ]} ],"eventCoding" : {"system" : "http://terminology.hl7.org/CodeSystem/v2-0003","code" : "R01","display" : "ORU^R01^ORU_R01"},"destination" : [ {"extension" : [ {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/hl7v2Field","valueString" : "MSH.5"} ],"name" : "PRIME_DOH","_endpoint" : {"extension" : [ {"url" : "http://hl7.org/fhir/StructureDefinition/data-absent-reason","valueCode" : "unknown"} ]},"receiver" : {"reference" : "Organization/1713967817405476000.4d15eb9e-d02c-45ba-9db1-40f70396839c"}} ],"sender" : {"reference" : "Organization/1713967817381358000.d7feeb9c-18b0-4170-aae7-cd81b2765888"},"source" : {"extension" : [ {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/namespace-id","valueString" : "CDC PRIME - Atlanta, Georgia (Dekalb)"}, {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/universal-id","valueString" : "2.16.840.1.114222.4.1.237821"}, {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/universal-id-type","valueString" : "ISO"}, {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/hl7v2Field","valueString" : "MSH.3"} ],"software" : "PRIME ReportStream","version" : "0.1-SNAPSHOT","endpoint" : "urn:oid:2.16.840.1.114222.4.1.237821"}}}, {"fullUrl" : "Organization/1713967817381358000.d7feeb9c-18b0-4170-aae7-cd81b2765888","resource" : {"resourceType" : "Organization","id" : "1713967817381358000.d7feeb9c-18b0-4170-aae7-cd81b2765888","identifier" : [ {"extension" : [ {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/hl7v2Field","valueString" : "HD.1"} ],"value" : "Avante at Ormond Beach"}, {"extension" : [ {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/hl7v2Field","valueString" : "HD.2,HD.3"} ],"type" : {"coding" : [ {"system" : "http://terminology.hl7.org/CodeSystem/v2-0301","code" : "CLIA"} ]},"value" : "10D0876999"} ],"address" : [ {"country" : "USA"} ]}}, {"fullUrl" : "Organization/1713967817405476000.4d15eb9e-d02c-45ba-9db1-40f70396839c","resource" : {"resourceType" : "Organization","id" : "1713967817405476000.4d15eb9e-d02c-45ba-9db1-40f70396839c","extension" : [ {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/hl7v2Field","valueString" : "MSH.6"} ],"identifier" : [ {"extension" : [ {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/hl7v2Field","valueString" : "HD.1"} ],"value" : "Prime ReportStream"} ]}}, {"fullUrl" : "Provenance/1713967817704638000.3ad99366-822b-403f-bcc4-9ac271ba1ef7","resource" : {"resourceType" : "Provenance","id" : "1713967817704638000.3ad99366-822b-403f-bcc4-9ac271ba1ef7","target" : [ {"reference" : "MessageHeader/4aeed951-99a9-3152-8885-6b0acc6dd35e"}, {"reference" : "DiagnosticReport/1713967817947485000.8a170b9c-97f8-49c8-83d0-79bd665b5deb"} ],"recorded" : "2021-02-10T17:07:37Z","activity" : {"coding" : [ {"display" : "ORU^R01^ORU_R01"} ]},"agent" : [ {"type" : {"coding" : [ {"system" : "http://terminology.hl7.org/CodeSystem/provenance-participant-type","code" : "author"} ]},"who" : {"reference" : "Organization/1713967817703975000.5cb873d1-15b8-448b-be15-29e61eb80be4"}} ],"entity" : [ {"role" : "source","what" : {"reference" : "Device/1713967817708034000.3dc48195-b1ed-4ce3-9c5c-e63091dd8a4a"}} ]}}, {"fullUrl" : "Organization/1713967817703975000.5cb873d1-15b8-448b-be15-29e61eb80be4","resource" : {"resourceType" : "Organization","id" : "1713967817703975000.5cb873d1-15b8-448b-be15-29e61eb80be4","identifier" : [ {"extension" : [ {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/hl7v2Field","valueString" : "HD.1"} ],"value" : "Avante at Ormond Beach"}, {"extension" : [ {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/hl7v2Field","valueString" : "HD.2,HD.3"} ],"type" : {"coding" : [ {"system" : "http://terminology.hl7.org/CodeSystem/v2-0301","code" : "CLIA"} ]},"value" : "10D0876999"} ]}}, {"fullUrl" : "Organization/1713967817707822000.3765cc03-990f-4123-894d-3335911dbacb","resource" : {"resourceType" : "Organization","id" : "1713967817707822000.3765cc03-990f-4123-894d-3335911dbacb","name" : "Centers for Disease Control and Prevention"}}, {"fullUrl" : "Device/1713967817708034000.3dc48195-b1ed-4ce3-9c5c-e63091dd8a4a","resource" : {"resourceType" : "Device","id" : "1713967817708034000.3dc48195-b1ed-4ce3-9c5c-e63091dd8a4a","extension" : [ {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/software-vendor-org","valueReference" : {"reference" : "Organization/1713967817707822000.3765cc03-990f-4123-894d-3335911dbacb"}} ],"manufacturer" : "Centers for Disease Control and Prevention","deviceName" : [ {"name" : "PRIME ReportStream","type" : "manufacturer-name"} ],"modelNumber" : "0.1-SNAPSHOT","version" : [ {"extension" : [ {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/software-install-date","valueDateTime" : "2021-02-10","_valueDateTime" : {"extension" : [ {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/hl7v2-date-time","valueString" : "20210210"} ]}} ],"value" : "0.1-SNAPSHOT"} ]}}, {"fullUrl" : "Provenance/1713967817714485000.5b9015d6-7c12-4289-a2b7-55918c4a2389","resource" : {"resourceType" : "Provenance","id" : "1713967817714485000.5b9015d6-7c12-4289-a2b7-55918c4a2389","recorded" : "2024-04-24T10:10:17Z","policy" : [ "http://hl7.org/fhir/uv/v2mappings/message-oru-r01-to-bundle" ],"activity" : {"coding" : [ {"code" : "v2-FHIR transformation"} ]},"agent" : [ {"type" : {"coding" : [ {"system" : "http://terminology.hl7.org/CodeSystem/provenance-participant-type","code" : "assembler"} ]},"who" : {"reference" : "Organization/1713967817714143000.1385c0a3-dcd9-4bf3-bbcf-60cd866b9497"}} ]}}, {"fullUrl" : "Organization/1713967817714143000.1385c0a3-dcd9-4bf3-bbcf-60cd866b9497","resource" : {"resourceType" : "Organization","id" : "1713967817714143000.1385c0a3-dcd9-4bf3-bbcf-60cd866b9497","identifier" : [ {"value" : "CDC PRIME - Atlanta"}, {"type" : {"coding" : [ {"system" : "http://terminology.hl7.org/CodeSystem/v2-0301"} ]},"system" : "urn:ietf:rfc:3986","value" : "2.16.840.1.114222.4.1.237821"} ]}}, {"fullUrl" : "Patient/1713967817739028000.61ba3462-9f36-472e-908b-5edd1c2593a2","resource" : {"resourceType" : "Patient","id" : "1713967817739028000.61ba3462-9f36-472e-908b-5edd1c2593a2","extension" : [ {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/pid-patient","extension" : [ {"url" : "PID.8","valueCodeableConcept" : {"coding" : [ {"extension" : [ {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/cwe-coding","valueString" : "coding"} ],"code" : "F"} ]}}, {"url" : "PID.30","valueString" : "N"} ]}, {"url" : "http://ibm.com/fhir/cdm/StructureDefinition/local-race-cd","valueCodeableConcept" : {"coding" : [ {"extension" : [ {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/cwe-coding","valueString" : "coding"}, {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/cwe-coding-system","valueString" : "HL70005"} ],"system" : "http://terminology.hl7.org/CodeSystem/v3-Race","version" : "2.5.1","code" : "2106-3","display" : "White"} ]}}, {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/ethnic-group","valueCodeableConcept" : {"coding" : [ {"extension" : [ {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/cwe-coding","valueString" : "coding"}, {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/cwe-coding-system","valueString" : "HL70189"} ],"system" : "http://terminology.hl7.org/CodeSystem/v2-0189","code" : "U","display" : "Unknown"} ]}} ],"identifier" : [ {"extension" : [ {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/cx-identifier","extension" : [ {"url" : "CX.5","valueString" : "PI"} ]}, {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/hl7v2Field","valueString" : "PID.3"} ],"type" : {"coding" : [ {"code" : "PI"} ]},"value" : "2a14112c-ece1-4f82-915c-7b3a8d152eda","assigner" : {"reference" : "Organization/1713967817723088000.7fd2280b-91a4-4b35-9d89-a65506f8c290"}} ],"name" : [ {"extension" : [ {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/xpn-human-name","extension" : [ {"url" : "XPN.2","valueString" : "Kareem"}, {"url" : "XPN.3","valueString" : "Millie"}, {"url" : "XPN.7","valueString" : "L"} ]} ],"use" : "official","family" : "Buckridge","given" : [ "Kareem", "Millie" ]} ],"telecom" : [ {"extension" : [ {"url" : "http://hl7.org/fhir/StructureDefinition/contactpoint-country","valueString" : "1"}, {"url" : "http://hl7.org/fhir/StructureDefinition/contactpoint-area","valueString" : "211"}, {"url" : "http://hl7.org/fhir/StructureDefinition/contactpoint-local","valueString" : "2240784"}, {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/xtn-contact-point","extension" : [ {"url" : "XTN.1","valueString" : "7275555555:1:"}, {"url" : "XTN.2","valueString" : "PRN"}, {"url" : "XTN.4","valueString" : "roscoe.wilkinson@email.com"}, {"url" : "XTN.7","valueString" : "2240784"} ]} ],"system" : "email","use" : "home"} ],"gender" : "female","birthDate" : "1958-08-10","_birthDate" : {"extension" : [ {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/hl7v2-date-time","valueString" : "19580810"} ]},"deceasedBoolean" : false,"address" : [ {"extension" : [ {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/xad-address","extension" : [ {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/sad-address-line","extension" : [ {"url" : "SAD.1","valueString" : "688 Leighann Inlet"} ]} ]} ],"line" : [ "688 Leighann Inlet" ],"city" : "South Rodneychester","district" : "48077","state" : "TX","postalCode" : "67071"} ]}}, {"fullUrl" : "Organization/1713967817723088000.7fd2280b-91a4-4b35-9d89-a65506f8c290","resource" : {"resourceType" : "Organization","id" : "1713967817723088000.7fd2280b-91a4-4b35-9d89-a65506f8c290","identifier" : [ {"extension" : [ {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/hl7v2Field","valueString" : "HD.1"} ],"value" : "Avante at Ormond Beach"} ]}}, {"fullUrl" : "Provenance/1713967817741500000.9589e75f-f6c9-42dc-a1b9-bfe30743833f","resource" : {"resourceType" : "Provenance","id" : "1713967817741500000.9589e75f-f6c9-42dc-a1b9-bfe30743833f","target" : [ {"reference" : "Patient/1713967817739028000.61ba3462-9f36-472e-908b-5edd1c2593a2"} ],"recorded" : "2024-04-24T10:10:17Z","activity" : {"coding" : [ {"system" : "https://terminology.hl7.org/CodeSystem/v3-DataOperation","code" : "UPDATE"} ]}}}, {"fullUrl" : "Observation/1713967817744195000.ea00b2e2-2ad0-4be7-b2b2-23ec4952d615","resource" : {"resourceType" : "Observation","id" : "1713967817744195000.ea00b2e2-2ad0-4be7-b2b2-23ec4952d615","extension" : [ {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/analysis-date-time","valueDateTime" : "2021-02-09T00:00:00-06:00","_valueDateTime" : {"extension" : [ {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/hl7v2-date-time","valueString" : "202102090000-0600"} ]}}, {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/obx-observation","extension" : [ {"url" : "OBX.2","valueId" : "CWE"}, {"url" : "OBX.11","valueString" : "F"}, {"url" : "OBX.17","valueCodeableConcept" : {"coding" : [ {"extension" : [ {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/cwe-coding","valueString" : "coding"}, {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/cwe-coding-system","valueString" : "99ELR"} ],"code" : "CareStart COVID-19 Antigen test_Access Bio, Inc._EUA"} ]}} ]} ],"status" : "final","code" : {"coding" : [ {"extension" : [ {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/cwe-coding","valueString" : "coding"}, {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/cwe-coding-system","valueString" : "LN"} ],"system" : "http://loinc.org","code" : "94558-4","display" : "SARS-CoV-2 (COVID-19) Ag [Presence] in Respiratory specimen by Rapid immunoassay"} ]},"subject" : {"reference" : "Patient/1713967817739028000.61ba3462-9f36-472e-908b-5edd1c2593a2"},"effectiveDateTime" : "2021-02-09T00:00:00-06:00","_effectiveDateTime" : {"extension" : [ {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/hl7v2-date-time","valueString" : "202102090000-0600"} ]},"performer" : [ {"reference" : "Organization/1713967817745189000.0de5081f-b0f8-4356-a33a-af18d418d873"} ],"valueCodeableConcept" : {"coding" : [ {"extension" : [ {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/cwe-coding","valueString" : "coding"}, {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/cwe-coding-system","valueString" : "SCT"} ],"system" : "http://snomed.info/sct","code" : "260415000","display" : "Not detected"} ]},"interpretation" : [ {"coding" : [ {"extension" : [ {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/cwe-coding","valueString" : "coding"}, {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/cwe-coding-system","valueString" : "HL70078"} ],"code" : "N","display" : "Normal (applies to non-numeric results)"} ]} ],"method" : {"coding" : [ {"extension" : [ {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/cwe-coding","valueString" : "coding"}, {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/cwe-coding-system","valueString" : "99ELR"} ],"code" : "CareStart COVID-19 Antigen test_Access Bio, Inc._EUA"} ]}}}, {"fullUrl" : "Organization/1713967817745189000.0de5081f-b0f8-4356-a33a-af18d418d873","resource" : {"resourceType" : "Organization","id" : "1713967817745189000.0de5081f-b0f8-4356-a33a-af18d418d873","extension" : [ {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/xon-organization","extension" : [ {"url" : "XON.10","valueString" : "10D0876999"} ]}, {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/hl7v2Field","valueString" : "OBX.25"} ],"identifier" : [ {"extension" : [ {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/assigning-authority","extension" : [ {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/namespace-id","valueString" : "CLIA"}, {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/universal-id","valueString" : "2.16.840.1.113883.4.7"}, {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/universal-id-type","valueCode" : "ISO"} ]} ],"value" : "10D0876999"} ],"name" : "Avante at Ormond Beach","address" : [ {"extension" : [ {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/xad-address","extension" : [ {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/sad-address-line","extension" : [ {"url" : "SAD.1","valueString" : "170 North King Road"} ]} ]} ],"line" : [ "170 North King Road" ],"city" : "Ormond Beach","district" : "12127","state" : "FL","postalCode" : "32174"} ]}}, {"fullUrl" : "Observation/1713967817748205000.5125db58-bd99-453c-ba31-e77dafe5267d","resource" : {"resourceType" : "Observation","id" : "1713967817748205000.5125db58-bd99-453c-ba31-e77dafe5267d","extension" : [ {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/obx-observation","extension" : [ {"url" : "OBX.2","valueId" : "CWE"}, {"url" : "OBX.11","valueString" : "F"} ]} ],"status" : "final","code" : {"coding" : [ {"extension" : [ {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/cwe-coding","valueString" : "coding"}, {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/cwe-coding-system","valueString" : "LN"} ],"system" : "http://loinc.org","code" : "95418-0","display" : "Whether patient is employed in a healthcare setting"} ]},"subject" : {"reference" : "Patient/1713967817739028000.61ba3462-9f36-472e-908b-5edd1c2593a2"},"effectiveDateTime" : "2021-02-09T00:00:00-06:00","_effectiveDateTime" : {"extension" : [ {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/hl7v2-date-time","valueString" : "202102090000-0600"} ]},"valueCodeableConcept" : {"coding" : [ {"extension" : [ {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/cwe-coding","valueString" : "coding"}, {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/cwe-coding-system","valueString" : "HL70136"} ],"code" : "Y","display" : "Yes"} ]}}}, {"fullUrl" : "Observation/1713967817750398000.01218b3a-a4b9-4d97-b3ee-9d9114d88a99","resource" : {"resourceType" : "Observation","id" : "1713967817750398000.01218b3a-a4b9-4d97-b3ee-9d9114d88a99","extension" : [ {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/obx-observation","extension" : [ {"url" : "OBX.2","valueId" : "CWE"}, {"url" : "OBX.11","valueString" : "F"} ]} ],"status" : "final","code" : {"coding" : [ {"extension" : [ {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/cwe-coding","valueString" : "coding"}, {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/cwe-coding-system","valueString" : "LN"} ],"system" : "http://loinc.org","code" : "95417-2","display" : "First test for condition of interest"} ]},"subject" : {"reference" : "Patient/1713967817739028000.61ba3462-9f36-472e-908b-5edd1c2593a2"},"effectiveDateTime" : "2021-02-09T00:00:00-06:00","_effectiveDateTime" : {"extension" : [ {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/hl7v2-date-time","valueString" : "202102090000-0600"} ]},"valueCodeableConcept" : {"coding" : [ {"extension" : [ {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/cwe-coding","valueString" : "coding"}, {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/cwe-coding-system","valueString" : "HL70136"} ],"code" : "Y","display" : "Yes"} ]}}}, {"fullUrl" : "Observation/1713967817752856000.781430e9-6956-480e-9aed-2262d0115226","resource" : {"resourceType" : "Observation","id" : "1713967817752856000.781430e9-6956-480e-9aed-2262d0115226","extension" : [ {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/obx-observation","extension" : [ {"url" : "OBX.2","valueId" : "CWE"}, {"url" : "OBX.11","valueString" : "F"} ]} ],"status" : "final","code" : {"coding" : [ {"extension" : [ {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/cwe-coding","valueString" : "coding"}, {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/cwe-coding-system","valueString" : "LN"} ],"system" : "http://loinc.org","code" : "95421-4","display" : "Resides in a congregate care setting"} ]},"subject" : {"reference" : "Patient/1713967817739028000.61ba3462-9f36-472e-908b-5edd1c2593a2"},"effectiveDateTime" : "2021-02-09T00:00:00-06:00","_effectiveDateTime" : {"extension" : [ {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/hl7v2-date-time","valueString" : "202102090000-0600"} ]},"valueCodeableConcept" : {"coding" : [ {"extension" : [ {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/cwe-coding","valueString" : "coding"}, {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/cwe-coding-system","valueString" : "HL70136"} ],"code" : "N","display" : "No"} ]}}}, {"fullUrl" : "Specimen/1713967817929028000.a8a661ad-94ea-4fa3-a7d1-5fcc088ac171","resource" : {"resourceType" : "Specimen","id" : "1713967817929028000.a8a661ad-94ea-4fa3-a7d1-5fcc088ac171","extension" : [ {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/hl7v2Segment","valueString" : "OBR"} ]}}, {"fullUrl" : "Specimen/1713967817931134000.75ad130b-65c0-4b90-a103-6df528d9df47","resource" : {"resourceType" : "Specimen","id" : "1713967817931134000.75ad130b-65c0-4b90-a103-6df528d9df47","extension" : [ {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/hl7v2Segment","valueString" : "SPM"} ],"identifier" : [ {"extension" : [ {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/hl7v2Component","valueString" : "SPM.2.1"} ],"type" : {"coding" : [ {"system" : "http://terminology.hl7.org/CodeSystem/v2-0203","code" : "PGN"} ]},"value" : "0cba76f5-35e0-4a28-803a-2f31308aae9b"} ],"type" : {"coding" : [ {"extension" : [ {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/cwe-coding","valueString" : "coding"}, {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/cwe-coding-system","valueString" : "SCT"} ],"system" : "http://snomed.info/sct","code" : "258500001","display" : "Nasopharyngeal swab"} ]},"receivedTime" : "2021-02-09T00:00:00-06:00","_receivedTime" : {"extension" : [ {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/hl7v2-date-time","valueString" : "202102090000-0600"} ]},"collection" : {"collectedDateTime" : "2021-02-09T00:00:00-06:00","_collectedDateTime" : {"extension" : [ {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/hl7v2-date-time","valueString" : "202102090000-0600"} ]},"bodySite" : {"coding" : [ {"extension" : [ {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/cwe-coding","valueString" : "coding"}, {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/cwe-coding-system","valueString" : "SCT"} ],"system" : "http://snomed.info/sct","version" : "2020-09-01","code" : "71836000","display" : "Nasopharyngeal structure (body structure)"} ]}}}}, {"fullUrl" : "ServiceRequest/1713967817942507000.4f329371-701d-4ecb-aba3-5f435b14dd6e","resource" : {"resourceType" : "ServiceRequest","id" : "1713967817942507000.4f329371-701d-4ecb-aba3-5f435b14dd6e","extension" : [ {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/business-event","valueCode" : "RE"}, {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/orc-common-order","extension" : [ {"url" : "orc-21-ordering-facility-name","valueReference" : {"reference" : "Organization/1713967817937499000.0c8cf54d-4662-4e5e-99c2-43d102aa163d"}}, {"url" : "orc-22-ordering-facility-address","valueAddress" : {"extension" : [ {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/xad-address","extension" : [ {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/sad-address-line","extension" : [ {"url" : "SAD.1","valueString" : "170 North King Road"} ]} ]} ],"line" : [ "170 North King Road" ],"city" : "Ormond Beach","district" : "12127","state" : "FL","postalCode" : "32174"}}, {"url" : "orc-24-ordering-provider-address","valueAddress" : {"postalCode" : "32174"}}, {"url" : "orc-12-ordering-provider","valueReference" : {"reference" : "Practitioner/1713967817939384000.805f81c8-a043-458c-aead-2766e8c3aaf3"}}, {"url" : "ORC.15","valueString" : "20210209"} ]}, {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/obr-observation-request","extension" : [ {"url" : "OBR.2","valueIdentifier" : {"value" : "73a6e9bd-aaec-418e-813a-0ad33366ca85"}}, {"url" : "OBR.3","valueIdentifier" : {"value" : "0cba76f5-35e0-4a28-803a-2f31308aae9b"}}, {"url" : "OBR.22","valueString" : "202102090000-0600"}, {"url" : "OBR.16","valueReference" : {"reference" : "Practitioner/1713967817940376000.214e26cb-2726-4294-addb-be0a1d60be76"}}, {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/callback-number","valueContactPoint" : {"extension" : [ {"url" : "http://hl7.org/fhir/StructureDefinition/contactpoint-country","valueString" : "1"}, {"url" : "http://hl7.org/fhir/StructureDefinition/contactpoint-area","valueString" : "386"}, {"url" : "http://hl7.org/fhir/StructureDefinition/contactpoint-local","valueString" : "6825220"}, {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/xtn-contact-point","extension" : [ {"url" : "XTN.2","valueString" : "WPN"}, {"url" : "XTN.7","valueString" : "6825220"} ]}, {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/hl7v2Field","valueString" : "OBR.17"} ],"_system" : {"extension" : [ {"url" : "http://hl7.org/fhir/StructureDefinition/data-absent-reason","valueCode" : "unknown"} ]},"use" : "work"}} ]} ],"identifier" : [ {"extension" : [ {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/hl7v2Field","valueString" : "ORC.2"} ],"type" : {"coding" : [ {"system" : "http://terminology.hl7.org/CodeSystem/v2-0203","code" : "PLAC"} ]},"value" : "73a6e9bd-aaec-418e-813a-0ad33366ca85"}, {"extension" : [ {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/hl7v2Field","valueString" : "ORC.3"} ],"type" : {"coding" : [ {"system" : "http://terminology.hl7.org/CodeSystem/v2-0203","code" : "FILL"} ]},"value" : "73a6e9bd-aaec-418e-813a-0ad33366ca85"} ],"status" : "unknown","code" : {"coding" : [ {"extension" : [ {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/cwe-coding","valueString" : "coding"}, {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/cwe-coding-system","valueString" : "LN"} ],"system" : "http://loinc.org","code" : "94558-4","display" : "SARS-CoV-2 (COVID-19) Ag [Presence] in Respiratory specimen by Rapid immunoassay"} ]},"subject" : {"reference" : "Patient/1713967817739028000.61ba3462-9f36-472e-908b-5edd1c2593a2"},"requester" : {"extension" : [ {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/callback-number","valueContactPoint" : {"extension" : [ {"url" : "http://hl7.org/fhir/StructureDefinition/contactpoint-country","valueString" : "1"}, {"url" : "http://hl7.org/fhir/StructureDefinition/contactpoint-area","valueString" : "386"}, {"url" : "http://hl7.org/fhir/StructureDefinition/contactpoint-local","valueString" : "6825220"}, {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/xtn-contact-point","extension" : [ {"url" : "XTN.2","valueString" : "WPN"}, {"url" : "XTN.7","valueString" : "6825220"} ]}, {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/hl7v2Field","valueString" : "ORC.14"} ],"_system" : {"extension" : [ {"url" : "http://hl7.org/fhir/StructureDefinition/data-absent-reason","valueCode" : "unknown"} ]},"use" : "work"}} ],"reference" : "PractitionerRole/1713967817932385000.44d55f02-395b-4a6f-9ee0-073a486e0db5"}}}, {"fullUrl" : "Practitioner/1713967817933804000.973fb0ea-7096-483a-811d-69c5c58e1a29","resource" : {"resourceType" : "Practitioner","id" : "1713967817933804000.973fb0ea-7096-483a-811d-69c5c58e1a29","extension" : [ {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/assigning-authority","extension" : [ {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/namespace-id","valueString" : "CMS"}, {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/universal-id","valueString" : "2.16.840.1.113883.3.249"}, {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/universal-id-type","valueCode" : "ISO"} ]}, {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/xcn-practitioner","extension" : [ {"url" : "XCN.3","valueString" : "Husam"} ]}, {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/hl7v2Field","valueString" : "ORC.12"} ],"identifier" : [ {"type" : {"coding" : [ {"extension" : [ {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/codeable-concept-id","valueBoolean" : true} ],"code" : "NPI"} ]},"system" : "CMS","value" : "1629082607"} ],"name" : [ {"family" : "Eddin","given" : [ "Husam" ]} ],"address" : [ {"postalCode" : "32174"} ]}}, {"fullUrl" : "Organization/1713967817934866000.fb2ab42f-b6a0-4925-912e-26852a024f17","resource" : {"resourceType" : "Organization","id" : "1713967817934866000.fb2ab42f-b6a0-4925-912e-26852a024f17","name" : "Avante at Ormond Beach","telecom" : [ {"extension" : [ {"url" : "http://hl7.org/fhir/StructureDefinition/contactpoint-country","valueString" : "1"}, {"url" : "http://hl7.org/fhir/StructureDefinition/contactpoint-area","valueString" : "407"}, {"url" : "http://hl7.org/fhir/StructureDefinition/contactpoint-local","valueString" : "7397506"}, {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/xtn-contact-point","extension" : [ {"url" : "XTN.2","valueString" : "WPN"}, {"url" : "XTN.4","valueString" : "jbrush@avantecenters.com"}, {"url" : "XTN.7","valueString" : "7397506"} ]} ],"system" : "email","use" : "work"} ],"address" : [ {"extension" : [ {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/xad-address","extension" : [ {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/sad-address-line","extension" : [ {"url" : "SAD.1","valueString" : "170 North King Road"} ]} ]} ],"line" : [ "170 North King Road" ],"city" : "Ormond Beach","district" : "12127","state" : "FL","postalCode" : "32174"} ]}}, {"fullUrl" : "PractitionerRole/1713967817932385000.44d55f02-395b-4a6f-9ee0-073a486e0db5","resource" : {"resourceType" : "PractitionerRole","id" : "1713967817932385000.44d55f02-395b-4a6f-9ee0-073a486e0db5","practitioner" : {"reference" : "Practitioner/1713967817933804000.973fb0ea-7096-483a-811d-69c5c58e1a29"},"organization" : {"reference" : "Organization/1713967817934866000.fb2ab42f-b6a0-4925-912e-26852a024f17"}}}, {"fullUrl" : "Organization/1713967817937499000.0c8cf54d-4662-4e5e-99c2-43d102aa163d","resource" : {"resourceType" : "Organization","id" : "1713967817937499000.0c8cf54d-4662-4e5e-99c2-43d102aa163d","name" : "Avante at Ormond Beach"}}, {"fullUrl" : "Practitioner/1713967817939384000.805f81c8-a043-458c-aead-2766e8c3aaf3","resource" : {"resourceType" : "Practitioner","id" : "1713967817939384000.805f81c8-a043-458c-aead-2766e8c3aaf3","extension" : [ {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/assigning-authority","extension" : [ {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/namespace-id","valueString" : "CMS"}, {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/universal-id","valueString" : "2.16.840.1.113883.3.249"}, {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/universal-id-type","valueCode" : "ISO"} ]}, {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/xcn-practitioner","extension" : [ {"url" : "XCN.3","valueString" : "Husam"} ]} ],"identifier" : [ {"type" : {"coding" : [ {"extension" : [ {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/codeable-concept-id","valueBoolean" : true} ],"code" : "NPI"} ]},"system" : "CMS","value" : "1629082607"} ],"name" : [ {"family" : "Eddin","given" : [ "Husam" ]} ]}}, {"fullUrl" : "Practitioner/1713967817940376000.214e26cb-2726-4294-addb-be0a1d60be76","resource" : {"resourceType" : "Practitioner","id" : "1713967817940376000.214e26cb-2726-4294-addb-be0a1d60be76","extension" : [ {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/assigning-authority","extension" : [ {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/namespace-id","valueString" : "CMS"}, {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/universal-id","valueString" : "2.16.840.1.113883.3.249"}, {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/universal-id-type","valueCode" : "ISO"} ]}, {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/xcn-practitioner","extension" : [ {"url" : "XCN.3","valueString" : "Husam"} ]} ],"identifier" : [ {"type" : {"coding" : [ {"extension" : [ {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/codeable-concept-id","valueBoolean" : true} ],"code" : "NPI"} ]},"system" : "CMS","value" : "1629082607"} ],"name" : [ {"family" : "Eddin","given" : [ "Husam" ]} ]}}, {"fullUrl" : "DiagnosticReport/1713967817947485000.8a170b9c-97f8-49c8-83d0-79bd665b5deb","resource" : {"resourceType" : "DiagnosticReport","id" : "1713967817947485000.8a170b9c-97f8-49c8-83d0-79bd665b5deb","identifier" : [ {"extension" : [ {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/hl7v2Field","valueString" : "ORC.2"} ],"type" : {"coding" : [ {"system" : "http://terminology.hl7.org/CodeSystem/v2-0203","code" : "PLAC"} ]},"value" : "73a6e9bd-aaec-418e-813a-0ad33366ca85"}, {"type" : {"coding" : [ {"system" : "http://terminology.hl7.org/CodeSystem/v2-0203","code" : "FILL"} ]},"value" : "73a6e9bd-aaec-418e-813a-0ad33366ca85"} ],"basedOn" : [ {"reference" : "ServiceRequest/1713967817942507000.4f329371-701d-4ecb-aba3-5f435b14dd6e"} ],"status" : "final","code" : {"coding" : [ {"extension" : [ {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/cwe-coding","valueString" : "coding"}, {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/cwe-coding-system","valueString" : "LN"} ],"system" : "http://loinc.org","code" : "94558-4","display" : "SARS-CoV-2 (COVID-19) Ag [Presence] in Respiratory specimen by Rapid immunoassay"} ]},"subject" : {"reference" : "Patient/1713967817739028000.61ba3462-9f36-472e-908b-5edd1c2593a2"},"effectivePeriod" : {"start" : "2021-02-09T00:00:00-06:00","_start" : {"extension" : [ {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/hl7v2-date-time","valueString" : "202102090000-0600"} ]},"end" : "2021-02-09T00:00:00-06:00","_end" : {"extension" : [ {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/hl7v2-date-time","valueString" : "202102090000-0600"} ]}},"issued" : "2021-02-09T00:00:00-06:00","_issued" : {"extension" : [ {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/hl7v2-date-time","valueString" : "202102090000-0600"} ]},"specimen" : [ {"reference" : "Specimen/1713967817931134000.75ad130b-65c0-4b90-a103-6df528d9df47"}, {"reference" : "Specimen/1713967817929028000.a8a661ad-94ea-4fa3-a7d1-5fcc088ac171"} ],"result" : [ {"reference" : "Observation/1713967817744195000.ea00b2e2-2ad0-4be7-b2b2-23ec4952d615"}, {"reference" : "Observation/1713967817748205000.5125db58-bd99-453c-ba31-e77dafe5267d"}, {"reference" : "Observation/1713967817750398000.01218b3a-a4b9-4d97-b3ee-9d9114d88a99"}, {"reference" : "Observation/1713967817752856000.781430e9-6956-480e-9aed-2262d0115226"} ]}} ]}"""

// The encoding ^~\&#! make this message not parseable
@Suppress("ktlint:standard:max-line-length")
private const val badEncodingHL7Record =
    """MSH|^~\&#!|CDC PRIME - Atlanta, Georgia (Dekalb)^2.16.840.1.114222.4.1.237821^ISO|Avante at Ormond Beach^10D0876999^CLIA|PRIME_DOH|Prime ReportStream|20210210170737||ORU^R01^ORU_R01|371784|P|2.5.1|||NE|NE|USA||||PHLabReportNoAck^ELR_Receiver^2.16.840.1.113883.9.99^ISO
SFT|Centers for Disease Control and Prevention|0.1-SNAPSHOT|PRIME ReportStream|0.1-SNAPSHOT||20210210
PID|1||2a14112c-ece1-4f82-915c-7b3a8d152eda^^^Avante at Ormond Beach^PI||Buckridge^Kareem^Millie^^^^L||19580810|F||2106-3^White^HL70005^^^^2.5.1|688 Leighann Inlet^^South Rodneychester^TX^67071^^^^48077||7275555555:1:^PRN^^roscoe.wilkinson@email.com^1^211^2240784|||||||||U^Unknown^HL70189||||||||N
ORC|RE|73a6e9bd-aaec-418e-813a-0ad33366ca85|73a6e9bd-aaec-418e-813a-0ad33366ca85|||||||||1629082607^Eddin^Husam^^^^^^CMS&2.16.840.1.113883.3.249&ISO^^^^NPI||^WPN^^^1^386^6825220|20210209||||||Avante at Ormond Beach|170 North King Road^^Ormond Beach^FL^32174^^^^12127|^WPN^^jbrush@avantecenters.com^1^407^7397506|^^^^32174
OBR|1|73a6e9bd-aaec-418e-813a-0ad33366ca85|0cba76f5-35e0-4a28-803a-2f31308aae9b|94558-4^SARS-CoV-2 (COVID-19) Ag [Presence] in Respiratory specimen by Rapid immunoassay^LN|||202102090000-0600|202102090000-0600||||||||1629082607^Eddin^Husam^^^^^^CMS&2.16.840.1.113883.3.249&ISO^^^^NPI|^WPN^^^1^386^6825220|||||202102090000-0600|||F
OBX|1|CWE|94558-4^SARS-CoV-2 (COVID-19) Ag [Presence] in Respiratory specimen by Rapid immunoassay^LN||260415000^Not detected^SCT|||N^Normal (applies to non-numeric results)^HL70078|||F|||202102090000-0600|||CareStart COVID-19 Antigen test_Access Bio, Inc._EUA^^99ELR||202102090000-0600||||Avante at Ormond Beach^^^^^CLIA&2.16.840.1.113883.4.7&ISO^^^^10D0876999^CLIA|170 North King Road^^Ormond Beach^FL^32174^^^^12127
OBX|2|CWE|95418-0^Whether patient is employed in a healthcare setting^LN^^^^2.69||Y^Yes^HL70136||||||F|||202102090000-0600|||||||||||||||QST
OBX|3|CWE|95417-2^First test for condition of interest^LN^^^^2.69||Y^Yes^HL70136||||||F|||202102090000-0600|||||||||||||||QST
OBX|4|CWE|95421-4^Resides in a congregate care setting^LN^^^^2.69||N^No^HL70136||||||F|||202102090000-0600|||||||||||||||QST
OBX|5|CWE|95419-8^Has symptoms related to condition of interest^LN^^^^2.69||N^No^HL70136||||||F|||202102090000-0600|||||||||||||||QST
SPM|1|0cba76f5-35e0-4a28-803a-2f31308aae9b||258500001^Nasopharyngeal swab^SCT||||71836000^Nasopharyngeal structure (body structure)^SCT^^^^2020-09-01|||||||||202102090000-0600|202102090000-0600"""

// The missing | MSH^~\& make this message not parseable
@Suppress("ktlint:standard:max-line-length")
private const val unparseableHL7Record =
    """MSH^~\&|CDC PRIME - Atlanta, Georgia (Dekalb)^2.16.840.1.114222.4.1.237821^ISO|Avante at Ormond Beach^10D0876999^CLIA|PRIME_DOH|Prime ReportStream|20210210170737||ORU^R01^ORU_R01|371784|P|2.5.1|||NE|NE|USA||||PHLabReportNoAck^ELR_Receiver^2.16.840.1.113883.9.99^ISO
SFT|Centers for Disease Control and Prevention|0.1-SNAPSHOT|PRIME ReportStream|0.1-SNAPSHOT||20210210
PID|1||2a14112c-ece1-4f82-915c-7b3a8d152eda^^^Avante at Ormond Beach^PI||Buckridge^Kareem^Millie^^^^L||19580810|F||2106-3^White^HL70005^^^^2.5.1|688 Leighann Inlet^^South Rodneychester^TX^67071^^^^48077||7275555555:1:^PRN^^roscoe.wilkinson@email.com^1^211^2240784|||||||||U^Unknown^HL70189||||||||N
ORC|RE|73a6e9bd-aaec-418e-813a-0ad33366ca85^6^7^8&F^9|73a6e9bd-aaec-418e-813a-0ad33366ca85|||||||||1629082607^Eddin^Husam^^^^^^CMS&2.16.840.1.113883.3.249&ISO^^^^NPI||^WPN^^^1^386^6825220|20210209||||||Avante at Ormond Beach|170 North King Road^^Ormond Beach^FL^32174^^^^12127|^WPN^^jbrush@avantecenters.com^1^407^7397506|^^^^32174
OBR|1|73a6e9bd-aaec-418e-813a-0ad33366ca85|0cba76f5-35e0-4a28-803a-2f31308aae9b|94558-4^SARS-CoV-2 (COVID-19) Ag [Presence] in Respiratory specimen by Rapid immunoassay^LN|||202102090000-0600|202102090000-0600||||||||1629082607^Eddin^Husam^^^^^^CMS&2.16.840.1.113883.3.249&ISO^^^^NPI|^WPN^^^1^386^6825220|||||202102090000-0600|||F
OBX|1|CWE|94558-4^SARS-CoV-2 (COVID-19) Ag [Presence] in Respiratory specimen by Rapid immunoassay^LN||260415000^Not detected^SCT|||N^Normal (applies to non-numeric results)^HL70078|||F|||202102090000-0600|||CareStart COVID-19 Antigen test_Access Bio, Inc._EUA^^99ELR||202102090000-0600||||Avante at Ormond Beach^^^^^CLIA&2.16.840.1.113883.4.7&ISO^^^^10D0876999^CLIA|170 North King Road^^Ormond Beach^FL^32174^^^^12127
OBX|2|CWE|95418-0^Whether patient is employed in a healthcare setting^LN^^^^2.69||Y^Yes^HL70136||||||F|||202102090000-0600|||||||||||||||QST
OBX|3|CWE|95417-2^First test for condition of interest^LN^^^^2.69||Y^Yes^HL70136||||||F|||202102090000-0600|||||||||||||||QST
OBX|4|CWE|95421-4^Resides in a congregate care setting^LN^^^^2.69||N^No^HL70136||||||F|||202102090000-0600|||||||||||||||QST
OBX|5|CWE|95419-8^Has symptoms related to condition of interest^LN^^^^2.69||N^No^HL70136||||||F|||202102090000-0600|||||||||||||||QST
SPM|1|0cba76f5-35e0-4a28-803a-2f31308aae9b||258500001^Nasopharyngeal swab^SCT||||71836000^Nasopharyngeal structure (body structure)^SCT^^^^2020-09-01|||||||||202102090000-0600|202102090000-0600"""

// One key difference that makes this unparseable by the 2.5.1 HAPI HL7 structures is that OBX.3 is a CWE not a CE
@Suppress("ktlint:standard:max-line-length")
private const val nistELRHL7Record =
    """MSH|^~\&#|STARLIMS.CDC.Stag^2.16.840.1.114222.4.3.3.2.1.2^ISO|CDC Atlanta^11D0668319^CLIA|MEDSS-ELR ^2.16.840.1.114222.4.3.3.6.2.1^ISO|MNDOH^2.16.840.1.114222.4.1.3661^ISO|20230501102531-0400||ORU^R01^ORU_R01|3003786103_4988249_33033|T|2.5.1|||NE|NE|USA||||PHLabReport-NoAck^PHIN^2.16.840.1.113883.9.11^ISO
SFT|CDC^^^^^CDC&2.16.840.1.114222.4&ISO^XX^^^CDC CLIA|ELIMS V11|STARLIMS|Binary ID unknown
PID|1||PID03953346^^^STARLIMS.CDC.Stag&2.16.840.1.114222.4.3.3.2.1.2&ISO^PI~10171284^^^SPHL-000034&2.16.840.1.114222.4.1.3661&ISO^PI||^^^^^^U||0000||||^^^^^USA^H
NTE|1|L|SPHL Submitter: MN PHL Division, Minnesota Department of Health, Submitter ID: SPHL-000034, Address: 601 Robert St. N.  St. Paul, Minnesota 55164-0899 United States, Email: Health.idlabreports@state.mn.us, Submitter Patient ID: 10171284, Submitter Alt Patient ID: , Submitter Specimen ID: 230011927, Submitter Alt Specimen ID:|RE^Remark^HL70364^^^^2.5.1^^^^^^^2.16.840.1.113883.12.364
ORC|RE|230011927^SPHL-000034^2.16.840.1.114222.4.1.3661^ISO|40_3003786103_4988249_1087^STARLIMS.CDC.Stag^2.16.840.1.114222.4.3.3.2.1.2^ISO|||||||||SPHL-000034^MN PHL Division, Minnesota Department of Health^^^^^^^STARLIMS.CDC.Stag&2.16.840.1.114222.4.3.3.2.1.2&ISO^^^^XX||^NET^Internet^Health.idlabreports@state.mn.us|||||||MN PHL Division, Minnesota Department of Health^D^^^^STARLIMS.CDC.Stag&2.16.840.1.114222.4.3.3.2.1.2&ISO^XX^^^SPHL-000034|601 Robert St. N.^^St. Paul^MN^55164-0899^USA^M|^WPN^Internet^Health.idlabreports@state.mn.us|601 Robert St. N.^^St. Paul^MN^55164-0899^USA^M
OBR|1|230011927^SPHL-000034^2.16.840.1.114222.4.1.3661^ISO|40_3003786103_4988249_1087^STARLIMS.CDC.Stag^2.16.840.1.114222.4.3.3.2.1.2^ISO|PLT1228^Mold and Yeast XXX MS.MALDI-TOF^PLT^1087^MALDI-TOF-CLIA^L^2.69^v unknown^^CDC-10179^Fungal Identification^L^^2.16.840.1.113883.6.1|||20230322|||||||||SPHL-000034^MN PHL Division, Minnesota Department of Health^^^^^^^STARLIMS.CDC.Stag&2.16.840.1.114222.4.3.3.2.1.2&ISO^^^^XX|^NET^Internet^Health.idlabreports@state.mn.us|||||202304271044-0400|||F
OBX|1|CWE|PLT1228^Mold and Yeast XXX MS.MALDI-TOF^PLT^3562^MALDI-TOF-CLIA^L^2.69^v_unknown^MALDI-TOF-CLIA|N8KHKA9H-1|712760003^Candida metapsilosis (organism)^SCT^^^^09012018^^Candida metapsilosis||||||F|||20230322|11D0668319^Centers for Disease Control and Prevention^CLIA^40^Fungus Reference Laboratory^L|HVR0@cdc.gov^Gade^Lalitha|||20230427092900||||Centers for Disease Control and Prevention^L^^^^CLIA&2.16.840.1.113883.4.7&ISO^XX^^^11D0668319|1600 Clifton Rd^^Atlanta^GA^30329^USA^B
SPM|1|230011927&SPHL-000034&2.16.840.1.114222.4.1.3661&ISO^3003786103&STARLIMS.CDC.Stag&2.16.840.1.114222.4.3.3.2.1.2&ISO||119365002^Specimen from wound^SCT^WND^Wound^L^0912017^Adobe_Code^Wound||||56459004^Foot^SCT^FOT^Foot^L^09012017^Adobe_Code^Foot||||||Isolate,|||20230322|20230421124150"""

@Suppress("ktlint:standard:max-line-length")
private const val nistELRHL7RecordConverted =
    """{"resourceType" : "Bundle","id" : "1713968212115404000.29d5f11c-672b-46d0-998a-ea8b9d87f7f8","meta" : {  "lastUpdated" : "2024-04-24T10:16:52.121-04:00"},"identifier" : {  "system" : "https://reportstream.cdc.gov/prime-router",  "value" : "3003786103_4988249_33033"},"type" : "message","timestamp" : "2023-05-01T10:25:31.000-04:00","entry" : [ {  "fullUrl" : "MessageHeader/0993dd0b-6ce5-3caf-a177-0b81cc780c18",  "resource" : {"resourceType" : "MessageHeader","id" : "0993dd0b-6ce5-3caf-a177-0b81cc780c18","meta" : {  "tag" : [ {"system" : "http://terminology.hl7.org/CodeSystem/v2-0103","code" : "T"  } ]},"extension" : [ {  "url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/encoding-characters",  "valueString" : "^~\\&#"}, {  "url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/msh-message-header",  "extension" : [ {"url" : "MSH.7","valueString" : "20230501102531-0400"  }, {"url" : "MSH.15","valueString" : "NE"  }, {"url" : "MSH.16","valueString" : "NE"  }, {"url" : "MSH.21","valueIdentifier" : {  "extension" : [ {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/assigning-authority","extension" : [ {  "url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/namespace-id",  "valueString" : "PHIN"}, {  "url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/universal-id",  "valueString" : "2.16.840.1.113883.9.11"}, {  "url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/universal-id-type",  "valueCode" : "ISO"} ]  } ],  "value" : "PHLabReport-NoAck"}  } ]} ],"eventCoding" : {  "system" : "http://terminology.hl7.org/CodeSystem/v2-0003",  "code" : "R01",  "display" : "ORU^R01^ORU_R01"},"destination" : [ {  "extension" : [ {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/universal-id","valueString" : "2.16.840.1.114222.4.3.3.6.2.1"  }, {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/universal-id-type","valueString" : "ISO"  }, {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/hl7v2Field","valueString" : "MSH.5"  } ],  "name" : "MEDSS-ELR ",  "endpoint" : "urn:oid:2.16.840.1.114222.4.3.3.6.2.1",  "receiver" : {"reference" : "Organization/1713968212195396000.84a65184-4719-44a7-b9c0-0ea111609486"  }} ],"sender" : {  "reference" : "Organization/1713968212170827000.de305777-2421-4705-9d82-ad0a5f81edb8"},"source" : {  "extension" : [ {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/namespace-id","valueString" : "STARLIMS.CDC.Stag"  }, {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/universal-id","valueString" : "2.16.840.1.114222.4.3.3.2.1.2"  }, {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/universal-id-type","valueString" : "ISO"  }, {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/hl7v2Field","valueString" : "MSH.3"  } ],  "software" : "STARLIMS",  "version" : "ELIMS V11",  "endpoint" : "urn:oid:2.16.840.1.114222.4.3.3.2.1.2"}  }}, {  "fullUrl" : "Organization/1713968212170827000.de305777-2421-4705-9d82-ad0a5f81edb8",  "resource" : {"resourceType" : "Organization","id" : "1713968212170827000.de305777-2421-4705-9d82-ad0a5f81edb8","identifier" : [ {  "extension" : [ {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/hl7v2Field","valueString" : "HD.1"  } ],  "value" : "CDC Atlanta"}, {  "extension" : [ {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/hl7v2Field","valueString" : "HD.2,HD.3"  } ],  "type" : {"coding" : [ {  "system" : "http://terminology.hl7.org/CodeSystem/v2-0301",  "code" : "CLIA"} ]  },  "value" : "11D0668319"} ],"address" : [ {  "country" : "USA"} ]  }}, {  "fullUrl" : "Organization/1713968212195396000.84a65184-4719-44a7-b9c0-0ea111609486",  "resource" : {"resourceType" : "Organization","id" : "1713968212195396000.84a65184-4719-44a7-b9c0-0ea111609486","extension" : [ {  "url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/hl7v2Field",  "valueString" : "MSH.6"} ],"identifier" : [ {  "extension" : [ {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/hl7v2Field","valueString" : "HD.1"  } ],  "value" : "MNDOH"}, {  "extension" : [ {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/hl7v2Field","valueString" : "HD.2,HD.3"  } ],  "type" : {"coding" : [ {  "system" : "http://terminology.hl7.org/CodeSystem/v2-0301",  "code" : "ISO"} ]  },  "system" : "urn:ietf:rfc:3986",  "value" : "2.16.840.1.114222.4.1.3661"} ]  }}, {  "fullUrl" : "Provenance/1713968212544259000.f38fd2fa-558e-4c0d-a955-cab6c654bdbd",  "resource" : {"resourceType" : "Provenance","id" : "1713968212544259000.f38fd2fa-558e-4c0d-a955-cab6c654bdbd","target" : [ {  "reference" : "MessageHeader/0993dd0b-6ce5-3caf-a177-0b81cc780c18"}, {  "reference" : "DiagnosticReport/1713968212806660000.5d26bd7c-45b6-4943-8b3c-382d83d08688"} ],"recorded" : "2023-05-01T10:25:31-04:00","activity" : {  "coding" : [ {"display" : "ORU^R01^ORU_R01"  } ]},"agent" : [ {  "type" : {"coding" : [ {  "system" : "http://terminology.hl7.org/CodeSystem/provenance-participant-type",  "code" : "author"} ]  },  "who" : {"reference" : "Organization/1713968212543567000.9a70a954-62c9-451b-b4fc-f0776e4d0eb0"  }} ],"entity" : [ {  "role" : "source",  "what" : {"reference" : "Device/1713968212547955000.cfe65eae-ce18-47e3-9b82-c5d1650318c1"  }} ]  }}, {  "fullUrl" : "Organization/1713968212543567000.9a70a954-62c9-451b-b4fc-f0776e4d0eb0",  "resource" : {"resourceType" : "Organization","id" : "1713968212543567000.9a70a954-62c9-451b-b4fc-f0776e4d0eb0","identifier" : [ {  "extension" : [ {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/hl7v2Field","valueString" : "HD.1"  } ],  "value" : "CDC Atlanta"}, {  "extension" : [ {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/hl7v2Field","valueString" : "HD.2,HD.3"  } ],  "type" : {"coding" : [ {  "system" : "http://terminology.hl7.org/CodeSystem/v2-0301",  "code" : "CLIA"} ]  },  "value" : "11D0668319"} ]  }}, {  "fullUrl" : "Organization/1713968212547742000.12036b89-00cb-4a9e-b008-b4d33a61476c",  "resource" : {"resourceType" : "Organization","id" : "1713968212547742000.12036b89-00cb-4a9e-b008-b4d33a61476c","extension" : [ {  "url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/xon-organization",  "extension" : [ {"url" : "XON.10","valueString" : "CDC CLIA"  } ]} ],"identifier" : [ {  "extension" : [ {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/assigning-authority","extension" : [ {  "url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/namespace-id",  "valueString" : "CDC"}, {  "url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/universal-id",  "valueString" : "2.16.840.1.114222.4"}, {  "url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/universal-id-type",  "valueCode" : "ISO"} ]  } ],  "type" : {"coding" : [ {  "extension" : [ {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/code-index-name","valueString" : "identifier"  } ],  "system" : "http://terminology.hl7.org/CodeSystem/v2-0203",  "code" : "XX"} ]  },  "value" : "CDC CLIA"} ],"name" : "CDC"  }}, {  "fullUrl" : "Device/1713968212547955000.cfe65eae-ce18-47e3-9b82-c5d1650318c1",  "resource" : {"resourceType" : "Device","id" : "1713968212547955000.cfe65eae-ce18-47e3-9b82-c5d1650318c1","extension" : [ {  "url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/software-vendor-org",  "valueReference" : {"reference" : "Organization/1713968212547742000.12036b89-00cb-4a9e-b008-b4d33a61476c"  }} ],"manufacturer" : "CDC","deviceName" : [ {  "name" : "STARLIMS",  "type" : "manufacturer-name"} ],"modelNumber" : "Binary ID unknown","version" : [ {  "value" : "ELIMS V11"} ]  }}, {  "fullUrl" : "Provenance/1713968212555449000.3a4cca10-848a-43b9-8daa-4c10b639975f",  "resource" : {"resourceType" : "Provenance","id" : "1713968212555449000.3a4cca10-848a-43b9-8daa-4c10b639975f","recorded" : "2024-04-24T10:16:52Z","policy" : [ "http://hl7.org/fhir/uv/v2mappings/message-oru-r01-to-bundle" ],"activity" : {  "coding" : [ {"code" : "v2-FHIR transformation"  } ]},"agent" : [ {  "type" : {"coding" : [ {  "system" : "http://terminology.hl7.org/CodeSystem/provenance-participant-type",  "code" : "assembler"} ]  },  "who" : {"reference" : "Organization/1713968212555084000.251e4609-0b31-4331-8ddb-8b38d4d853f4"  }} ]  }}, {  "fullUrl" : "Organization/1713968212555084000.251e4609-0b31-4331-8ddb-8b38d4d853f4",  "resource" : {"resourceType" : "Organization","id" : "1713968212555084000.251e4609-0b31-4331-8ddb-8b38d4d853f4","identifier" : [ {  "value" : "CDC PRIME - Atlanta"}, {  "type" : {"coding" : [ {  "system" : "http://terminology.hl7.org/CodeSystem/v2-0301"} ]  },  "system" : "urn:ietf:rfc:3986",  "value" : "2.16.840.1.114222.4.1.237821"} ]  }}, {  "fullUrl" : "Patient/1713968212574383000.b742ca3a-046f-4df2-b916-0ba5d27d3fac",  "resource" : {"resourceType" : "Patient","id" : "1713968212574383000.b742ca3a-046f-4df2-b916-0ba5d27d3fac","extension" : [ {  "url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/patient-notes",  "valueAnnotation" : {"extension" : [ {  "url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/note-type",  "valueCodeableConcept" : {"extension" : [ {  "url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/coding-system-oid",  "valueOid" : "urn:oid:2.16.840.1.113883.12.364"} ],"coding" : [ {  "extension" : [ {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/cwe-coding","valueString" : "coding"  }, {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/cwe-coding-system","valueString" : "HL70364"  } ],  "version" : "2.5.1",  "code" : "RE",  "display" : "Remark"} ]  }}, {  "url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/note-comment",  "valueId" : "SPHL Submitter: MN PHL Division, Minnesota Department of Health, Submitter ID: SPHL-000034, Address: 601 Robert St. N.  St. Paul, Minnesota 55164-0899 United States, Email: Health.idlabreports@state.mn.us, Submitter Patient ID: 10171284, Submitter Alt Patient ID: , Submitter Specimen ID: 230011927, Submitter Alt Specimen ID:"}, {  "url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/note-source",  "valueId" : "L"} ],"text" : "SPHL Submitter: MN PHL Division, Minnesota Department of Health, Submitter ID: SPHL-000034, Address: 601 Robert St. N.  St. Paul, Minnesota 55164-0899 United States, Email: Health.idlabreports@state.mn.us, Submitter Patient ID: 10171284, Submitter Alt Patient ID: , Submitter Specimen ID: 230011927, Submitter Alt Specimen ID:"  }} ],"identifier" : [ {  "extension" : [ {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/cx-identifier","extension" : [ {  "url" : "CX.5",  "valueString" : "PI"} ]  }, {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/hl7v2Field","valueString" : "PID.3"  } ],  "type" : {"coding" : [ {  "code" : "PI"} ]  },  "system" : "STARLIMS.CDC.Stag",  "_system" : {"extension" : [ {  "url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/namespace-id",  "valueString" : "STARLIMS.CDC.Stag"}, {  "url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/universal-id",  "valueString" : "2.16.840.1.114222.4.3.3.2.1.2"}, {  "url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/universal-id-type",  "valueString" : "ISO"} ]  },  "value" : "PID03953346"}, {  "extension" : [ {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/cx-identifier","extension" : [ {  "url" : "CX.5",  "valueString" : "PI"} ]  }, {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/hl7v2Field","valueString" : "PID.3"  } ],  "type" : {"coding" : [ {  "code" : "PI"} ]  },  "system" : "SPHL-000034",  "_system" : {"extension" : [ {  "url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/namespace-id",  "valueString" : "SPHL-000034"}, {  "url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/universal-id",  "valueString" : "2.16.840.1.114222.4.1.3661"}, {  "url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/universal-id-type",  "valueString" : "ISO"} ]  },  "value" : "10171284"} ],"name" : [ {  "extension" : [ {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/xpn-human-name","extension" : [ {  "url" : "XPN.7",  "valueString" : "U"} ]  } ]} ],"_birthDate" : {  "extension" : [ {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/hl7v2-date-time","valueString" : "0000"  } ]},"address" : [ {  "extension" : [ {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/xad-address","extension" : [ {  "url" : "XAD.7",  "valueCode" : "H"} ]  } ],  "use" : "home",  "country" : "USA"} ]  }}, {  "fullUrl" : "Provenance/1713968212576092000.88d80e6c-333f-479e-bf42-37d930896c3d",  "resource" : {"resourceType" : "Provenance","id" : "1713968212576092000.88d80e6c-333f-479e-bf42-37d930896c3d","target" : [ {  "reference" : "Patient/1713968212574383000.b742ca3a-046f-4df2-b916-0ba5d27d3fac"} ],"recorded" : "2024-04-24T10:16:52Z","activity" : {  "coding" : [ {"system" : "https://terminology.hl7.org/CodeSystem/v3-DataOperation","code" : "UPDATE"  } ]}  }}, {  "fullUrl" : "Observation/1713968212583840000.717c0592-17e9-4dcd-839f-8438e868ce83",  "resource" : {"resourceType" : "Observation","id" : "1713968212583840000.717c0592-17e9-4dcd-839f-8438e868ce83","extension" : [ {  "url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/sub-id",  "valueString" : "N8KHKA9H-1"}, {  "url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/analysis-date-time",  "valueDateTime" : "2023-04-27T09:29:00Z",  "_valueDateTime" : {"extension" : [ {  "url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/hl7v2-date-time",  "valueString" : "20230427092900"} ]  }}, {  "url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/obx-observation",  "extension" : [ {"url" : "OBX.2","valueId" : "CWE"  }, {"url" : "OBX.11","valueString" : "F"  } ]} ],"status" : "final","code" : {  "coding" : [ {"extension" : [ {  "url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/cwe-coding",  "valueString" : "coding"}, {  "url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/cwe-coding-system",  "valueString" : "PLT"} ],"version" : "2.69","code" : "PLT1228","display" : "Mold and Yeast XXX MS.MALDI-TOF"  }, {"extension" : [ {  "url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/cwe-coding",  "valueString" : "alt-coding"}, {  "url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/cwe-coding-system",  "valueString" : "L"} ],"system" : "https://terminology.hl7.org/CodeSystem-v2-0396.html#v2-0396-99zzzorL","version" : "v_unknown","code" : "3562","display" : "MALDI-TOF-CLIA"  } ],  "text" : "MALDI-TOF-CLIA"},"subject" : {  "reference" : "Patient/1713968212574383000.b742ca3a-046f-4df2-b916-0ba5d27d3fac"},"effectiveDateTime" : "2023-03-22","_effectiveDateTime" : {  "extension" : [ {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/hl7v2-date-time","valueString" : "20230322"  } ]},"performer" : [ {  "reference" : "Organization/1713968212584526000.73998d0f-868b-4e42-baa6-71a92b4715e3"}, {  "reference" : "PractitionerRole/1713968212584807000.7fb41b30-fd79-4919-8d2c-488beddc31bb"}, {  "reference" : "Organization/1713968212587709000.22063778-4a51-40d2-9a8c-c173faa87849"} ],"valueCodeableConcept" : {  "coding" : [ {"extension" : [ {  "url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/cwe-coding",  "valueString" : "coding"}, {  "url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/cwe-coding-system",  "valueString" : "SCT"} ],"system" : "http://snomed.info/sct","version" : "09012018","code" : "712760003","display" : "Candida metapsilosis (organism)"  } ],  "text" : "Candida metapsilosis"}  }}, {  "fullUrl" : "Organization/1713968212584526000.73998d0f-868b-4e42-baa6-71a92b4715e3",  "resource" : {"resourceType" : "Organization","id" : "1713968212584526000.73998d0f-868b-4e42-baa6-71a92b4715e3","extension" : [ {  "url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/cwe-organization",  "valueCodeableConcept" : {"coding" : [ {  "extension" : [ {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/cwe-coding","valueString" : "coding"  }, {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/cwe-coding-system","valueString" : "CLIA"  } ],  "code" : "11D0668319",  "display" : "Centers for Disease Control and Prevention"}, {  "extension" : [ {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/cwe-coding","valueString" : "alt-coding"  }, {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/cwe-coding-system","valueString" : "L"  } ],  "system" : "https://terminology.hl7.org/CodeSystem-v2-0396.html#v2-0396-99zzzorL",  "code" : "40",  "display" : "Fungus Reference Laboratory"} ]  }}, {  "url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/hl7v2Field",  "valueString" : "OBX.15"} ],"identifier" : [ {  "system" : "CLIA",  "value" : "11D0668319"} ],"name" : "Centers for Disease Control and Prevention"  }}, {  "fullUrl" : "Practitioner/1713968212585353000.8a2be81e-25f1-439f-82da-5d8c3f1edc4b",  "resource" : {"resourceType" : "Practitioner","id" : "1713968212585353000.8a2be81e-25f1-439f-82da-5d8c3f1edc4b","extension" : [ {  "url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/xcn-practitioner",  "extension" : [ {"url" : "XCN.3","valueString" : "Lalitha"  } ]} ],"identifier" : [ {  "value" : "HVR0@cdc.gov"} ],"name" : [ {  "family" : "Gade",  "given" : [ "Lalitha" ]} ]  }}, {  "fullUrl" : "PractitionerRole/1713968212584807000.7fb41b30-fd79-4919-8d2c-488beddc31bb",  "resource" : {"resourceType" : "PractitionerRole","id" : "1713968212584807000.7fb41b30-fd79-4919-8d2c-488beddc31bb","practitioner" : {  "reference" : "Practitioner/1713968212585353000.8a2be81e-25f1-439f-82da-5d8c3f1edc4b"},"code" : [ {  "coding" : [ {"system" : "http://terminology.hl7.org/CodeSystem/practitioner-role","code" : "responsibleObserver"  } ]} ]  }}, {  "fullUrl" : "Organization/1713968212587709000.22063778-4a51-40d2-9a8c-c173faa87849",  "resource" : {"resourceType" : "Organization","id" : "1713968212587709000.22063778-4a51-40d2-9a8c-c173faa87849","extension" : [ {  "url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/organization-name-type",  "valueCoding" : {"extension" : [ {  "url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/cwe-coding",  "valueCodeableConcept" : {"extension" : [ {  "url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/hl7v2Field",  "valueString" : "XON.2"} ],"coding" : [ {  "extension" : [ {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/cwe-coding","valueString" : "coding"  } ],  "code" : "L"} ]  }} ],"code" : "L"  }}, {  "url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/xon-organization",  "extension" : [ {"url" : "XON.10","valueString" : "11D0668319"  } ]}, {  "url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/hl7v2Field",  "valueString" : "OBX.25"} ],"identifier" : [ {  "extension" : [ {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/assigning-authority","extension" : [ {  "url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/namespace-id",  "valueString" : "CLIA"}, {  "url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/universal-id",  "valueString" : "2.16.840.1.113883.4.7"}, {  "url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/universal-id-type",  "valueCode" : "ISO"} ]  } ],  "type" : {"coding" : [ {  "extension" : [ {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/code-index-name","valueString" : "identifier"  } ],  "system" : "http://terminology.hl7.org/CodeSystem/v2-0203",  "code" : "XX"} ]  },  "value" : "11D0668319"} ],"name" : "Centers for Disease Control and Prevention","address" : [ {  "extension" : [ {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/xad-address","extension" : [ {  "url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/sad-address-line",  "extension" : [ {"url" : "SAD.1","valueString" : "1600 Clifton Rd"  } ]}, {  "url" : "XAD.7",  "valueCode" : "B"} ]  } ],  "use" : "work",  "line" : [ "1600 Clifton Rd" ],  "city" : "Atlanta",  "state" : "GA",  "postalCode" : "30329",  "country" : "USA"} ]  }}, {  "fullUrl" : "Specimen/1713968212782446000.a37c4111-0390-4469-8170-634609215f3f",  "resource" : {"resourceType" : "Specimen","id" : "1713968212782446000.a37c4111-0390-4469-8170-634609215f3f","extension" : [ {  "url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/hl7v2Segment",  "valueString" : "OBR"} ]  }}, {  "fullUrl" : "Specimen/1713968212786590000.8e7467c1-4c49-4d35-b202-af950b5d50c8",  "resource" : {"resourceType" : "Specimen","id" : "1713968212786590000.8e7467c1-4c49-4d35-b202-af950b5d50c8","extension" : [ {  "url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/hl7v2Segment",  "valueString" : "SPM"} ],"identifier" : [ {  "extension" : [ {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/namespace-id","valueString" : "SPHL-000034"  }, {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/universal-id","valueString" : "2.16.840.1.114222.4.1.3661"  }, {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/universal-id-type","valueString" : "ISO"  }, {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/filler-assigned-identifier","extension" : [ {  "url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/entity-identifier",  "valueString" : "3003786103"}, {  "url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/namespace-id",  "valueString" : "STARLIMS.CDC.Stag"}, {  "url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/universal-id",  "valueString" : "2.16.840.1.114222.4.3.3.2.1.2"}, {  "url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/universal-id-type",  "valueString" : "ISO"} ]  }, {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/hl7v2Component","valueString" : "SPM.2.1"  } ],  "type" : {"coding" : [ {  "system" : "http://terminology.hl7.org/CodeSystem/v2-0203",  "code" : "PGN"} ]  },  "value" : "230011927"}, {  "extension" : [ {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/namespace-id","valueString" : "STARLIMS.CDC.Stag"  }, {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/universal-id","valueString" : "2.16.840.1.114222.4.3.3.2.1.2"  }, {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/universal-id-type","valueString" : "ISO"  }, {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/placer-assigned-identifier","extension" : [ {  "url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/entity-identifier",  "valueString" : "230011927"}, {  "url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/namespace-id",  "valueString" : "SPHL-000034"}, {  "url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/universal-id",  "valueString" : "2.16.840.1.114222.4.1.3661"}, {  "url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/universal-id-type",  "valueString" : "ISO"} ]  }, {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/hl7v2Component","valueString" : "SPM.2.2"  } ],  "type" : {"coding" : [ {  "system" : "http://terminology.hl7.org/CodeSystem/v2-0203",  "code" : "FGN"} ]  },  "value" : "3003786103"} ],"type" : {  "coding" : [ {"extension" : [ {  "url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/cwe-coding",  "valueString" : "coding"}, {  "url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/cwe-coding-system",  "valueString" : "SCT"} ],"system" : "http://snomed.info/sct","version" : "0912017","code" : "119365002","display" : "Specimen from wound"  }, {"extension" : [ {  "url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/cwe-coding",  "valueString" : "alt-coding"}, {  "url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/cwe-coding-system",  "valueString" : "L"} ],"system" : "https://terminology.hl7.org/CodeSystem-v2-0396.html#v2-0396-99zzzorL","version" : "Adobe_Code","code" : "WND","display" : "Wound"  } ],  "text" : "Wound"},"receivedTime" : "2023-04-21T12:41:50Z","_receivedTime" : {  "extension" : [ {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/hl7v2-date-time","valueString" : "20230421124150"  } ]},"collection" : {  "collectedDateTime" : "2023-03-22",  "_collectedDateTime" : {"extension" : [ {  "url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/hl7v2-date-time",  "valueString" : "20230322"} ]  },  "bodySite" : {"coding" : [ {  "extension" : [ {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/cwe-coding","valueString" : "coding"  }, {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/cwe-coding-system","valueString" : "SCT"  } ],  "system" : "http://snomed.info/sct",  "version" : "09012017",  "code" : "56459004",  "display" : "Foot"}, {  "extension" : [ {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/cwe-coding","valueString" : "alt-coding"  }, {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/cwe-coding-system","valueString" : "L"  } ],  "system" : "https://terminology.hl7.org/CodeSystem-v2-0396.html#v2-0396-99zzzorL",  "version" : "Adobe_Code",  "code" : "FOT",  "display" : "Foot"} ],"text" : "Foot"  }},"note" : [ {  "extension" : [ {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/hl7v2Field","valueString" : "SPM.14"  } ],  "text" : "Isolate,"} ]  }}, {  "fullUrl" : "ServiceRequest/1713968212800084000.5c54d82a-4926-4727-ac2f-1b2ae4e42a49",  "resource" : {"resourceType" : "ServiceRequest","id" : "1713968212800084000.5c54d82a-4926-4727-ac2f-1b2ae4e42a49","extension" : [ {  "url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/business-event",  "valueCode" : "RE"}, {  "url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/orc-common-order",  "extension" : [ {"url" : "orc-21-ordering-facility-name","valueReference" : {  "reference" : "Organization/1713968212795095000.c3db36c7-dcdb-433c-af71-b0df5ef62a79"}  }, {"url" : "orc-22-ordering-facility-address","valueAddress" : {  "extension" : [ {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/xad-address","extension" : [ {  "url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/sad-address-line",  "extension" : [ {"url" : "SAD.1","valueString" : "601 Robert St. N."  } ]}, {  "url" : "XAD.7",  "valueCode" : "M"} ]  } ],  "type" : "postal",  "line" : [ "601 Robert St. N." ],  "city" : "St. Paul",  "state" : "MN",  "postalCode" : "55164-0899",  "country" : "USA"}  }, {"url" : "orc-24-ordering-provider-address","valueAddress" : {  "extension" : [ {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/xad-address","extension" : [ {  "url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/sad-address-line",  "extension" : [ {"url" : "SAD.1","valueString" : "601 Robert St. N."  } ]}, {  "url" : "XAD.7",  "valueCode" : "M"} ]  } ],  "type" : "postal",  "line" : [ "601 Robert St. N." ],  "city" : "St. Paul",  "state" : "MN",  "postalCode" : "55164-0899",  "country" : "USA"}  }, {"url" : "orc-12-ordering-provider","valueReference" : {  "reference" : "Practitioner/1713968212796961000.6368d3cc-c28e-4b84-bfd7-573e305ab82c"}  } ]}, {  "url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/obr-observation-request",  "extension" : [ {"url" : "OBR.2","valueIdentifier" : {  "extension" : [ {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/assigning-authority","extension" : [ {  "url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/namespace-id",  "valueString" : "SPHL-000034"}, {  "url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/universal-id",  "valueString" : "2.16.840.1.114222.4.1.3661"}, {  "url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/universal-id-type",  "valueCode" : "ISO"} ]  } ],  "value" : "230011927"}  }, {"url" : "OBR.3","valueIdentifier" : {  "extension" : [ {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/assigning-authority","extension" : [ {  "url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/namespace-id",  "valueString" : "STARLIMS.CDC.Stag"}, {  "url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/universal-id",  "valueString" : "2.16.840.1.114222.4.3.3.2.1.2"}, {  "url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/universal-id-type",  "valueCode" : "ISO"} ]  } ],  "value" : "40_3003786103_4988249_1087"}  }, {"url" : "OBR.22","valueString" : "202304271044-0400"  }, {"url" : "OBR.16","valueReference" : {  "reference" : "Practitioner/1713968212798771000.8e4e3827-f9bd-4b5b-9f70-8a9b3730e638"}  }, {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/callback-number","valueContactPoint" : {  "extension" : [ {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/xtn-contact-point","extension" : [ {  "url" : "XTN.2",  "valueString" : "NET"}, {  "url" : "XTN.3",  "valueString" : "Internet"}, {  "url" : "XTN.4",  "valueString" : "Health.idlabreports@state.mn.us"} ]  }, {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/hl7v2Field","valueString" : "OBR.17"  } ],  "system" : "email",  "value" : "Health.idlabreports@state.mn.us"}  } ]} ],"identifier" : [ {  "extension" : [ {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/hl7v2Field","valueString" : "ORC.2"  }, {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/assigning-authority","extension" : [ {  "url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/namespace-id",  "valueString" : "SPHL-000034"}, {  "url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/universal-id",  "valueString" : "2.16.840.1.114222.4.1.3661"}, {  "url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/universal-id-type",  "valueCode" : "ISO"} ]  } ],  "type" : {"coding" : [ {  "system" : "http://terminology.hl7.org/CodeSystem/v2-0203",  "code" : "PLAC"} ]  },  "value" : "230011927"}, {  "extension" : [ {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/hl7v2Field","valueString" : "ORC.3"  }, {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/assigning-authority","extension" : [ {  "url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/namespace-id",  "valueString" : "STARLIMS.CDC.Stag"}, {  "url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/universal-id",  "valueString" : "2.16.840.1.114222.4.3.3.2.1.2"}, {  "url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/universal-id-type",  "valueCode" : "ISO"} ]  } ],  "type" : {"coding" : [ {  "system" : "http://terminology.hl7.org/CodeSystem/v2-0203",  "code" : "FILL"} ]  },  "value" : "40_3003786103_4988249_1087"} ],"status" : "unknown","code" : {  "extension" : [ {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/coding-system-oid","valueOid" : "urn:oid:2.16.840.1.113883.6.1"  } ],  "coding" : [ {"extension" : [ {  "url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/cwe-coding",  "valueString" : "coding"}, {  "url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/cwe-coding-system",  "valueString" : "PLT"} ],"version" : "2.69","code" : "PLT1228","display" : "Mold and Yeast XXX MS.MALDI-TOF"  }, {"extension" : [ {  "url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/cwe-coding",  "valueString" : "secondary-alt-coding"}, {  "url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/cwe-coding-system",  "valueString" : "L"} ],"system" : "https://terminology.hl7.org/CodeSystem-v2-0396.html#v2-0396-99zzzorL","code" : "CDC-10179","display" : "Fungal Identification"  }, {"extension" : [ {  "url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/cwe-coding",  "valueString" : "alt-coding"}, {  "url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/cwe-coding-system",  "valueString" : "L"} ],"system" : "https://terminology.hl7.org/CodeSystem-v2-0396.html#v2-0396-99zzzorL","version" : "v unknown","code" : "1087","display" : "MALDI-TOF-CLIA"  } ]},"subject" : {  "reference" : "Patient/1713968212574383000.b742ca3a-046f-4df2-b916-0ba5d27d3fac"},"requester" : {  "extension" : [ {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/callback-number","valueContactPoint" : {  "extension" : [ {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/xtn-contact-point","extension" : [ {  "url" : "XTN.2",  "valueString" : "NET"}, {  "url" : "XTN.3",  "valueString" : "Internet"}, {  "url" : "XTN.4",  "valueString" : "Health.idlabreports@state.mn.us"} ]  }, {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/hl7v2Field","valueString" : "ORC.14"  } ],  "system" : "email",  "value" : "Health.idlabreports@state.mn.us"}  } ],  "reference" : "PractitionerRole/1713968212788535000.69d5aa56-e0c4-4b33-b1db-573878b3162d"}  }}, {  "fullUrl" : "Practitioner/1713968212790428000.431f363d-6700-4ada-bf66-070758ca2d7a",  "resource" : {"resourceType" : "Practitioner","id" : "1713968212790428000.431f363d-6700-4ada-bf66-070758ca2d7a","extension" : [ {  "url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/assigning-authority",  "extension" : [ {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/namespace-id","valueString" : "STARLIMS.CDC.Stag"  }, {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/universal-id","valueString" : "2.16.840.1.114222.4.3.3.2.1.2"  }, {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/universal-id-type","valueCode" : "ISO"  } ]}, {  "url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/hl7v2Field",  "valueString" : "ORC.12"} ],"identifier" : [ {  "type" : {"coding" : [ {  "extension" : [ {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/codeable-concept-id","valueBoolean" : true  } ],  "code" : "XX"} ]  },  "system" : "STARLIMS.CDC.Stag",  "value" : "SPHL-000034"} ],"name" : [ {  "family" : "MN PHL Division, Minnesota Department of Health"} ],"address" : [ {  "extension" : [ {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/xad-address","extension" : [ {  "url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/sad-address-line",  "extension" : [ {"url" : "SAD.1","valueString" : "601 Robert St. N."  } ]}, {  "url" : "XAD.7",  "valueCode" : "M"} ]  } ],  "type" : "postal",  "line" : [ "601 Robert St. N." ],  "city" : "St. Paul",  "state" : "MN",  "postalCode" : "55164-0899",  "country" : "USA"} ]  }}, {  "fullUrl" : "Organization/1713968212792434000.c487a154-aaae-4f80-9b7b-46b1fa4db039",  "resource" : {"resourceType" : "Organization","id" : "1713968212792434000.c487a154-aaae-4f80-9b7b-46b1fa4db039","extension" : [ {  "url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/organization-name-type",  "valueCoding" : {"extension" : [ {  "url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/cwe-coding",  "valueCodeableConcept" : {"extension" : [ {  "url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/hl7v2Field",  "valueString" : "XON.2"} ],"coding" : [ {  "extension" : [ {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/cwe-coding","valueString" : "coding"  } ],  "code" : "D"} ]  }} ],"code" : "D"  }}, {  "url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/xon-organization",  "extension" : [ {"url" : "XON.10","valueString" : "SPHL-000034"  } ]} ],"identifier" : [ {  "extension" : [ {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/assigning-authority","extension" : [ {  "url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/namespace-id",  "valueString" : "STARLIMS.CDC.Stag"}, {  "url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/universal-id",  "valueString" : "2.16.840.1.114222.4.3.3.2.1.2"}, {  "url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/universal-id-type",  "valueCode" : "ISO"} ]  } ],  "type" : {"coding" : [ {  "extension" : [ {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/code-index-name","valueString" : "identifier"  } ],  "system" : "http://terminology.hl7.org/CodeSystem/v2-0203",  "code" : "XX"} ]  },  "value" : "SPHL-000034"} ],"name" : "MN PHL Division, Minnesota Department of Health","telecom" : [ {  "extension" : [ {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/xtn-contact-point","extension" : [ {  "url" : "XTN.2",  "valueString" : "WPN"}, {  "url" : "XTN.3",  "valueString" : "Internet"}, {  "url" : "XTN.4",  "valueString" : "Health.idlabreports@state.mn.us"} ]  } ],  "system" : "email",  "value" : "Health.idlabreports@state.mn.us",  "use" : "work"} ],"address" : [ {  "extension" : [ {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/xad-address","extension" : [ {  "url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/sad-address-line",  "extension" : [ {"url" : "SAD.1","valueString" : "601 Robert St. N."  } ]}, {  "url" : "XAD.7",  "valueCode" : "M"} ]  } ],  "type" : "postal",  "line" : [ "601 Robert St. N." ],  "city" : "St. Paul",  "state" : "MN",  "postalCode" : "55164-0899",  "country" : "USA"} ]  }}, {  "fullUrl" : "PractitionerRole/1713968212788535000.69d5aa56-e0c4-4b33-b1db-573878b3162d",  "resource" : {"resourceType" : "PractitionerRole","id" : "1713968212788535000.69d5aa56-e0c4-4b33-b1db-573878b3162d","practitioner" : {  "reference" : "Practitioner/1713968212790428000.431f363d-6700-4ada-bf66-070758ca2d7a"},"organization" : {  "reference" : "Organization/1713968212792434000.c487a154-aaae-4f80-9b7b-46b1fa4db039"}  }}, {  "fullUrl" : "Organization/1713968212795095000.c3db36c7-dcdb-433c-af71-b0df5ef62a79",  "resource" : {"resourceType" : "Organization","id" : "1713968212795095000.c3db36c7-dcdb-433c-af71-b0df5ef62a79","extension" : [ {  "url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/organization-name-type",  "valueCoding" : {"extension" : [ {  "url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/cwe-coding",  "valueCodeableConcept" : {"extension" : [ {  "url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/hl7v2Field",  "valueString" : "XON.2"} ],"coding" : [ {  "extension" : [ {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/cwe-coding","valueString" : "coding"  } ],  "code" : "D"} ]  }} ],"code" : "D"  }}, {  "url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/xon-organization",  "extension" : [ {"url" : "XON.10","valueString" : "SPHL-000034"  } ]} ],"identifier" : [ {  "extension" : [ {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/assigning-authority","extension" : [ {  "url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/namespace-id",  "valueString" : "STARLIMS.CDC.Stag"}, {  "url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/universal-id",  "valueString" : "2.16.840.1.114222.4.3.3.2.1.2"}, {  "url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/universal-id-type",  "valueCode" : "ISO"} ]  } ],  "type" : {"coding" : [ {  "extension" : [ {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/code-index-name","valueString" : "identifier"  } ],  "system" : "http://terminology.hl7.org/CodeSystem/v2-0203",  "code" : "XX"} ]  },  "value" : "SPHL-000034"} ],"name" : "MN PHL Division, Minnesota Department of Health"  }}, {  "fullUrl" : "Practitioner/1713968212796961000.6368d3cc-c28e-4b84-bfd7-573e305ab82c",  "resource" : {"resourceType" : "Practitioner","id" : "1713968212796961000.6368d3cc-c28e-4b84-bfd7-573e305ab82c","extension" : [ {  "url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/assigning-authority",  "extension" : [ {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/namespace-id","valueString" : "STARLIMS.CDC.Stag"  }, {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/universal-id","valueString" : "2.16.840.1.114222.4.3.3.2.1.2"  }, {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/universal-id-type","valueCode" : "ISO"  } ]} ],"identifier" : [ {  "type" : {"coding" : [ {  "extension" : [ {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/codeable-concept-id","valueBoolean" : true  } ],  "code" : "XX"} ]  },  "system" : "STARLIMS.CDC.Stag",  "value" : "SPHL-000034"} ],"name" : [ {  "family" : "MN PHL Division, Minnesota Department of Health"} ]  }}, {  "fullUrl" : "Practitioner/1713968212798771000.8e4e3827-f9bd-4b5b-9f70-8a9b3730e638",  "resource" : {"resourceType" : "Practitioner","id" : "1713968212798771000.8e4e3827-f9bd-4b5b-9f70-8a9b3730e638","extension" : [ {  "url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/assigning-authority",  "extension" : [ {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/namespace-id","valueString" : "STARLIMS.CDC.Stag"  }, {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/universal-id","valueString" : "2.16.840.1.114222.4.3.3.2.1.2"  }, {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/universal-id-type","valueCode" : "ISO"  } ]} ],"identifier" : [ {  "type" : {"coding" : [ {  "extension" : [ {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/codeable-concept-id","valueBoolean" : true  } ],  "code" : "XX"} ]  },  "system" : "STARLIMS.CDC.Stag",  "value" : "SPHL-000034"} ],"name" : [ {  "family" : "MN PHL Division, Minnesota Department of Health"} ]  }}, {  "fullUrl" : "DiagnosticReport/1713968212806660000.5d26bd7c-45b6-4943-8b3c-382d83d08688",  "resource" : {"resourceType" : "DiagnosticReport","id" : "1713968212806660000.5d26bd7c-45b6-4943-8b3c-382d83d08688","identifier" : [ {  "extension" : [ {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/hl7v2Field","valueString" : "ORC.2"  }, {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/assigning-authority","extension" : [ {  "url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/namespace-id",  "valueString" : "SPHL-000034"}, {  "url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/universal-id",  "valueString" : "2.16.840.1.114222.4.1.3661"}, {  "url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/universal-id-type",  "valueCode" : "ISO"} ]  } ],  "type" : {"coding" : [ {  "system" : "http://terminology.hl7.org/CodeSystem/v2-0203",  "code" : "PLAC"} ]  },  "value" : "230011927"}, {  "extension" : [ {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/assigning-authority","extension" : [ {  "url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/namespace-id",  "valueString" : "STARLIMS.CDC.Stag"}, {  "url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/universal-id",  "valueString" : "2.16.840.1.114222.4.3.3.2.1.2"}, {  "url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/universal-id-type",  "valueCode" : "ISO"} ]  } ],  "type" : {"coding" : [ {  "system" : "http://terminology.hl7.org/CodeSystem/v2-0203",  "code" : "FILL"} ]  },  "value" : "40_3003786103_4988249_1087"} ],"basedOn" : [ {  "reference" : "ServiceRequest/1713968212800084000.5c54d82a-4926-4727-ac2f-1b2ae4e42a49"} ],"status" : "final","code" : {  "extension" : [ {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/coding-system-oid","valueOid" : "urn:oid:2.16.840.1.113883.6.1"  } ],  "coding" : [ {"extension" : [ {  "url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/cwe-coding",  "valueString" : "coding"}, {  "url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/cwe-coding-system",  "valueString" : "PLT"} ],"version" : "2.69","code" : "PLT1228","display" : "Mold and Yeast XXX MS.MALDI-TOF"  }, {"extension" : [ {  "url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/cwe-coding",  "valueString" : "secondary-alt-coding"}, {  "url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/cwe-coding-system",  "valueString" : "L"} ],"system" : "https://terminology.hl7.org/CodeSystem-v2-0396.html#v2-0396-99zzzorL","code" : "CDC-10179","display" : "Fungal Identification"  }, {"extension" : [ {  "url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/cwe-coding",  "valueString" : "alt-coding"}, {  "url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/cwe-coding-system",  "valueString" : "L"} ],"system" : "https://terminology.hl7.org/CodeSystem-v2-0396.html#v2-0396-99zzzorL","version" : "v unknown","code" : "1087","display" : "MALDI-TOF-CLIA"  } ]},"subject" : {  "reference" : "Patient/1713968212574383000.b742ca3a-046f-4df2-b916-0ba5d27d3fac"},"effectiveDateTime" : "2023-03-22","_effectiveDateTime" : {  "extension" : [ {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/hl7v2-date-time","valueString" : "20230322"  } ]},"issued" : "2023-04-27T10:44:00-04:00","_issued" : {  "extension" : [ {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/hl7v2-date-time","valueString" : "202304271044-0400"  } ]},"specimen" : [ {  "reference" : "Specimen/1713968212786590000.8e7467c1-4c49-4d35-b202-af950b5d50c8"}, {  "reference" : "Specimen/1713968212782446000.a37c4111-0390-4469-8170-634609215f3f"} ],"result" : [ {  "reference" : "Observation/1713968212583840000.717c0592-17e9-4dcd-839f-8438e868ce83"} ]  }} ]  }"""

@Suppress("ktlint:standard:max-line-length")
private const val nistELRHL7RecordWithoutMSH21 =
    """MSH|^~\&#|STARLIMS.CDC.Stag^2.16.840.1.114222.4.3.3.2.1.2^ISO|CDC Atlanta^11D0668319^CLIA|MEDSS-ELR ^2.16.840.1.114222.4.3.3.6.2.1^ISO|MNDOH^2.16.840.1.114222.4.1.3661^ISO|20230501102531-0400||ORU^R01^ORU_R01|3003786103_4988249_33033|T|2.5.1|||NE|NE|USA||||PHLabReport-NoAck^PHIN^2.16.840.1.113883.9.11^ISO
SFT|CDC^^^^^CDC&2.16.840.1.114222.4&ISO^XX^^^CDC CLIA|ELIMS V11|STARLIMS|Binary ID unknown
PID|1||PID03953346^^^STARLIMS.CDC.Stag&2.16.840.1.114222.4.3.3.2.1.2&ISO^PI~10171284^^^SPHL-000034&2.16.840.1.114222.4.1.3661&ISO^PI||^^^^^^U||0000||||^^^^^USA^H
NTE|1|L|SPHL Submitter: MN PHL Division, Minnesota Department of Health, Submitter ID: SPHL-000034, Address: 601 Robert St. N.  St. Paul, Minnesota 55164-0899 United States, Email: Health.idlabreports@state.mn.us, Submitter Patient ID: 10171284, Submitter Alt Patient ID: , Submitter Specimen ID: 230011927, Submitter Alt Specimen ID:|RE^Remark^HL70364^^^^2.5.1^^^^^^^2.16.840.1.113883.12.364
ORC|RE|230011927^SPHL-000034^2.16.840.1.114222.4.1.3661^ISO|40_3003786103_4988249_1087^STARLIMS.CDC.Stag^2.16.840.1.114222.4.3.3.2.1.2^ISO|||||||||SPHL-000034^MN PHL Division, Minnesota Department of Health^^^^^^^STARLIMS.CDC.Stag&2.16.840.1.114222.4.3.3.2.1.2&ISO^^^^XX||^NET^Internet^Health.idlabreports@state.mn.us|||||||MN PHL Division, Minnesota Department of Health^D^^^^STARLIMS.CDC.Stag&2.16.840.1.114222.4.3.3.2.1.2&ISO^XX^^^SPHL-000034|601 Robert St. N.^^St. Paul^MN^55164-0899^USA^M|^WPN^Internet^Health.idlabreports@state.mn.us|601 Robert St. N.^^St. Paul^MN^55164-0899^USA^M
OBR|1|230011927^SPHL-000034^2.16.840.1.114222.4.1.3661^ISO|40_3003786103_4988249_1087^STARLIMS.CDC.Stag^2.16.840.1.114222.4.3.3.2.1.2^ISO|PLT1228^Mold and Yeast XXX MS.MALDI-TOF^PLT^1087^MALDI-TOF-CLIA^L^2.69^v unknown^^CDC-10179^Fungal Identification^L^^2.16.840.1.113883.6.1|||20230322|||||||||SPHL-000034^MN PHL Division, Minnesota Department of Health^^^^^^^STARLIMS.CDC.Stag&2.16.840.1.114222.4.3.3.2.1.2&ISO^^^^XX|^NET^Internet^Health.idlabreports@state.mn.us|||||202304271044-0400|||F
OBX|1|CE|PLT1228^Mold and Yeast XXX MS.MALDI-TOF^PLT^3562^MALDI-TOF-CLIA^L^2.69^v_unknown^MALDI-TOF-CLIA|N8KHKA9H-1|712760003^Candida metapsilosis (organism)^SCT^^^^09012018^^Candida metapsilosis||||||F|||20230322|11D0668319^Centers for Disease Control and Prevention^CLIA^40^Fungus Reference Laboratory^L|HVR0@cdc.gov^Gade^Lalitha|||20230427092900||||Centers for Disease Control and Prevention^L^^^^CLIA&2.16.840.1.113883.4.7&ISO^XX^^^11D0668319|1600 Clifton Rd^^Atlanta^GA^30329^USA^B
SPM|1|230011927&SPHL-000034&2.16.840.1.114222.4.1.3661&ISO^3003786103&STARLIMS.CDC.Stag&2.16.840.1.114222.4.3.3.2.1.2&ISO||119365002^Specimen from wound^SCT^WND^Wound^L^0912017^Adobe_Code^Wound||||56459004^Foot^SCT^FOT^Foot^L^09012017^Adobe_Code^Foot||||||Isolate,|||20230322|20230421124150"""

@Suppress("ktlint:standard:max-line-length")
private const val validRadxMarsHL7Message =
    """MSH|^~\&|MMTC.PROD^2.16.840.1.113883.3.8589.4.2.106.1^ISO|CAREEVOLUTION^00Z0000024^CLIA|AIMS.INTEGRATION.PRD^2.16.840.1.114222.4.3.15.1^ISO|AIMS.PLATFORM^2.16.840.1.114222.4.1.217446^ISO|20240403205305+0000||ORU^R01^ORU_R01|20240403205305_dba7572cc6334f1ea0744c5f235c823e|P|2.5.1|||NE|NE|||||PHLabReport-NoAck^ELR251R1_Rcvr_Prof^2.16.840.1.113883.9.11^ISO
SFT|CAREEVOLUTION|2022|MMTC.PROD|16498||20240402
PID|1||8be6fa3710374dcebe0174e0fd5a1a7c^^^MMTC.PROD&2.16.840.1.113883.3.8589.4.2.106.1&ISO^PI||^^^^^^~^^^^^^||||||^^^^02139^USA||^^^^^111^1111111
ORC|RE||^MMTC.PROD^2.16.840.1.113883.3.8589.4.2.106.1^ISO|||||||||^^||^^^^^^|||||||SA.OTCSelfReport|^^^^02139^^^^|^^^^^^
OBR|1||^MMTC.PROD^2.16.840.1.113883.3.8589.4.2.106.1^ISO|94558-4^SARS-CoV-2 (COVID-19) Ag [Presence] in Respiratory specimen by Rapid immunoassay^LN^^^^2.71|||20240403120000-0400|||||||||^^|^^^^^^|||||20240403120000-0400|||F
OBX|1|CWE|94558-4^SARS-CoV-2 (COVID-19) Ag [Presence] in Respiratory specimen by Rapid immunoassay^LN^^^^2.71||260373001^Detected^SCT^^^^20200901||||||F||||00Z0000042||BinaxNOW COVID-19 Antigen Self Test_Abbott Diagnostics Scarborough, Inc._EUA^^99ELR^^^^Vunknown||20240403120000-0400||||SA.OTCSelfReport^^^^^&2.16.840.1.113883.3.8589.4.1.152&ISO^XX^^^00Z0000042|
NTE|1|L|Note
OBX|2|NM|35659-2^Age at specimen collection^LN^^^^2.71||24|a^year^UCUM^^^^2.1|||||F||||00Z0000042||||||||SA.OTCSelfReport^^^^^&2.16.840.1.113883.3.8589.4.1.152&ISO^XX^^^00Z0000042||||||QST
SPM|1|^dba7572cc6334f1ea0744c5f235c823e&MMTC.PROD&2.16.840.1.113883.3.8589.4.2.106.1&ISO||697989009^Anterior nares swab^SCT^^^^20200901|||||||||||||20240403120000-0400|20240403120000-0400"""

@Suppress("ktlint:standard:max-line-length")
private const val validRadxMarsHL7MessageConverted =
    """{"resourceType" : "Bundle","id" : "1715168012009230000.244bbb8e-fa7e-40e0-b296-f5cdb9d0c818","meta" : {"lastUpdated" : "2024-05-08T07:33:32.015-04:00"},"identifier" : {"system" : "https://reportstream.cdc.gov/prime-router","value" : "20240403205305_dba7572cc6334f1ea0744c5f235c823e"},"type" : "message","timestamp" : "2024-04-03T16:53:05.000-04:00","entry" : [ {"fullUrl" : "MessageHeader/df373c48-bfb2-36b0-b63c-5be13bc5d051","resource" : {"resourceType" : "MessageHeader","id" : "df373c48-bfb2-36b0-b63c-5be13bc5d051","meta" : {"tag" : [ {"system" : "http://terminology.hl7.org/CodeSystem/v2-0103","code" : "P"} ]},"extension" : [ {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/msh-message-header","extension" : [ {"url" : "MSH.7","valueString" : "20240403205305+0000"}, {"url" : "MSH.15","valueString" : "NE"}, {"url" : "MSH.16","valueString" : "NE"}, {"url" : "MSH.21","valueIdentifier" : {"extension" : [ {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/assigning-authority","extension" : [ {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/namespace-id","valueString" : "ELR251R1_Rcvr_Prof"}, {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/universal-id","valueString" : "2.16.840.1.113883.9.11"}, {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/universal-id-type","valueCode" : "ISO"} ]} ],"value" : "PHLabReport-NoAck"}} ]} ],"eventCoding" : {"system" : "http://terminology.hl7.org/CodeSystem/v2-0003","code" : "R01","display" : "ORU^R01^ORU_R01"},"destination" : [ {"extension" : [ {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/universal-id","valueString" : "2.16.840.1.114222.4.3.15.1"}, {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/universal-id-type","valueString" : "ISO"}, {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/hl7v2Field","valueString" : "MSH.5"} ],"name" : "AIMS.INTEGRATION.PRD","endpoint" : "urn:oid:2.16.840.1.114222.4.3.15.1","receiver" : {"reference" : "Organization/1715168012078111000.c75985db-2489-44ee-bb31-d5721bb660a3"}} ],"sender" : {"reference" : "Organization/1715168012054756000.3c1c2370-09b5-42d4-94b1-e62f9047d51e"},"source" : {"extension" : [ {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/namespace-id","valueString" : "MMTC.PROD"}, {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/universal-id","valueString" : "2.16.840.1.113883.3.8589.4.2.106.1"}, {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/universal-id-type","valueString" : "ISO"}, {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/hl7v2Field","valueString" : "MSH.3"} ],"software" : "MMTC.PROD","version" : "2022","endpoint" : "urn:oid:2.16.840.1.113883.3.8589.4.2.106.1"}}}, {"fullUrl" : "Organization/1715168012054756000.3c1c2370-09b5-42d4-94b1-e62f9047d51e","resource" : {"resourceType" : "Organization","id" : "1715168012054756000.3c1c2370-09b5-42d4-94b1-e62f9047d51e","identifier" : [ {"extension" : [ {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/hl7v2Field","valueString" : "HD.1"} ],"value" : "CAREEVOLUTION"}, {"extension" : [ {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/hl7v2Field","valueString" : "HD.2,HD.3"} ],"type" : {"coding" : [ {"system" : "http://terminology.hl7.org/CodeSystem/v2-0301","code" : "CLIA"} ]},"value" : "00Z0000024"} ]}}, {"fullUrl" : "Organization/1715168012078111000.c75985db-2489-44ee-bb31-d5721bb660a3","resource" : {"resourceType" : "Organization","id" : "1715168012078111000.c75985db-2489-44ee-bb31-d5721bb660a3","extension" : [ {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/hl7v2Field","valueString" : "MSH.6"} ],"identifier" : [ {"extension" : [ {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/hl7v2Field","valueString" : "HD.1"} ],"value" : "AIMS.PLATFORM"}, {"extension" : [ {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/hl7v2Field","valueString" : "HD.2,HD.3"} ],"type" : {"coding" : [ {"system" : "http://terminology.hl7.org/CodeSystem/v2-0301","code" : "ISO"} ]},"system" : "urn:ietf:rfc:3986","value" : "2.16.840.1.114222.4.1.217446"} ]}}, {"fullUrl" : "Provenance/1715168012400304000.c5b82346-7841-4a1f-9d7e-f969a2585b14","resource" : {"resourceType" : "Provenance","id" : "1715168012400304000.c5b82346-7841-4a1f-9d7e-f969a2585b14","target" : [ {"reference" : "MessageHeader/df373c48-bfb2-36b0-b63c-5be13bc5d051"}, {"reference" : "DiagnosticReport/1715168012622030000.757c7fba-9472-4613-bc21-adacf9dffca0"} ],"recorded" : "2024-04-03T20:53:05Z","activity" : {"coding" : [ {"display" : "ORU^R01^ORU_R01"} ]},"agent" : [ {"type" : {"coding" : [ {"system" : "http://terminology.hl7.org/CodeSystem/provenance-participant-type","code" : "author"} ]},"who" : {"reference" : "Organization/1715168012399724000.ca7f0575-125c-42d7-9df7-6fcae8879d78"}} ],"entity" : [ {"role" : "source","what" : {"reference" : "Device/1715168012403845000.2f280069-344e-490b-9660-ef6f272c1181"}} ]}}, {"fullUrl" : "Organization/1715168012399724000.ca7f0575-125c-42d7-9df7-6fcae8879d78","resource" : {"resourceType" : "Organization","id" : "1715168012399724000.ca7f0575-125c-42d7-9df7-6fcae8879d78","identifier" : [ {"extension" : [ {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/hl7v2Field","valueString" : "HD.1"} ],"value" : "CAREEVOLUTION"}, {"extension" : [ {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/hl7v2Field","valueString" : "HD.2,HD.3"} ],"type" : {"coding" : [ {"system" : "http://terminology.hl7.org/CodeSystem/v2-0301","code" : "CLIA"} ]},"value" : "00Z0000024"} ]}}, {"fullUrl" : "Organization/1715168012403476000.9fcbde80-ba22-44fb-aaa8-a3f493bc1d98","resource" : {"resourceType" : "Organization","id" : "1715168012403476000.9fcbde80-ba22-44fb-aaa8-a3f493bc1d98","name" : "CAREEVOLUTION"}}, {"fullUrl" : "Device/1715168012403845000.2f280069-344e-490b-9660-ef6f272c1181","resource" : {"resourceType" : "Device","id" : "1715168012403845000.2f280069-344e-490b-9660-ef6f272c1181","extension" : [ {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/software-vendor-org","valueReference" : {"reference" : "Organization/1715168012403476000.9fcbde80-ba22-44fb-aaa8-a3f493bc1d98"}} ],"manufacturer" : "CAREEVOLUTION","deviceName" : [ {"name" : "MMTC.PROD","type" : "manufacturer-name"} ],"modelNumber" : "16498","version" : [ {"extension" : [ {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/software-install-date","valueDateTime" : "2024-04-02","_valueDateTime" : {"extension" : [ {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/hl7v2-date-time","valueString" : "20240402"} ]}} ],"value" : "2022"} ]}}, {"fullUrl" : "Provenance/1715168012410235000.1d1c0f5d-e9d9-41d5-879f-1a20a867eef5","resource" : {"resourceType" : "Provenance","id" : "1715168012410235000.1d1c0f5d-e9d9-41d5-879f-1a20a867eef5","recorded" : "2024-05-08T07:33:32Z","policy" : [ "http://hl7.org/fhir/uv/v2mappings/message-oru-r01-to-bundle" ],"activity" : {"coding" : [ {"code" : "v2-FHIR transformation"} ]},"agent" : [ {"type" : {"coding" : [ {"system" : "http://terminology.hl7.org/CodeSystem/provenance-participant-type","code" : "assembler"} ]},"who" : {"reference" : "Organization/1715168012409936000.89db2a7e-c562-4c89-a6e7-fd8bb07da3ce"}} ]}}, {"fullUrl" : "Organization/1715168012409936000.89db2a7e-c562-4c89-a6e7-fd8bb07da3ce","resource" : {"resourceType" : "Organization","id" : "1715168012409936000.89db2a7e-c562-4c89-a6e7-fd8bb07da3ce","identifier" : [ {"value" : "CDC PRIME - Atlanta"}, {"type" : {"coding" : [ {"system" : "http://terminology.hl7.org/CodeSystem/v2-0301"} ]},"system" : "urn:ietf:rfc:3986","value" : "2.16.840.1.114222.4.1.237821"} ]}}, {"fullUrl" : "Patient/1715168012431490000.ec379448-a5f9-4d36-b79d-782577a7e876","resource" : {"resourceType" : "Patient","id" : "1715168012431490000.ec379448-a5f9-4d36-b79d-782577a7e876","identifier" : [ {"extension" : [ {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/cx-identifier","extension" : [ {"url" : "CX.5","valueString" : "PI"} ]}, {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/hl7v2Field","valueString" : "PID.3"} ],"type" : {"coding" : [ {"code" : "PI"} ]},"system" : "MMTC.PROD","_system" : {"extension" : [ {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/namespace-id","valueString" : "MMTC.PROD"}, {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/universal-id","valueString" : "2.16.840.1.113883.3.8589.4.2.106.1"}, {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/universal-id-type","valueString" : "ISO"} ]},"value" : "8be6fa3710374dcebe0174e0fd5a1a7c"} ],"name" : [ { }, { } ],"telecom" : [ {"extension" : [ {"url" : "http://hl7.org/fhir/StructureDefinition/contactpoint-area","valueString" : "111"}, {"url" : "http://hl7.org/fhir/StructureDefinition/contactpoint-local","valueString" : "1111111"}, {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/xtn-contact-point","extension" : [ {"url" : "XTN.7","valueString" : "1111111"} ]} ],"_system" : {"extension" : [ {"url" : "http://hl7.org/fhir/StructureDefinition/data-absent-reason","valueCode" : "unknown"} ]},"use" : "home"} ],"address" : [ {"postalCode" : "02139","country" : "USA"} ]}}, {"fullUrl" : "Provenance/1715168012433157000.2fd33707-5dfa-463d-9795-3cb3f02467b3","resource" : {"resourceType" : "Provenance","id" : "1715168012433157000.2fd33707-5dfa-463d-9795-3cb3f02467b3","target" : [ {"reference" : "Patient/1715168012431490000.ec379448-a5f9-4d36-b79d-782577a7e876"} ],"recorded" : "2024-05-08T07:33:32Z","activity" : {"coding" : [ {"system" : "https://terminology.hl7.org/CodeSystem/v3-DataOperation","code" : "UPDATE"} ]}}}, {"fullUrl" : "Observation/1715168012438166000.6725833b-086d-48c8-922d-13daa66fa9de","resource" : {"resourceType" : "Observation","id" : "1715168012438166000.6725833b-086d-48c8-922d-13daa66fa9de","extension" : [ {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/analysis-date-time","valueDateTime" : "2024-04-03T12:00:00-04:00","_valueDateTime" : {"extension" : [ {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/hl7v2-date-time","valueString" : "20240403120000-0400"} ]}}, {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/obx-observation","extension" : [ {"url" : "OBX.2","valueId" : "CWE"}, {"url" : "OBX.11","valueString" : "F"}, {"url" : "OBX.17","valueCodeableConcept" : {"coding" : [ {"extension" : [ {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/cwe-coding","valueString" : "coding"}, {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/cwe-coding-system","valueString" : "99ELR"} ],"version" : "Vunknown","code" : "BinaxNOW COVID-19 Antigen Self Test_Abbott Diagnostics Scarborough, Inc._EUA"} ]}} ]} ],"status" : "final","code" : {"coding" : [ {"extension" : [ {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/cwe-coding","valueString" : "coding"}, {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/cwe-coding-system","valueString" : "LN"} ],"system" : "http://loinc.org","version" : "2.71","code" : "94558-4","display" : "SARS-CoV-2 (COVID-19) Ag [Presence] in Respiratory specimen by Rapid immunoassay"} ]},"subject" : {"reference" : "Patient/1715168012431490000.ec379448-a5f9-4d36-b79d-782577a7e876"},"performer" : [ {"reference" : "Organization/1715168012438886000.c1b2945b-fae8-41d9-a215-7a4714a2ffb9"}, {"reference" : "Organization/1715168012440039000.ee69937c-6764-4973-a958-72ec0826f7ea"} ],"valueCodeableConcept" : {"coding" : [ {"extension" : [ {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/cwe-coding","valueString" : "coding"}, {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/cwe-coding-system","valueString" : "SCT"} ],"system" : "http://snomed.info/sct","version" : "20200901","code" : "260373001","display" : "Detected"} ]},"note" : [ {"extension" : [ {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/note-comment","valueId" : "Note"}, {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/note-source","valueId" : "L"} ],"text" : "Note"} ],"method" : {"coding" : [ {"extension" : [ {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/cwe-coding","valueString" : "coding"}, {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/cwe-coding-system","valueString" : "99ELR"} ],"version" : "Vunknown","code" : "BinaxNOW COVID-19 Antigen Self Test_Abbott Diagnostics Scarborough, Inc._EUA"} ]}}}, {"fullUrl" : "Organization/1715168012438886000.c1b2945b-fae8-41d9-a215-7a4714a2ffb9","resource" : {"resourceType" : "Organization","id" : "1715168012438886000.c1b2945b-fae8-41d9-a215-7a4714a2ffb9","extension" : [ {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/cwe-organization","valueCodeableConcept" : {"coding" : [ {"extension" : [ {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/cwe-coding","valueString" : "coding"} ],"code" : "00Z0000042"} ]}}, {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/hl7v2Field","valueString" : "OBX.15"} ],"identifier" : [ {"value" : "00Z0000042"} ]}}, {"fullUrl" : "Organization/1715168012440039000.ee69937c-6764-4973-a958-72ec0826f7ea","resource" : {"resourceType" : "Organization","id" : "1715168012440039000.ee69937c-6764-4973-a958-72ec0826f7ea","extension" : [ {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/xon-organization","extension" : [ {"url" : "XON.10","valueString" : "00Z0000042"} ]}, {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/hl7v2Field","valueString" : "OBX.25"} ],"identifier" : [ {"extension" : [ {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/assigning-authority","extension" : [ {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/namespace-id"}, {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/universal-id","valueString" : "2.16.840.1.113883.3.8589.4.1.152"}, {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/universal-id-type","valueCode" : "ISO"} ]} ],"type" : {"coding" : [ {"extension" : [ {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/code-index-name","valueString" : "identifier"} ],"system" : "http://terminology.hl7.org/CodeSystem/v2-0203","code" : "XX"} ]},"value" : "00Z0000042"} ],"name" : "SA.OTCSelfReport"}}, {"fullUrl" : "Observation/1715168012445074000.63d7c3df-2ae8-4613-ba0f-d1410dd4add0","resource" : {"resourceType" : "Observation","id" : "1715168012445074000.63d7c3df-2ae8-4613-ba0f-d1410dd4add0","extension" : [ {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/obx-observation","extension" : [ {"url" : "OBX.2","valueId" : "NM"}, {"url" : "OBX.6","valueCodeableConcept" : {"coding" : [ {"extension" : [ {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/cwe-coding","valueString" : "coding"}, {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/cwe-coding-system","valueString" : "UCUM"} ],"system" : "http://unitsofmeasure.org","version" : "2.1","code" : "a","display" : "year"} ]}}, {"url" : "OBX.29","valueId" : "QST"}, {"url" : "OBX.11","valueString" : "F"} ]} ],"status" : "final","code" : {"coding" : [ {"extension" : [ {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/cwe-coding","valueString" : "coding"}, {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/cwe-coding-system","valueString" : "LN"} ],"system" : "http://loinc.org","version" : "2.71","code" : "35659-2","display" : "Age at specimen collection"} ]},"subject" : {"reference" : "Patient/1715168012431490000.ec379448-a5f9-4d36-b79d-782577a7e876"},"performer" : [ {"reference" : "Organization/1715168012445629000.6b722869-36dc-4d62-a4f3-41c9fa0c724c"}, {"reference" : "Organization/1715168012446477000.65e20829-2c98-4896-bf2c-db204ec2a828"} ],"valueQuantity" : {"value" : 24,"unit" : "year","system" : "UCUM","code" : "a"}}}, {"fullUrl" : "Organization/1715168012445629000.6b722869-36dc-4d62-a4f3-41c9fa0c724c","resource" : {"resourceType" : "Organization","id" : "1715168012445629000.6b722869-36dc-4d62-a4f3-41c9fa0c724c","extension" : [ {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/cwe-organization","valueCodeableConcept" : {"coding" : [ {"extension" : [ {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/cwe-coding","valueString" : "coding"} ],"code" : "00Z0000042"} ]}}, {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/hl7v2Field","valueString" : "OBX.15"} ],"identifier" : [ {"value" : "00Z0000042"} ]}}, {"fullUrl" : "Organization/1715168012446477000.65e20829-2c98-4896-bf2c-db204ec2a828","resource" : {"resourceType" : "Organization","id" : "1715168012446477000.65e20829-2c98-4896-bf2c-db204ec2a828","extension" : [ {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/xon-organization","extension" : [ {"url" : "XON.10","valueString" : "00Z0000042"} ]}, {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/hl7v2Field","valueString" : "OBX.25"} ],"identifier" : [ {"extension" : [ {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/assigning-authority","extension" : [ {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/namespace-id"}, {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/universal-id","valueString" : "2.16.840.1.113883.3.8589.4.1.152"}, {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/universal-id-type","valueCode" : "ISO"} ]} ],"type" : {"coding" : [ {"extension" : [ {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/code-index-name","valueString" : "identifier"} ],"system" : "http://terminology.hl7.org/CodeSystem/v2-0203","code" : "XX"} ]},"value" : "00Z0000042"} ],"name" : "SA.OTCSelfReport"}}, {"fullUrl" : "Specimen/1715168012607972000.b0dcab8e-aec8-4709-bd5c-87d8dce5555d","resource" : {"resourceType" : "Specimen","id" : "1715168012607972000.b0dcab8e-aec8-4709-bd5c-87d8dce5555d","extension" : [ {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/hl7v2Segment","valueString" : "OBR"} ]}}, {"fullUrl" : "Specimen/1715168012610330000.e620cc10-e3bf-4939-9ed4-7009dfb60bf9","resource" : {"resourceType" : "Specimen","id" : "1715168012610330000.e620cc10-e3bf-4939-9ed4-7009dfb60bf9","extension" : [ {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/hl7v2Segment","valueString" : "SPM"} ],"identifier" : [ {"extension" : [ {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/namespace-id","valueString" : "MMTC.PROD"}, {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/universal-id","valueString" : "2.16.840.1.113883.3.8589.4.2.106.1"}, {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/universal-id-type","valueString" : "ISO"}, {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/hl7v2Component","valueString" : "SPM.2.2"} ],"type" : {"coding" : [ {"system" : "http://terminology.hl7.org/CodeSystem/v2-0203","code" : "FGN"} ]},"value" : "dba7572cc6334f1ea0744c5f235c823e"} ],"type" : {"coding" : [ {"extension" : [ {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/cwe-coding","valueString" : "coding"}, {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/cwe-coding-system","valueString" : "SCT"} ],"system" : "http://snomed.info/sct","version" : "20200901","code" : "697989009","display" : "Anterior nares swab"} ]},"receivedTime" : "2024-04-03T12:00:00-04:00","_receivedTime" : {"extension" : [ {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/hl7v2-date-time","valueString" : "20240403120000-0400"} ]},"collection" : {"collectedDateTime" : "2024-04-03T12:00:00-04:00","_collectedDateTime" : {"extension" : [ {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/hl7v2-date-time","valueString" : "20240403120000-0400"} ]}}}}, {"fullUrl" : "ServiceRequest/1715168012618428000.84205e35-32db-48b6-b29a-23966d7f41e8","resource" : {"resourceType" : "ServiceRequest","id" : "1715168012618428000.84205e35-32db-48b6-b29a-23966d7f41e8","extension" : [ {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/business-event","valueCode" : "RE"}, {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/orc-common-order","extension" : [ {"url" : "orc-21-ordering-facility-name","valueReference" : {"reference" : "Organization/1715168012615566000.cf7b15e3-a92a-44e9-b996-128699594bed"}}, {"url" : "orc-22-ordering-facility-address","valueAddress" : {"postalCode" : "02139"}}, {"url" : "orc-12-ordering-provider","valueReference" : {"reference" : "Practitioner/1715168012616416000.c0edc16f-fbbd-40ed-9551-eeee647e8250"}} ]}, {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/obr-observation-request","extension" : [ {"url" : "OBR.3","valueIdentifier" : {"extension" : [ {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/assigning-authority","extension" : [ {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/namespace-id","valueString" : "MMTC.PROD"}, {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/universal-id","valueString" : "2.16.840.1.113883.3.8589.4.2.106.1"}, {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/universal-id-type","valueCode" : "ISO"} ]} ]}}, {"url" : "OBR.22","valueString" : "20240403120000-0400"}, {"url" : "OBR.16","valueReference" : {"reference" : "Practitioner/1715168012617273000.0ba14302-ee75-4c2f-9f96-25dfc58aaf5b"}}, {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/callback-number","valueContactPoint" : {"extension" : [ {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/hl7v2Field","valueString" : "OBR.17"} ],"_system" : {"extension" : [ {"url" : "http://hl7.org/fhir/StructureDefinition/data-absent-reason","valueCode" : "unknown"} ]}}} ]} ],"identifier" : [ {"extension" : [ {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/hl7v2Field","valueString" : "ORC.3"}, {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/assigning-authority","extension" : [ {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/namespace-id","valueString" : "MMTC.PROD"}, {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/universal-id","valueString" : "2.16.840.1.113883.3.8589.4.2.106.1"}, {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/universal-id-type","valueCode" : "ISO"} ]} ],"type" : {"coding" : [ {"system" : "http://terminology.hl7.org/CodeSystem/v2-0203","code" : "FILL"} ]}} ],"status" : "unknown","code" : {"coding" : [ {"extension" : [ {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/cwe-coding","valueString" : "coding"}, {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/cwe-coding-system","valueString" : "LN"} ],"system" : "http://loinc.org","version" : "2.71","code" : "94558-4","display" : "SARS-CoV-2 (COVID-19) Ag [Presence] in Respiratory specimen by Rapid immunoassay"} ]},"subject" : {"reference" : "Patient/1715168012431490000.ec379448-a5f9-4d36-b79d-782577a7e876"},"requester" : {"extension" : [ {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/callback-number","valueContactPoint" : {"extension" : [ {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/hl7v2Field","valueString" : "ORC.14"} ],"_system" : {"extension" : [ {"url" : "http://hl7.org/fhir/StructureDefinition/data-absent-reason","valueCode" : "unknown"} ]}}} ],"reference" : "PractitionerRole/1715168012611857000.3b86ddc2-c261-4a64-8c80-e4da5d05c100"}}}, {"fullUrl" : "Practitioner/1715168012612722000.df2ee267-33c2-43aa-9bf5-224f341cd058","resource" : {"resourceType" : "Practitioner","id" : "1715168012612722000.df2ee267-33c2-43aa-9bf5-224f341cd058","extension" : [ {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/hl7v2Field","valueString" : "ORC.12"} ]}}, {"fullUrl" : "Organization/1715168012613584000.f5a02548-3d4a-4e70-9544-6e2e0afba922","resource" : {"resourceType" : "Organization","id" : "1715168012613584000.f5a02548-3d4a-4e70-9544-6e2e0afba922","name" : "SA.OTCSelfReport","telecom" : [ {"_system" : {"extension" : [ {"url" : "http://hl7.org/fhir/StructureDefinition/data-absent-reason","valueCode" : "unknown"} ]}} ],"address" : [ {"postalCode" : "02139"} ]}}, {"fullUrl" : "PractitionerRole/1715168012611857000.3b86ddc2-c261-4a64-8c80-e4da5d05c100","resource" : {"resourceType" : "PractitionerRole","id" : "1715168012611857000.3b86ddc2-c261-4a64-8c80-e4da5d05c100","practitioner" : {"reference" : "Practitioner/1715168012612722000.df2ee267-33c2-43aa-9bf5-224f341cd058"},"organization" : {"reference" : "Organization/1715168012613584000.f5a02548-3d4a-4e70-9544-6e2e0afba922"}}}, {"fullUrl" : "Organization/1715168012615566000.cf7b15e3-a92a-44e9-b996-128699594bed","resource" : {"resourceType" : "Organization","id" : "1715168012615566000.cf7b15e3-a92a-44e9-b996-128699594bed","name" : "SA.OTCSelfReport"}}, {"fullUrl" : "Practitioner/1715168012616416000.c0edc16f-fbbd-40ed-9551-eeee647e8250","resource" : {"resourceType" : "Practitioner","id" : "1715168012616416000.c0edc16f-fbbd-40ed-9551-eeee647e8250"}}, {"fullUrl" : "Practitioner/1715168012617273000.0ba14302-ee75-4c2f-9f96-25dfc58aaf5b","resource" : {"resourceType" : "Practitioner","id" : "1715168012617273000.0ba14302-ee75-4c2f-9f96-25dfc58aaf5b"}}, {"fullUrl" : "DiagnosticReport/1715168012622030000.757c7fba-9472-4613-bc21-adacf9dffca0","resource" : {"resourceType" : "DiagnosticReport","id" : "1715168012622030000.757c7fba-9472-4613-bc21-adacf9dffca0","identifier" : [ {"extension" : [ {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/assigning-authority","extension" : [ {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/namespace-id","valueString" : "MMTC.PROD"}, {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/universal-id","valueString" : "2.16.840.1.113883.3.8589.4.2.106.1"}, {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/universal-id-type","valueCode" : "ISO"} ]} ],"type" : {"coding" : [ {"system" : "http://terminology.hl7.org/CodeSystem/v2-0203","code" : "FILL"} ]}} ],"basedOn" : [ {"reference" : "ServiceRequest/1715168012618428000.84205e35-32db-48b6-b29a-23966d7f41e8"} ],"status" : "final","code" : {"coding" : [ {"extension" : [ {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/cwe-coding","valueString" : "coding"}, {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/cwe-coding-system","valueString" : "LN"} ],"system" : "http://loinc.org","version" : "2.71","code" : "94558-4","display" : "SARS-CoV-2 (COVID-19) Ag [Presence] in Respiratory specimen by Rapid immunoassay"} ]},"subject" : {"reference" : "Patient/1715168012431490000.ec379448-a5f9-4d36-b79d-782577a7e876"},"effectiveDateTime" : "2024-04-03T12:00:00-04:00","_effectiveDateTime" : {"extension" : [ {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/hl7v2-date-time","valueString" : "20240403120000-0400"} ]},"issued" : "2024-04-03T12:00:00-04:00","_issued" : {"extension" : [ {"url" : "https://reportstream.cdc.gov/fhir/StructureDefinition/hl7v2-date-time","valueString" : "20240403120000-0400"} ]},"specimen" : [ {"reference" : "Specimen/1715168012610330000.e620cc10-e3bf-4939-9ed4-7009dfb60bf9"}, {"reference" : "Specimen/1715168012607972000.b0dcab8e-aec8-4709-bd5c-87d8dce5555d"} ],"result" : [ {"reference" : "Observation/1715168012438166000.6725833b-086d-48c8-922d-13daa66fa9de"}, {"reference" : "Observation/1715168012445074000.63d7c3df-2ae8-4613-ba0f-d1410dd4add0"}]}}]}"""

@Suppress("ktlint:standard:max-line-length")
private const val invalidRadxMarsHL7Message =
    """MSH|^~\&|MMTC.PROD^2.16.840.1.113883.3.8589.4.2.106.1^ISO|CAREEVOLUTION^00Z0000024^CLIA|AIMS.INTEGRATION.PRD^2.16.840.1.114222.4.3.15.1^ISO|AIMS.PLATFORM^2.16.840.1.114222.4.1.217446^ISO|20240403205305+0000||ORU^R01^ORU_R01|20240403205305_dba7572cc6334f1ea0744c5f235c823e|P|2.5.1|||NE|NE|||||PHLabReport-NoAck^ELR251R1_Rcvr_Prof^2.16.840.1.113883.9.11^ISO
SFT|CAREEVOLUTION|2022|MMTC.PROD|16498||20240402
PID|1||8be6fa3710374dcebe0174e0fd5a1a7c^^^MMTC.PROD&2.16.840.1.113883.3.8589.4.2.106.1&ISO^PI||^^^^^^~^^^^^^||||||^^^^02139^USA||^^^^^111^1111111
ORC|RE||^MMTC.PROD^2.16.840.1.113883.3.8589.4.2.106.1^ISO|||||||||^^||^^^^^^|||||||SA.OTCSelfReport|^^^^02139^^^^|^^^^^^
OBR|1||^MMTC.PROD^2.16.840.1.113883.3.8589.4.2.106.1^ISO|94558-4^SARS-CoV-2 (COVID-19) Ag [Presence] in Respiratory specimen by Rapid immunoassay^LN^^^^2.71|||20240403120000|||||||||^^|^^^^^^|||||20240403120000|||F
OBX|1|CWE|94558-4^SARS-CoV-2 (COVID-19) Ag [Presence] in Respiratory specimen by Rapid immunoassay^LN^^^^2.71||260373001^Detected^SCT^^^^20200901||||||F||||00Z0000042||BinaxNOW COVID-19 Antigen Self Test_Abbott Diagnostics Scarborough, Inc._EUA^^99ELR^^^^Vunknown||20240403120000||||SA.OTCSelfReport^^^^^&2.16.840.1.113883.3.8589.4.1.152&ISO^XX^^^00Z0000042|
NTE|1|L|Note
OBX|2|NM|35659-2^Age at specimen collection^LN^^^^2.71||24|a^year^UCUM^^^^2.1|||||F||||00Z0000042||||||||SA.OTCSelfReport^^^^^&2.16.840.1.113883.3.8589.4.1.152&ISO^XX^^^00Z0000042||||||QST
SPM|1|^dba7572cc6334f1ea0744c5f235c823e&MMTC.PROD&2.16.840.1.113883.3.8589.4.2.106.1&ISO||697989009^Anterior nares swab^SCT^^^^20200901|||||||||||||20240403120000-0400|20240403120000-0400"""

@Testcontainers
@ExtendWith(ReportStreamTestDatabaseSetupExtension::class)
class FhirFunctionIntegrationTests() {

    @Container
    val azuriteContainer = TestcontainersUtils.createAzuriteContainer(
        customImageName = "azurite_fhirfunctionintegration1",
        customEnv = mapOf(
            "AZURITE_ACCOUNTS" to "devstoreaccount1:keydevstoreaccount1"
        )
    )

    val oneOrganization = DeepOrganization(
        "phd", "test", Organization.Jurisdiction.FEDERAL,
        receivers = listOf(
            Receiver(
                "elr",
                "phd",
                Topic.TEST,
                CustomerStatus.INACTIVE,
                "one",
                timing = Receiver.Timing(numberPerDay = 1, maxReportCount = 1, whenEmpty = Receiver.WhenEmpty())
            ),
            Receiver(
                "elr2",
                "phd",
                Topic.FULL_ELR,
                CustomerStatus.ACTIVE,
                "classpath:/metadata/hl7_mapping/ORU_R01/ORU_R01-base.yml",
                timing = Receiver.Timing(numberPerDay = 1, maxReportCount = 1, whenEmpty = Receiver.WhenEmpty()),
                jurisdictionalFilter = listOf("true"),
                qualityFilter = listOf("true"),
                processingModeFilter = listOf("true"),
                format = Report.Format.HL7,
            )
        ),
    )

    private fun makeWorkflowEngine(
        metadata: Metadata,
        settings: SettingsProvider,
        databaseAccess: DatabaseAccess,
    ): WorkflowEngine {
        return spyk(
            WorkflowEngine.Builder().metadata(metadata).settingsProvider(settings).databaseAccess(databaseAccess)
                .build()
        )
    }

    private fun seedTask(
        fileFormat: Report.Format,
        currentAction: TaskAction,
        nextAction: TaskAction,
        nextEventAction: Event.EventAction,
        topic: Topic,
        taskIndex: Long = 0,
        organization: DeepOrganization,
        childReport: Report? = null,
        bodyURL: String? = null,
    ): Report {
        val report = Report(
            fileFormat,
            listOf(ClientSource(organization = organization.name, client = "Test Sender")),
            1,
            metadata = UnitTestUtils.simpleMetadata,
            nextAction = nextAction,
            topic = topic
        )
        ReportStreamTestDatabaseContainer.testDatabaseAccess.transact { txn ->
            val action = Action().setActionName(currentAction)
            val actionId = ReportStreamTestDatabaseContainer.testDatabaseAccess.insertAction(txn, action)
            report.bodyURL = bodyURL ?: "http://${report.id}.${fileFormat.toString().lowercase()}"
            val reportFile = ReportFile().setSchemaTopic(topic).setReportId(report.id)
                .setActionId(actionId).setSchemaName("").setBodyFormat(fileFormat.toString()).setItemCount(1)
                .setExternalName("test-external-name")
                .setBodyUrl(report.bodyURL)
            ReportStreamTestDatabaseContainer.testDatabaseAccess.insertReportFile(
                reportFile, txn, action
            )
            if (childReport != null) {
                ReportStreamTestDatabaseContainer.testDatabaseAccess
                    .insertReportLineage(
                        ReportLineage(
                            taskIndex,
                            actionId,
                            report.id,
                            childReport.id,
                            OffsetDateTime.now()
                        ),
                        txn
                    )
            }

            ReportStreamTestDatabaseContainer.testDatabaseAccess.insertTask(
                report,
                fileFormat.toString().lowercase(),
                report.bodyURL,
                nextAction = ProcessEvent(
                    nextEventAction,
                    report.id,
                    Options.None,
                    emptyMap(),
                    emptyList()
                ),
                txn
            )
        }

        return report
    }

    @BeforeEach
    fun beforeEach() {
        unmockkAll()
    }

    @AfterEach
    fun afterEach() {
        unmockkAll()
    }

    @Test
    fun `test does not update the DB or send messages on an error`() {
        val report = seedTask(
            Report.Format.HL7,
            TaskAction.receive,
            TaskAction.convert,
            Event.EventAction.CONVERT,
            Topic.FULL_ELR,
            0,
            oneOrganization
        )

        mockkObject(BlobAccess)
        mockkObject(QueueMessage)
        mockkObject(QueueAccess)
        every { BlobAccess.downloadBlobAsByteArray(any()) } returns cleanHL7Record.toByteArray()
        every {
            BlobAccess.uploadBody(
                any(),
                any(),
                any(),
                any(),
                any()
            )
        } throws RuntimeException("manual error")
        every { QueueAccess.sendMessage(any(), any()) } returns Unit

        val settings = FileSettings().loadOrganizations(oneOrganization)
        val fhirEngine = FHIRConverter(
            UnitTestUtils.simpleMetadata,
            settings,
            ReportStreamTestDatabaseContainer.testDatabaseAccess,
        )

        val actionHistory = spyk(ActionHistory(TaskAction.receive))
        val workflowEngine =
            makeWorkflowEngine(
                UnitTestUtils.simpleMetadata,
                settings,
                ReportStreamTestDatabaseContainer.testDatabaseAccess
            )

        val queueMessage = "{\"type\":\"convert\",\"reportId\":\"${report.id}\"," +
            "\"blobURL\":\"http://azurite:10000/devstoreaccount1/reports/receive%2Fignore.ignore-full-elr%2F" +
            "None-${report.id}.hl7\",\"digest\"" +
            ":\"${BlobAccess.digestToString(BlobAccess.sha256Digest(cleanHL7Record.toByteArray()))}\"," +
            "\"blobSubFolderName\":" +
            "\"ignore.ignore-full-elr\",\"schemaName\":\"\",\"topic\":\"full-elr\"}"

        val fhirFunc = FHIRFunctions(
            workflowEngine,
            databaseAccess = ReportStreamTestDatabaseContainer.testDatabaseAccess
        )
        assertThrows<RuntimeException> {
            fhirFunc.doConvert(queueMessage, 1, fhirEngine, actionHistory)
        }

        val processTask = ReportStreamTestDatabaseContainer.testDatabaseAccess.fetchTask(report.id)
        assertThat(processTask.processedAt).isNull()
        ReportStreamTestDatabaseContainer.testDatabaseAccess.transact { txn ->
            val routeTask = DSL.using(txn).select(Task.TASK.asterisk()).from(Task.TASK)
                .where(Task.TASK.NEXT_ACTION.eq(TaskAction.route))
                .fetchOneInto(Task.TASK)
            assertThat(routeTask).isNull()
            val convertReportFile =
                DSL.using(txn).select(gov.cdc.prime.router.azure.db.tables.ReportFile.REPORT_FILE.asterisk())
                    .from(gov.cdc.prime.router.azure.db.tables.ReportFile.REPORT_FILE)
                    .where(gov.cdc.prime.router.azure.db.tables.ReportFile.REPORT_FILE.NEXT_ACTION.eq(TaskAction.route))
                    .fetchOneInto(gov.cdc.prime.router.azure.db.tables.ReportFile.REPORT_FILE)
            assertThat(convertReportFile).isNull()
        }
        verify(exactly = 0) {
            QueueAccess.sendMessage(any(), any())
        }
    }

    @Test
    fun `test successfully processes a convert message for HL7`() {
        val report = seedTask(
            Report.Format.HL7,
            TaskAction.receive,
            TaskAction.convert,
            Event.EventAction.CONVERT,
            Topic.FULL_ELR,
            0,
            oneOrganization
        )
        val metadata = Metadata(UnitTestUtils.simpleSchema)

        metadata.lookupTableStore += mapOf(
            "observation-mapping" to LookupTable("observation-mapping", emptyList())
        )

        mockkObject(BlobAccess)
        mockkObject(QueueMessage)
        mockkObject(QueueAccess)
        every { BlobAccess.downloadBlobAsByteArray(any()) } returns cleanHL7Record.toByteArray()
        every {
            BlobAccess.uploadBody(
                any(),
                any(),
                any(),
                any(),
                any()
            )
        } returns BlobAccess.BlobInfo(Report.Format.FHIR, "", "".toByteArray())
        every { QueueAccess.sendMessage(any(), any()) } returns Unit

        val settings = FileSettings().loadOrganizations(oneOrganization)
        val fhirEngine = FHIRConverter(
            metadata,
            settings,
            ReportStreamTestDatabaseContainer.testDatabaseAccess,
        )

        val actionHistory = spyk(ActionHistory(TaskAction.receive))
        val workflowEngine =
            makeWorkflowEngine(
                UnitTestUtils.simpleMetadata,
                settings,
                ReportStreamTestDatabaseContainer.testDatabaseAccess
            )

        val queueMessage = "{\"type\":\"convert\",\"reportId\":\"${report.id}\"," +
            "\"blobURL\":\"http://azurite:10000/devstoreaccount1/reports/receive%2Fignore.ignore-full-elr%2F" +
            "None-${report.id}.hl7\",\"digest\":" +
            "\"${BlobAccess.digestToString(BlobAccess.sha256Digest(cleanHL7Record.toByteArray()))}\"," +
            "\"blobSubFolderName\":" +
            "\"ignore.ignore-full-elr\",\"schemaName\":\"\",\"topic\":\"full-elr\"}"

        val fhirFunc = FHIRFunctions(
            workflowEngine,
            databaseAccess = ReportStreamTestDatabaseContainer.testDatabaseAccess
        )
        fhirFunc.doConvert(queueMessage, 1, fhirEngine, actionHistory)

        val processTask = ReportStreamTestDatabaseContainer.testDatabaseAccess.fetchTask(report.id)
        assertThat(processTask.processedAt).isNotNull()
        ReportStreamTestDatabaseContainer.testDatabaseAccess.transact { txn ->
            val routeTask = DSL.using(txn).select(Task.TASK.asterisk()).from(Task.TASK)
                .where(Task.TASK.NEXT_ACTION.eq(TaskAction.route))
                .fetchOneInto(Task.TASK)
            assertThat(routeTask).isNotNull()
            val convertReportFile =
                DSL.using(txn).select(gov.cdc.prime.router.azure.db.tables.ReportFile.REPORT_FILE.asterisk())
                    .from(gov.cdc.prime.router.azure.db.tables.ReportFile.REPORT_FILE)
                    .where(gov.cdc.prime.router.azure.db.tables.ReportFile.REPORT_FILE.NEXT_ACTION.eq(TaskAction.route))
                    .fetchOneInto(gov.cdc.prime.router.azure.db.tables.ReportFile.REPORT_FILE)
            assertThat(convertReportFile).isNotNull()
        }
        verify(exactly = 1) {
            QueueAccess.sendMessage(elrRoutingQueueName, any())
            BlobAccess.uploadBody(Report.Format.FHIR, any(), any(), any(), any())
        }
    }

    @Test
    fun `test successfully processes a convert message for bulk HL7 message`() {
        val validBatch = cleanHL7Record + "\n" + invalidHL7Record
        val report = seedTask(
            Report.Format.HL7,
            TaskAction.receive,
            TaskAction.convert,
            Event.EventAction.CONVERT,
            Topic.FULL_ELR,
            0,
            oneOrganization
        )
        val metadata = Metadata(UnitTestUtils.simpleSchema)

        metadata.lookupTableStore += mapOf(
            "observation-mapping" to LookupTable("observation-mapping", emptyList())
        )

        mockkObject(BlobAccess)
        mockkObject(QueueMessage)
        mockkObject(QueueAccess)
        every { BlobAccess.downloadBlobAsByteArray(any()) } returns validBatch.toByteArray()
        every {
            BlobAccess.uploadBody(
                any(),
                any(),
                any(),
                any(),
                any()
            )
        } answers { BlobAccess.BlobInfo(Report.Format.FHIR, UUID.randomUUID().toString(), "".toByteArray()) }
        every { QueueAccess.sendMessage(any(), any()) } returns Unit

        val settings = FileSettings().loadOrganizations(oneOrganization)
        val fhirEngine = FHIRConverter(
            metadata,
            settings,
            ReportStreamTestDatabaseContainer.testDatabaseAccess,
        )

        val actionHistory = spyk(ActionHistory(TaskAction.receive))
        val workflowEngine =
            makeWorkflowEngine(
                UnitTestUtils.simpleMetadata,
                settings,
                ReportStreamTestDatabaseContainer.testDatabaseAccess
            )

        val queueMessage = "{\"type\":\"convert\",\"reportId\":\"${report.id}\"," +
            "\"blobURL\":\"http://azurite:10000/devstoreaccount1/reports/receive%2Fignore.ignore-full-elr%2F" +
            "None-${report.id}.hl7\",\"digest\":" +
            "\"${BlobAccess.digestToString(BlobAccess.sha256Digest(validBatch.toByteArray()))}\"," +
            "\"blobSubFolderName\":" +
            "\"ignore.ignore-full-elr\",\"schemaName\":\"\",\"topic\":\"full-elr\"}"

        val fhirFunc = FHIRFunctions(
            workflowEngine,
            databaseAccess = ReportStreamTestDatabaseContainer.testDatabaseAccess
        )
        fhirFunc.doConvert(queueMessage, 1, fhirEngine, actionHistory)

        val processTask = ReportStreamTestDatabaseContainer.testDatabaseAccess.fetchTask(report.id)
        assertThat(processTask.processedAt).isNotNull()
        ReportStreamTestDatabaseContainer.testDatabaseAccess.transact { txn ->
            val routeTask = DSL.using(txn).select(Task.TASK.asterisk()).from(Task.TASK)
                .where(Task.TASK.NEXT_ACTION.eq(TaskAction.route))
                .fetchInto(Task.TASK)
            assertThat(routeTask).hasSize(2)
            val convertReportFile =
                DSL.using(txn).select(gov.cdc.prime.router.azure.db.tables.ReportFile.REPORT_FILE.asterisk())
                    .from(gov.cdc.prime.router.azure.db.tables.ReportFile.REPORT_FILE)
                    .where(gov.cdc.prime.router.azure.db.tables.ReportFile.REPORT_FILE.NEXT_ACTION.eq(TaskAction.route))
                    .fetchInto(gov.cdc.prime.router.azure.db.tables.ReportFile.REPORT_FILE)
            assertThat(convertReportFile).hasSize(2)
        }
        verify(exactly = 2) {
            QueueAccess.sendMessage(elrRoutingQueueName, any())
        }
        verify(exactly = 1) {
            BlobAccess.uploadBody(
                Report.Format.FHIR,
                match { bytes ->
                    val result = CompareData().compare(
                        bytes.inputStream(),
                        cleanHL7RecordConverted.byteInputStream(),
                        Report.Format.FHIR,
                        null
                    )
                    result.passed
                },
                any(), any(), any()
            )
            BlobAccess.uploadBody(
                Report.Format.FHIR,
                match { bytes ->
                    val result = CompareData().compare(
                        bytes.inputStream(),
                        invalidHL7RecordConverted.byteInputStream(),
                        Report.Format.FHIR,
                        null
                    )
                    result.passed
                },
                any(), any(), any()
            )
        }
    }

    @Test
    fun `test no items routed for HL7 if any in batch are invalid`() {
        val validBatch =
            cleanHL7Record + "\n" + invalidHL7Record + "\n" + badEncodingHL7Record + "\n" + unparseableHL7Record
        val report = seedTask(
            Report.Format.HL7,
            TaskAction.receive,
            TaskAction.convert,
            Event.EventAction.CONVERT,
            Topic.FULL_ELR,
            0,
            oneOrganization
        )
        val metadata = Metadata(UnitTestUtils.simpleSchema)

        metadata.lookupTableStore += mapOf(
            "observation-mapping" to LookupTable("observation-mapping", emptyList())
        )

        mockkObject(BlobAccess)
        mockkObject(QueueMessage)
        mockkObject(QueueAccess)
        every { BlobAccess.downloadBlobAsByteArray(any()) } returns validBatch.toByteArray()
        every {
            BlobAccess.uploadBody(
                any(),
                any(),
                any(),
                any(),
                any()
            )
        } answers { BlobAccess.BlobInfo(Report.Format.FHIR, UUID.randomUUID().toString(), "".toByteArray()) }
        every { QueueAccess.sendMessage(any(), any()) } returns Unit

        val settings = FileSettings().loadOrganizations(oneOrganization)
        val fhirEngine = FHIRConverter(
            metadata,
            settings,
            ReportStreamTestDatabaseContainer.testDatabaseAccess,
        )

        val actionHistory = spyk(ActionHistory(TaskAction.receive))
        val workflowEngine =
            makeWorkflowEngine(
                UnitTestUtils.simpleMetadata,
                settings,
                ReportStreamTestDatabaseContainer.testDatabaseAccess
            )

        val queueMessage = "{\"type\":\"convert\",\"reportId\":\"${report.id}\"," +
            "\"blobURL\":\"http://azurite:10000/devstoreaccount1/reports/receive%2Fignore.ignore-full-elr%2F" +
            "None-${report.id}.hl7\",\"digest\":" +
            "\"${BlobAccess.digestToString(BlobAccess.sha256Digest(validBatch.toByteArray()))}\"," +
            "\"blobSubFolderName\":" +
            "\"ignore.ignore-full-elr\",\"schemaName\":\"\",\"topic\":\"full-elr\"}"

        val actionLogger = ActionLogger()
        val fhirFunc = FHIRFunctions(
            workflowEngine,
            actionLogger = actionLogger,
            databaseAccess = ReportStreamTestDatabaseContainer.testDatabaseAccess
        )
        fhirFunc.doConvert(queueMessage, 1, fhirEngine, actionHistory)

        val processTask = ReportStreamTestDatabaseContainer.testDatabaseAccess.fetchTask(report.id)
        assertThat(processTask.processedAt).isNotNull()
        ReportStreamTestDatabaseContainer.testDatabaseAccess.transact { txn ->
            val routeTask = DSL.using(txn).select(Task.TASK.asterisk()).from(Task.TASK)
                .where(Task.TASK.NEXT_ACTION.eq(TaskAction.route))
                .fetchInto(Task.TASK)
            assertThat(routeTask).hasSize(2)
            val convertReportFile =
                DSL.using(txn).select(gov.cdc.prime.router.azure.db.tables.ReportFile.REPORT_FILE.asterisk())
                    .from(gov.cdc.prime.router.azure.db.tables.ReportFile.REPORT_FILE)
                    .where(gov.cdc.prime.router.azure.db.tables.ReportFile.REPORT_FILE.NEXT_ACTION.eq(TaskAction.route))
                    .fetchInto(gov.cdc.prime.router.azure.db.tables.ReportFile.REPORT_FILE)
            assertThat(convertReportFile).hasSize(2)
            assertThat(actionLogger.errors).hasSize(2)
        }
        verify(exactly = 2) {
            QueueAccess.sendMessage(elrRoutingQueueName, any())
        }

        verify(exactly = 1) {
            BlobAccess.uploadBody(
                Report.Format.FHIR,
                match { bytes ->
                    val result = CompareData().compare(
                        bytes.inputStream(),
                        cleanHL7RecordConverted.byteInputStream(),
                        Report.Format.FHIR,
                        null
                    )
                    result.passed
                },
                any(), any(), any()
            )
            BlobAccess.uploadBody(
                Report.Format.FHIR,
                match { bytes ->
                    val result = CompareData().compare(
                        bytes.inputStream(),
                        invalidHL7RecordConverted.byteInputStream(),
                        Report.Format.FHIR,
                        null
                    )
                    result.passed
                },
                any(), any(), any()
            )
        }
    }

    @Test
    fun `test successfully processes a convert message for a bulk (ndjson) FHIR message`() {
        val report = seedTask(
            Report.Format.FHIR,
            TaskAction.receive,
            TaskAction.convert,
            Event.EventAction.CONVERT,
            Topic.FULL_ELR,
            0,
            oneOrganization
        )
        val metadata = Metadata(UnitTestUtils.simpleSchema)

        metadata.lookupTableStore += mapOf(
            "observation-mapping" to LookupTable("observation-mapping", emptyList())
        )

        mockkObject(BlobAccess)
        mockkObject(QueueMessage)
        mockkObject(QueueAccess)
        val bulkFHIRRecord =
            listOf(
                validFHIRRecord1,
                invalidEmptyFHIRRecord,
                validFHIRRecord2,
                invalidMalformedFHIRRecord
            ).joinToString(
                "\n"
            )
        every { BlobAccess.downloadBlobAsByteArray(any()) } returns bulkFHIRRecord.toByteArray()
        every {
            BlobAccess.uploadBody(
                any(),
                any(),
                any(),
                any(),
                any()
            )
        } answers {
            BlobAccess.BlobInfo(Report.Format.FHIR, UUID.randomUUID().toString(), "".toByteArray())
        }
        every { QueueAccess.sendMessage(any(), any()) } returns Unit

        val settings = FileSettings().loadOrganizations(oneOrganization)
        val fhirEngine = FHIRConverter(
            metadata,
            settings,
            ReportStreamTestDatabaseContainer.testDatabaseAccess,
        )

        val actionHistory = spyk(ActionHistory(TaskAction.receive))
        val actionLogger = ActionLogger()
        val workflowEngine =
            makeWorkflowEngine(
                UnitTestUtils.simpleMetadata,
                settings,
                ReportStreamTestDatabaseContainer.testDatabaseAccess
            )

        val queueMessage = "{\"type\":\"convert\",\"reportId\":\"${report.id}\"," +
            "\"blobURL\":\"http://azurite:10000/devstoreaccount1/reports/receive%2Fignore.ignore-full-elr%2F" +
            "None-${report.id}.fhir\",\"digest\":" +
            "\"${BlobAccess.digestToString(BlobAccess.sha256Digest(bulkFHIRRecord.toByteArray()))}\"," +
            "\"blobSubFolderName\":" +
            "\"ignore.ignore-full-elr\",\"schemaName\":\"\",\"topic\":\"full-elr\"}"

        val fhirFunc = FHIRFunctions(
            workflowEngine,
            databaseAccess = ReportStreamTestDatabaseContainer.testDatabaseAccess,
            actionLogger = actionLogger
        )

        fhirFunc.doConvert(queueMessage, 1, fhirEngine, actionHistory)

        val processTask = ReportStreamTestDatabaseContainer.testDatabaseAccess.fetchTask(report.id)
        assertThat(processTask.processedAt).isNotNull()
        ReportStreamTestDatabaseContainer.testDatabaseAccess.transact { txn ->
            val routeTask = DSL.using(txn).select(Task.TASK.asterisk()).from(Task.TASK)
                .where(Task.TASK.NEXT_ACTION.eq(TaskAction.route))
                .fetchInto(Task.TASK)
            assertThat(routeTask).hasSize(2)
            val convertReportFile =
                DSL.using(txn).select(gov.cdc.prime.router.azure.db.tables.ReportFile.REPORT_FILE.asterisk())
                    .from(gov.cdc.prime.router.azure.db.tables.ReportFile.REPORT_FILE)
                    .where(gov.cdc.prime.router.azure.db.tables.ReportFile.REPORT_FILE.NEXT_ACTION.eq(TaskAction.route))
                    .fetchInto(gov.cdc.prime.router.azure.db.tables.ReportFile.REPORT_FILE)
            assertThat(convertReportFile).hasSize(2)

            // Expect two errors for the two badly formed bundles
            assertThat(actionLogger.errors).hasSize(2)
        }
        verify(exactly = 2) {
            QueueAccess.sendMessage(elrRoutingQueueName, any())
            BlobAccess.uploadBody(Report.Format.FHIR, any(), any(), any(), any())
        }
        verify(exactly = 1) {
            BlobAccess.uploadBody(
                Report.Format.FHIR,
                validFHIRRecord1.toByteArray(),
                any(),
                any(),
                any()
            )
            BlobAccess.uploadBody(
                Report.Format.FHIR,
                validFHIRRecord2.toByteArray(),
                any(),
                any(),
                any()
            )
        }
    }

    @Test
    fun `test successfully processes a convert message with invalid HL7 items`() {
        val receiveReport = seedTask(
            Report.Format.HL7,
            TaskAction.receive,
            TaskAction.convert,
            Event.EventAction.CONVERT,
            Topic.FULL_ELR,
            0,
            oneOrganization
        )
        val metadata = Metadata(UnitTestUtils.simpleSchema)

        metadata.lookupTableStore += mapOf(
            "observation-mapping" to LookupTable("observation-mapping", emptyList())
        )
        val settings = FileSettings().loadOrganizations(oneOrganization)
        val workflowEngine =
            makeWorkflowEngine(
                UnitTestUtils.simpleMetadata,
                settings,
                ReportStreamTestDatabaseContainer.testDatabaseAccess
            )

        // set up and seed azure blobstore
        val blobConnectionString =
            """DefaultEndpointsProtocol=http;AccountName=devstoreaccount1;AccountKey=keydevstoreaccount1;BlobEndpoint=http://${azuriteContainer.host}:${
                azuriteContainer.getMappedPort(
                    10000
                )
            }/devstoreaccount1;QueueEndpoint=http://${azuriteContainer.host}:${
                azuriteContainer.getMappedPort(
                    10001
                )
            }/devstoreaccount1;"""
        val blobContainerMetadata = BlobAccess.BlobContainerMetadata(
            "container1",
            blobConnectionString
        )

        mockkObject(BlobAccess)
        every { BlobAccess getProperty "defaultBlobMetadata" } returns blobContainerMetadata
        mockkObject(QueueMessage)
        mockkObject(QueueAccess)
        every { QueueAccess.sendMessage(any(), any()) } returns Unit
        mockkObject(BlobAccess.BlobContainerMetadata)

        val receiveReportBytes = (cleanHL7Record + "\n" + invalidHL7Record + "\n" + unparseableHL7Record).toByteArray()
        val receiveBlobUrl = BlobAccess.uploadBlob(
            "convertBlob.hl7",
            receiveReportBytes,
            blobContainerMetadata
        )

        val queueMessage = "{\"type\":\"convert\",\"reportId\":\"${receiveReport.id}\"," +
            "\"blobURL\":\"" + receiveBlobUrl +
            "\",\"digest\":\"${
                BlobAccess.digestToString(
                    BlobAccess.sha256Digest(
                        receiveReportBytes
                    )
                )
            }\",\"blobSubFolderName\":" +
            "\"ignore.ignore-full-elr\",\"schemaName\":\"\",\"topic\":\"full-elr\"," +
            "\"receiverFullName\":\"phd.elr2\"}"

        val fhirFunc = FHIRFunctions(
            workflowEngine,
            databaseAccess = ReportStreamTestDatabaseContainer.testDatabaseAccess
        )
        val fhirEngine = spyk(
            FHIRConverter(
                metadata,
                settings,
                ReportStreamTestDatabaseContainer.testDatabaseAccess,
            )
        )

        fhirFunc.doConvert(queueMessage, 1, fhirEngine)

        verify(exactly = 2) {
            QueueAccess.sendMessage(any(), any())
        }

        ReportStreamTestDatabaseContainer.testDatabaseAccess.transact { txn ->
            // Verify that there were two created reports from the 2 items that were parseable
            val routedReports = DSL
                .using(txn)
                .select(gov.cdc.prime.router.azure.db.tables.ReportFile.REPORT_FILE.asterisk())
                .from(gov.cdc.prime.router.azure.db.tables.ReportFile.REPORT_FILE)
                .where(
                    gov.cdc.prime.router.azure.db.tables.ReportFile.REPORT_FILE.REPORT_ID
                        .`in`(
                            DSL
                                .select(
                                    gov.cdc.prime.router.azure.db.tables.ReportLineage.REPORT_LINEAGE.CHILD_REPORT_ID
                                )
                                .from(gov.cdc.prime.router.azure.db.tables.ReportLineage.REPORT_LINEAGE)
                                .where(
                                    gov.cdc.prime.router.azure.db.tables.ReportLineage.REPORT_LINEAGE.PARENT_REPORT_ID
                                        .eq(
                                            receiveReport.id
                                        )
                                )
                        )
                ).fetchInto(ReportFile::class.java)
            assertThat(routedReports).hasSize(2)

            // Verify that the expected FHIR bundles were uploaded
            val fhirBundles =
                routedReports.map { BlobAccess.downloadBlobAsByteArray(it.bodyUrl, blobContainerMetadata) }
            assertThat(fhirBundles).each {
                it.matchesPredicate { bytes ->
                    val invalidHL7Result = CompareData().compare(
                        bytes.inputStream(),
                        invalidHL7RecordConverted.byteInputStream(),
                        Report.Format.FHIR,
                        null
                    )
                    invalidHL7Result.passed

                    val cleanHL7Result = CompareData().compare(
                        bytes.inputStream(),
                        cleanHL7RecordConverted.byteInputStream(),
                        Report.Format.FHIR,
                        null
                    )
                    invalidHL7Result.passed || cleanHL7Result.passed
                }
            }

            // Verify that there was an action log with an error created
            val actionLogs = DSL.using(txn).select(ACTION_LOG.asterisk()).from(ACTION_LOG)
                .where(ACTION_LOG.REPORT_ID.eq(receiveReport.id)).and(ACTION_LOG.TYPE.eq(ActionLogType.error))
                .fetchInto(
                    DetailedActionLog::class.java
                )

            assertThat(actionLogs).hasSize(1)
            @Suppress("ktlint:standard:max-line-length")
            assertThat(actionLogs.first()).transform { it.detail.message }
                .isEqualTo("Item 3 in the report was not parseable. Reason: exception while parsing HL7: Determine encoding for message. The following is the first 50 chars of the message for reference, although this may not be where the issue is: MSH^~\\&|CDC PRIME - Atlanta, Georgia (Dekalb)^2.16")
        }
    }

    @Test
    fun `test successfully processes a convert message with invalid FHIR items`() {
        val receiveReport = seedTask(
            Report.Format.FHIR,
            TaskAction.receive,
            TaskAction.convert,
            Event.EventAction.CONVERT,
            Topic.FULL_ELR,
            0,
            oneOrganization
        )
        val metadata = Metadata(UnitTestUtils.simpleSchema)

        metadata.lookupTableStore += mapOf(
            "observation-mapping" to LookupTable("observation-mapping", emptyList())
        )
        val settings = FileSettings().loadOrganizations(oneOrganization)
        val workflowEngine =
            makeWorkflowEngine(
                UnitTestUtils.simpleMetadata,
                settings,
                ReportStreamTestDatabaseContainer.testDatabaseAccess
            )

        // set up and seed azure blobstore
        val blobConnectionString =
            """DefaultEndpointsProtocol=http;AccountName=devstoreaccount1;AccountKey=keydevstoreaccount1;BlobEndpoint=http://${azuriteContainer.host}:${
                azuriteContainer.getMappedPort(
                    10000
                )
            }/devstoreaccount1;QueueEndpoint=http://${azuriteContainer.host}:${
                azuriteContainer.getMappedPort(
                    10001
                )
            }/devstoreaccount1;"""
        val blobContainerMetadata = BlobAccess.BlobContainerMetadata(
            "container1",
            blobConnectionString
        )

        mockkObject(BlobAccess)
        every { BlobAccess getProperty "defaultBlobMetadata" } returns blobContainerMetadata
        mockkObject(QueueMessage)
        mockkObject(QueueAccess)
        every { QueueAccess.sendMessage(any(), any()) } returns Unit
        mockkObject(BlobAccess.BlobContainerMetadata)
        val bulkFHIRRecord =
            listOf(
                validFHIRRecord1,
                invalidEmptyFHIRRecord,
                validFHIRRecord2,
                invalidMalformedFHIRRecord
            ).joinToString(
                "\n"
            )
        val receiveBlobUrl = BlobAccess.uploadBlob(
            "convertBlob.fhir",
            bulkFHIRRecord.toByteArray(),
            blobContainerMetadata
        )

        val queueMessage = "{\"type\":\"convert\",\"reportId\":\"${receiveReport.id}\"," +
            "\"blobURL\":\"" + receiveBlobUrl +
            "\",\"digest\":\"${
                BlobAccess.digestToString(
                    BlobAccess.sha256Digest(
                        bulkFHIRRecord.toByteArray()
                    )
                )
            }\",\"blobSubFolderName\":" +
            "\"ignore.ignore-full-elr\",\"schemaName\":\"\",\"topic\":\"full-elr\"," +
            "\"receiverFullName\":\"phd.elr2\"}"

        val fhirFunc = FHIRFunctions(
            workflowEngine,
            databaseAccess = ReportStreamTestDatabaseContainer.testDatabaseAccess
        )
        val fhirEngine = spyk(
            FHIRConverter(
                metadata,
                settings,
                ReportStreamTestDatabaseContainer.testDatabaseAccess,
            )
        )

        fhirFunc.doConvert(queueMessage, 1, fhirEngine)

        verify(exactly = 2) {
            QueueAccess.sendMessage(any(), any())
        }

        ReportStreamTestDatabaseContainer.testDatabaseAccess.transact { txn ->
            // Verify that there were two created reports from the 2 items that were parseable
            val routedReports = DSL
                .using(txn)
                .select(gov.cdc.prime.router.azure.db.tables.ReportFile.REPORT_FILE.asterisk())
                .from(gov.cdc.prime.router.azure.db.tables.ReportFile.REPORT_FILE)
                .where(
                    gov.cdc.prime.router.azure.db.tables.ReportFile.REPORT_FILE.REPORT_ID
                        .`in`(
                            DSL
                                .select(
                                    gov.cdc.prime.router.azure.db.tables.ReportLineage.REPORT_LINEAGE.CHILD_REPORT_ID
                                )
                                .from(gov.cdc.prime.router.azure.db.tables.ReportLineage.REPORT_LINEAGE)
                                .where(
                                    gov.cdc.prime.router.azure.db.tables.ReportLineage.REPORT_LINEAGE.PARENT_REPORT_ID
                                        .eq(
                                            receiveReport.id
                                        )
                                )
                        )
                ).fetchInto(ReportFile::class.java)
            assertThat(routedReports).hasSize(2)

            // Verify that the expected FHIR bundles were uploaded
            val fhirBundles =
                routedReports.map { BlobAccess.downloadBlobAsByteArray(it.bodyUrl, blobContainerMetadata) }
                    .map { it.toString(Charset.defaultCharset()) }
            assertThat(fhirBundles).containsOnly(validFHIRRecord1, validFHIRRecord2)

            // Verify that there was an action log with an error created
            val actionLogs = DSL.using(txn).select(ACTION_LOG.asterisk()).from(ACTION_LOG)
                .where(ACTION_LOG.REPORT_ID.eq(receiveReport.id)).and(ACTION_LOG.TYPE.eq(ActionLogType.error))
                .fetchInto(
                    DetailedActionLog::class.java
                )

            assertThat(actionLogs).hasSize(2)
            @Suppress("ktlint:standard:max-line-length")
            assertThat(actionLogs).transform {
                it.map { log ->
                    log.detail.message
                }
            }
                .containsOnly(
                    "Item 2 in the report was not parseable. Reason: exception while parsing FHIR: HAPI-1838: Invalid JSON content detected, missing required element: 'resourceType'",
                    "Item 4 in the report was not parseable. Reason: exception while parsing FHIR: HAPI-1861: Failed to parse JSON encoded FHIR content: Unexpected end-of-input: was expecting closing quote for a string value\n" +
                        " at [line: 1, column: 23]"
                )
        }
    }

    @Test
    fun `test successfully converting a NIST ELR HL7 message`() {
        val receiveReport = seedTask(
            Report.Format.HL7,
            TaskAction.receive,
            TaskAction.convert,
            Event.EventAction.CONVERT,
            Topic.FULL_ELR,
            0,
            oneOrganization
        )
        val metadata = Metadata(UnitTestUtils.simpleSchema)

        metadata.lookupTableStore += mapOf(
            "observation-mapping" to LookupTable("observation-mapping", emptyList())
        )
        val settings = FileSettings().loadOrganizations(oneOrganization)
        val workflowEngine =
            makeWorkflowEngine(
                UnitTestUtils.simpleMetadata,
                settings,
                ReportStreamTestDatabaseContainer.testDatabaseAccess
            )

        // set up and seed azure blobstore
        val blobConnectionString =
            """DefaultEndpointsProtocol=http;AccountName=devstoreaccount1;AccountKey=keydevstoreaccount1;BlobEndpoint=http://${azuriteContainer.host}:${
                azuriteContainer.getMappedPort(
                    10000
                )
            }/devstoreaccount1;QueueEndpoint=http://${azuriteContainer.host}:${
                azuriteContainer.getMappedPort(
                    10001
                )
            }/devstoreaccount1;"""
        val blobContainerMetadata = BlobAccess.BlobContainerMetadata(
            "container1",
            blobConnectionString
        )

        mockkObject(BlobAccess)
        every { BlobAccess getProperty "defaultBlobMetadata" } returns blobContainerMetadata
        mockkObject(QueueMessage)
        mockkObject(QueueAccess)
        every { QueueAccess.sendMessage(any(), any()) } returns Unit
        mockkObject(BlobAccess.BlobContainerMetadata)

        val receiveReportBytes = nistELRHL7Record.toByteArray()
        val receiveBlobUrl = BlobAccess.uploadBlob(
            "convertBlob.hl7",
            receiveReportBytes,
            blobContainerMetadata
        )

        val queueMessage = "{\"type\":\"convert\",\"reportId\":\"${receiveReport.id}\"," +
            "\"blobURL\":\"" + receiveBlobUrl +
            "\",\"digest\":\"${
                BlobAccess.digestToString(
                    BlobAccess.sha256Digest(
                        receiveReportBytes
                    )
                )
            }\",\"blobSubFolderName\":" +
            "\"ignore.ignore-full-elr\",\"schemaName\":\"\",\"topic\":\"elr-elims\"," +
            "\"receiverFullName\":\"phd.elr2\"}"

        val fhirFunc = FHIRFunctions(
            workflowEngine,
            databaseAccess = ReportStreamTestDatabaseContainer.testDatabaseAccess
        )
        val fhirEngine = spyk(
            FHIRConverter(
                metadata,
                settings,
                ReportStreamTestDatabaseContainer.testDatabaseAccess,
            )
        )

        fhirFunc.doConvert(queueMessage, 1, fhirEngine)

        verify(exactly = 1) {
            QueueAccess.sendMessage(any(), any())
        }

        ReportStreamTestDatabaseContainer.testDatabaseAccess.transact { txn ->
            // Verify that there were two created reports from the 2 items that were parseable
            val routedReports = DSL
                .using(txn)
                .select(gov.cdc.prime.router.azure.db.tables.ReportFile.REPORT_FILE.asterisk())
                .from(gov.cdc.prime.router.azure.db.tables.ReportFile.REPORT_FILE)
                .where(
                    gov.cdc.prime.router.azure.db.tables.ReportFile.REPORT_FILE.REPORT_ID
                        .`in`(
                            DSL
                                .select(
                                    gov.cdc.prime.router.azure.db.tables.ReportLineage.REPORT_LINEAGE.CHILD_REPORT_ID
                                )
                                .from(gov.cdc.prime.router.azure.db.tables.ReportLineage.REPORT_LINEAGE)
                                .where(
                                    gov.cdc.prime.router.azure.db.tables.ReportLineage.REPORT_LINEAGE.PARENT_REPORT_ID
                                        .eq(
                                            receiveReport.id
                                        )
                                )
                        )
                ).fetchInto(ReportFile::class.java)
            assertThat(routedReports).hasSize(1)

            // Verify that the expected FHIR bundles were uploaded
            val fhirBundles =
                routedReports.map { BlobAccess.downloadBlobAsByteArray(it.bodyUrl, blobContainerMetadata) }
            assertThat(fhirBundles).each {
                it.matchesPredicate { bytes ->
                    val nistELRResult = CompareData().compare(
                        bytes.inputStream(),
                        nistELRHL7RecordConverted.byteInputStream(),
                        Report.Format.FHIR,
                        null
                    )
                    nistELRResult.passed
                }
            }

            // Verify that there was an action log with an error created
            val actionLogs = DSL.using(txn).select(ACTION_LOG.asterisk()).from(ACTION_LOG)
                .where(ACTION_LOG.REPORT_ID.eq(receiveReport.id)).and(ACTION_LOG.TYPE.eq(ActionLogType.error))
                .fetchInto(
                    DetailedActionLog::class.java
                )

            assertThat(actionLogs).hasSize(0)
        }
    }

    @Test
    fun `test successfully processes a valid RADxMARS HL7 message`() {
        val receiveReport = seedTask(
            Report.Format.HL7,
            TaskAction.receive,
            TaskAction.convert,
            Event.EventAction.CONVERT,
            Topic.MARS_OTC_ELR,
            0,
            oneOrganization
        )
        val metadata = Metadata(UnitTestUtils.simpleSchema)

        metadata.lookupTableStore += mapOf(
            "observation-mapping" to LookupTable("observation-mapping", emptyList())
        )
        val settings = FileSettings().loadOrganizations(oneOrganization)
        val workflowEngine =
            makeWorkflowEngine(
                UnitTestUtils.simpleMetadata,
                settings,
                ReportStreamTestDatabaseContainer.testDatabaseAccess
            )

        // set up and seed azure blobstore
        val blobConnectionString =
            """DefaultEndpointsProtocol=http;AccountName=devstoreaccount1;AccountKey=keydevstoreaccount1;BlobEndpoint=http://${azuriteContainer.host}:${
                azuriteContainer.getMappedPort(
                    10000
                )
            }/devstoreaccount1;QueueEndpoint=http://${azuriteContainer.host}:${
                azuriteContainer.getMappedPort(
                    10001
                )
            }/devstoreaccount1;"""
        val blobContainerMetadata = BlobAccess.BlobContainerMetadata(
            "container1",
            blobConnectionString
        )

        mockkObject(BlobAccess)
        every { BlobAccess getProperty "defaultBlobMetadata" } returns blobContainerMetadata
        mockkObject(QueueMessage)
        mockkObject(QueueAccess)
        every { QueueAccess.sendMessage(any(), any()) } returns Unit
        mockkObject(BlobAccess.BlobContainerMetadata)

        val receiveReportBytes = (invalidRadxMarsHL7Message + "\n" + validRadxMarsHL7Message).toByteArray()
        val receiveBlobUrl = BlobAccess.uploadBlob(
            "convertBlob.hl7",
            receiveReportBytes,
            blobContainerMetadata
        )

        val queueMessage = "{\"type\":\"convert\",\"reportId\":\"${receiveReport.id}\"," +
            "\"blobURL\":\"" + receiveBlobUrl +
            "\",\"digest\":\"${
                BlobAccess.digestToString(
                    BlobAccess.sha256Digest(
                        receiveReportBytes
                    )
                )
            }\",\"blobSubFolderName\":" +
            "\"ignore.ignore-full-elr\",\"schemaName\":\"\",\"topic\":\"mars-otc-elr\"," +
            "\"receiverFullName\":\"phd.elr2\"}"

        val fhirFunc = FHIRFunctions(
            workflowEngine,
            databaseAccess = ReportStreamTestDatabaseContainer.testDatabaseAccess
        )
        val fhirEngine = spyk(
            FHIRConverter(
                metadata,
                settings,
                ReportStreamTestDatabaseContainer.testDatabaseAccess,
            )
        )

        fhirFunc.doConvert(queueMessage, 1, fhirEngine)

        verify(exactly = 1) {
            QueueAccess.sendMessage(any(), any())
        }

        ReportStreamTestDatabaseContainer.testDatabaseAccess.transact { txn ->
            // Verify that there were two created reports from the 2 items that were parseable
            val routedReports = DSL
                .using(txn)
                .select(gov.cdc.prime.router.azure.db.tables.ReportFile.REPORT_FILE.asterisk())
                .from(gov.cdc.prime.router.azure.db.tables.ReportFile.REPORT_FILE)
                .where(
                    gov.cdc.prime.router.azure.db.tables.ReportFile.REPORT_FILE.REPORT_ID
                        .`in`(
                            DSL
                                .select(
                                    gov.cdc.prime.router.azure.db.tables.ReportLineage.REPORT_LINEAGE.CHILD_REPORT_ID
                                )
                                .from(gov.cdc.prime.router.azure.db.tables.ReportLineage.REPORT_LINEAGE)
                                .where(
                                    gov.cdc.prime.router.azure.db.tables.ReportLineage.REPORT_LINEAGE.PARENT_REPORT_ID
                                        .eq(
                                            receiveReport.id
                                        )
                                )
                        )
                ).fetchInto(ReportFile::class.java)
            assertThat(routedReports).hasSize(1)

            // Verify that the expected FHIR bundles were uploaded
            val fhirBundles =
                routedReports.map { BlobAccess.downloadBlobAsByteArray(it.bodyUrl, blobContainerMetadata) }
            assertThat(fhirBundles).each {
                it.matchesPredicate { bytes ->
                    val radxMarsResult = CompareData().compare(
                        bytes.inputStream(),
                        validRadxMarsHL7MessageConverted.byteInputStream(),
                        Report.Format.FHIR,
                        null
                    )
                    radxMarsResult.passed
                }
            }

            // Verify that there was an action log with an error created
            val actionLogs = DSL.using(txn).select(ACTION_LOG.asterisk()).from(ACTION_LOG)
                .where(ACTION_LOG.REPORT_ID.eq(receiveReport.id)).and(ACTION_LOG.TYPE.eq(ActionLogType.error))
                .fetchInto(
                    DetailedActionLog::class.java
                )

            assertThat(actionLogs).hasSize(1)
            @Suppress("ktlint:standard:max-line-length")
            assertThat(actionLogs.first()).transform { it.detail.message }
                .isEqualTo(
                    "Item 1 in the report was not valid. Reason: HL7 was not valid at OBX[1]-19[1].1 for validator: RADx MARS"
                )
        }
    }

    @Test
    fun `test successfully processes a route message`() {
        val reportServiceMock = mockk<ReportService>()
        val report = seedTask(
            Report.Format.HL7,
            TaskAction.receive,
            TaskAction.translate,
            Event.EventAction.TRANSLATE,
            Topic.FULL_ELR,
            0,
            oneOrganization
        )

        mockkObject(BlobAccess)
        mockkObject(QueueMessage)
        mockkObject(QueueAccess)
        val routeFhirBytes =
            File(VALID_FHIR_PATH).readBytes()
        every {
            BlobAccess.downloadBlobAsByteArray(any())
        } returns routeFhirBytes
        every {
            BlobAccess.uploadBody(
                any(),
                any(),
                any(),
                any(),
                any()
            )
        } returns BlobAccess.BlobInfo(Report.Format.FHIR, "", "".toByteArray())
        every { QueueAccess.sendMessage(any(), any()) } returns Unit
        every { reportServiceMock.getSenderName(any()) } returns "senderOrg.senderOrgClient"

        val settings = FileSettings().loadOrganizations(oneOrganization)
        val fhirEngine = FHIRRouter(
            UnitTestUtils.simpleMetadata,
            settings,
            ReportStreamTestDatabaseContainer.testDatabaseAccess,
            reportService = reportServiceMock
        )

        val actionHistory = spyk(ActionHistory(TaskAction.receive))
        val workflowEngine =
            makeWorkflowEngine(
                UnitTestUtils.simpleMetadata,
                settings,
                ReportStreamTestDatabaseContainer.testDatabaseAccess
            )

        val queueMessage = "{\"type\":\"route\",\"reportId\":\"${report.id}\"," +
            "\"blobURL\":\"http://azurite:10000/devstoreaccount1/reports/receive%2Fignore.ignore-full-elr%2F" +
            "None-${report.id}.hl7\",\"digest\":" +
            "\"${BlobAccess.digestToString(BlobAccess.sha256Digest(routeFhirBytes))}\",\"blobSubFolderName\":" +
            "\"ignore.ignore-full-elr\",\"schemaName\":\"\",\"topic\":\"full-elr\"}"

        val fhirFunc = FHIRFunctions(
            workflowEngine,

            databaseAccess = ReportStreamTestDatabaseContainer.testDatabaseAccess
        )
        fhirFunc.doRoute(queueMessage, 1, fhirEngine, actionHistory)

        val convertTask = ReportStreamTestDatabaseContainer.testDatabaseAccess.fetchTask(report.id)
        assertThat(convertTask.routedAt).isNotNull()
        ReportStreamTestDatabaseContainer.testDatabaseAccess.transact { txn ->
            val routeTask = DSL.using(txn).select(Task.TASK.asterisk()).from(Task.TASK)
                .where(Task.TASK.NEXT_ACTION.eq(TaskAction.translate))
                .fetchOneInto(Task.TASK)
            assertThat(routeTask).isNotNull()
            val convertReportFile =
                DSL.using(txn).select(gov.cdc.prime.router.azure.db.tables.ReportFile.REPORT_FILE.asterisk())
                    .from(gov.cdc.prime.router.azure.db.tables.ReportFile.REPORT_FILE)
                    .where(
                        gov.cdc.prime.router.azure.db.tables.ReportFile.REPORT_FILE.NEXT_ACTION
                            .eq(TaskAction.translate)
                    )
                    .fetchOneInto(gov.cdc.prime.router.azure.db.tables.ReportFile.REPORT_FILE)
            assertThat(convertReportFile).isNotNull()
        }
        verify(exactly = 1) {
            QueueAccess.sendMessage(elrTranslationQueueName, any())
        }
    }

    /*
    Send a FHIR message to an HL7v2 receiver and ensure the message receiver receives is translated to HL7v2
     */
    @Test
    fun `test successfully processes a translate message when isSendOriginal is false`() {
        // set up and seed azure blobstore
        val blobConnectionString =
            """DefaultEndpointsProtocol=http;AccountName=devstoreaccount1;AccountKey=keydevstoreaccount1;BlobEndpoint=http://${azuriteContainer.host}:${
                azuriteContainer.getMappedPort(
                    10000
                )
            }/devstoreaccount1;QueueEndpoint=http://${azuriteContainer.host}:${
                azuriteContainer.getMappedPort(
                    10001
                )
            }/devstoreaccount1;"""
        val blobContainerMetadata = BlobAccess.BlobContainerMetadata(
            "container1",
            blobConnectionString
        )

        mockkObject(BlobAccess)
        every { BlobAccess getProperty "defaultBlobMetadata" } returns blobContainerMetadata

        // upload reports
        val receiveBlobName = "receiveBlobName"
        val translateFhirBytes = File(
            MULTIPLE_TARGETS_FHIR_PATH
        ).readBytes()
        val receiveBlobUrl = BlobAccess.uploadBlob(
            receiveBlobName,
            translateFhirBytes,
            blobContainerMetadata
        )

        // Seed the steps backwards so report lineage can be correctly generated
        val translateReport = seedTask(
            Report.Format.FHIR,
            TaskAction.translate,
            TaskAction.send,
            Event.EventAction.SEND,
            Topic.ELR_ELIMS,
            100,
            oneOrganization
        )
        val routeReport = seedTask(
            Report.Format.FHIR,
            TaskAction.route,
            TaskAction.translate,
            Event.EventAction.TRANSLATE,
            Topic.ELR_ELIMS,
            99,
            oneOrganization,
            translateReport
        )
        val convertReport = seedTask(
            Report.Format.FHIR,
            TaskAction.convert,
            TaskAction.route,
            Event.EventAction.ROUTE,
            Topic.ELR_ELIMS,
            98,
            oneOrganization,
            routeReport
        )
        val receiveReport = seedTask(
            Report.Format.FHIR,
            TaskAction.receive,
            TaskAction.convert,
            Event.EventAction.CONVERT,
            Topic.ELR_ELIMS,
            97,
            oneOrganization,
            convertReport,
            receiveBlobUrl
        )

        val settings = FileSettings().loadOrganizations(oneOrganization)
        val fhirEngine = spyk(
            FHIRTranslator(
                UnitTestUtils.simpleMetadata,
                settings,
                ReportStreamTestDatabaseContainer.testDatabaseAccess,
                reportService = ReportService(ReportGraph(ReportStreamTestDatabaseContainer.testDatabaseAccess))
            )
        )

        val actionHistory = spyk(ActionHistory(TaskAction.receive))
        val workflowEngine =
            makeWorkflowEngine(
                UnitTestUtils.simpleMetadata,
                settings,
                ReportStreamTestDatabaseContainer.testDatabaseAccess
            )

        mockkObject(QueueMessage)
        mockkObject(QueueAccess)
        every { QueueAccess.sendMessage(any(), any()) } returns Unit
        mockkObject(BlobAccess.BlobContainerMetadata)
        every { BlobAccess.BlobContainerMetadata.build("metadata", any()) } returns BlobAccess.BlobContainerMetadata(
            "metadata",
            blobConnectionString
        )

        // The topic param of queueMessage is what should determine how the Translate function runs
        val queueMessage = "{\"type\":\"translate\",\"reportId\":\"${translateReport.id}\"," +
            "\"blobURL\":\"" + receiveBlobUrl +
            "\",\"digest\":\"${
                BlobAccess.digestToString(
                    BlobAccess.sha256Digest(
                        translateFhirBytes
                    )
                )
            }\",\"blobSubFolderName\":" +
            "\"ignore.ignore-full-elr\",\"schemaName\":\"\",\"topic\":\"full-elr\"," +
            "\"receiverFullName\":\"phd.elr2\"}"

        val fhirFunc = FHIRFunctions(
            workflowEngine,
            databaseAccess = ReportStreamTestDatabaseContainer.testDatabaseAccess
        )

        fhirFunc.doTranslate(queueMessage, 1, fhirEngine, actionHistory)

        // verify task and report_file tables were updated correctly in the Translate function (new task and new
        // record file created)
        ReportStreamTestDatabaseContainer.testDatabaseAccess.transact { txn ->
            val queueTask = DSL.using(txn).select(Task.TASK.asterisk()).from(Task.TASK)
                .where(Task.TASK.NEXT_ACTION.eq(TaskAction.batch))
                .fetchOneInto(Task.TASK)
            assertThat(queueTask).isNotNull()

            val sendReportFile =
                DSL.using(txn).select(gov.cdc.prime.router.azure.db.tables.ReportFile.REPORT_FILE.asterisk())
                    .from(gov.cdc.prime.router.azure.db.tables.ReportFile.REPORT_FILE)
                    .where(
                        gov.cdc.prime.router.azure.db.tables.ReportFile.REPORT_FILE.REPORT_ID
                            .eq(queueTask!!.reportId)
                    )
                    .fetchOneInto(gov.cdc.prime.router.azure.db.tables.ReportFile.REPORT_FILE)
            assertThat(sendReportFile).isNotNull()

            // verify sendReportFile message does not match the original message from receive step
            assertThat(BlobAccess.downloadBlobAsByteArray(sendReportFile!!.bodyUrl, blobContainerMetadata))
                .isNotEqualTo(BlobAccess.downloadBlobAsByteArray(receiveReport.bodyURL, blobContainerMetadata))
        }

        // verify we did not call the sendOriginal function
        verify(exactly = 0) {
            fhirEngine.sendOriginal(any(), any(), any())
        }

        // verify we called the sendTranslated function
        verify(exactly = 1) {
            fhirEngine.sendTranslated(any(), any(), any())
        }

        // verify sendMessage did not get called because next action should be Batch
        verify(exactly = 0) {
            QueueAccess.sendMessage(any(), any())
        }
    }

    /*
    Send a FHIR message to an HL7v2 receiver and ensure the message receiver receives is the original FHIR and NOT
    translated to HL7v2
     */
    @Test
    fun `test successfully processes a translate message when isSendOriginal is true`() {
        // set up and seed azure blobstore
        val blobConnectionString =
            """DefaultEndpointsProtocol=http;AccountName=devstoreaccount1;AccountKey=keydevstoreaccount1;BlobEndpoint=http://${azuriteContainer.host}:${
                azuriteContainer.getMappedPort(
                    10000
                )
            }/devstoreaccount1;QueueEndpoint=http://${azuriteContainer.host}:${
                azuriteContainer.getMappedPort(
                    10001
                )
            }/devstoreaccount1;"""
        val blobContainerMetadata = BlobAccess.BlobContainerMetadata(
            "container1",
            blobConnectionString
        )

        mockkObject(BlobAccess)
        every { BlobAccess getProperty "defaultBlobMetadata" } returns blobContainerMetadata

        // upload reports
        val receiveBlobName = "receiveBlobName"
        val translateFhirBytes = File(
            MULTIPLE_TARGETS_FHIR_PATH
        ).readBytes()
        val receiveBlobUrl = BlobAccess.uploadBlob(
            receiveBlobName,
            translateFhirBytes,
            blobContainerMetadata
        )

        // Seed the steps backwards so report lineage can be correctly generated
        val translateReport = seedTask(
            Report.Format.FHIR,
            TaskAction.translate,
            TaskAction.send,
            Event.EventAction.SEND,
            Topic.ELR_ELIMS,
            100,
            oneOrganization
        )
        val routeReport = seedTask(
            Report.Format.FHIR,
            TaskAction.route,
            TaskAction.translate,
            Event.EventAction.TRANSLATE,
            Topic.ELR_ELIMS,
            99,
            oneOrganization,
            translateReport
        )
        val convertReport = seedTask(
            Report.Format.FHIR,
            TaskAction.convert,
            TaskAction.route,
            Event.EventAction.ROUTE,
            Topic.ELR_ELIMS,
            98,
            oneOrganization,
            routeReport
        )
        val receiveReport = seedTask(
            Report.Format.FHIR,
            TaskAction.receive,
            TaskAction.convert,
            Event.EventAction.CONVERT,
            Topic.ELR_ELIMS,
            97,
            oneOrganization,
            convertReport,
            receiveBlobUrl
        )

        val settings = FileSettings().loadOrganizations(oneOrganization)
        val fhirEngine = spyk(
            FHIRTranslator(
                UnitTestUtils.simpleMetadata,
                settings,
                ReportStreamTestDatabaseContainer.testDatabaseAccess,
                reportService = ReportService(ReportGraph(ReportStreamTestDatabaseContainer.testDatabaseAccess))
            )
        )

        val actionHistory = spyk(ActionHistory(TaskAction.receive))
        val workflowEngine =
            makeWorkflowEngine(
                UnitTestUtils.simpleMetadata,
                settings,
                ReportStreamTestDatabaseContainer.testDatabaseAccess
            )

        mockkObject(QueueMessage)
        mockkObject(QueueAccess)
        every { QueueAccess.sendMessage(any(), any()) } returns Unit

        // The topic param of queueMessage is what should determine how the Translate function runs
        val queueMessage = "{\"type\":\"translate\",\"reportId\":\"${translateReport.id}\"," +
            "\"blobURL\":\"" + receiveBlobUrl +
            "\",\"digest\":\"${
                BlobAccess.digestToString(
                    BlobAccess.sha256Digest(
                        translateFhirBytes
                    )
                )
            }\",\"blobSubFolderName\":" +
            "\"ignore.ignore-full-elr\",\"schemaName\":\"\",\"topic\":\"elr-elims\"," +
            "\"receiverFullName\":\"phd.elr2\"}"

        val fhirFunc = FHIRFunctions(
            workflowEngine,

            databaseAccess = ReportStreamTestDatabaseContainer.testDatabaseAccess
        )

        fhirFunc.doTranslate(queueMessage, 1, fhirEngine, actionHistory)

        // verify task and report_file tables were updated correctly in the Translate function
        ReportStreamTestDatabaseContainer.testDatabaseAccess.transact { txn ->
            val sendTask = DSL.using(txn).select(Task.TASK.asterisk()).from(Task.TASK)
                .where(Task.TASK.NEXT_ACTION.eq(TaskAction.send))
                .fetchOneInto(Task.TASK)
            assertThat(sendTask).isNotNull()

            val sendReportFile =
                DSL.using(txn).select(gov.cdc.prime.router.azure.db.tables.ReportFile.REPORT_FILE.asterisk())
                    .from(gov.cdc.prime.router.azure.db.tables.ReportFile.REPORT_FILE)
                    .where(
                        gov.cdc.prime.router.azure.db.tables.ReportFile.REPORT_FILE.REPORT_ID
                            .eq(sendTask!!.reportId)
                    )
                    .fetchOneInto(gov.cdc.prime.router.azure.db.tables.ReportFile.REPORT_FILE)
            assertThat(sendReportFile).isNotNull()

            // verify sendReportFile message matches the original message from receive step
            assertThat(BlobAccess.downloadBlobAsByteArray(sendReportFile!!.bodyUrl, blobContainerMetadata))
                .isEqualTo(BlobAccess.downloadBlobAsByteArray(receiveReport.bodyURL, blobContainerMetadata))
        }

        // verify we called the sendOriginal function
        verify(exactly = 1) {
            fhirEngine.sendOriginal(any(), any(), any())
        }

        // verify we did not call the sendTranslated function
        verify(exactly = 0) {
            fhirEngine.sendTranslated(any(), any(), any())
        }

        // verify sendMessage did get called because next action should be Send since isOriginal skips the batch
        // step
        verify(exactly = 1) {
            QueueAccess.sendMessage(any(), any())
        }
    }

    @Test
    fun `test unmapped observation error messages`() {
        val report = seedTask(
            Report.Format.FHIR,
            TaskAction.receive,
            TaskAction.convert,
            Event.EventAction.CONVERT,
            Topic.FULL_ELR,
            0,
            oneOrganization
        )
        val metadata = Metadata(UnitTestUtils.simpleSchema)
        val fhirRecordBytes = fhirengine.azure.fhirRecord.toByteArray()

        metadata.lookupTableStore += mapOf(
            "observation-mapping" to LookupTable("observation-mapping", emptyList())
        )

        mockkObject(BlobAccess)
        mockkObject(QueueMessage)
        mockkObject(QueueAccess)
        every { BlobAccess.downloadBlobAsByteArray(any()) } returns fhirRecordBytes
        every {
            BlobAccess.uploadBody(
                any(),
                any(),
                any(),
                any(),
                any()
            )
        } returns BlobAccess.BlobInfo(Report.Format.FHIR, "", "".toByteArray())
        every { QueueAccess.sendMessage(any(), any()) } returns Unit

        val settings = FileSettings().loadOrganizations(oneOrganization)
        val fhirEngine = FHIRConverter(
            metadata,
            settings,
            ReportStreamTestDatabaseContainer.testDatabaseAccess,
        )

        val actionHistory = spyk(ActionHistory(TaskAction.receive))
        val workflowEngine =
            makeWorkflowEngine(
                metadata,
                settings,
                ReportStreamTestDatabaseContainer.testDatabaseAccess
            )

        val queueMessage = "{\"type\":\"convert\",\"reportId\":\"${report.id}\"," +
            "\"blobURL\":\"http://azurite:10000/devstoreaccount1/reports/receive%2Fignore.ignore-full-elr%2F" +
            "None-${report.id}.fhir\",\"digest\":" +
            "\"${BlobAccess.digestToString(BlobAccess.sha256Digest(fhirRecordBytes))}\"," +
            "\"blobSubFolderName\":" +
            "\"ignore.ignore-full-elr\",\"schemaName\":\"\",\"topic\":\"full-elr\"}"

        val fhirFunc = FHIRFunctions(
            workflowEngine,
            databaseAccess = ReportStreamTestDatabaseContainer.testDatabaseAccess
        )
        fhirFunc.doConvert(queueMessage, 1, fhirEngine, actionHistory)

        val processTask = ReportStreamTestDatabaseContainer.testDatabaseAccess.fetchTask(report.id)
        assertThat(processTask.processedAt).isNotNull()
        ReportStreamTestDatabaseContainer.testDatabaseAccess.transact { txn ->
            val actionLogs = DSL.using(txn)
                .select(ActionLog.ACTION_LOG.asterisk())
                .from(ActionLog.ACTION_LOG)
                .fetchMany()
                .map { it.into(gov.cdc.prime.router.azure.db.tables.pojos.ActionLog::class.java) }
            assertThat(actionLogs.size).isEqualTo(1)
            assertThat(actionLogs[0].size).isEqualTo(2)
            assertThat(actionLogs[0].map { it.detail.message }).isEqualTo(
                listOf(
                    "Missing mapping for code(s): 80382-5",
                    "Missing mapping for code(s): 260373001"
                )
            )
        }
    }

    @Test
    fun `test codeless observation error message`() {
        val report = seedTask(
            Report.Format.FHIR,
            TaskAction.receive,
            TaskAction.convert,
            Event.EventAction.CONVERT,
            Topic.FULL_ELR,
            0,
            oneOrganization
        )
        val metadata = Metadata(UnitTestUtils.simpleSchema)
        val fhirRecordBytes = fhirengine.azure.codelessFhirRecord.toByteArray()

        metadata.lookupTableStore += mapOf(
            "observation-mapping" to LookupTable("observation-mapping", emptyList())
        )

        mockkObject(BlobAccess)
        mockkObject(QueueMessage)
        mockkObject(QueueAccess)
        every { BlobAccess.downloadBlobAsByteArray(any()) } returns fhirRecordBytes
        every {
            BlobAccess.uploadBody(
                any(),
                any(),
                any(),
                any(),
                any()
            )
        } returns BlobAccess.BlobInfo(Report.Format.FHIR, "", "".toByteArray())
        every { QueueAccess.sendMessage(any(), any()) } returns Unit

        val settings = FileSettings().loadOrganizations(oneOrganization)
        val fhirEngine = FHIRConverter(
            metadata,
            settings,
            ReportStreamTestDatabaseContainer.testDatabaseAccess,

            )

        val actionHistory = spyk(ActionHistory(TaskAction.receive))
        val workflowEngine =
            makeWorkflowEngine(
                metadata,
                settings,
                ReportStreamTestDatabaseContainer.testDatabaseAccess
            )

        val queueMessage = "{\"type\":\"convert\",\"reportId\":\"${report.id}\"," +
            "\"blobURL\":\"http://azurite:10000/devstoreaccount1/reports/receive%2Fignore.ignore-full-elr%2F" +
            "None-${report.id}.fhir\",\"digest\":" +
            "\"${BlobAccess.digestToString(BlobAccess.sha256Digest(fhirRecordBytes))}\"," +
            "\"blobSubFolderName\":" +
            "\"ignore.ignore-full-elr\",\"schemaName\":\"\",\"topic\":\"full-elr\"}"

        val fhirFunc = FHIRFunctions(
            workflowEngine,

            databaseAccess = ReportStreamTestDatabaseContainer.testDatabaseAccess
        )
        fhirFunc.doConvert(queueMessage, 1, fhirEngine, actionHistory)

        val processTask = ReportStreamTestDatabaseContainer.testDatabaseAccess.fetchTask(report.id)
        assertThat(processTask.processedAt).isNotNull()
        ReportStreamTestDatabaseContainer.testDatabaseAccess.transact { txn ->
            val actionLogs = DSL.using(txn)
                .select(ActionLog.ACTION_LOG.asterisk())
                .from(ActionLog.ACTION_LOG).fetchMany()
                .map { it.into(gov.cdc.prime.router.azure.db.tables.pojos.ActionLog::class.java) }
            assertThat(actionLogs.size).isEqualTo(1)
            assertThat(actionLogs[0].size).isEqualTo(1)
            assertThat(actionLogs[0][0].detail.message).isEqualTo("Observation missing code")
        }
    }
}