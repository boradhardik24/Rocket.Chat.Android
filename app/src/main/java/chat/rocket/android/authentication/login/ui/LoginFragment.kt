package chat.rocket.android.authentication.login.ui

import DrawableHelper
import android.app.Activity
import android.app.PendingIntent
import android.content.Intent
import android.content.IntentSender
import android.graphics.PorterDuff
import android.os.Build
import android.os.Bundle
import android.support.v4.app.Fragment
import android.support.v4.app.FragmentActivity
import android.text.style.ClickableSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.*
import androidx.core.view.isVisible
import androidx.core.view.postDelayed
import chat.rocket.android.R
import chat.rocket.android.authentication.domain.model.LoginDeepLinkInfo
import chat.rocket.android.authentication.login.presentation.LoginPresenter
import chat.rocket.android.authentication.login.presentation.LoginView
import chat.rocket.android.helper.KeyboardHelper
import chat.rocket.android.helper.TextHelper
import chat.rocket.android.util.extensions.*
import chat.rocket.android.webview.cas.ui.INTENT_CAS_TOKEN
import chat.rocket.android.webview.cas.ui.casWebViewIntent
import chat.rocket.android.webview.oauth.ui.INTENT_OAUTH_CREDENTIAL_SECRET
import chat.rocket.android.webview.oauth.ui.INTENT_OAUTH_CREDENTIAL_TOKEN
import chat.rocket.android.webview.oauth.ui.oauthWebViewIntent
import chat.rocket.common.util.ifNull
import com.google.android.gms.auth.api.Auth
import com.google.android.gms.auth.api.credentials.*
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.GoogleApiClient
import com.google.android.gms.common.api.ResolvingResultCallbacks
import com.google.android.gms.common.api.Status
import dagger.android.support.AndroidSupportInjection
import kotlinx.android.synthetic.main.fragment_authentication_log_in.*
import timber.log.Timber
import javax.inject.Inject


internal const val REQUEST_CODE_FOR_CAS = 1
internal const val REQUEST_CODE_FOR_OAUTH = 2
internal const val MULTIPLE_CREDENTIALS_READ = 3
internal const val NO_CREDENTIALS_EXIST = 4
internal const val SAVE_CREDENTIALS = 5

lateinit var googleApiClient: GoogleApiClient

class LoginFragment : Fragment(), LoginView, GoogleApiClient.ConnectionCallbacks {
    @Inject
    lateinit var presenter: LoginPresenter
    private var isOauthViewEnable = false
    private val layoutListener = ViewTreeObserver.OnGlobalLayoutListener {
        areLoginOptionsNeeded()
    }
    private var isOauthSuccessful = false
    private var isGlobalLayoutListenerSetUp = false
    private var deepLinkInfo: LoginDeepLinkInfo? = null
    private var credentialsToBeSaved: Credential? = null

    companion object {
        private const val DEEP_LINK_INFO = "DeepLinkInfo"

        fun newInstance(deepLinkInfo: LoginDeepLinkInfo? = null) = LoginFragment().apply {
            arguments = Bundle().apply {
                putParcelable(DEEP_LINK_INFO, deepLinkInfo)
            }
        }
    }

    override fun onConnected(bundle: Bundle?) {
        saveSmartLockCredentials(credentialsToBeSaved)
    }

    override fun onConnectionSuspended(errorCode: Int) {
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AndroidSupportInjection.inject(this)
        buildGoogleApiClient()
        deepLinkInfo = arguments?.getParcelable(DEEP_LINK_INFO)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? =
        container?.inflate(R.layout.fragment_authentication_log_in)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.M) {
            tintEditTextDrawableStart()
        }

        deepLinkInfo?.let {
            presenter.authenticateWithDeepLink(it)
        }.ifNull {
            presenter.setupView()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        if (isGlobalLayoutListenerSetUp) {
            scroll_view.viewTreeObserver.removeOnGlobalLayoutListener(layoutListener)
            isGlobalLayoutListenerSetUp = false
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (resultCode == Activity.RESULT_OK) {
            if (data != null) {
                when (requestCode) {
                    REQUEST_CODE_FOR_CAS -> data.apply {
                        presenter.authenticateWithCas(getStringExtra(INTENT_CAS_TOKEN))
                    }
                    REQUEST_CODE_FOR_OAUTH -> {
                        isOauthSuccessful = true
                        data.apply {
                            presenter.authenticateWithOauth(
                                getStringExtra(INTENT_OAUTH_CREDENTIAL_TOKEN),
                                getStringExtra(INTENT_OAUTH_CREDENTIAL_SECRET)
                            )
                        }
                    }
                    MULTIPLE_CREDENTIALS_READ -> {
                        val loginCredentials: Credential =
                            data.getParcelableExtra(Credential.EXTRA_KEY)
                        handleCredential(loginCredentials)
                    }
                    NO_CREDENTIALS_EXIST -> {
                        //use the hints to autofill sign in forms to reduce the info to be filled
                        val loginCredentials: Credential =
                            data.getParcelableExtra(Credential.EXTRA_KEY)
                        val email = loginCredentials.id
                        val password = loginCredentials.password

                        text_username_or_email.setText(email)
                        text_password.setText(password)
                    }
                    SAVE_CREDENTIALS -> Toast.makeText(
                        context,
                        getString(R.string.message_credentials_saved_successfully),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
        //cancel button pressed by the user in case of reading from smart lock
        else if (resultCode == Activity.RESULT_CANCELED && requestCode == REQUEST_CODE_FOR_OAUTH) {
            Timber.d("Returned from oauth")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        googleApiClient.let {
            activity?.let { it1 -> it.stopAutoManage(it1) }
            it.disconnect()
        }
    }

    private fun buildGoogleApiClient() {
        googleApiClient = GoogleApiClient.Builder(context!!)
            .enableAutoManage(activity as FragmentActivity, {
                Timber.e("ERROR: Connection to client failed")
            })
            .addConnectionCallbacks(this)
            .addApi(Auth.CREDENTIALS_API)
            .build()
    }

    override fun onStart() {
        super.onStart()
        if (!isOauthSuccessful) {
            requestCredentials()
        }
    }

    private fun requestCredentials() {
        val request: CredentialRequest = CredentialRequest.Builder()
            .setPasswordLoginSupported(true)
            .build()

        Auth.CredentialsApi.request(googleApiClient, request)
            .setResultCallback { credentialRequestResult ->
                val status = credentialRequestResult.status
                when {
                    status.isSuccess -> handleCredential(credentialRequestResult.credential)
                    (status.statusCode == CommonStatusCodes.RESOLUTION_REQUIRED) -> resolveResult(
                        status,
                        MULTIPLE_CREDENTIALS_READ
                    )
                    (status.statusCode == CommonStatusCodes.SIGN_IN_REQUIRED) -> {
                        val hintRequest: HintRequest = HintRequest.Builder()
                            .setHintPickerConfig(
                                CredentialPickerConfig.Builder()
                                    .setShowCancelButton(true)
                                    .build()
                            )
                            .setEmailAddressIdentifierSupported(true)
                            .setAccountTypes(IdentityProviders.GOOGLE)
                            .build()
                        val intent: PendingIntent =
                            Auth.CredentialsApi.getHintPickerIntent(googleApiClient, hintRequest)
                        try {
                            startIntentSenderForResult(
                                intent.intentSender,
                                NO_CREDENTIALS_EXIST,
                                null,
                                0,
                                0,
                                0,
                                null
                            )
                        } catch (e: IntentSender.SendIntentException) {
                            Timber.e("ERROR: Could not start hint picker Intent")
                        }
                    }
                    else -> Timber.d("ERROR: nothing happening")
                }
            }
    }

    private fun handleCredential(loginCredentials: Credential) {
        if (loginCredentials.accountType == null) {
            presenter.authenticateWithUserAndPassword(
                loginCredentials.id,
                loginCredentials.password.toString()
            )
        }
    }

    private fun resolveResult(status: Status, requestCode: Int) {
        try {
            status.startResolutionForResult(activity, requestCode)
        } catch (e: IntentSender.SendIntentException) {
            Timber.e("Failed to send Credentials intent")
        }
    }

    override fun saveSmartLockCredentials(loginCredential: Credential?) {
        credentialsToBeSaved = loginCredential
        if (credentialsToBeSaved == null) {
            return
        }

        activity?.let {
            Auth.CredentialsApi.save(googleApiClient, credentialsToBeSaved).setResultCallback(
                object : ResolvingResultCallbacks<Status>(it, SAVE_CREDENTIALS) {
                    override fun onSuccess(status: Status) {
                        Timber.d("credentials save:SUCCESS:$status")
                        credentialsToBeSaved = null
                    }

                    override fun onUnresolvableFailure(status: Status) {
                        Timber.e("credentials save:FAILURE:$status")
                        credentialsToBeSaved = null
                    }
                })
        }
    }

    private fun tintEditTextDrawableStart() {
        ui {
            val personDrawable =
                DrawableHelper.getDrawableFromId(R.drawable.ic_assignment_ind_black_24dp, it)
            val lockDrawable = DrawableHelper.getDrawableFromId(R.drawable.ic_lock_black_24dp, it)

            val drawables = arrayOf(personDrawable, lockDrawable)
            DrawableHelper.wrapDrawables(drawables)
            DrawableHelper.tintDrawables(drawables, it, R.color.colorDrawableTintGrey)
            DrawableHelper.compoundDrawables(
                arrayOf(text_username_or_email, text_password),
                drawables
            )
        }
    }

    override fun showLoading() {
        ui {
            view_loading.isVisible = true
        }
    }

    override fun hideLoading() {
        ui {
            view_loading.isVisible = false
        }
    }

    override fun showMessage(resId: Int) {
        ui {
            showToast(resId)
        }
    }

    override fun showMessage(message: String) {
        ui {
            showToast(message)
        }
    }

    override fun showGenericErrorMessage() {
        showMessage(R.string.msg_generic_error)
    }

    override fun showFormView() {
        ui {
            text_username_or_email.isVisible = true
            text_password.isVisible = true
        }
    }

    override fun hideFormView() {
        ui {
            text_username_or_email.isVisible = false
            text_password.isVisible = false
        }
    }

    override fun setupLoginButtonListener() {
        ui {
            button_log_in.setOnClickListener {
                presenter.authenticateWithUserAndPassword(
                    text_username_or_email.textContent,
                    text_password.textContent
                )
            }
        }
    }

    override fun enableUserInput() {
        ui {
            button_log_in.isEnabled = true
            text_username_or_email.isEnabled = true
            text_password.isEnabled = true
        }
    }

    override fun disableUserInput() {
        ui {
            button_log_in.isEnabled = false
            text_username_or_email.isEnabled = false
            text_password.isEnabled = false
        }
    }

    override fun showCasButton() {
        ui {
            button_cas.isVisible = true
        }
    }

    override fun hideCasButton() {
        ui {
            button_cas.isVisible = false
        }
    }

    override fun setupCasButtonListener(casUrl: String, casToken: String) {
        ui { activity ->
            button_cas.setOnClickListener {
                startActivityForResult(
                    activity.casWebViewIntent(casUrl, casToken),
                    REQUEST_CODE_FOR_CAS
                )
                activity.overridePendingTransition(R.anim.slide_up, R.anim.hold)
            }
        }
    }

    override fun showSignUpView() {
        ui {
            text_new_to_rocket_chat.isVisible = true
        }
    }

    override fun setupSignUpView() {
        ui {
            val signUp = getString(R.string.title_sign_up)
            val newToRocketChat = String.format(getString(R.string.msg_new_user), signUp)

            text_new_to_rocket_chat.text = newToRocketChat

            val signUpListener = object : ClickableSpan() {
                override fun onClick(view: View) = presenter.signup()
            }

            TextHelper.addLink(text_new_to_rocket_chat, arrayOf(signUp), arrayOf(signUpListener))
        }
    }

    override fun showForgotPasswordView() {
        ui {
            text_forgot_your_password.isVisible = true
        }
    }

    override fun setupForgotPasswordView() {
        ui {
            val reset = getString(R.string.msg_reset)
            val forgotPassword = String.format(getString(R.string.msg_forgot_password), reset)

            text_forgot_your_password.text = forgotPassword

            val resetListener = object : ClickableSpan() {
                override fun onClick(view: View) = presenter.forgotPassword()
            }

            TextHelper.addLink(text_forgot_your_password, arrayOf(reset), arrayOf(resetListener))
        }
    }

    override fun hideSignUpView() {
        ui {
            text_new_to_rocket_chat.isVisible = false
        }
    }

    override fun enableOauthView() {
        ui {
            isOauthViewEnable = true
            showThreeSocialAccountsMethods()
            social_accounts_container.isVisible = true
        }
    }

    override fun disableOauthView() {
        ui {
            isOauthViewEnable = false
            social_accounts_container.isVisible = false
        }
    }

    override fun showLoginButton() {
        ui {
            button_log_in.isVisible = true
        }
    }

    override fun hideLoginButton() {
        ui {
            button_log_in.isVisible = false
        }
    }

    override fun enableLoginByFacebook() {
        ui {
            button_facebook.isClickable = true
        }
    }

    override fun setupFacebookButtonListener(facebookOauthUrl: String, state: String) {
        ui { activity ->
            button_facebook.setOnClickListener {
                startActivityForResult(
                    activity.oauthWebViewIntent(facebookOauthUrl, state),
                    REQUEST_CODE_FOR_OAUTH
                )
                activity.overridePendingTransition(R.anim.slide_up, R.anim.hold)
            }
        }
    }

    override fun enableLoginByGithub() {
        ui {
            button_github.isClickable = true
        }
    }

    override fun setupGithubButtonListener(githubUrl: String, state: String) {
        ui { activity ->
            button_github.setOnClickListener {
                startActivityForResult(
                    activity.oauthWebViewIntent(githubUrl, state),
                    REQUEST_CODE_FOR_OAUTH
                )
                activity.overridePendingTransition(R.anim.slide_up, R.anim.hold)
            }
        }
    }

    override fun enableLoginByGoogle() {
        ui {
            button_google.isClickable = true
        }
    }

    // TODO: Use custom tabs instead of web view.
    // See https://github.com/RocketChat/Rocket.Chat.Android/issues/968
    override fun setupGoogleButtonListener(googleUrl: String, state: String) {
        ui { activity ->
            button_google.setOnClickListener {
                startActivityForResult(
                    activity.oauthWebViewIntent(googleUrl, state),
                    REQUEST_CODE_FOR_OAUTH
                )
                activity.overridePendingTransition(R.anim.slide_up, R.anim.hold)
            }
        }
    }

    override fun enableLoginByLinkedin() {
        ui {
            button_linkedin.isClickable = true
        }
    }

    override fun setupLinkedinButtonListener(linkedinUrl: String, state: String) {
        ui { activity ->
            button_linkedin.setOnClickListener {
                startActivityForResult(
                    activity.oauthWebViewIntent(linkedinUrl, state),
                    REQUEST_CODE_FOR_OAUTH
                )
                activity.overridePendingTransition(R.anim.slide_up, R.anim.hold)
            }
        }
    }

    override fun enableLoginByMeteor() {
        ui {
            button_meteor.isClickable = true
        }
    }

    override fun enableLoginByTwitter() {
        ui {
            button_twitter.isClickable = true
        }
    }

    override fun enableLoginByGitlab() {
        ui {
            button_gitlab.isClickable = true
        }
    }

    override fun setupGitlabButtonListener(gitlabUrl: String, state: String) {
        ui { activity ->
            button_gitlab.setOnClickListener {
                startActivityForResult(
                    activity.oauthWebViewIntent(gitlabUrl, state),
                    REQUEST_CODE_FOR_OAUTH
                )
                activity.overridePendingTransition(R.anim.slide_up, R.anim.hold)
            }
        }
    }

    override fun addCustomOauthServiceButton(
        customOauthUrl: String,
        state: String,
        serviceName: String,
        serviceNameColor: Int,
        buttonColor: Int
    ) {
        ui { activity ->
            val button = getCustomOauthButton(serviceName, serviceNameColor, buttonColor)
            social_accounts_container.addView(button)

            button.setOnClickListener {
                startActivityForResult(
                    activity.oauthWebViewIntent(customOauthUrl, state),
                    REQUEST_CODE_FOR_OAUTH
                )
                activity.overridePendingTransition(R.anim.slide_up, R.anim.hold)
            }
        }
    }

    override fun setupFabListener() {
        ui {
            button_fab.isVisible = true
            button_fab.setOnClickListener({
                button_fab.hide()
                showRemainingSocialAccountsView()
                scrollToBottom()
            })
        }
    }

    override fun setupGlobalListener() {
        // We need to setup the layout to hide and show the oauth interface when the soft keyboard
        // is shown (which means that the user has touched the text_username_or_email or
        // text_password EditText to fill that respective fields).
        if (!isGlobalLayoutListenerSetUp) {
            scroll_view.viewTreeObserver.addOnGlobalLayoutListener(layoutListener)
            isGlobalLayoutListenerSetUp = true
        }
    }

    override fun alertWrongUsernameOrEmail() {
        ui {
            vibrateSmartPhone()
            text_username_or_email.shake()
            text_username_or_email.requestFocus()
        }
    }

    override fun alertWrongPassword() {
        ui {
            vibrateSmartPhone()
            text_password.shake()
            text_password.requestFocus()
        }
    }

    private fun showRemainingSocialAccountsView() {
        social_accounts_container.postDelayed(300) {
            ui {
                (0..social_accounts_container.childCount)
                    .mapNotNull { social_accounts_container.getChildAt(it) as? ImageButton }
                    .filter { it.isClickable }
                    .forEach { it.isVisible = true }
            }
        }
    }

    // Scrolling to the bottom of the screen.
    private fun scrollToBottom() {
        scroll_view.postDelayed(1250) {
            ui {
                scroll_view.fullScroll(ScrollView.FOCUS_DOWN)
            }
        }
    }


    private fun areLoginOptionsNeeded() {
        if (!isEditTextEmpty() || KeyboardHelper.isSoftKeyboardShown(scroll_view.rootView)) {
            hideSignUpView()
            hideOauthView()
            showLoginButton()
        } else {
            showSignUpView()
            showOauthView()
            hideLoginButton()
        }
    }

    // Returns true if *all* EditTexts are empty.
    private fun isEditTextEmpty(): Boolean {
        return text_username_or_email.textContent.isBlank() && text_password.textContent.isEmpty()
    }

    private fun showThreeSocialAccountsMethods() {
        (0..social_accounts_container.childCount)
            .mapNotNull { social_accounts_container.getChildAt(it) as? ImageButton }
            .filter { it.isClickable }
            .take(3)
            .forEach { it.isVisible = true }
    }

    private fun showOauthView() {
        if (isOauthViewEnable) {
            social_accounts_container.isVisible = true
            if (enabledSocialAccounts() > 3) {
                button_fab.isVisible = true
            }
        }
    }

    private fun hideOauthView() {
        if (isOauthViewEnable) {
            social_accounts_container.isVisible = false
            button_fab.isVisible = false
        }
    }

    private fun enabledSocialAccounts(): Int {
        return enabledOauthAccountsImageButtons() + enabledServicesAccountsButtons()
    }

    private fun enabledOauthAccountsImageButtons(): Int {
        return (0..social_accounts_container.childCount)
            .mapNotNull { social_accounts_container.getChildAt(it) as? ImageButton }
            .filter { it.isClickable }
            .size
    }

    private fun enabledServicesAccountsButtons(): Int {
        return (0..social_accounts_container.childCount)
            .mapNotNull { social_accounts_container.getChildAt(it) as? Button }
            .size
    }

    /**
     * Gets a stylized custom OAuth button.
     */
    private fun getCustomOauthButton(
        buttonText: String,
        buttonTextColor: Int,
        buttonBgColor: Int
    ): Button {
        val params: LinearLayout.LayoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )

        val margin = resources.getDimensionPixelSize(R.dimen.screen_edge_left_and_right_margins)
        params.setMargins(margin, margin, margin, 0)

        val button = Button(context)
        button.layoutParams = params
        button.text = buttonText
        button.setTextColor(buttonTextColor)
        button.background.setColorFilter(buttonBgColor, PorterDuff.Mode.MULTIPLY)

        return button
    }
}