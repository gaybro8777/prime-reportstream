package gov.cdc.prime.router.fhirengine.translation

import ca.uhn.hl7v2.model.Message
import ca.uhn.hl7v2.model.Segment
import ca.uhn.hl7v2.util.Terser
import gov.cdc.prime.router.fhirengine.utils.FhirTranscoder
import gov.cdc.prime.router.fhirengine.utils.addProvenanceReference
import gov.cdc.prime.router.fhirengine.utils.enhanceBundleMetadata
import gov.cdc.prime.router.fhirengine.utils.handleBirthTime
import io.github.linuxforhealth.hl7.message.HL7MessageEngine
import io.github.linuxforhealth.hl7.message.HL7MessageModel
import io.github.linuxforhealth.hl7.resource.ResourceReader
import org.apache.logging.log4j.kotlin.Logging
import org.hl7.fhir.r4.model.Bundle

/**
 * Translate an HL7 message to FHIR.
 */
class HL7toFhirTranslator internal constructor(
    private val messageEngine: HL7MessageEngine = FhirTranscoder.getMessageEngine()
) : Logging {
    companion object {
        init {
            // TODO Change to use the local classpath per documentation
            val props = System.getProperties()
            props.setProperty("hl7converter.config.home", "./metadata/fhir_mapping")
        }

        /**
         * A Default set of message templates for HL7 -> FHIR translation
         */
        internal val defaultMessageTemplates: MutableMap<String, HL7MessageModel> =
            ResourceReader.getInstance().messageTemplates

        /**
         * Singleton object
         */
        private val singletonInstance: HL7toFhirTranslator by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
            HL7toFhirTranslator()
        }

        /**
         * Get the singleton instance.
         * @return the translator instance
         */
        fun getInstance(): HL7toFhirTranslator {
            return singletonInstance
        }
    }

    /**
     * Get the HL7 Message Model used to translate an [hl7Message] between HL7 and FHIR.
     * @return the message model
     */
    internal fun getHL7MessageModel(
        hl7Message: Message
    ): HL7MessageModel {
        val messageTemplateType = getMessageTemplateType(hl7Message)
        return defaultMessageTemplates[messageTemplateType]
            ?: throw UnsupportedOperationException("Message type not yet supported $messageTemplateType")
    }

    /**
     * Translate an [hl7Message] into a FHIR Bundle.
     * @return a FHIR bundle
     *
     * Custom translations can be handled by adding a customized message type
     * such as test_ORU_R01 to the list in config.properties. Then creating the accompanying files, such as
     * hl7/message/test_ORU_R01. Any files in fhir_mapping/hl7/resource will override the defaults allowing customization
     * of resources like Address or Organization.
     */
    fun translate(
        hl7Message: Message,
    ): Bundle {
        // NOTE:
        // extracted from
        // https://github.com/LinuxForHealth/hl7v2-fhir-converter/blob/d5e43fffa96654e7c5bc896e020ff2fa8aac4ff2/src/main/java/io/github/linuxforhealth/hl7/HL7ToFHIRConverter.java#L135-L159
        // If timezone specification is needed it can be provided via a custom HL7MessageEngine with a custom FHIRContext that has the time zone ID set

        val messageModel = getHL7MessageModel(hl7Message)
        val bundle = messageModel.convert(hl7Message, messageEngine)
        bundle.enhanceBundleMetadata(hl7Message)
        bundle.addProvenanceReference()
        bundle.handleBirthTime(hl7Message)
        return bundle
    }

    /**
     * Obtain the message type for a given HL7 [message].
     * @return the message type
     */
    internal fun getMessageTemplateType(message: Message): String {
        val header = message.get("MSH") as Segment
        return Terser.get(header, 9, 0, 3, 1)
    }
}