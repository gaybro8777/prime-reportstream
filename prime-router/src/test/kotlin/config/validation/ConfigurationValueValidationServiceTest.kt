package gov.cdc.prime.router.config.validation

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isInstanceOf
import gov.cdc.prime.router.CustomerStatus
import gov.cdc.prime.router.DeepOrganization
import gov.cdc.prime.router.Organization
import gov.cdc.prime.router.Receiver
import gov.cdc.prime.router.Report
import gov.cdc.prime.router.Topic
import java.io.File
import kotlin.test.Test

class ConfigurationValueValidationServiceTest {

    private val configurationValueValidationService: ConfigurationValueValidationService =
        ConfigurationValueValidationServiceImpl()

    @Test
    fun `well formatted filter`() {
        val receiver = Receiver(
            "Unit test receiver",
            "org",
            Topic.TEST,
            CustomerStatus.INACTIVE,
            "classpath:/metadata/hl7_mapping/fake.yml",
            format = Report.Format.FHIR,
            jurisdictionalFilter = listOf(
                "matches(a, b)"
            )
        )

        val org = DeepOrganization(
            name = "UnitTest",
            description = "unit test description",
            jurisdiction = Organization.Jurisdiction.FEDERAL,
            receivers = listOf(receiver)
        )

        val result = configurationValueValidationService.validate(
            ConfigurationType.Organizations,
            listOf(org)
        )

        assertThat(result).isInstanceOf<ConfigurationValidationSuccess<List<Organization>>>()
    }

    @Test
    fun `badly formatted filter`() {
        val receiver = Receiver(
            "Unit test receiver",
            "org",
            Topic.TEST,
            CustomerStatus.INACTIVE,
            "classpath:/metadata/hl7_mapping/fake.yml",
            format = Report.Format.FHIR,
            jurisdictionalFilter = listOf(
                "bad Filter formatting!"
            )
        )

        val org = DeepOrganization(
            name = "UnitTest",
            description = "unit test description",
            jurisdiction = Organization.Jurisdiction.FEDERAL,
            receivers = listOf(receiver)
        )

        val result = configurationValueValidationService.validate(
            ConfigurationType.Organizations,
            listOf(org)
        )

        assertThat(result)
            .isInstanceOf<ConfigurationValidationFailure<List<Organization>>>()
            .transform { it.errors.first() }
            .contains("bad Filter formatting!")
    }

    @Test
    fun organizations() {
        val yaml = File("settings/organizations.yml")
        val orgs = ConfigurationType.Organizations.parse(yaml.inputStream())

        val result = configurationValueValidationService.validate(
            ConfigurationType.Organizations,
            orgs
        )

        assertThat(result).isInstanceOf<ConfigurationValidationSuccess<List<Organization>>>()
    }
}