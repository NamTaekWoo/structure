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
 *  Copyright © 2021 Shinhan Bank. All rights reserved.
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
     * 뱅킹회원전환 화면에서 콜백을 받기위한 launcher
     */
    private val changeBankingGradeLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (it.resultCode == RESULT_OK) {
                val param = it.data?.getStringExtra(PluginContextImpl.EXTRA_DATA_FEEDBACK)
                if (!param.isNullOrBlank()) {
                    val isJoinBankingSuccessful = JSONObject(param).optString("isJoinBankingSuccessful", "")
                    if (isJoinBankingSuccessful.lowercase() == "true") {
                        // 뱅킹회원전환 성공응답콜백을 받았을때만 화면으로 이동
                        baseViewModel.getCachedProgramData()?.let { program ->
                            canGoPRID(program.programEntity, program.param, program.requestId)
                        }
                    }
                }
            }
            baseViewModel.clearCachedProgramData()
        }

    /**
     * 스마트뱅킹 가입 유의사항 및 이용동의 화면에서 콜백을 받기위한 launcher
     */
    private val smartBankingTermsLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (it.resultCode == RESULT_OK) {
                val param = it.data?.getStringExtra(PluginContextImpl.EXTRA_DATA_FEEDBACK)
                if (!param.isNullOrBlank()) {
                    val paramObject = JSONObject(param)
                    if (paramObject.optBoolean("동의여부")) {
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
     * 추가인증(ARS) 화면에서 콜백을 받기위한 launcher
     */
    private val additionalAuthLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (it.resultCode == RESULT_OK) {
                val param = it.data?.getStringExtra(PluginContextImpl.EXTRA_DATA_FEEDBACK)
                if (!param.isNullOrBlank()) {
                    val paramObject = JSONObject(param)
                    if (paramObject.optString("인증여부") == "Y") {
                        finish()
                        return@registerForActivityResult
                    }
                }
            }

            showNoAdditionalAuthDialog()
        }

    /**
     * 회원인증 화면에서 콜백을 받기위한 launcher
     */
    private val memberAuthUserLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (it.resultCode == RESULT_OK) {
                val param = it.data?.getStringExtra(PluginContextImpl.EXTRA_DATA_FEEDBACK)
                if (!param.isNullOrBlank()) {
                    val isMemberAuthSuccessful = JSONObject(param).optString("isMemberAuthSuccess", "")
                    if (isMemberAuthSuccessful.lowercase() == "true") {
                        // 회원인증 성공응답콜백을 받았을때만 화면으로 이동
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

        // 원격제어 & 바이러스 체크
        CoroutineScope(Dispatchers.Default).launch {
            BankingApplication.getInstance().checkRemote()
            BankingApplication.getInstance().checkMalware()
        }

        if (baseViewModel.isLoginUseCase()) { // 로그인 후 resume되는 화면부터 register 되면서 흔들기 센서 감지
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
        /* TODO 결함관리버튼 임시로 제거합니다. 추후 운영상황에서 활용하게 되면 조건 수정해서 활용할 예정.
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

        if (!isGranted) { // 필수 권한 중 미동의 항목이 있는 경우
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
                            LogUtil.d("유량제어 시작", TAG)
                        }
                        NetfunnelState.PROGRESS -> {
                            LogUtil.d("유량제어 ${it.remainTime}초 남았습니다. ${it.waitingPersonCount}명 남았습니다.", TAG)

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
                            LogUtil.d("유량제어 Block", TAG)

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
                            // 화면이동간에는 여기 타지 않음.
                            cancel()
                        }
                        NetfunnelState.END -> {
                            LogUtil.d("유량제어 완료", TAG)
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
            timeString += minute.toString() + "분 "
        }
        timeString += second.toString() + "초"
        return timeString
    }

    fun setCommonAlert(viewModel: BaseViewModel) {
        // 중복로그인
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

        // 중복로그인 이외 에러
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

    // 타이틀 있으면 타이틀 넣고 없으면 컨텐츠만 있는 버튼 1개짜리 ok 다이얼로그
    fun confirm(title: String = "", message: String) {
        CustomDialogFragment.Builder()
        CustomDialogFragment.Builder()
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(getString(R.string.ok_button))
            .show(supportFragmentManager)
    }

    // 타이틀 있으면 타이틀 넣고 없으면 컨텐츠만 있는 버튼 1개짜리 ok 다이얼로그에 클릭이벤트
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
        // 클립보드 저장 유무, text인지 판단
        if (manager.hasPrimaryClip() && manager.primaryClipDescription?.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN) == true) {
            return manager.primaryClip?.getItemAt(0)?.coerceToText(applicationContext)?.toString()
        }
        return null
    }

    /**
     * 서버에서 주는 응답 programLink 를 그대로 화면이동에 사용할 때 호출하는 함수
     * @param programLink programLink.programId - 화면 ID, programLink.param - JSONObject string
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
     * @param prId 예시) "CO0605H0011F01", "BM0101S0000F01"
     * @param params 전달 param
     * @param requestId webViewActivity에서 overriding한 startPartnerWebViewActivity 메소드에서 사용하기 위한 것으로, 네이티브에서는 사용하지 않습니다.
     * @param isNeedCallback 웹화면 중 콜백이 필요하여 런쳐로 불러야하는 경우에 이 값을 true 로 하여 웹 prId를 호출합니다. 콜백이 필요하지 않을 때(back에 의한 호출 등) 해당 prid가 새 웹뷰로 뜨는것을 막기 위함입니다.
     *
     * 프로그램 ID 이동 케이스 - WebViewActivity의 preCheckProgramID 함수도 함께 고쳐야함.
     * 1. 서비스 이용불가능한 programId 으로 이동               공지 바텀싯 띄움
     * 2. programId 가 없거나 메뉴파일에서 찾을 수 없음          이동 X
     * 3. 정상 이동 가능                                    메뉴파일의 로그인레벨과 현재 고객상태를 체크하여 필요한 전처리 후 화면 이동
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
            .setTitle("다른 방법으로 로그인 안내")
            .setMessage("아이디, 비밀번호 로그인으로 이용할 수 없는 메뉴입니다.")
            .setHighlightedMessage("다른 방법으로 로그인하시겠어요?")
            .setPositiveButton(getString(R.string.yes_button)) {
                baseViewModel.saveCachedProgramData(program, params, requestId)
                moveWithPrId(ID_NATIVE_LOGIN, null)
            }
            .setNegativeButton(resources.getString(R.string.no_button))
            .show(supportFragmentManager)
    }

    fun showUpdateBottomSheet() {
        CustomBottomSheetDialogFragment.Builder()
            .setTitle("업데이트 안내")
            .setMessage("선택하신 서비스는 신한 쏠(SOL) 최신 버전으로 이용할 수 있습니다.")
            .setHighlightedMessage("최신 버전으로 업데이트를 할까요?")
            .setPositiveButton("업데이트") {
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
            .setTitle("계좌 미보유 고객 안내")
            .setMessage("신한은행 계좌가 없으시네요!\n계좌를 만드신 후에 이용할 수 있습니다.")
            .setHighlightedMessage("계좌 만들기를 진행할까요?")
            .setPositiveButton("계좌 만들기") {
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
     * 이동하고자하는 program 의 loginLevel 과 현재 고객 상태를 체크하여 필요한 전처리 후 program 으로 이동시키는 함수
     */
    private fun isAbleLoginLevelAndGoPrId(
        program: ProgramEntity,
        params: JSONObject?,
        requestId: String,
    ): Boolean {
        return if (baseViewModel.isLoginUseCase()) { // 로그인 상태
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
            // 비로그인 상태
            if (program.permissionType != ProgramEntity.PermissionType.NO_LOGIN) {
                if (baseViewModel.hasLoginMethod()) {
                    // 등록된 로그인수단이 있는 상태 -> 로그인안내 바텀싯
                    showNeedLoginBottomSheetAndMoveToCachedPRID(program, params, requestId)
                } else {
                    // 등록된 로그인수단이 없는 상태 -> 회원인증 바텀싯
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
     * requestId는 webViewActivity에서 overriding한 startPartnerWebViewActivity 메소드에서 사용하기 위한 것으로, 네이티브에서는 사용하지 않습니다.
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
                        Toast.makeText(this, "프로그램ID가 잘못되었는지 확인해주세요.", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private fun startNativeMainActivity(id: String, params: JSONObject?): Boolean {
        // 이동할 탭 정보 Pair<메인탭, 서브탭>
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
            else -> return false    // 메인탭에 해당하지 않는 programId
        }

        // 메인액티비티에서 탭선택에 의해 이동된경우라면 서브탭으로 이동하면 안됨. 이를 구별하기 위한 flag
        val wasMovedByBottomNavi = params?.optBoolean(MainActivity.KEY_BOTTOM_NAVI_SELECTED, false) ?: false
        val mainTab = mainTabInfo.first.value
        val subTab = mainTabInfo.second?.value

        if (this is MainActivity) {
            // 메인액티비티에서 호출된 경우라면 clear top, single top 하지않고 moveByIntentTab 함수 직접 호출
            if (wasMovedByBottomNavi) {
                moveByIntentTab(mainTab, null)
            } else {
                moveByIntentTab(mainTab, subTab)
            }
        } else {
            // 그 외는 메인 위에 모든 액티비티 날리고 메인 호출
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
        // 호출 Activity에서 바로 띄움
        SharedPlatformUiInteractionImpl(
            this,
            lifecycleScope
        ).run {
            launch(program, params)
        }
    }

    // TODO : TestActivity 추후 수정, params 추가
    fun moveWithUrl(url: String, params: JSONObject? = null, className: String = "") {
        LogUtil.d("moveWithUrl url : $url / params : $params", className)

        startActivity(Intent(this, WebViewActivity::class.java).apply {
            putExtra(WebViewActivity.EXTRA_URL, url)
            params?.let { putExtra(WebViewActivity.EXTRA_DATA, it.toString()) }
        })
    }

    /**
     * 해당 업무 프로그램을 이용할 수 없는 경우 진입을 막고(경우에 따라 진입 허용하고) 공지성 바텀시트를 표시한다.
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
     * programId가 없거나, 찾는 programId가 프로그램 리스트에 없는 경우 처리
     */
    fun noticeEmptyProgram(programId: String) {
        LogUtil.e("programId: $programId", TAG)
        CustomBottomSheetDialogFragment.Builder()
            .setTitle("서비스 이용 안내")
            .setMessage("서비스 준비중입니다.")
            .setHighlightedMessage("조금만 기다려주세요.")
            .setPositiveButton("확인")
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

        // TODO : 토스트 텍스트 리소스로 추출 및 기획문구로 변경.
        // 앱에 필요한 권한을 요청 시 - 유저가 (처음 권한을 요청 받은 상태인지) / (기 요청을 거부한 상태인지) 에 따라 요청방식을 다르게 해야함.
        if (isUserDeniedBefore(WRITE_EXTERNAL_STORAGE))
            Toast.makeText(this, "외부 저장소 사용을 위해 읽기/쓰기 권한 필요", Toast.LENGTH_SHORT).show()
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

        // 전화
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            permissionList.add(Manifest.permission.READ_PHONE_STATE)
        } else {
            permissionList.add(Manifest.permission.READ_PHONE_NUMBERS)
        }

        // 저장공간
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            permissionList.add(WRITE_EXTERNAL_STORAGE)
        }

        essentialPermissionList = permissionList
    }

    private fun setOptionalPermissionList() {
        val optionalPermissionList = mutableListOf(
            Manifest.permission.ACCESS_COARSE_LOCATION,     // 위치정보
            Manifest.permission.READ_CALENDAR,              // 캘린더
            Manifest.permission.WRITE_CONTACTS,             // 주소록
            Manifest.permission.CAMERA,                     // 카메라
            Manifest.permission.RECORD_AUDIO,               // 마이크
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            optionalPermissionList.add(Manifest.permission.ACTIVITY_RECOGNITION)    // 건강
        }

        this.optionalPermissionList = optionalPermissionList
    }

    private fun checkDeniedPermissions() {
        // 필수적 권한들 중 허용하지 않은 권한들
        deniedEssentialPermissionList.clear()
        essentialPermissionList?.forEach {
            if (!isAlreadyGranted(it)) {
                deniedEssentialPermissionList.add(it)
            }
        }

        // 선택적 권한들 중 허용하지 않은 권한들
        deniedOptionalPermissionList.clear()
        optionalPermissionList?.forEach {
            if (!isAlreadyGranted(it)) {
                deniedOptionalPermissionList.add(it)
            }
        }

        // 필요 권한들(필수적 + 선택적) 중 허용하지 않은 권한들
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
                            // 로그아웃 연장안내
                            if (refreshLoginSessionBottomSheetInstance == null) {
                                showRefreshLoginSessionBottomSheet(it.remainingSec)
                            } else {
                                updateRemainingLoginSessionSec(it.remainingSec)
                            }
                        }
                        is SessionTimer.TimerState.Finish -> {
                            // 로그아웃 수행
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
     * 흔들기 감지 시 설정된 페이지로 이동
     */
    override fun onSensorChanged(p0: SensorEvent?) {
        // TODO 설정 on/off 확인하고 보내주기.
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
