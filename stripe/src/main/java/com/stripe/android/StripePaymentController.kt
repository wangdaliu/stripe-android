package com.stripe.android

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.HandlerThread
import androidx.annotation.VisibleForTesting
import com.stripe.android.exception.APIException
import com.stripe.android.exception.StripeException
import com.stripe.android.model.ConfirmPaymentIntentParams
import com.stripe.android.model.ConfirmSetupIntentParams
import com.stripe.android.model.ConfirmStripeIntentParams
import com.stripe.android.model.PaymentIntent
import com.stripe.android.model.SetupIntent
import com.stripe.android.model.Source
import com.stripe.android.model.Stripe3ds2AuthResult
import com.stripe.android.model.Stripe3ds2Fingerprint
import com.stripe.android.model.Stripe3dsRedirect
import com.stripe.android.model.StripeIntent
import com.stripe.android.stripe3ds2.service.StripeThreeDs2Service
import com.stripe.android.stripe3ds2.service.StripeThreeDs2ServiceImpl
import com.stripe.android.stripe3ds2.transaction.CompletionEvent
import com.stripe.android.stripe3ds2.transaction.MessageVersionRegistry
import com.stripe.android.stripe3ds2.transaction.ProtocolErrorEvent
import com.stripe.android.stripe3ds2.transaction.RuntimeErrorEvent
import com.stripe.android.stripe3ds2.transaction.StripeChallengeParameters
import com.stripe.android.stripe3ds2.transaction.StripeChallengeStatusReceiver
import com.stripe.android.stripe3ds2.transaction.Transaction
import com.stripe.android.stripe3ds2.views.ChallengeProgressDialogActivity
import com.stripe.android.view.AuthActivityStarter
import java.security.cert.CertificateException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

/**
 * A controller responsible for confirming and authenticating payment (typically through resolving
 * any required customer action). The payment authentication mechanism (e.g. 3DS) will be determined
 * by the [PaymentIntent] or [SetupIntent] object.
 */
internal class StripePaymentController internal constructor(
    context: Context,
    publishableKey: String,
    private val stripeRepository: StripeRepository,
    private val enableLogging: Boolean = false,
    private val messageVersionRegistry: MessageVersionRegistry =
        MessageVersionRegistry(),
    private val config: PaymentAuthConfig =
        PaymentAuthConfig.get(),
    private val threeDs2Service: StripeThreeDs2Service =
        StripeThreeDs2ServiceImpl(context, StripeSSLSocketFactory(), enableLogging),
    private val analyticsRequestExecutor: FireAndForgetRequestExecutor =
        StripeFireAndForgetRequestExecutor(Logger.getInstance(enableLogging)),
    private val analyticsDataFactory: AnalyticsDataFactory =
        AnalyticsDataFactory(context.applicationContext, publishableKey),
    private val challengeFlowStarter: ChallengeFlowStarter =
        ChallengeFlowStarterImpl(),
    private val workScope: CoroutineScope = CoroutineScope(Dispatchers.IO)
) : PaymentController {
    private val logger = Logger.getInstance(enableLogging)
    private val analyticsRequestFactory = AnalyticsRequestFactory(logger)

    init {
        threeDs2Service.initialize(
            config.stripe3ds2Config.uiCustomization.uiCustomization
        )
    }

    /**
     * Confirm the Stripe Intent and resolve any next actions
     */
    override fun startConfirmAndAuth(
        host: AuthActivityStarter.Host,
        confirmStripeIntentParams: ConfirmStripeIntentParams,
        requestOptions: ApiRequest.Options
    ) {
        ConfirmStripeIntentTask(
            stripeRepository, confirmStripeIntentParams, requestOptions, workScope,
            ConfirmStripeIntentCallback(
                host, requestOptions, this, getRequestCode(confirmStripeIntentParams)
            )
        ).execute()
    }

    override fun startAuth(
        host: AuthActivityStarter.Host,
        clientSecret: String,
        requestOptions: ApiRequest.Options
    ) {
        stripeRepository.retrieveIntent(clientSecret, requestOptions,
            object : ApiResultCallback<StripeIntent> {
                override fun onSuccess(result: StripeIntent) {
                    handleNextAction(host, result, requestOptions)
                }

                override fun onError(e: Exception) {
                    handleError(host, PAYMENT_REQUEST_CODE, e)
                }
            }
        )
    }

    override fun startAuthenticateSource(
        host: AuthActivityStarter.Host,
        source: Source,
        requestOptions: ApiRequest.Options
    ) {
        analyticsRequestExecutor.executeAsync(
            analyticsRequestFactory.create(
                analyticsDataFactory.createAuthSourceParams(
                    AnalyticsEvent.AuthSourceStart,
                    source.id
                ),
                requestOptions
            )
        )

        stripeRepository.retrieveSource(
            sourceId = source.id.orEmpty(),
            clientSecret = source.clientSecret.orEmpty(),
            options = requestOptions,
            callback = object : ApiResultCallback<Source> {
                override fun onSuccess(result: Source) {
                    onSourceRetrieved(host, result, requestOptions)
                }

                override fun onError(e: Exception) {
                    handleError(host, SOURCE_REQUEST_CODE, e)
                }
            }
        )
    }

    private fun onSourceRetrieved(
        host: AuthActivityStarter.Host,
        source: Source,
        requestOptions: ApiRequest.Options
    ) {
        if (source.flow == Source.SourceFlow.REDIRECT) {
            analyticsRequestExecutor.executeAsync(
                analyticsRequestFactory.create(
                    analyticsDataFactory.createAuthSourceParams(
                        AnalyticsEvent.AuthSourceRedirect,
                        source.id
                    ),
                    requestOptions
                )
            )

            PaymentAuthWebViewStarter(
                host,
                SOURCE_REQUEST_CODE
            ).start(PaymentAuthWebViewStarter.Args(
                clientSecret = source.clientSecret.orEmpty(),
                url = source.redirect?.url.orEmpty(),
                returnUrl = source.redirect?.returnUrl,
                enableLogging = enableLogging
            ))
        } else {
            bypassAuth(host, source)
        }
    }

    /**
     * Decide whether [handlePaymentResult] should be called.
     */
    override fun shouldHandlePaymentResult(requestCode: Int, data: Intent?): Boolean {
        return requestCode == PAYMENT_REQUEST_CODE && data != null
    }

    /**
     * Decide whether [handleSetupResult] should be called.
     */
    override fun shouldHandleSetupResult(requestCode: Int, data: Intent?): Boolean {
        return requestCode == SETUP_REQUEST_CODE && data != null
    }

    override fun shouldHandleSourceResult(requestCode: Int, data: Intent?): Boolean {
        return requestCode == SOURCE_REQUEST_CODE && data != null
    }

    /**
     * If payment authentication triggered an exception, get the exception object and pass to
     * [ApiResultCallback.onError].
     *
     * Otherwise, get the PaymentIntent's client_secret from {@param data} and use to retrieve
     * the PaymentIntent object with updated status.
     *
     * @param data the result Intent
     */
    override fun handlePaymentResult(
        data: Intent,
        requestOptions: ApiRequest.Options,
        callback: ApiResultCallback<PaymentIntentResult>
    ) {
        val result = PaymentController.Result.fromIntent(data) ?: PaymentController.Result()
        val authException = result.exception
        if (authException is Exception) {
            callback.onError(authException)
            return
        }

        val shouldCancelSource = result.shouldCancelSource
        val sourceId = result.sourceId.orEmpty()
        @StripeIntentResult.Outcome val flowOutcome = result.flowOutcome

        stripeRepository.retrieveIntent(getClientSecret(data), requestOptions,
            createPaymentIntentCallback(
                requestOptions, flowOutcome, sourceId, shouldCancelSource, callback
            )
        )
    }

    /**
     * If setup authentication triggered an exception, get the exception object and pass to
     * [ApiResultCallback.onError].
     *
     * Otherwise, get the SetupIntent's client_secret from {@param data} and use to retrieve the
     * SetupIntent object with updated status.
     *
     * @param data the result Intent
     */
    override fun handleSetupResult(
        data: Intent,
        requestOptions: ApiRequest.Options,
        callback: ApiResultCallback<SetupIntentResult>
    ) {
        val result = PaymentController.Result.fromIntent(data) ?: PaymentController.Result()
        val authException = result.exception
        if (authException is Exception) {
            callback.onError(authException)
            return
        }

        val shouldCancelSource = result.shouldCancelSource
        val sourceId = result.sourceId.orEmpty()
        @StripeIntentResult.Outcome val flowOutcome = result.flowOutcome

        stripeRepository.retrieveIntent(getClientSecret(data), requestOptions,
            createSetupIntentCallback(
                requestOptions, flowOutcome, sourceId, shouldCancelSource, callback
            )
        )
    }

    override fun handleSourceResult(
        data: Intent,
        requestOptions: ApiRequest.Options,
        callback: ApiResultCallback<Source>
    ) {
        val result = PaymentController.Result.fromIntent(data)
        val sourceId = result?.sourceId.orEmpty()
        val clientSecret = result?.clientSecret.orEmpty()

        analyticsRequestExecutor.executeAsync(
            analyticsRequestFactory.create(
                analyticsDataFactory.createAuthSourceParams(
                    AnalyticsEvent.AuthSourceResult,
                    sourceId
                ),
                requestOptions
            )
        )

        stripeRepository.retrieveSource(sourceId, clientSecret, requestOptions, callback)
    }

    private fun createPaymentIntentCallback(
        requestOptions: ApiRequest.Options,
        @StripeIntentResult.Outcome flowOutcome: Int,
        sourceId: String,
        shouldCancelSource: Boolean = false,
        callback: ApiResultCallback<PaymentIntentResult>
    ): ApiResultCallback<StripeIntent> {
        return object : ApiResultCallback<StripeIntent> {
            override fun onSuccess(result: StripeIntent) {
                if (result is PaymentIntent) {
                    if (shouldCancelSource && result.requiresAction()) {
                        logger.debug("Canceling source '$sourceId' for PaymentIntent")
                        stripeRepository.cancelIntent(
                            result,
                            sourceId,
                            requestOptions,
                            createPaymentIntentCallback(
                                requestOptions,
                                flowOutcome,
                                sourceId,
                                false, // don't attempt to cancel source again!
                                callback
                            )
                        )
                    } else {
                        logger.debug("Dispatching PaymentIntentResult for ${result.id}")
                        callback.onSuccess(
                            PaymentIntentResult(result, flowOutcome)
                        )
                    }
                } else {
                    callback.onError(IllegalArgumentException(
                        "Expected a PaymentIntent, received a ${result.javaClass.simpleName}"
                    ))
                }
            }

            override fun onError(e: Exception) {
                callback.onError(e)
            }
        }
    }

    private fun createSetupIntentCallback(
        requestOptions: ApiRequest.Options,
        @StripeIntentResult.Outcome flowOutcome: Int,
        sourceId: String,
        shouldCancelSource: Boolean = false,
        resultCallback: ApiResultCallback<SetupIntentResult>
    ): ApiResultCallback<StripeIntent> {
        return object : ApiResultCallback<StripeIntent> {
            override fun onSuccess(result: StripeIntent) {
                if (result is SetupIntent) {
                    if (shouldCancelSource && result.requiresAction()) {
                        logger.debug("Canceling source '$sourceId' for SetupIntent")
                        stripeRepository.cancelIntent(
                            result,
                            sourceId,
                            requestOptions,
                            createSetupIntentCallback(
                                requestOptions,
                                flowOutcome,
                                sourceId,
                                false, // don't attempt to cancel source again!
                                resultCallback
                            )
                        )
                    } else {
                        logger.debug("Dispatching SetupIntentResult for ${result.id}")
                        resultCallback.onSuccess(
                            SetupIntentResult(result, flowOutcome)
                        )
                    }
                } else {
                    resultCallback.onError(IllegalArgumentException(
                        "Expected a SetupIntent, received a ${result.javaClass.simpleName}"
                    ))
                }
            }

            override fun onError(e: Exception) {
                resultCallback.onError(e)
            }
        }
    }

    /**
     * Determine which authentication mechanism should be used, or bypass authentication
     * if it is not needed.
     */
    @VisibleForTesting
    override fun handleNextAction(
        host: AuthActivityStarter.Host,
        stripeIntent: StripeIntent,
        requestOptions: ApiRequest.Options
    ) {
        if (stripeIntent.requiresAction()) {
            when (stripeIntent.nextActionType) {
                StripeIntent.NextActionType.UseStripeSdk -> {
                    val sdkData = stripeIntent.stripeSdkData
                    when {
                        sdkData?.is3ds2 == true -> {
                            analyticsRequestExecutor.executeAsync(
                                analyticsRequestFactory.create(
                                    analyticsDataFactory.createAuthParams(
                                        AnalyticsEvent.Auth3ds2Fingerprint,
                                        stripeIntent.id.orEmpty()
                                    ),
                                    requestOptions
                                )
                            )
                            try {
                                begin3ds2Auth(host, stripeIntent,
                                    Stripe3ds2Fingerprint.create(sdkData),
                                    requestOptions)
                            } catch (e: CertificateException) {
                                handleError(host, getRequestCode(stripeIntent), e)
                            }
                        }
                        sdkData?.is3ds1 == true -> {
                            analyticsRequestExecutor.executeAsync(
                                analyticsRequestFactory.create(
                                    analyticsDataFactory.createAuthParams(
                                        AnalyticsEvent.Auth3ds1Sdk,
                                        stripeIntent.id.orEmpty()
                                    ),
                                    requestOptions
                                )
                            )
                            beginWebAuth(
                                host,
                                getRequestCode(stripeIntent),
                                stripeIntent.clientSecret.orEmpty(),
                                Stripe3dsRedirect.create(sdkData).url,
                                enableLogging = enableLogging
                            )
                        }
                        else -> // authentication type is not supported
                            bypassAuth(host, stripeIntent)
                    }
                }
                StripeIntent.NextActionType.RedirectToUrl -> {
                    analyticsRequestExecutor.executeAsync(
                        analyticsRequestFactory.create(
                            analyticsDataFactory.createAuthParams(
                                AnalyticsEvent.AuthRedirect,
                                stripeIntent.id.orEmpty()
                            ),
                            requestOptions
                        )
                    )

                    val redirectData = stripeIntent.redirectData
                    beginWebAuth(
                        host,
                        getRequestCode(stripeIntent),
                        stripeIntent.clientSecret.orEmpty(),
                        redirectData?.url.toString(),
                        redirectData?.returnUrl,
                        enableLogging = enableLogging
                    )
                }
                else -> // next action type is not supported, so bypass authentication
                    bypassAuth(host, stripeIntent)
            }
        } else {
            // no action required, so bypass authentication
            bypassAuth(host, stripeIntent)
        }
    }

    private fun bypassAuth(host: AuthActivityStarter.Host, stripeIntent: StripeIntent) {
        PaymentRelayStarter.create(host, getRequestCode(stripeIntent))
            .start(PaymentRelayStarter.Args.create(stripeIntent))
    }

    private fun bypassAuth(host: AuthActivityStarter.Host, source: Source) {
        PaymentRelayStarter.create(host, SOURCE_REQUEST_CODE)
            .start(PaymentRelayStarter.Args.create(source))
    }

    private fun begin3ds2Auth(
        host: AuthActivityStarter.Host,
        stripeIntent: StripeIntent,
        stripe3ds2Fingerprint: Stripe3ds2Fingerprint,
        requestOptions: ApiRequest.Options
    ) {
        val activity = host.activity ?: return

        val transaction = threeDs2Service.createTransaction(
            stripe3ds2Fingerprint.directoryServer.id,
            messageVersionRegistry.current, stripeIntent.isLiveMode,
            stripe3ds2Fingerprint.directoryServer.networkName,
            stripe3ds2Fingerprint.directoryServerEncryption.rootCerts,
            stripe3ds2Fingerprint.directoryServerEncryption.directoryServerPublicKey,
            stripe3ds2Fingerprint.directoryServerEncryption.keyId
        )

        ChallengeProgressDialogActivity.show(
            activity,
            stripe3ds2Fingerprint.directoryServer.networkName,
            false,
            config.stripe3ds2Config.uiCustomization.uiCustomization
        )

        val redirectData = stripeIntent.redirectData
        val returnUrl = redirectData?.returnUrl

        val areqParams = transaction.authenticationRequestParameters
        val timeout = config.stripe3ds2Config.timeout
        val authParams = Stripe3ds2AuthParams(
            stripe3ds2Fingerprint.source,
            areqParams.sdkAppId,
            areqParams.sdkReferenceNumber,
            areqParams.sdkTransactionId,
            areqParams.deviceData,
            areqParams.sdkEphemeralPublicKey,
            areqParams.messageVersion,
            timeout,
            returnUrl
        )
        stripeRepository.start3ds2Auth(
            authParams,
            stripeIntent.id.orEmpty(),
            requestOptions,
            Stripe3ds2AuthCallback(
                host, stripeRepository, transaction, timeout,
                stripeIntent, stripe3ds2Fingerprint.source, requestOptions,
                analyticsRequestExecutor, analyticsDataFactory,
                challengeFlowStarter, enableLogging)
        )
    }

    private class ConfirmStripeIntentTask(
        private val stripeRepository: StripeRepository,
        params: ConfirmStripeIntentParams,
        private val requestOptions: ApiRequest.Options,
        workScope: CoroutineScope,
        callback: ApiResultCallback<StripeIntent>
    ) : ApiOperation<StripeIntent>(workScope, callback) {
        // mark this request as `use_stripe_sdk=true`
        private val params: ConfirmStripeIntentParams =
            params.withShouldUseStripeSdk(shouldUseStripeSdk = true)

        @Throws(StripeException::class)
        override suspend fun getResult(): StripeIntent? {
            return when (params) {
                is ConfirmPaymentIntentParams ->
                    stripeRepository.confirmPaymentIntent(params, requestOptions)
                is ConfirmSetupIntentParams ->
                    stripeRepository.confirmSetupIntent(params, requestOptions)
                else -> null
            }
        }
    }

    private class ConfirmStripeIntentCallback constructor(
        private val host: AuthActivityStarter.Host,
        private val requestOptions: ApiRequest.Options,
        private val paymentController: PaymentController,
        private val requestCode: Int
    ) : ApiResultCallback<StripeIntent> {

        override fun onSuccess(result: StripeIntent) {
            paymentController.handleNextAction(host, result, requestOptions)
        }

        override fun onError(e: Exception) {
            handleError(host, requestCode, e)
        }
    }

    internal class Stripe3ds2AuthCallback @VisibleForTesting internal constructor(
        private val host: AuthActivityStarter.Host,
        private val stripeRepository: StripeRepository,
        private val transaction: Transaction,
        private val maxTimeout: Int,
        private val stripeIntent: StripeIntent,
        private val sourceId: String,
        private val requestOptions: ApiRequest.Options,
        private val analyticsRequestExecutor: FireAndForgetRequestExecutor,
        private val analyticsDataFactory: AnalyticsDataFactory,
        private val challengeFlowStarter: ChallengeFlowStarter,
        private val enableLogging: Boolean = false,
        private val paymentRelayStarter: PaymentRelayStarter =
            PaymentRelayStarter.create(host, getRequestCode(stripeIntent))
    ) : ApiResultCallback<Stripe3ds2AuthResult> {

        private val analyticsRequestFactory: AnalyticsRequestFactory = AnalyticsRequestFactory(
            Logger.getInstance(enableLogging)
        )

        override fun onSuccess(result: Stripe3ds2AuthResult) {
            val ares = result.ares
            if (ares != null) {
                if (ares.isChallenge) {
                    startChallengeFlow(ares)
                } else {
                    startFrictionlessFlow()
                }
            } else if (result.fallbackRedirectUrl != null) {
                analyticsRequestExecutor.executeAsync(
                    analyticsRequestFactory.create(
                        analyticsDataFactory.createAuthParams(
                            AnalyticsEvent.Auth3ds2Fallback,
                            stripeIntent.id.orEmpty()
                        ),
                        requestOptions
                    )
                )
                beginWebAuth(
                    host,
                    getRequestCode(stripeIntent),
                    stripeIntent.clientSecret.orEmpty(),
                    result.fallbackRedirectUrl,
                    enableLogging = enableLogging
                )
            } else {
                val error = result.error
                val errorMessage: String
                errorMessage = if (error != null) {
                    "Code: ${error.errorCode}, " +
                        "Detail: ${error.errorDetail}, " +
                        "Description: ${error.errorDescription}, " +
                        "Component: ${error.errorComponent}"
                } else {
                    "Invalid 3DS2 authentication response"
                }

                onError(RuntimeException(
                    "Error encountered during 3DS2 authentication request. $errorMessage"))
            }
        }

        override fun onError(e: Exception) {
            paymentRelayStarter.start(PaymentRelayStarter.Args.create(
                when (e) {
                    is StripeException -> e
                    else -> APIException(e)
                }
            ))
        }

        private fun startFrictionlessFlow() {
            analyticsRequestExecutor.executeAsync(
                analyticsRequestFactory.create(
                    analyticsDataFactory.createAuthParams(
                        AnalyticsEvent.Auth3ds2Frictionless,
                        stripeIntent.id.orEmpty()
                    ),
                    requestOptions
                )
            )
            paymentRelayStarter.start(PaymentRelayStarter.Args.create(stripeIntent))
        }

        private fun startChallengeFlow(ares: Stripe3ds2AuthResult.Ares) {
            val challengeParameters = StripeChallengeParameters()
            challengeParameters.acsSignedContent = ares.acsSignedContent
            challengeParameters.threeDsServerTransactionId = ares.threeDSServerTransId
            challengeParameters.acsTransactionId = ares.acsTransId

            challengeFlowStarter.start(Runnable {
                val activity = host.activity ?: return@Runnable
                transaction.doChallenge(activity,
                    challengeParameters,
                    PaymentAuth3ds2ChallengeStatusReceiver.create(
                        host,
                        stripeRepository,
                        stripeIntent,
                        sourceId,
                        requestOptions,
                        analyticsRequestExecutor,
                        analyticsDataFactory,
                        transaction,
                        analyticsRequestFactory
                    ),
                    maxTimeout
                )
            })
        }
    }

    internal class PaymentAuth3ds2ChallengeStatusReceiver internal constructor(
        private val stripeRepository: StripeRepository,
        private val stripeIntent: StripeIntent,
        private val sourceId: String,
        private val requestOptions: ApiRequest.Options,
        private val analyticsRequestExecutor: FireAndForgetRequestExecutor,
        private val analyticsDataFactory: AnalyticsDataFactory,
        private val transaction: Transaction,
        private val complete3ds2AuthCallbackFactory: Complete3ds2AuthCallbackFactory,
        private val analyticsRequestFactory: AnalyticsRequestFactory
    ) : StripeChallengeStatusReceiver() {

        override fun completed(completionEvent: CompletionEvent, uiTypeCode: String) {
            super.completed(completionEvent, uiTypeCode)
            analyticsRequestExecutor.executeAsync(
                analyticsRequestFactory.create(
                    analyticsDataFactory.create3ds2ChallengeParams(
                        AnalyticsEvent.Auth3ds2ChallengeCompleted,
                        stripeIntent.id.orEmpty(),
                        uiTypeCode
                    ),
                    requestOptions
                )
            )
            notifyCompletion(Stripe3ds2CompletionStarter.Args(stripeIntent,
                if (VALUE_YES == completionEvent.transactionStatus)
                    Stripe3ds2CompletionStarter.ChallengeFlowOutcome.COMPLETE_SUCCESSFUL
                else
                    Stripe3ds2CompletionStarter.ChallengeFlowOutcome.COMPLETE_UNSUCCESSFUL
            ))
        }

        override fun cancelled(uiTypeCode: String) {
            super.cancelled(uiTypeCode)
            analyticsRequestExecutor.executeAsync(
                analyticsRequestFactory.create(
                    analyticsDataFactory.create3ds2ChallengeParams(
                        AnalyticsEvent.Auth3ds2ChallengeCanceled,
                        stripeIntent.id.orEmpty(),
                        uiTypeCode
                    ),
                    requestOptions
                )
            )
            notifyCompletion(Stripe3ds2CompletionStarter.Args(stripeIntent,
                Stripe3ds2CompletionStarter.ChallengeFlowOutcome.CANCEL))
        }

        override fun timedout(uiTypeCode: String) {
            super.timedout(uiTypeCode)
            analyticsRequestExecutor.executeAsync(
                analyticsRequestFactory.create(
                    analyticsDataFactory.create3ds2ChallengeParams(
                        AnalyticsEvent.Auth3ds2ChallengeTimedOut,
                        stripeIntent.id.orEmpty(),
                        uiTypeCode
                    ),
                    requestOptions
                )
            )
            notifyCompletion(Stripe3ds2CompletionStarter.Args(stripeIntent,
                Stripe3ds2CompletionStarter.ChallengeFlowOutcome.TIMEOUT))
        }

        override fun protocolError(protocolErrorEvent: ProtocolErrorEvent) {
            super.protocolError(protocolErrorEvent)
            analyticsRequestExecutor.executeAsync(
                analyticsRequestFactory.create(
                    analyticsDataFactory.create3ds2ChallengeErrorParams(
                        stripeIntent.id.orEmpty(),
                        protocolErrorEvent
                    ),
                    requestOptions
                )
            )
            notifyCompletion(Stripe3ds2CompletionStarter.Args(stripeIntent,
                Stripe3ds2CompletionStarter.ChallengeFlowOutcome.PROTOCOL_ERROR))
        }

        override fun runtimeError(runtimeErrorEvent: RuntimeErrorEvent) {
            super.runtimeError(runtimeErrorEvent)
            analyticsRequestExecutor.executeAsync(
                analyticsRequestFactory.create(
                    analyticsDataFactory.create3ds2ChallengeErrorParams(
                        stripeIntent.id.orEmpty(),
                        runtimeErrorEvent
                    ),
                    requestOptions
                )
            )
            notifyCompletion(Stripe3ds2CompletionStarter.Args(stripeIntent,
                Stripe3ds2CompletionStarter.ChallengeFlowOutcome.RUNTIME_ERROR))
        }

        private fun notifyCompletion(args: Stripe3ds2CompletionStarter.Args) {
            analyticsRequestExecutor.executeAsync(
                analyticsRequestFactory.create(
                    analyticsDataFactory.create3ds2ChallengeParams(
                        AnalyticsEvent.Auth3ds2ChallengePresented,
                        stripeIntent.id.orEmpty(),
                        transaction.initialChallengeUiType.orEmpty()
                    ),
                    requestOptions
                )
            )

            stripeRepository.complete3ds2Auth(sourceId, requestOptions,
                complete3ds2AuthCallbackFactory.create(args))
        }

        internal interface Complete3ds2AuthCallbackFactory :
            Factory1<Stripe3ds2CompletionStarter.Args, ApiResultCallback<Boolean>>

        internal companion object {
            private const val VALUE_YES = "Y"

            internal fun create(
                host: AuthActivityStarter.Host,
                stripeRepository: StripeRepository,
                stripeIntent: StripeIntent,
                sourceId: String,
                requestOptions: ApiRequest.Options,
                analyticsRequestExecutor: FireAndForgetRequestExecutor,
                analyticsDataFactory: AnalyticsDataFactory,
                transaction: Transaction,
                analyticsRequestFactory: AnalyticsRequestFactory
            ): PaymentAuth3ds2ChallengeStatusReceiver {
                return PaymentAuth3ds2ChallengeStatusReceiver(
                    stripeRepository,
                    stripeIntent,
                    sourceId,
                    requestOptions,
                    analyticsRequestExecutor,
                    analyticsDataFactory,
                    transaction,
                    createComplete3ds2AuthCallbackFactory(
                        Stripe3ds2CompletionStarter(host, getRequestCode(stripeIntent)),
                        host,
                        stripeIntent
                    ),
                    analyticsRequestFactory
                )
            }

            private fun createComplete3ds2AuthCallbackFactory(
                starter: Stripe3ds2CompletionStarter,
                host: AuthActivityStarter.Host,
                stripeIntent: StripeIntent
            ): Complete3ds2AuthCallbackFactory {
                return object : Complete3ds2AuthCallbackFactory {
                    override fun create(arg: Stripe3ds2CompletionStarter.Args):
                        ApiResultCallback<Boolean> {
                        return object : ApiResultCallback<Boolean> {
                            override fun onSuccess(result: Boolean) {
                                starter.start(arg)
                            }

                            override fun onError(e: Exception) {
                                handleError(host, getRequestCode(stripeIntent), e)
                            }
                        }
                    }
                }
            }
        }
    }

    private class ChallengeFlowStarterImpl : ChallengeFlowStarter {
        override fun start(runnable: Runnable) {
            val handlerThread = HandlerThread(Stripe3ds2AuthCallback::class.java.simpleName)
            // create Handler to notifyCompletion challenge flow on background thread
            val handler: Handler = createHandler(handlerThread)

            handler.postDelayed({
                runnable.run()
                handlerThread.quitSafely()
            }, TimeUnit.SECONDS.toMillis(DELAY_SECONDS))
        }

        private companion object {
            private const val DELAY_SECONDS = 2L

            private fun createHandler(handlerThread: HandlerThread): Handler {
                handlerThread.start()
                return Handler(handlerThread.looper)
            }
        }
    }

    internal interface ChallengeFlowStarter {
        fun start(runnable: Runnable)
    }

    internal companion object {
        internal const val PAYMENT_REQUEST_CODE = 50000
        internal const val SETUP_REQUEST_CODE = 50001
        internal const val SOURCE_REQUEST_CODE = 50002

        /**
         * Get the appropriate request code for the given stripe intent type
         *
         * @param intent the [StripeIntent] to get the request code for
         * @return PAYMENT_REQUEST_CODE or SETUP_REQUEST_CODE
         */
        @JvmSynthetic
        internal fun getRequestCode(intent: StripeIntent): Int {
            return if (intent is PaymentIntent) {
                PAYMENT_REQUEST_CODE
            } else {
                SETUP_REQUEST_CODE
            }
        }

        /**
         * Get the appropriate request code for the given stripe intent params type
         *
         * @param params the [ConfirmStripeIntentParams] to get the request code for
         * @return PAYMENT_REQUEST_CODE or SETUP_REQUEST_CODE
         */
        @JvmSynthetic
        internal fun getRequestCode(params: ConfirmStripeIntentParams): Int {
            return if (params is ConfirmPaymentIntentParams) {
                PAYMENT_REQUEST_CODE
            } else {
                SETUP_REQUEST_CODE
            }
        }

        /**
         * Start in-app WebView activity.
         *
         * @param host the payment authentication result will be returned as a result to this view host
         */
        private fun beginWebAuth(
            host: AuthActivityStarter.Host,
            requestCode: Int,
            clientSecret: String,
            authUrl: String,
            returnUrl: String? = null,
            enableLogging: Boolean = false
        ) {
            Logger.getInstance(enableLogging).debug("PaymentAuthWebViewStarter#start()")
            val starter = PaymentAuthWebViewStarter(host, requestCode)
            starter.start(
                PaymentAuthWebViewStarter.Args(clientSecret, authUrl, returnUrl, enableLogging)
            )
        }

        private fun handleError(
            host: AuthActivityStarter.Host,
            requestCode: Int,
            exception: Exception
        ) {
            PaymentRelayStarter.create(host, requestCode)
                .start(PaymentRelayStarter.Args.create(
                    when (exception) {
                        is StripeException -> exception
                        else -> APIException(exception)
                    }
                ))
        }

        @JvmStatic
        @JvmOverloads
        fun create(
            context: Context,
            publishableKey: String,
            stripeRepository: StripeRepository,
            enableLogging: Boolean = false
        ): PaymentController {
            return StripePaymentController(
                context.applicationContext,
                publishableKey,
                stripeRepository,
                enableLogging
            )
        }

        @JvmSynthetic
        internal fun getClientSecret(data: Intent): String {
            return requireNotNull(PaymentController.Result.fromIntent(data)?.clientSecret)
        }
    }
}
