package com.shinhan.newapp.ui.common

import android.Manifest
import android.Manifest.permission.WRITE_EXTERNAL_STORAGE
import android.app.Dialog
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.shinhan.common.*
import com.shinhan.common.constant.KEY_AUTH_PARAM
import com.shinhan.common.constant.KEY_SERVICE_SEQ
import com.shinhan.common.constant.KEY_SHAKE_SETTING_DATA
import com.shinhan.common.constant.SERVICE_SEQ_AFTER_LOGIN
import com.shinhan.common.contract.GoAppDetailsSettings
import com.shinhan.common.extension.collectRepeatOnStarted
import com.shinhan.common.extension.getStringOrNull
import com.shinhan.common.model.NoFaceEntryArgs
import com.shinhan.domain.entity.common.DataResult
import com.shinhan.domain.entity.common.ProgramEntity
import com.shinhan.domain.entity.intro.NetfunnelEntity
import com.shinhan.domain.entity.intro.NetfunnelState
import com.shinhan.domain.entity.intro.ProgramNoticeEntity
import com.shinhan.domain.entity.login.CustomerStatus
import com.shinhan.domain.entity.login.MemberGrade
import com.shinhan.domain.entity.notice.NoticeEntity
import com.shinhan.newapp.BankingApplication
import com.shinhan.newapp.R
import com.shinhan.newapp.databinding.LayoutPermissionCheckBinding
import com.shinhan.newapp.model.LoginType
import com.shinhan.newapp.model.MainSubTabConstants
import com.shinhan.newapp.model.MainTabConstants
import com.shinhan.newapp.model.ProgramLinkModel
import com.shinhan.newapp.ui.MainActivity
import com.shinhan.newapp.ui.allaccount.AllAccountActivity
import com.shinhan.newapp.ui.deeplink.EntryActivity
import com.shinhan.newapp.ui.home.represent.ChangeRepresentModeActivity
import com.shinhan.newapp.ui.intro.IntroActivity
import com.shinhan.newapp.ui.login.LoginActivity
import com.shinhan.newapp.ui.noface.entry.NoFaceEntryWebViewActivity
import com.shinhan.newapp.ui.sharedplatform.SharedPlatformUiInteractionImpl
import com.shinhan.newapp.ui.transfer.bridge.TransferBridgeActivity
import com.shinhan.newapp.ui.webview.PartnerWebViewActivity
import com.shinhan.newapp.ui.webview.WebViewActivity
import com.shinhan.newapp.ui.webview.food.FoodWebViewActivity
import com.shinhan.remote.network.SessionTimer
import com.shinhan.web.PluginContextImpl
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONException
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

/**
 *  BaseActivity.kt
 *
 *  Created by Sangeun Lee on 2022/03/02
 *  Copyright ?? 2021 Shinhan Bank. All rights reserved.
 */

@AndroidEntryPoint
open class BaseActivity : AppCompatActivity(), SensorEventListener {
    val baseViewModel: BaseViewModel by viewModels()

    private var essentialPermissionList: List<String>? = null
    private var optionalPermissionList: List<String>? = null

    private val deniedEssentialPermissionList = mutableListOf<String>()
    private val deniedOptionalPermissionList = mutableListOf<String>()
    private val deniedTotalPermissionList = mutableListOf<String>()

    private val loginLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (it.resultCode == RESULT_OK) {
                baseViewModel.setCheckAfterLoginPopup()
            } else {
                baseViewModel.clearCachedProgramData()
            }
        }

    private val sensorManager by lazy {
        getSystemService(Context.SENSOR_SERVICE) as SensorManager
    }

    /**
     * ?????????????????? ???????????? ????????? ???????????? launcher
     */
    private val changeBankingGradeLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (it.resultCode == RESULT_OK) {
                val param = it.data?.getStringExtra(PluginContextImpl.EXTRA_DATA_FEEDBACK)
                if (!param.isNullOrBlank()) {
                    val isJoinBankingSuccessful = JSONObject(param).optString("isJoinBankingSuccessful", "")
                    if (isJoinBankingSuccessful.lowercase() == "true") {
                        // ?????????????????? ????????????????????? ??????????????? ???????????? ??????
                        baseViewModel.getCachedProgramData()?.let { program ->
                            canGoPRID(program.programEntity, program.param, program.requestId)
                        }
                    }
                }
            }
            baseViewModel.clearCachedProgramData()
        }

    /**
     * ??????????????? ?????? ???????????? ??? ???????????? ???????????? ????????? ???????????? launcher
     */
    private val smartBankingTermsLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (it.resultCode == RESULT_OK) {
                val param = it.data?.getStringExtra(PluginContextImpl.EXTRA_DATA_FEEDBACK)
                if (!param.isNullOrBlank()) {
                    val paramObject = JSONObject(param)
                    if (paramObject.optBoolean("????????????")) {
                        if (baseViewModel.needAdditionalAuth()) {
                            moveToAdditionalAuthScreen()
                        } else {
                            finish()
                        }
                        return@registerForActivityResult
                    }
                }
            }

            logoutAndGoHomeOrPage()
        }

    /**
     * ????????????(ARS) ???????????? ????????? ???????????? launcher
     */
    private val additionalAuthLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (it.resultCode == RESULT_OK) {
                val param = it.data?.getStringExtra(PluginContextImpl.EXTRA_DATA_FEEDBACK)
                if (!param.isNullOrBlank()) {
                    val paramObject = JSONObject(param)
                    if (paramObject.optString("????????????") == "Y") {
                        finish()
                        return@registerForActivityResult
                    }
                }
            }

            showNoAdditionalAuthDialog()
        }

    /**
     * ???????????? ???????????? ????????? ???????????? launcher
     */
    private val memberAuthUserLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (it.resultCode == RESULT_OK) {
                val param = it.data?.getStringExtra(PluginContextImpl.EXTRA_DATA_FEEDBACK)
                if (!param.isNullOrBlank()) {
                    val isMemberAuthSuccessful = JSONObject(param).optString("isMemberAuthSuccess", "")
                    if (isMemberAuthSuccessful.lowercase() == "true") {
                        // ???????????? ????????????????????? ??????????????? ???????????? ??????
                        baseViewModel.getCachedProgramData()?.let { program ->
                            canGoPRID(program.programEntity, program.param, program.requestId)
                        }
                    }
                }
            }
            baseViewModel.clearCachedProgramData()
        }

    private var refreshLoginSessionBottomSheetInstance: RefreshLoginSessionBottomSheetFragment? =
        null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (this !is IntroActivity && this !is EntryActivity) {
            checkUsesPermission()
        }

        setSessionTimerCollector()
    }

    override fun onResume() {
        super.onResume()

        // ???????????? & ???????????? ??????
        CoroutineScope(Dispatchers.Default).launch {
            BankingApplication.getInstance().checkRemote()
            BankingApplication.getInstance().checkMalware()
        }

        if (baseViewModel.isLoginUseCase()) { // ????????? ??? resume?????? ???????????? register ????????? ????????? ?????? ??????
            sensorManager.registerListener(
                this,
                sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER),
                SensorManager.SENSOR_DELAY_UI
            )
        }
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
    }

    override fun setContentView(layoutResID: Int) {
        /* TODO ?????????????????? ????????? ???????????????. ?????? ?????????????????? ???????????? ?????? ?????? ???????????? ????????? ??????.
        if (this !is WebViewActivity && this !is TransferBridgeActivity) {
            val viewGroup = window.decorView.rootView as ViewGroup
            val defectView = DefectView(this)
            viewGroup.addView(defectView)
        }
        */

        if (BuildConfig.DEBUG && ServerInfo.currentServerType != ServerInfo.ServerType.PROD) {
            val serverText = TextView(this)
            serverText.apply {
                textSize = 4.dp.toFloat()
                text =
                    if (ServerInfo.currentServerType == ServerInfo.ServerType.DEV) "DEV" else "TEST"
                setTextColor(resources.getColor(R.color.black, null))
                x = -4.dp.toFloat()
                y = 50.dp.toFloat()
                gravity = Gravity.END
            }

            val viewGroup = window.decorView.rootView as ViewGroup
            viewGroup.addView(serverText)
        }

        super.setContentView(layoutResID)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        var isGranted = true
        val deniedPermissionList = mutableListOf<String>()
        permissions.forEachIndexed { index, permission ->
            if (grantResults[index] != PackageManager.PERMISSION_GRANTED) {
                if (deniedEssentialPermissionList.find { it == permission } != null) {
                    deniedPermissionList.add(permissions[index])
                    isGranted = false
                }
            }
        }

        if (!isGranted) { // ?????? ?????? ??? ????????? ????????? ?????? ??????
            val deniedPermissionNameList = mutableListOf<String>()
            deniedPermissionList.forEach {
                val deniedPermission = when (it) {
                    Manifest.permission.READ_PHONE_NUMBERS,
                    Manifest.permission.READ_PHONE_STATE,
                    -> getString(R.string.permission_phone)
                    WRITE_EXTERNAL_STORAGE -> getString(R.string.permission_storage)
                    else -> null
                }

                deniedPermission?.let { deniedPermissionNameList.add("[$deniedPermission]") }
            }

            val dialogMessage =
                getString(
                    R.string.permission_denied_popup,
                    deniedPermissionNameList.joinToString(", ")
                )
            CustomDialogFragment.Builder()
                .setMessage(dialogMessage)
                .setPositiveButton(getString(R.string.ok_button)) {
                    goToApplicationDetailsSettings()
                    finish()
                }
                .setNegativeButton(getString(R.string.no_button)) {
                    finish()
                }
                .setCancelable(false)
                .show(supportFragmentManager)
        }
    }

    private var netfunnelBS: NetFunnelBottomSheetDialogFragment? = null
    private fun showNetFunnelBottomSheet(waitCountText: String, waitTimeText: String, onCloseEvent: (() -> Unit)? = null) {
        if (netfunnelBS == null) {
            netfunnelBS = NetFunnelBottomSheetDialogFragment.newInstance(waitCountText, waitTimeText, onCloseEvent)
            netfunnelBS?.show(supportFragmentManager, TAG)
        } else {
            netfunnelBS?.setText(waitCountText, waitTimeText)
        }
    }

    private fun closeNetFunnelBottomSheet() {
        netfunnelBS?.dismiss()
        netfunnelBS = null
    }

    fun collectNetFunnelData(netfunnelData: MutableStateFlow<NetfunnelEntity>) {
        netfunnelData.value = NetfunnelEntity(NetfunnelState.NONE)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                netfunnelData.collect {
                    when (it.state) {
                        NetfunnelState.START -> {
                            LogUtil.d("???????????? ??????", TAG)
                        }
                        NetfunnelState.PROGRESS -> {
                            LogUtil.d("???????????? ${it.remainTime}??? ???????????????. ${it.waitingPersonCount}??? ???????????????.", TAG)

                            val waitCountText = String.format(getString(R.string.flow_control_count), it.waitingPersonCount)
                            val waitTimeText = getRemainTime(it.remainTime)

                            if (this@BaseActivity is IntroActivity) {
                                hideCommonLoading()
                                binding.flowControlLayout.visibility = View.VISIBLE
                                binding.splashSubImage.setGone()
                                binding.waitCount.text = waitCountText
                                binding.waitTime.text = waitTimeText
                            } else {
                                showNetFunnelBottomSheet(waitCountText, waitTimeText){
                                    if (this@BaseActivity is WebViewActivity) setJSResult?.invoke(false)
                                    baseViewModel.netFunnelManager.exitNetFunnel()
                                    cancel()
                                    closeNetFunnelBottomSheet()
                                }
                            }
                        }
                        NetfunnelState.BLOCK -> {
                            LogUtil.d("???????????? Block", TAG)

                            val blockNoticeData = it.blockNoticeData
                            if (this@BaseActivity is IntroActivity) {
                                hideCommonLoading()
                                binding.splashImage.setGone()
                                binding.splashSubImage.setGone()
                                binding.netFunnelBlockLayout.apply {
                                    root.setVisible()
                                    notice = blockNoticeData
                                    if (blockNoticeData.solDownload == "Y") {
                                        downloadPrevVerButton.setVisible()
                                    }
                                }
                            }
                            // ????????????????????? ?????? ?????? ??????.
                            cancel()
                        }
                        NetfunnelState.END -> {
                            LogUtil.d("???????????? ??????", TAG)
                            if (this@BaseActivity is IntroActivity) {
                                hideCommonLoading()
                                binding.flowControlLayout.visibility = View.GONE
                                binding.splashSubImage.setVisible()
                            } else {
                                closeNetFunnelBottomSheet()
                                if (this@BaseActivity is WebViewActivity) {
                                    setJSResult?.invoke(true)
                                } else {
                                    baseViewModel.getCachedProgramData()?.let { program ->
                                        startActivityWithProgram(program.programEntity, program.param, program.requestId)
                                        baseViewModel.clearCachedProgramData()
                                    }
                                }
                            }
                            cancel()
                        }
                    }
                }
            }
        }
    }

    private fun getRemainTime(seconds: Int): String {
        val minute =
            TimeUnit.SECONDS.toMinutes(seconds.toLong()) - TimeUnit.SECONDS.toHours(seconds.toLong()) * 60
        val second =
            TimeUnit.SECONDS.toSeconds(seconds.toLong()) - TimeUnit.SECONDS.toMinutes(seconds.toLong()) * 60
        var timeString = ""
        if (minute > 0) {
            timeString += minute.toString() + "??? "
        }
        timeString += second.toString() + "???"
        return timeString
    }

    fun setCommonAlert(viewModel: BaseViewModel) {
        // ???????????????
        viewModel.showDuplicateLoginErrorEvent.collectRepeatOnStarted(this) {
            if (supportFragmentManager.findFragmentByTag(DuplicateLoginBottomSheetFragment.TAG) != null) {
                return@collectRepeatOnStarted
            }
            DuplicateLoginBottomSheetFragment.newInstance(
                logoutAndGoPage = { logoutAndGoHomeOrPage(it) },
                logoutAndGoHome = ::logoutAndGoHomeOrPage
            ).show(
                supportFragmentManager,
                DuplicateLoginBottomSheetFragment.TAG
            )
        }

        // ??????????????? ?????? ??????
        viewModel.showCommonErrorLogoutEvent.collectRepeatOnStarted(this) { alertMessage ->
            if (supportFragmentManager.findFragmentByTag(COMMON_ERROR_LOGOUT_ALERT) != null) {
                return@collectRepeatOnStarted
            }
            CustomDialogFragment.Builder()
                .setMessage(alertMessage)
                .setPositiveButton(getString(R.string.ok_label)) {
                    logoutAndGoHomeOrPage()
                }
                .setCancelable(false)
                .show(supportFragmentManager, COMMON_ERROR_LOGOUT_ALERT)
        }
    }

    // ????????? ????????? ????????? ?????? ????????? ???????????? ?????? ?????? 1????????? ok ???????????????
    fun confirm(title: String = "", message: String) {
        CustomDialogFragment.Builder()
        CustomDialogFragment.Builder()
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(getString(R.string.ok_button))
            .show(supportFragmentManager)
    }

    // ????????? ????????? ????????? ?????? ????????? ???????????? ?????? ?????? 1????????? ok ?????????????????? ???????????????
    fun confirm(title: String = "", message: String = "", onClick: (() -> Unit) = {}) {
        CustomDialogFragment.Builder()
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(getString(R.string.ok_button)) {
                onClick()
            }.show(supportFragmentManager)
    }

    fun confirm(title: Int = 0, message: Int) {
        confirm(
            if (title == 0) "" else getString(title),
            getString(message)
        )
    }

    fun showChatbotErrorPopup(
        errorCode: String,
        message: String,
        showChatbotBtn: Boolean,
        onConfirmClick: (() -> Unit) = {},
    ) {
        val builder = ServerErrorChatbotDialogFragment.Builder()
            .setErrorCode(errorCode)
            .setMessage(message)
            .setConfirmBtnClickListener {
                onConfirmClick()
            }
        if (showChatbotBtn) {
            builder.setChatbotBtnClickListener {
                moveWithPrId(ID_COMMON_CHATBOT, JSONObject().put("triggerID", errorCode))
            }
        }

        builder.show(supportFragmentManager)
    }

    fun getClipboardText(): String? {
        val manager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        // ???????????? ?????? ??????, text?????? ??????
        if (manager.hasPrimaryClip() && manager.primaryClipDescription?.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN) == true) {
            return manager.primaryClip?.getItemAt(0)?.coerceToText(applicationContext)?.toString()
        }
        return null
    }

    /**
     * ???????????? ?????? ?????? programLink ??? ????????? ??????????????? ????????? ??? ???????????? ??????
     * @param programLink programLink.programId - ?????? ID, programLink.param - JSONObject string
     */
    fun moveWithProgramLink(programLink: ProgramLinkModel) {
        val programId = programLink.programId
        val param = if (programLink.param.isNullOrBlank()) {
            null
        } else {
            try {
                JSONObject(programLink.param)
            } catch (exception: JSONException) {
                null
            }
        }
        moveWithPrId(programId, param)
    }

    /**
     * @param prId ??????) "CO0605H0011F01", "BM0101S0000F01"
     * @param params ?????? param
     * @param requestId webViewActivity?????? overriding??? startPartnerWebViewActivity ??????????????? ???????????? ?????? ?????????, ????????????????????? ???????????? ????????????.
     * @param isNeedCallback ????????? ??? ????????? ???????????? ????????? ??????????????? ????????? ??? ?????? true ??? ?????? ??? prId??? ???????????????. ????????? ???????????? ?????? ???(back??? ?????? ?????? ???) ?????? prid??? ??? ????????? ???????????? ?????? ???????????????.
     *
     * ???????????? ID ?????? ????????? - WebViewActivity??? preCheckProgramID ????????? ?????? ????????????.
     * 1. ????????? ?????????????????? programId ?????? ??????               ?????? ????????? ??????
     * 2. programId ??? ????????? ?????????????????? ?????? ??? ??????          ?????? X
     * 3. ?????? ?????? ??????                                    ??????????????? ?????????????????? ?????? ??????????????? ???????????? ????????? ????????? ??? ?????? ??????
     */
    fun moveWithPrId(
        prId: String,
        params: JSONObject?,
        requestId: String = "",
        isNeedCallback: Boolean = false,
    ) {
        lifecycleScope.launch {
            val programId = prId.trim()
            val program = baseViewModel.getProgramUseCase.invoke(programId)
            LogUtil.d("moveWithPrId : $programId / params : $params", TAG)

            if (programId.isEmpty() || program.id.isEmpty()) {
                noticeEmptyProgram(program.id)
                return@launch
            }

            canGoPRID(program, params, requestId, isNeedCallback)
        }
    }

    private fun canGoPRID(
        program: ProgramEntity,
        params: JSONObject?,
        requestId: String,
        isNeedCallback: Boolean = false,
    ) {
        lifecycleScope.launch {
            if (baseViewModel.getAppVersionUseCase().isLowerVersionThan(program.minVersion)) {
                showUpdateBottomSheet()
                return@launch
            }

            if (!isAbleLoginLevelAndGoPrId(program, params, requestId)) return@launch

            if (program.longTermTransferCheckNeed && baseViewModel.getCustomerStatusUseCase() == CustomerStatus.LONG_TERM_NON_TRANSFER) {
                moveWithPrId(ID_LONG_TERM_NON_TRANSFER, null)
                return@launch
            }

            if (program.needAccountCheck && !baseViewModel.hasSavingsAccountUseCase()) {
                showNoSavingsAccountBottomSheet()
                return@launch
            }

            if (ProgramNoticeInfo.hasNotice(program.id)) {
                val programNotice: ProgramNoticeEntity? = ProgramNoticeInfo.getNotice(program.id)

                if (programNotice!!.needNetFunnel) {
                    baseViewModel.saveCachedProgramData(program, params, requestId)
                    collectNetFunnelData(baseViewModel.netfunnelData as MutableStateFlow<NetfunnelEntity>)
                    baseViewModel.startNetFunnelCheck(programNotice.popLinkId)
                    return@launch
                } else {
                    baseViewModel.getNoticeContentsUseCase(programNotice.sequence).also {
                        when (it) {
                            is DataResult.Success<NoticeEntity> -> {
                                val noticeContents = it.data
                                showNoticeBottomSheet(
                                    noticeContents,
                                    onClick = {
                                        if (noticeContents.type == "BLOCK") return@showNoticeBottomSheet
                                        else startActivityWithProgram(program, params, requestId)
                                    }
                                )
                                return@launch
                            }
                            is DataResult.Error -> {
                            }
                        }
                    }
                }
            }

            startActivityWithProgram(program, params, requestId, isNeedCallback)
        }
    }

    fun showOtherLoginBottomSheet(program: ProgramEntity, params: JSONObject?, requestId: String) {
        CustomBottomSheetDialogFragment.Builder()
            .setTitle("?????? ???????????? ????????? ??????")
            .setMessage("?????????, ???????????? ??????????????? ????????? ??? ?????? ???????????????.")
            .setHighlightedMessage("?????? ???????????? ?????????????????????????")
            .setPositiveButton(getString(R.string.yes_button)) {
                baseViewModel.saveCachedProgramData(program, params, requestId)
                moveWithPrId(ID_NATIVE_LOGIN, null)
            }
            .setNegativeButton(resources.getString(R.string.no_button))
            .show(supportFragmentManager)
    }

    fun showUpdateBottomSheet() {
        CustomBottomSheetDialogFragment.Builder()
            .setTitle("???????????? ??????")
            .setMessage("???????????? ???????????? ?????? ???(SOL) ?????? ???????????? ????????? ??? ????????????.")
            .setHighlightedMessage("?????? ???????????? ??????????????? ??????????")
            .setPositiveButton("????????????") {
                val intent = Intent(Intent.ACTION_VIEW)
                intent.addCategory(Intent.CATEGORY_DEFAULT)
                intent.data = Uri.parse("market://details?id=${this.packageName}")
                startActivity(intent)
            }
            .setNegativeButton(resources.getString(R.string.no_button))
            .show(supportFragmentManager)
    }

    fun showNoSavingsAccountBottomSheet() {
        CustomBottomSheetDialogFragment.Builder()
            .setTitle("?????? ????????? ?????? ??????")
            .setMessage("???????????? ????????? ???????????????!\n????????? ????????? ?????? ????????? ??? ????????????.")
            .setHighlightedMessage("?????? ???????????? ????????????????")
            .setPositiveButton("?????? ?????????") {
                moveWithPrId(
                    ID_COMMON_REGISTER_ACCOUNT,
                    JSONObject().put("P", JSONObject().put("prdtC", "110003904"))
                )
            }
            .setNegativeButton(resources.getString(R.string.no_button))
            .show(supportFragmentManager)
    }

    fun moveToCachedProgramData() {
        baseViewModel.getCachedProgramData()?.let { program ->
            canGoPRID(program.programEntity, program.param, program.requestId)
        }
        baseViewModel.clearCachedProgramData()
    }

    /**
     * ????????????????????? program ??? loginLevel ??? ?????? ?????? ????????? ???????????? ????????? ????????? ??? program ?????? ??????????????? ??????
     */
    private fun isAbleLoginLevelAndGoPrId(
        program: ProgramEntity,
        params: JSONObject?,
        requestId: String,
    ): Boolean {
        return if (baseViewModel.isLoginUseCase()) { // ????????? ??????
            when {
                program.permissionType == ProgramEntity.PermissionType.BANKING && baseViewModel.getMemberGradeUseCase() != MemberGrade.BANKING -> {
                    showChangeBankingGradeBottomSheetAndMoveToCachedPRID(program, params, requestId)
                    false
                }
                baseViewModel.getLoginMethod() == LoginType.ID.policyValue && !program.isIdPwUsable -> {
                    showOtherLoginBottomSheet(program, params, requestId)
                    false
                }
                else -> {
                    true
                }
            }
        } else {
            // ???????????? ??????
            if (program.permissionType != ProgramEntity.PermissionType.NO_LOGIN) {
                if (baseViewModel.hasLoginMethod()) {
                    // ????????? ?????????????????? ?????? ?????? -> ??????????????? ?????????
                    showNeedLoginBottomSheetAndMoveToCachedPRID(program, params, requestId)
                } else {
                    // ????????? ?????????????????? ?????? ?????? -> ???????????? ?????????
                    showNeedMemberAuthBottomSheetAndMoveToCachedPRID(program, params, requestId)
                }
                false
            } else {
                true
            }
        }
    }

    fun startActivityWithProgram(
        program: ProgramEntity,
        params: JSONObject?,
        requestId: String = "",
        isNeedCallback: Boolean = false,
    ) {
        if (program.id.isNotEmpty()) {
            lifecycleScope.launch {
                NLoggerUtil.movePageEvent(program.id, params, program.ovovId)
                SplunkMintUtil.pageIn(program.id)
            }
        }

        when (program.type) {
            NATIVE -> startNativeActivity(program.id, params)
            REACT_WEB, NON_REACT_WEB -> {
                if (isNeedCallback) {
                    when (program.id) {
                        ID_CHANGE_BANKING_GRADE -> startChangeBankingGradeLauncher(program)
                        ID_SMART_BANKING_TERMS -> startSmartBankingTermsLauncher(program, params)
                        ID_ADDITIONAL_AUTH -> startAdditionalAuthLauncher(program, params)
                        ID_HOME_REGISTER_USER -> startMemberAuthUserLauncher(program)
                    }
                } else {
                    startWebActivity(program.path, params)
                }
            }
            FOOD_WEB -> startFoodWebActivity(params)
            PARTNER_WEB -> startPartnerWebViewActivity(program, params, requestId)
            SHINHAN_PLUS -> startShinhanPlusActivity(program, params)
        }
    }

    private fun startChangeBankingGradeLauncher(program: ProgramEntity) {
        changeBankingGradeLauncher.launch(Intent(this, WebViewActivity::class.java).apply {
            putExtra(
                WebViewActivity.EXTRA_URL,
                ServerInfo.currentServerType.frontUrl + program.path
            )
        })
    }

    private fun startSmartBankingTermsLauncher(program: ProgramEntity, params: JSONObject?) {
        val url = ServerInfo.currentServerType.frontUrl + program.path
        smartBankingTermsLauncher.launch(Intent(this, WebViewActivity::class.java).apply {
            putExtra(WebViewActivity.EXTRA_URL, url)
            params?.let { putExtra(WebViewActivity.EXTRA_DATA, it.toString()) }
        })
    }

    private fun startAdditionalAuthLauncher(program: ProgramEntity, params: JSONObject?) {
        val url = ServerInfo.currentServerType.frontUrl + program.path
        additionalAuthLauncher.launch(Intent(this, WebViewActivity::class.java).apply {
            putExtra(WebViewActivity.EXTRA_URL, url)
            params?.let { putExtra(WebViewActivity.EXTRA_DATA, it.toString()) }
        })
    }

    private fun startMemberAuthUserLauncher(program: ProgramEntity) {
        memberAuthUserLauncher.launch(Intent(this, WebViewActivity::class.java).apply {
            putExtra(
                WebViewActivity.EXTRA_URL,
                ServerInfo.currentServerType.frontUrl + program.path
            )
        })
    }

    fun moveToAdditionalAuthScreen() {
        val authParam = JSONObject()
        authParam.put(KEY_SERVICE_SEQ, SERVICE_SEQ_AFTER_LOGIN)

        moveWithPrId(
            ID_ADDITIONAL_AUTH,
            JSONObject().apply { put(KEY_AUTH_PARAM, authParam) },
            isNeedCallback = true
        )
    }

    open fun startWebActivity(programPath: String, params: JSONObject?) {
        val url = ServerInfo.currentServerType.frontUrl + programPath
        LogUtil.d("startWebActivity - url: $url, params: ${params.toString()}", TAG)
        startActivity(Intent(this, WebViewActivity::class.java).apply {
            putExtra(WebViewActivity.EXTRA_URL, url)
            params?.let { putExtra(WebViewActivity.EXTRA_DATA, it.toString()) }
        })
    }

    private fun startFoodWebActivity(params: JSONObject?) {
        val domain =
            if (ServerInfo.currentServerType == ServerInfo.ServerType.PROD) "https://fdofd.ddangyo.com" else "https://devfdofd.ddangyo.com"
        val associateCusNumber = baseViewModel.getAssociateCustomerNumberUseCase()
        val appVersion = baseViewModel.getAppVersionUseCase()

        var url = "${domain}/sol_main.html?solid=${associateCusNumber}&appVer=${appVersion}"
        params?.let { param->
            param.optJSONObject("P")?.let { pjson->
                url += "&${pjson.toQueryString()}"
            } ?: run {
                url += "&${param.toQueryString()}"
            }
        }

        startActivity(Intent(this, FoodWebViewActivity::class.java).apply {
            putExtra(FoodWebViewActivity.EXTRA_URL, url)
            putExtra(FoodWebViewActivity.EXTRA_ASSOCIATE_CUSTOMER_NUMBER, associateCusNumber)
        })
    }

    fun JSONObject.toQueryString(): String {
        var result = ""
        keys().forEach {
            result += "${it}=${getString(it)}&"
        }
        if(result.isNotBlank()) result = result.dropLast(1)
        return result
    }

    /**
     * requestId??? webViewActivity?????? overriding??? startPartnerWebViewActivity ??????????????? ???????????? ?????? ?????????, ????????????????????? ???????????? ????????????.
     */
    open fun startPartnerWebViewActivity(
        program: ProgramEntity,
        params: JSONObject?,
        requestId: String = "",
    ) {
        val title = params?.optString("title")
        val url = ServerInfo.currentServerType.frontUrl + program.path + params?.optString("url")

        startActivity(Intent(this, PartnerWebViewActivity::class.java).apply {
            putExtra(PartnerWebViewActivity.EXTRA_TITLE, title)
            putExtra(PartnerWebViewActivity.EXTRA_URL, url)
        })
    }

    private fun startNativeActivity(id: String, params: JSONObject?) {
        when (id) {
            ID_NATIVE_TRANSFER -> {
                startActivity(Intent(this, TransferBridgeActivity::class.java).apply {
                    putExtra(TransferBridgeActivity.TRANSFER_DATA_PARAMS, params?.toString())
                })
            }
            ID_NATIVE_ALL_ACCOUNT_INQUIRY -> {
                startActivity(Intent(this, AllAccountActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    putExtra(AllAccountActivity.ALL_ACCOUNT_PARAM, params?.toString())
                })
            }
            ID_NATIVE_NO_FACE_STAB -> {
                startNoFaceOdsActivity(params)
            }
            ID_NATIVE_LOGIN -> {
                loginLauncher.launch(Intent(this, LoginActivity::class.java))
            }
            ID_NATIVE_CHANGE_REPRESENT_MODE -> {
                startActivity(ChangeRepresentModeActivity.getIntent(this))
            }
            else -> {
                startNativeMainActivity(id, params).let { isMainTabPrId ->
                    if (!isMainTabPrId) {
                        Toast.makeText(this, "????????????ID??? ?????????????????? ??????????????????.", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private fun startNativeMainActivity(id: String, params: JSONObject?): Boolean {
        // ????????? ??? ?????? Pair<?????????, ?????????>
        val mainTabInfo: Pair<MainTabConstants, MainSubTabConstants?> = when (id) {
            ID_NATIVE_MAIN_HOME -> {
                Pair(MainTabConstants.HOME, null)
            }
            ID_NATIVE_MAIN_MONEY_OVERVIEW -> {
                Pair(MainTabConstants.MONEY, MainSubTabConstants.MONEY_OVERVIEW)
            }
            ID_NATIVE_MAIN_MONEY_ASSET -> {
                Pair(MainTabConstants.MONEY, MainSubTabConstants.MONEY_ASSET)
            }
            ID_NATIVE_MAIN_MONEY_CONSUME -> {
                Pair(MainTabConstants.MONEY, MainSubTabConstants.MONEY_CONSUME)
            }
            ID_NATIVE_MAIN_MONEY_GIFT -> {
                Pair(MainTabConstants.MONEY, MainSubTabConstants.MONEY_GIFT)
            }
            ID_NATIVE_MAIN_PRODUCT -> {
                Pair(MainTabConstants.PRODUCT, null)
            }
            ID_NATIVE_MAIN_BENEFIT_RECOMMEND -> {
                Pair(MainTabConstants.BENEFIT, MainSubTabConstants.BENEFIT_RECOMMEND)
            }
            ID_NATIVE_MAIN_BENEFIT_LIFE -> {
                Pair(MainTabConstants.BENEFIT, MainSubTabConstants.BENEFIT_LIFE)
            }
            ID_NATIVE_MAIN_MENU -> {
                Pair(MainTabConstants.MENU, null)
            }
            else -> return false    // ???????????? ???????????? ?????? programId
        }

        // ???????????????????????? ???????????? ?????? ????????????????????? ??????????????? ???????????? ??????. ?????? ???????????? ?????? flag
        val wasMovedByBottomNavi = params?.optBoolean(MainActivity.KEY_BOTTOM_NAVI_SELECTED, false) ?: false
        val mainTab = mainTabInfo.first.value
        val subTab = mainTabInfo.second?.value

        if (this is MainActivity) {
            // ???????????????????????? ????????? ???????????? clear top, single top ???????????? moveByIntentTab ?????? ?????? ??????
            if (wasMovedByBottomNavi) {
                moveByIntentTab(mainTab, null)
            } else {
                moveByIntentTab(mainTab, subTab)
            }
        } else {
            // ??? ?????? ?????? ?????? ?????? ???????????? ????????? ?????? ??????
            startActivity(Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra(MainActivity.EXTRA_MAIN_TAB_TYPE, mainTab)
                subTab?.let { putExtra(MainActivity.EXTRA_SUB_TAB_TYPE, subTab) }
            })
        }

        return true
    }

    private fun startNoFaceOdsActivity(params: JSONObject?) {
        startActivity(Intent(this, NoFaceEntryWebViewActivity::class.java).apply {
            val args = NoFaceEntryArgs.Ods(
                data = buildMap {
                    params?.keys()?.forEach { key -> put(key, params.optString(key)) }
                }
            )
            putExtra(NoFaceEntryWebViewActivity.EXTRA_NO_FACE_ENTRY_ARGS, args)
        })
    }

    private fun startShinhanPlusActivity(program: ProgramEntity, params: JSONObject?) {
        LogUtil.d(
            "startShinhanPlusActivity with id : " + program.id + ", url : " + program.path,
            TAG
        )
        // ?????? Activity?????? ?????? ??????
        SharedPlatformUiInteractionImpl(
            this,
            lifecycleScope
        ).run {
            launch(program, params)
        }
    }

    // TODO : TestActivity ?????? ??????, params ??????
    fun moveWithUrl(url: String, params: JSONObject? = null, className: String = "") {
        LogUtil.d("moveWithUrl url : $url / params : $params", className)

        startActivity(Intent(this, WebViewActivity::class.java).apply {
            putExtra(WebViewActivity.EXTRA_URL, url)
            params?.let { putExtra(WebViewActivity.EXTRA_DATA, it.toString()) }
        })
    }

    /**
     * ?????? ?????? ??????????????? ????????? ??? ?????? ?????? ????????? ??????(????????? ?????? ?????? ????????????) ????????? ??????????????? ????????????.
     */
    fun showNoticeBottomSheet(
        notice: NoticeEntity,
        onDismissCallback: (() -> Unit)? = null,
        onClick: () -> Unit,
        isCancelable: Boolean = true,
    ) {
        CustomBottomSheetDialogFragment.Builder()
            .setTitle(notice.title)
            .setMessage(
                baseViewModel.setSixMsgWithEnter(
                    notice.msg1,
                    notice.msg2,
                    notice.msg3,
                    notice.msg4,
                    notice.msg5,
                    notice.msg6
                )
            )
            .setPositiveButton(getString(R.string.ok_button)) {
                onClick()
            }
            .setDismissCallback {
                onDismissCallback?.invoke()
            }
            .setCancelable(isCancelable)
            .show(supportFragmentManager)
    }

    /**
     * programId??? ?????????, ?????? programId??? ???????????? ???????????? ?????? ?????? ??????
     */
    fun noticeEmptyProgram(programId: String) {
        LogUtil.e("programId: $programId", TAG)
        CustomBottomSheetDialogFragment.Builder()
            .setTitle("????????? ?????? ??????")
            .setMessage("????????? ??????????????????.")
            .setHighlightedMessage("????????? ??????????????????.")
            .setPositiveButton("??????")
            .show(supportFragmentManager)
    }

    private fun showNeedLoginBottomSheetAndMoveToCachedPRID(
        program: ProgramEntity,
        params: JSONObject?,
        requestId: String,
    ) {
        CustomBottomSheetDialogFragment.Builder()
            .setTitle(resources.getString(R.string.login_guide_bottomsheet_title))
            .setMessage(resources.getString(R.string.login_guide_bottomsheet_msg))
            .setHighlightedMessage(resources.getString(R.string.login_guide_bottomsheet_msg_highlight))
            .setNegativeButton(resources.getString(R.string.no_button))
            .setPositiveButton(resources.getString(R.string.login_guide_bottomsheet_login)) {
                baseViewModel.saveCachedProgramData(program, params, requestId)
                moveWithPrId(ID_NATIVE_LOGIN, null)
            }
            .show(supportFragmentManager)
    }

    private fun showChangeBankingGradeBottomSheetAndMoveToCachedPRID(
        program: ProgramEntity,
        params: JSONObject?,
        requestId: String,
    ) {
        CustomBottomSheetDialogFragment.Builder()
            .setTitle(resources.getString(R.string.change_banking_grade_bottomsheet_title))
            .setMessage(resources.getString(R.string.change_banking_grade_bottomsheet_msg))
            .setHighlightedMessage(resources.getString(R.string.change_banking_grade_bottomsheet_msg_highlight))
            .setNegativeButton(resources.getString(R.string.next_time))
            .setPositiveButton(resources.getString(R.string.auth_label)) {
                lifecycleScope.launch {
                    baseViewModel.saveCachedProgramData(program, params, requestId)
                    moveWithPrId(ID_CHANGE_BANKING_GRADE, null, isNeedCallback = true)
                }
            }
            .show(supportFragmentManager)
    }

    private fun showNeedMemberAuthBottomSheetAndMoveToCachedPRID(
        program: ProgramEntity,
        params: JSONObject?,
        requestId: String,
    ) {
        CustomBottomSheetDialogFragment.Builder()
            .setTitle(resources.getString(R.string.need_auth_bottomsheet_title))
            .setMessage(resources.getString(R.string.need_auth_bottomsheet_msg))
            .setHighlightedMessage(resources.getString(R.string.need_auth_bottomsheet_msg_highlight))
            .setNegativeButton(resources.getString(R.string.no_button))
            .setPositiveButton(resources.getString(R.string.auth_label)) {
                lifecycleScope.launch {
                    baseViewModel.saveCachedProgramData(program, params, requestId)
                    moveWithPrId(ID_HOME_REGISTER_USER, null, isNeedCallback = true)
                }
            }
            .show(supportFragmentManager)
    }

    @RequiresApi(Build.VERSION_CODES.M)
    fun checkExternalStoragePermission() {
        if (isAlreadyGranted(WRITE_EXTERNAL_STORAGE)) return

        // TODO : ????????? ????????? ???????????? ?????? ??? ??????????????? ??????.
        // ?????? ????????? ????????? ?????? ??? - ????????? (?????? ????????? ?????? ?????? ????????????) / (??? ????????? ????????? ????????????) ??? ?????? ??????????????? ????????? ?????????.
        if (isUserDeniedBefore(WRITE_EXTERNAL_STORAGE))
            Toast.makeText(this, "?????? ????????? ????????? ?????? ??????/?????? ?????? ??????", Toast.LENGTH_SHORT).show()
        else
            requestPermissions(arrayOf(WRITE_EXTERNAL_STORAGE), 2)
    }

    private fun isAlreadyGranted(permission: String): Boolean =
        checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private fun isUserDeniedBefore(permission: String): Boolean =
        shouldShowRequestPermissionRationale(permission)

    fun checkUsesPermission(): Boolean {
        if (essentialPermissionList == null) {
            setEssentialPermissionList()
            setOptionalPermissionList()
        }

        checkDeniedPermissions()

        return if (deniedEssentialPermissionList.size > 0) {
            showRequirePermissionDialog()
            false
        } else {
            true
        }
    }

    private fun setEssentialPermissionList() {
        val permissionList = mutableListOf<String>()

        // ??????
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            permissionList.add(Manifest.permission.READ_PHONE_STATE)
        } else {
            permissionList.add(Manifest.permission.READ_PHONE_NUMBERS)
        }

        // ????????????
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            permissionList.add(WRITE_EXTERNAL_STORAGE)
        }

        essentialPermissionList = permissionList
    }

    private fun setOptionalPermissionList() {
        val optionalPermissionList = mutableListOf(
            Manifest.permission.ACCESS_COARSE_LOCATION,     // ????????????
            Manifest.permission.READ_CALENDAR,              // ?????????
            Manifest.permission.WRITE_CONTACTS,             // ?????????
            Manifest.permission.CAMERA,                     // ?????????
            Manifest.permission.RECORD_AUDIO,               // ?????????
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            optionalPermissionList.add(Manifest.permission.ACTIVITY_RECOGNITION)    // ??????
        }

        this.optionalPermissionList = optionalPermissionList
    }

    private fun checkDeniedPermissions() {
        // ????????? ????????? ??? ???????????? ?????? ?????????
        deniedEssentialPermissionList.clear()
        essentialPermissionList?.forEach {
            if (!isAlreadyGranted(it)) {
                deniedEssentialPermissionList.add(it)
            }
        }

        // ????????? ????????? ??? ???????????? ?????? ?????????
        deniedOptionalPermissionList.clear()
        optionalPermissionList?.forEach {
            if (!isAlreadyGranted(it)) {
                deniedOptionalPermissionList.add(it)
            }
        }

        // ?????? ?????????(????????? + ?????????) ??? ???????????? ?????? ?????????
        deniedTotalPermissionList.clear()
        deniedTotalPermissionList.addAll(deniedEssentialPermissionList)
        deniedTotalPermissionList.addAll(deniedOptionalPermissionList)
    }

    private fun showRequirePermissionDialog() {
        val binding = LayoutPermissionCheckBinding.inflate(layoutInflater)
        val permissionDialog = Dialog(this, R.style.Theme_NewApp).apply {
            window?.setLayout(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
            setContentView(binding.root)
            setCancelable(false)
        }

        binding.okButton.setOnClickListener {
            if (deniedEssentialPermissionList.isNotEmpty()) {
                requestPermissions(deniedEssentialPermissionList.toTypedArray(), 2)
            }

            permissionDialog.dismiss()
        }

        showDeniedPermissions(binding)
        permissionDialog.show()
    }

    private fun showDeniedPermissions(binding: LayoutPermissionCheckBinding) {
        deniedTotalPermissionList.forEach {
            when (it) {
                Manifest.permission.READ_PHONE_NUMBERS,
                Manifest.permission.READ_PHONE_STATE,
                -> {
                    binding.phonePermission.setVisible()
                }
                WRITE_EXTERNAL_STORAGE -> {
                    binding.storagePermission.setVisible()
                }
                Manifest.permission.ACCESS_COARSE_LOCATION -> {
                    binding.locationPermission.setVisible()
                }
                Manifest.permission.READ_CALENDAR -> {
                    binding.calendarPermission.setVisible()
                }
                Manifest.permission.WRITE_CONTACTS -> {
                    binding.contactsPermission.setVisible()
                }
                Manifest.permission.CAMERA -> {
                    binding.cameraPermission.setVisible()
                }
                Manifest.permission.RECORD_AUDIO -> {
                    binding.microphonePermission.setVisible()
                }
                Manifest.permission.ACTIVITY_RECOGNITION -> {
                    binding.healthPermission.setVisible()
                }
            }
        }
    }

    private fun goToApplicationDetailsSettings() {
        val intent = GoAppDetailsSettings().createIntent(this, packageName)
        startActivity(intent)
    }

    private fun setSessionTimerCollector() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                SessionTimer.timerStateFlow.collect {
                    when (it) {
                        is SessionTimer.TimerState.Running.Silent -> {
                            refreshLoginSessionBottomSheetInstance?.dismiss()
                            refreshLoginSessionBottomSheetInstance = null
                        }
                        is SessionTimer.TimerState.Running.NotifyRefresh -> {
                            // ???????????? ????????????
                            if (refreshLoginSessionBottomSheetInstance == null) {
                                showRefreshLoginSessionBottomSheet(it.remainingSec)
                            } else {
                                updateRemainingLoginSessionSec(it.remainingSec)
                            }
                        }
                        is SessionTimer.TimerState.Finish -> {
                            // ???????????? ??????
                            dismissAll()
                            logoutAndGoHomeOrPage()
                        }
                    }
                }
            }
        }
    }

    private fun showRefreshLoginSessionBottomSheet(remainingSec: Int) {
        refreshLoginSessionBottomSheetInstance = RefreshLoginSessionBottomSheetFragment.newInstance(
            initialSec = remainingSec,
            logoutEvent = {
                dismissAll()
                logoutAndGoHomeOrPage()
            },
            refreshSessionEvent = {
                refreshLoginSession()
            },
        ).also {
            it.show(supportFragmentManager, RefreshLoginSessionBottomSheetFragment.TAG)
        }
    }

    private fun updateRemainingLoginSessionSec(remainingSec: Int) {
        refreshLoginSessionBottomSheetInstance?.setRemainingLoginSessionSecMessage(remainingSec)
    }

    private fun dismissAll() {
        supportFragmentManager.fragments.forEach {
            if (it is BottomSheetDialogFragment) {
                it.dismiss()
            }
        }
        refreshLoginSessionBottomSheetInstance = null
    }

    fun logoutAndGoHomeOrPage(programId: String = "") {
        dismissAll()
        baseViewModel.requestLogout {
            startActivity(Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra(MainActivity.EXTRA_MAIN_TAB_TYPE, MainTabConstants.HOME.value)
                if (programId.isNotEmpty()) {
                    putExtra(MainActivity.EXTRA_PROGRAM_ID, programId)
                }
            })
        }
    }

    private fun showNoAdditionalAuthDialog() {
        CustomDialogFragment.Builder()
            .setTitle(getString(R.string.additional_auth_title))
            .setMessage(getString(R.string.additional_auth_message))
            .setPositiveButton(getString(R.string.ok_label)) {
                logoutAndGoHomeOrPage()
            }
            .setCancelable(false)
            .show(supportFragmentManager)
    }

    private fun refreshLoginSession() {
        refreshLoginSessionBottomSheetInstance?.dismiss()
        refreshLoginSessionBottomSheetInstance = null
        baseViewModel.requestLoginSessionRefresh()
    }

    fun exitApp() {
        val webView = WebView(this)
        webView.clearCache(true)
        lifecycleScope.launch {
            baseViewModel.requestLogout {
                baseViewModel.clearCachedProgramData()
                finishAffinity()
                Handler(Looper.getMainLooper()).postDelayed({ exitProcess(0) }, 300)
            }
        }
    }

    /**
     * ????????? ?????? ??? ????????? ???????????? ??????
     */
    override fun onSensorChanged(p0: SensorEvent?) {
        // TODO ?????? on/off ???????????? ????????????.
        lifecycleScope.launch {
            p0?.let {
                if (p0.sensor.type == Sensor.TYPE_ACCELEROMETER && ShakerUtil.isShake(p0.values[0])) {
                    val encryptedKey = getEncSavedDataKey(
                        baseViewModel.getCustomerNumberUseCase(),
                        KEY_SHAKE_SETTING_DATA
                    )
                    if (encryptedKey.isNullOrBlank()) return@launch

                    val data = baseViewModel.getStringPreferencesOnceUseCase(encryptedKey)
                    if (data.isNullOrBlank()) return@launch
                    if (JSONObject(data).optString("shakeYn") == "N") return@launch

                    val target = JSONObject(data).optString("shakeProgramId")
                    LogUtil.e("shake detected, target page : $target", TAG)

                    startActivity(Intent(applicationContext, MainActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        putExtra(MainActivity.EXTRA_MAIN_TAB_TYPE, MainTabConstants.HOME.value)
                        if (target != ID_NATIVE_MAIN_HOME) {
                            putExtra(MainActivity.EXTRA_PROGRAM_ID, target)
                        }
                    })
                }
            }
        }
    }

    override fun onAccuracyChanged(p0: Sensor?, p1: Int) {
        // unused
    }

    companion object {
        private val TAG = BaseActivity::class.java.simpleName
        private const val COMMON_ERROR_LOGOUT_ALERT = "CommonErrorLogoutAlert"
    }
}
