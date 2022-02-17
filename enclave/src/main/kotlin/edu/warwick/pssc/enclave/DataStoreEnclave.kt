package edu.warwick.pssc.enclave

import com.r3.conclave.enclave.Enclave
import com.r3.conclave.enclave.EnclavePostOffice
import com.r3.conclave.mail.EnclaveMail
import edu.warwick.pssc.conclave.AddressPlaceholder
import edu.warwick.pssc.conclave.EthContractDiscloseCondition
import edu.warwick.pssc.conclave.EthPublicKey
import edu.warwick.pssc.conclave.PublicKeyDiscloseCondition
import edu.warwick.pssc.conclave.common.*
import edu.warwick.pssc.conclave.common.MessageSerializer.decodeMessage
import edu.warwick.pssc.conclave.common.MessageSerializer.encodeMessage
import org.web3j.abi.datatypes.Address
import org.web3j.abi.datatypes.Type
import org.web3j.crypto.Keys.getAddress
import java.security.SignatureException
import java.util.*

/**
 * Enclave that accepts data submissions from the Clients,
 * Stores data with a rule on who can access it.
 * Whenever any client would like to access data,
 * they provide proof that he can access it and the Enclave returns it
 */
@Suppress("unused")
class DataStoreEnclave : Enclave() {

    data class OracleSubmission (
        val oracle: String,
        val data: List<Type<Any>>?,
    )

    data class RequesterDetails (
        val routingHint: String?,
        val postOffice: EnclavePostOffice,
        val verifiedPublicKey: EthPublicKey,
    )

    data class PendingDataRequest(val dataId: UUID, val requester: RequesterDetails, val oracleSubmissions: List<OracleSubmission>)

    private val database : SecretDatabase = SecretDatabase()
    private val validator : DataRequestValidator = DataRequestValidator()
    private val oracleManager : OracleManager = OracleManager()
    private val pendingDataRequests : MutableMap<UUID, PendingDataRequest> = mutableMapOf() // Map between the Topic of the request sent to Oracle to the UUID of the

    override fun onStartup() {
        logInfo("Waiting for Data Submissions and requests!")
    }

    override fun receiveFromUntrustedHost(bytes: ByteArray): ByteArray? {
        TODO("Not Supported right now")
    }

    override fun receiveMail(mail: EnclaveMail, routingHint: String?) {
        logInfo("Received mail!")
        logInfo("Mail Topic: ${mail.topic} RoutingHint: $routingHint")

        val message = mail.bodyAsBytes.decodeMessage()

        val response: Message? = try {
            when (message) {
                is SecretDataSubmission.Submission -> processDataSubmission(message)
                is SecretDataRequest.Request -> processDataRequestRequest(message, mail, routingHint)
                is OracleRegistration.Request -> processOracleRegistrationRequest(message, mail, routingHint)
                is OracleEthDataCall.Response -> processOracleDataCallResponse(message, mail)
                else -> {
                    logInfo("Received an unknown message!")
                    throw EnclaveStateException("Received an unknown message!")
                } // Add processing for error messages from oracles
            }
        } catch (e: EnclaveStateException) {
            ErrorMessage(e.message)
        }

        logInfo("Finished processing mail!")

        if (response != null) {
            logInfo("Sending response!")
            val responseBytes = response.encodeMessage()
            val encryptedMail = postOffice(mail).encryptMail(responseBytes)
            postMail(encryptedMail, routingHint)
        }
    }

    private fun processOracleDataCallResponse(
        message: OracleEthDataCall.Response,
        mail: EnclaveMail
    ): Message? {
        logInfo("Processing Oracle Data Call Response!")
        val requestId = UUID.fromString(mail.topic) // We maintain the ID of the request in the topic of the mail
        val pendingDataRequest = pendingDataRequests[requestId]
            ?: throw EnclaveStateException("No pending data request with ID $requestId")

        // TODO - add consensus mechanism

        val condition = try { database.getCondition(pendingDataRequest.dataId) as EthContractDiscloseCondition }
            catch (e: Exception) { throw EnclaveStateException("Could not get condition for data ID ${pendingDataRequest.dataId}") }
        catch (e: IllegalArgumentException) {
            throw EnclaveStateException("Provided data ID does not exist!")
        }

        val authorised = condition.check(message.outputData)

        val messageToRequester = try {
            getData(pendingDataRequest.dataId, authorised)
        } catch (e: EnclaveStateException) {
            ErrorMessage(e.message)
        }

        val encryptedMail = pendingDataRequest.requester.postOffice.encryptMail(messageToRequester.encodeMessage())
        postMail(encryptedMail, pendingDataRequest.requester.routingHint)

        return null
    }

    private fun processOracleRegistrationRequest(
        message: OracleRegistration.Request,
        mail: EnclaveMail,
        routingHint: String?
    ): Message {
        logInfo("Processing Oracle Registration Request!")

        val oracleKey = message.key

        val oracleRegistered = oracleManager.registerOracle(oracleKey, OracleManager.ConnectionData(mail.authenticatedSender, routingHint))

        return if (!oracleRegistered) {
            logInfo("Oracle with key $oracleKey already registered!")
            OracleRegistration.AlreadyRegisteredResponse
        } else {
            logInfo ("Oracle with key $oracleKey registered!")
            OracleRegistration.SuccessResponse
        }
    }

    private fun processDataRequestRequest(
        message: SecretDataRequest.Request,
        mail: EnclaveMail,
        routingHint: String?
    ): Message? {
        logInfo("Processing Data Disclosure Request!")
        logInfo("Requested Data Id: ${message.dataReferenceId}")

        val condition = try { database.getCondition(message.dataReferenceId) }
        catch (e: IllegalArgumentException) {
            throw EnclaveStateException("Provided data ID does not exist!")
        }

        val validatedRequesterKey = try {
            DataRequestValidator.getSignedPublicKey(
                message.dataReferenceId.toByteArray(),
                message.dataReferenceIdSignature
            )
        } catch (e: SignatureException) {
            throw EnclaveStateException("Could not verify signature provided!")
        }

        val authorised = when (condition) {
            is PublicKeyDiscloseCondition ->
                condition.check(validatedRequesterKey)
            is EthContractDiscloseCondition -> {

                @Suppress("UNCHECKED_CAST")
                val requesterAddress = Address(getAddress(validatedRequesterKey)) as Type<Any> // TODO - check that this is safe
                val inputData = condition.inputData.map { item ->
                    // Replace all empty positions in the input data with the Address of the requester
                    if (item.javaClass == AddressPlaceholder::class.java) {
                        requesterAddress
                    } else {
                        item
                    }
                }

                val requestToOracles = OracleEthDataCall.Request(
                    condition.contractAddress,
                    condition.functionName,
                    inputData,
                    condition.outputDataType
                )

                val dataRequestId = UUID.randomUUID()
                val oracles = oracleManager.getOracles()

                if (oracles.isEmpty())
                    throw EnclaveStateException("Could not check the data request as no oracles are online!")

                oracles.forEach { (oracleKey, oracleConnection) ->
                    logInfo("Calling Oracle: ${oracleKey.subSequence(0, 10)}...")
                    val encryptedMail = postOffice(oracleConnection.publicKey, dataRequestId.toString()).encryptMail(requestToOracles.encodeMessage())
                    postMail(encryptedMail, oracleConnection.routingHint)
                }

                val requester = RequesterDetails(routingHint, postOffice(mail), validatedRequesterKey)

                pendingDataRequests[dataRequestId] = PendingDataRequest(
                    message.dataReferenceId,
                    requester,
                    oracles.map { OracleSubmission(it.key, null) })

                // By this point we have sent out all requests to the oracles, so we can wait for the responses
                // will trigger a new execution of the "receiveMail" method, which will process the response
                // That is why we finish the execution here and make the requester wait until we obtain the information
                return null
            }
            else -> throw IllegalArgumentException("Unknown condition type!")
        }

        return getData(message.dataReferenceId, authorised)
    }

    private fun processDataSubmission(
        message: SecretDataSubmission.Submission
    ): Message {
        logInfo("Received a Secret Data Submission!")
        // Store secret data in database
        val storedDataId = database.storeData(message.data, message.condition)

        // Send back the stored data id
        return SecretDataSubmission.Response(storedDataId)
    }


    private fun getData(dataId: UUID, authorised: Boolean): Message {
        try {
            return SecretDataRequest.Response(database.getDataIfAuthorised(dataId, authorised))
        } catch (e: IllegalArgumentException) {
            throw EnclaveStateException("Could not get the requested data!")
        }
    }


    /**
     * Logging function for Enclave
     */
    private fun logInfo(vararg messages: Any) {
        messages.forEach { println("[ENCLAVE] $it") }
    }
}