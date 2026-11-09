package com.example.lifemaster.presentation

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.AlertDialog
import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.navigation.findNavController
import androidx.navigation.fragment.NavHostFragment
import androidx.lifecycle.lifecycleScope
import com.example.lifemaster.SubscriptionHelper
import com.example.lifemaster.presentation.total.mypage.model.MeResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.core.content.edit
import com.example.lifemaster.R
import com.example.lifemaster.Utils.getDailyUsageStats
import com.example.lifemaster.databinding.ActivityMainBinding
import com.example.lifemaster.network.NetworkService
import com.example.lifemaster.network.TokenManager
import com.example.lifemaster.presentation.home.sleep.viewmodel.SleepViewModel
import com.example.lifemaster.presentation.home.sleep.viewmodel.SleepViewModelFactory
import com.example.lifemaster.presentation.home.todo.viewmodel.ToDoViewModel
import com.example.lifemaster.presentation.total.detox.viewmodel.DetoxCommonViewModel
import com.example.lifemaster.presentation.total.detox.viewmodel.DetoxRepeatLockViewModel
import com.example.lifemaster.presentation.total.detox.viewmodel.DetoxViewModel
import com.example.lifemaster.presentation.home.pomodoro.model.PomodoroButtonStatus
import com.example.lifemaster.presentation.home.pomodoro.viewmodel.PomodoroViewModel
import dagger.hilt.android.AndroidEntryPoint
import java.text.SimpleDateFormat
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.Calendar
import java.util.Date
import java.util.Locale
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    // View 관련 변수
    private lateinit var binding: ActivityMainBinding
    private lateinit var requiredApps: List<ApplicationInfo>
    private var foregroundStartTime: Long = 0L

    // ViewModel 변수
    private val detoxCommonViewModel: DetoxCommonViewModel by viewModels()
    private val detoxRepeatLockViewModel: DetoxRepeatLockViewModel by viewModels()
    private val detoxViewModel: DetoxViewModel by viewModels()
    private val toDoViewModel: ToDoViewModel by viewModels()
    private val pomodoroViewModel: PomodoroViewModel by viewModels()

    // 실시간 UI 변경을 위한 변수
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var updateRunnable: Runnable

    // 수면 관련 변수
    private var lastUsageTimeBeforeSleep: Long = 0L // 마지막 사용 시간 = 핸드폰 화면을 끈 시간
    private var firstUsageTimeAfterWake: Long? = null // 핸드폰을 처음 킨 시간 (잠금 해제x)
    private val sleepViewModel: SleepViewModel by viewModels {
        SleepViewModelFactory(networkService)
    }

    @Inject lateinit var tokenManager: TokenManager
    @Inject lateinit var networkService: NetworkService

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        if (intent.getBooleanExtra("openDetox", false)) {
            binding.root.post {
                val navController =
                    (supportFragmentManager.findFragmentById(R.id.fragmentContainerView) as NavHostFragment).navController

                binding.bottomNavigation.selectedItemId = R.id.action_total
                navController.navigate(R.id.detoxFragment)
            }
        }

        // 여기서 로그인 API를 다시 호출하면 안 됨
        // LoginEmailFragment에서 저장한 토큰을 그대로 사용해야 함
        Log.d("MainActivity", "saved bearer token = ${tokenManager.getBearerToken()}")

        val targetFragment = intent.getStringExtra("destination")
        if (targetFragment == "alarm") {
            val time = intent.getLongExtra("time", 0L) // 알람이 울린 시간
            val navController =
                (supportFragmentManager.findFragmentById(R.id.fragmentContainerView) as NavHostFragment).navController
            navController.navigate(
                R.id.alarmRingsFragment,
                bundleOf("time" to time)
            )
            binding.bottomNavigation.isVisible = false
        }

        updateRunnable = object : Runnable {
            override fun run() {
                val elapsedForegroundTime =
                    SystemClock.elapsedRealtime() - foregroundStartTime // 포그라운드로 전환 이후 누적된 시간
                detoxCommonViewModel.updateTempElapsedForegroundTime(elapsedForegroundTime)
                handler.postDelayed(this, 1000L)
            }
        }

        val totalApps = packageManager.getInstalledApplications(0)
        requiredApps = totalApps.filter { app ->
            val isSystemApp = (app.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            val isUpdatedSystemApp = (app.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
            !isSystemApp && !isUpdatedSystemApp
        }

        if (savedInstanceState == null) binding.bottomNavigation.selectedItemId = R.id.action_home

        setupListeners()

        requestUsageAccessPermission(this) // 사용 용도: 디톡스, 수면시간 측정
        requestOverlayPermission(this)
        requestNotificationPermission()

        getUserSleepInfo()
        fetchMe()
    }

    private fun fetchMe() {
        val bearer = tokenManager.getBearerToken() ?: return
        lifecycleScope.launch {
            val meRes = withContext(Dispatchers.IO) { runCatching { networkService.getMe(bearer) } }
            val couponRes = withContext(Dispatchers.IO) { runCatching { networkService.getMyCoupons(bearer) } }

            meRes.onSuccess { response ->
                if (response.isSuccessful) {
                    val me = response.body()
                    if (me != null) {
                        SubscriptionHelper.saveAuthUserFromMe(this@MainActivity, me)

                        couponRes.onSuccess { cRes ->
                            if (cRes.isSuccessful) {
                                val coupons = cRes.body()
                                val isPremiumByCoupon = coupons?.any {
                                    it.user != null && SubscriptionHelper.isPremiumPlan(it.user.subscriptionPlan)
                                } ?: false
                                if (isPremiumByCoupon) {
                                    SubscriptionHelper.markPremiumActive(this@MainActivity)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // 사용자의 전날 수면 정보를 가져오는 함수
    private fun getUserSleepInfo() {
        lastUsageTimeBeforeSleep = 0L
        firstUsageTimeAfterWake = null

        // 로그인 직후에는 사용 정보 접근 권한이 없을 수 있으므로 조회하지 않음
        if (!checkUsageAccessPermission(this)) {
            sleepViewModel.isMeasured = false
            return
        }

        // 사용자가 잠든 시간 추적하기
        val usageStatsManager = getSystemService(USAGE_STATS_SERVICE) as UsageStatsManager // 1. USAGE_STATS_SERVICE란? UsageStatsManager란?
        val sleepCalendar = Calendar.getInstance().apply {
            // 오늘 날짜
            set(Calendar.HOUR_OF_DAY, 4)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val sleepTrackingEndTime = sleepCalendar.timeInMillis // 04:00

        sleepCalendar.add(Calendar.HOUR_OF_DAY, -8)
        val sleepTrackingStartTime = sleepCalendar.timeInMillis // 20:00

        val sleepEvent = UsageEvents.Event() // 2.

        val sleepUsageEvents = try {
            usageStatsManager.queryEvents( // 3.
                sleepTrackingStartTime,
                sleepTrackingEndTime
            )
        } catch (e: SecurityException) {
            Log.w("SLEEP", "사용 정보 접근 권한이 없어 수면 기록을 조회할 수 없습니다.", e)
            null
        }
        if (sleepUsageEvents == null) {
            sleepViewModel.isMeasured = false
            return
        }

        while (sleepUsageEvents.hasNextEvent()) {
            sleepUsageEvents.getNextEvent(sleepEvent)
            if (sleepEvent.eventType == UsageEvents.Event.SCREEN_NON_INTERACTIVE) lastUsageTimeBeforeSleep = sleepEvent.timeStamp
        }

        Log.e("SLEEP(NIGHT)", "잠든 시간: ${Date(lastUsageTimeBeforeSleep)}")

        // 사용자가 일어난 시간 추적하기 (화면을 킨 시점)
        val now = Calendar.getInstance()
        val wakeUpCalendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 5)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val wakeTrackingStartTime = wakeUpCalendar.timeInMillis // 오전 5시

        wakeUpCalendar.add(Calendar.HOUR_OF_DAY, 5) // 오전 10시
        val wakeTrackingEndTime = wakeUpCalendar.timeInMillis

        // 만약 현재 시간이 오전 5시 이전이라면, 아직 기상 전이므로 오늘 날짜의 측정은 유효하지 않음
        if (now.timeInMillis < wakeTrackingStartTime) {
            Log.d("SLEEP", "기상 시간 측정 범위(05:00~10:00) 전입니다.")
            return
        }

        val wakeEvent = UsageEvents.Event()
        val wakeUsageEvents = try {
            usageStatsManager.queryEvents(
                wakeTrackingStartTime,
                wakeTrackingEndTime
            ) // 금일 오전 5시 ~ 금일 오전 10시 사이의 이벤트 조회
        } catch (e: SecurityException) {
            Log.w("SLEEP", "사용 정보 접근 권한이 없어 기상 기록을 조회할 수 없습니다.", e)
            null
        }
        if (wakeUsageEvents == null) {
            sleepViewModel.isMeasured = false
            return
        }

        while (wakeUsageEvents.hasNextEvent()) {
            wakeUsageEvents.getNextEvent(wakeEvent)
            if (wakeEvent.eventType == UsageEvents.Event.SCREEN_INTERACTIVE && firstUsageTimeAfterWake == null) {
                firstUsageTimeAfterWake = wakeEvent.timeStamp // 기상 후 처음 핸드폰을 킨 시간 추적
                Log.e("SLEEP(MORNING)", "일어난 시간: ${Date(firstUsageTimeAfterWake ?: 0L)}")
            }
        }

        // FIXME: 오전 12:07 시점 앱 다운 + 로그 값 lastUsageTimeBeforeSleep: 1753973258389, firstUsageTimeAfterWake: null
        // FIXME: 원인 → 자정 이후로 금일 오전 5시 이후부터의 데이터는 존재하지 않기에 null 발생 (= 자정 이후 ~ 오전 5시 이전 앱 들어가면 튕김)

        val sharedPreference = getSharedPreferences("user_sleep_info", MODE_PRIVATE)

        if(lastUsageTimeBeforeSleep == 0L || firstUsageTimeAfterWake == null) {
            // 수면 기록 측정이 제대로 안된 경우
            sharedPreference.edit().putString(LocalDate.now().toString(), "null").apply()
            sleepViewModel.isMeasured = false
        } else {
            // 수면 기록이 측정이 제대로 된 경우
            Log.e("VALUE", "잠든 시간: ${Instant.ofEpochMilli(lastUsageTimeBeforeSleep)}, 일어난 시간: ${Instant.ofEpochMilli(firstUsageTimeAfterWake!!)}")

            sleepViewModel.isMeasured = true

            val shortTimeFormatter = SimpleDateFormat("HH:mm", Locale.getDefault())
            sleepViewModel.sleepTime = shortTimeFormatter.format(lastUsageTimeBeforeSleep) // 01:11
            sleepViewModel.rawSleepTime = lastUsageTimeBeforeSleep
            sleepViewModel.wakeTime = shortTimeFormatter.format(firstUsageTimeAfterWake) // 07:44
            sleepViewModel.rawWakeTime = firstUsageTimeAfterWake

            val longTimeFormatter = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

            val sleepTime = longTimeFormatter.format(lastUsageTimeBeforeSleep) // 01:11:58
            val wakeTime = longTimeFormatter.format(firstUsageTimeAfterWake) // 07:44:56

            val sleepTimeSeconds = sleepTime.split(":")[2].toInt() // ["01", "11", "58"] → "58" → 58
            val wakeTimeSeconds = wakeTime.split(":")[2].toInt() // ["07", "44", "56"] → "56" → 56

            val duration = Duration.ofMillis(firstUsageTimeAfterWake!! - lastUsageTimeBeforeSleep)

            sleepViewModel.sleepDurationHour = duration.toHours().toInt()
            sleepViewModel.sleepDurationMinutes =
                if (sleepTimeSeconds > wakeTimeSeconds) (duration.toMinutes() % 60 + 1).toInt() else (duration.toMinutes() % 60).toInt()

            // 로컬에 수면 정보 저장
            sharedPreference.edit().putString(
                LocalDate.now().toString(),
                "${sleepViewModel.sleepDurationHour}시간 ${sleepViewModel.sleepDurationMinutes}분"
            ).apply()
        }
    }

    override fun onResume() {
        super.onResume()
        foregroundStartTime = SystemClock.elapsedRealtime() // 앱이 포그라운드로 전환된 시간 기록
        updateUsageStats()
        handler.post(updateRunnable)
        getUserSleepInfo()
        fetchMe()
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(updateRunnable)
    }

    // 앱 사용 시간을 실시간으로 업데이트하는 함수 (1초마다 실행됨)
    private fun updateUsageStats() {

        // 권한 없이 통계 조회 시 일부 기기에서 예외가 발생하는 것 방지
        if (!checkUsageAccessPermission(this)) {
            detoxCommonViewModel.updateTotalAccumulatedAppUsageTimes(0L)
            return
        }

        val usageStatsMap = try {
            getDailyUsageStats(this)
        } catch (e: SecurityException) {
            Log.w("MainActivity", "사용 정보 접근 권한이 없어 사용 시간을 조회할 수 없습니다.", e)
            return
        }

        var totalUsageTime = 0L

        for (app in requiredApps) {
            val accumulatedUsageTime = usageStatsMap[app.packageName] ?: 0L
//            if(accumulatedUsageTime>0) {
//                Log.d("debugging", "${app.loadLabel(packageManager)}: ${convertLongFormat(accumulatedUsageTime)}")
//            }
            totalUsageTime += accumulatedUsageTime
        }

        detoxCommonViewModel.updateTotalAccumulatedAppUsageTimes(totalUsageTime)
    }

    private fun convertLongFormat(milliseconds: Long): String {
        val totalSeconds = milliseconds / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val remainSeconds = totalSeconds % 60

        return String.format("%02d:%02d:%02d", hours, minutes, remainSeconds)
    }

    private fun isPomodoroBlockingNavigation(): Boolean {
        return pomodoroViewModel.pomodoroStatus == PomodoroButtonStatus.ESCAPE
    }

    // 클릭 이벤트 관련 함수
    private fun setupListeners() {
        // [!] setOnClickListener 가 아니라 setOnItemSelectedListener 이기 때문에, 하단 개별 뷰를 누르지 않더라도 selectedItemId 를 해당 뷰로 바꿔주면 동작한다.
        binding.bottomNavigation.setOnItemSelectedListener { item ->
            if (isPomodoroBlockingNavigation()) {
                Toast.makeText(
                    this,
                    "포모도로 진행 중에는 비상 탈출을 완료해야 이동할 수 있어요.",
                    Toast.LENGTH_SHORT
                ).show()
                return@setOnItemSelectedListener false
            }
            when (item.itemId) {
                R.id.action_home -> {
                    findNavController(R.id.fragmentContainerView).navigate(R.id.homeFragment)
                    true
                }

                R.id.action_group -> {
                    findNavController(R.id.fragmentContainerView).navigate(R.id.groupFragment)
                    true
                }

                R.id.action_community -> {
                    findNavController(R.id.fragmentContainerView).navigate(R.id.communityFragment)
                    true
                }

                R.id.action_total -> {
                    findNavController(R.id.fragmentContainerView).navigate(R.id.totalFragment)
                    true
                }

                else -> false
            }
        }
    }

    // 차단 서비스 기능을 위한 접근성 권한 활성화 여부 확인
    private fun isAccessibilityPermitted(context: Context): Boolean {
        val accessibilityManager =
            context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val enabledServices =
            accessibilityManager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_GENERIC)

        for (serviceInfo in enabledServices) {
            if (serviceInfo.resolveInfo.serviceInfo.packageName == application.packageName) return true
        }

        return false
    }

    fun requestAccessibilityPermission(context: Context) {
        if (!isAccessibilityPermitted(context)) {
            AlertDialog.Builder(context).apply {
                setTitle("접근성 서비스 사용 안내")
                setMessage(
                    """
                LifeMaster는 디지털 디톡스 기능을 제공하기 위해 Android 접근성 서비스를 사용합니다.
                접근성 서비스를 통해 현재 실행 중인 앱의 식별 정보(앱 패키지명)를 확인하여 사용자가 직접 설정한 차단 대상 앱이 실행되었는지 판단합니다.
                이 정보는 시간 잠금, 반복 잠금, 영구 잠금 기능을 적용하기 위해서만 사용됩니다.
                접근성 서비스를 통해 확인한 앱 정보는 기기 내에서만 처리되며 서버로 전송하거나 제3자에게 공유되지 않습니다.
                """.trimIndent()
                )
                setNegativeButton("취소") { dialog, _ ->
                    dialog.dismiss()
                    binding.bottomNavigation.selectedItemId = R.id.action_home
                }
                setPositiveButton("동의하고 설정하기") { _, _ ->
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
                setCancelable(false)
                create().show()
            }
        }
    }

    // 앱 사용 시간 권한이 없을 시 다이얼로그 띄우고 설정 화면으로 이동하는 함수
    private fun requestUsageAccessPermission(context: Context) {
        if (!checkUsageAccessPermission(context)) {
            val dialog = AlertDialog.Builder(context).apply {
                setTitle("권한 요청 다이얼로그")
                setMessage("사용자의 수면시간 추적을 위해 사용 정보 접근 권한이 필요합니다. 설정에 들어가서 권한을 허용해주세요.")
                setPositiveButton("설정 이동") { _, _ ->
                    val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
                    startActivity(intent)
                }
                setCancelable(false)
                create()
            }
            dialog.show()
        }
    }

    // 앱 사용 시간 권한 활성 여부를 확인하는 함수
    private fun checkUsageAccessPermission(context: Context): Boolean {
        val appOpsManager = context.getSystemService(APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android 10 이상
            appOpsManager.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(),
                context.packageName
            )
        } else {
            // Android 10 미만
            appOpsManager.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(),
                context.packageName
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun requestOverlayPermission(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(context)) {
                AlertDialog.Builder(context).apply {
                    setTitle("다른 앱 위에 표시 권한 필요")
                    setMessage("알람 화면을 즉시 띄우기 위해 '다른 앱 위에 표시' 권한이 필요합니다. 설정에서 허용해주세요.")
                    setPositiveButton("설정 이동") { _, _ ->
                        val intent = Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            "package:${context.packageName}".toUri()
                        )
                        startActivity(intent)
                    }
                    setCancelable(false)
                    create().show()
                }
            }
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (!isGranted) {
            Toast.makeText(this, "알림 권한이 거부되었습니다. 알람이 울리지 않을 수 있습니다.", Toast.LENGTH_SHORT).show()
        }
    }
}
