package com.example.lifemaster.presentation.home

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.util.Log
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.bumptech.glide.Glide
import com.example.lifemaster.SubscriptionHelper
import com.example.lifemaster.R
import com.example.lifemaster.databinding.FragmentHomeBinding
import com.example.lifemaster.network.NetworkService
import com.example.lifemaster.network.TokenProvider
import com.example.lifemaster.presentation.home.alarm.model.AlarmModel
import com.example.lifemaster.presentation.home.alarm.model.DataResource
import com.example.lifemaster.presentation.home.alarm.model.mapper.toPresentation
import com.example.lifemaster.presentation.home.calendar.view.CalendarFragment
import com.example.lifemaster.presentation.home.calendar.viewmodel.CalendarMode
import com.example.lifemaster.presentation.home.calendar.viewmodel.CalendarViewModel
import com.example.lifemaster.presentation.home.edit.view.HomeEditActivity
import com.example.lifemaster.presentation.home.group.adapter.HomeGroupPreviewAdapter
import com.example.lifemaster.presentation.home.pomodoro.model.PomodoroModel
import com.example.lifemaster.presentation.home.pomodoro.viewmodel.PomodoroViewModel
import com.example.lifemaster.presentation.home.sleep.SleepFeatureGate
import com.example.lifemaster.presentation.home.sleep.model.Result
import com.example.lifemaster.presentation.home.sleep.model.SleepResponse
import com.example.lifemaster.presentation.home.sleep.viewmodel.SleepViewModel
import com.example.lifemaster.presentation.home.sleep.viewmodel.SleepViewModelFactory
import com.example.lifemaster.presentation.home.todo.adapter.ToDoAdapter
import com.example.lifemaster.presentation.home.todo.model.TODO
import com.example.lifemaster.presentation.home.todo.model.TodoModel
import com.example.lifemaster.presentation.home.todo.view.ToDoDialog
import com.example.lifemaster.presentation.home.todo.viewmodel.ToDoViewModel
import com.example.lifemaster.presentation.total.challenge.model.ChallengeCompleteRequest
import com.example.lifemaster.presentation.total.challenge.model.ChallengeItem
import com.example.lifemaster.presentation.total.challenge.model.toPresentation as toChallengePresentation
import com.example.lifemaster.presentation.total.detox.DetoxRepeatLockLocalManager
import com.example.lifemaster.presentation.tutorial.TutorialDialogFragment
import com.example.lifemaster.presentation.tutorial.TutorialPrefs
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.OffsetDateTime
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import java.util.Locale
import javax.inject.Inject

@AndroidEntryPoint
class HomeFragment : Fragment() {

    @Inject
    lateinit var networkService: NetworkService

    private lateinit var binding: FragmentHomeBinding

    // 할일 관련 변수
    private val toDoViewModel: ToDoViewModel by activityViewModels()
    private lateinit var todoAddDialog: ToDoDialog
    private lateinit var todoEditDialog: ToDoDialog

    // 포모도로 관련 변수
    private val pomodoroViewModel: PomodoroViewModel by activityViewModels()

    // 수면 관련 변수
    private val sleepViewModel: SleepViewModel by activityViewModels {
        SleepViewModelFactory(networkService)
    }
    private val calendarVM: CalendarViewModel by activityViewModels()

    private val todoDateFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")
    private var tvAlarmDate: TextView? = null
    private var tvAlarmTime: TextView? = null
    private var btnAlarmSetting: View? = null

    private var tvDetoxBlockedCount: TextView? = null
    private var btnDetox: View? = null

    private lateinit var homeGroupAdapter: HomeGroupPreviewAdapter

    // 알람 미리보기용 캐시
    private var cachedAlarmList: List<AlarmModel> = emptyList()

    // 수면 미리보기용 캐시
    private var cachedSleepList: List<SleepResponse> = emptyList()

    // 챌린지 미리보기용 캐시
    private var cachedChallengeList: List<ChallengeItem> = emptyList()

    private var challengePreviewContainer: LinearLayout? = null
    private var challengePreviewScrollView: View? = null

    private fun LocalDate.toTodoApiDate(): String {
        return this.format(todoDateFormatter)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        initViews()

        binding.containerCalendar.post {
            if (childFragmentManager.findFragmentById(R.id.container_calendar) == null) {
                childFragmentManager.beginTransaction()
                    .replace(R.id.container_calendar, CalendarFragment())
                    .commit()
            }
        }

        val (visible, ordered) = loadHomeConfiguration()
        applyHomeLayout(visible, ordered)

        bindAlarmPreviewViews()
        bindDetoxPreviewViews()
        bindChallengePreviewViews()

        setupCalendarHeader()
        observeCalendarState()
        fetchRemoteData()

        initListeners()
        initObservers()

        setupHomeCardNavigation()
        setupIntrospectionPreviewClicks()

        setupHomeGroupPreviewRecycler()
        fetchHomeJoinedGroups()

        fetchAlarmPreviewList()
        fetchSleepPreviewList()
        fetchMyChallengePreviewList()

        val today = calendarVM.selectedDate.value ?: LocalDate.now()
        loadAlarmPreviewForDate(today)
        loadSleepPreviewForDate(today)
        showDetoxPreview()

        showHomeTodoTutorialIfNeeded()
    }

    private fun showHomeTodoTutorialIfNeeded() {
        if (TutorialPrefs.hasSeenHomeTodoTutorial(requireContext())) return
        TutorialPrefs.markHomeTodoTutorialSeen(requireContext())
        TutorialDialogFragment.newInstance(R.drawable.tutorial_home_todo)
            .show(childFragmentManager, TutorialDialogFragment.TAG)
    }

    override fun onResume() {
        super.onResume()

        val (visible, ordered) = loadHomeConfiguration()
        applyHomeLayout(visible, ordered)
        rebindCalendarHeaderUI()

        bindAlarmPreviewViews()
        bindDetoxPreviewViews()
        bindChallengePreviewViews()
        setupIntrospectionPreviewClicks()
        setupHomeCardNavigation()

        ensureHomeGroupRecyclerAttached()
        fetchHomeJoinedGroups()

        fetchAlarmPreviewList()
        fetchSleepPreviewList()
        fetchMyChallengePreviewList()

        val currentDate = calendarVM.selectedDate.value ?: LocalDate.now()
        toDoViewModel.getTodoItemsByDate(currentDate.toTodoApiDate())
        loadAlarmPreviewForDate(currentDate)
        loadSleepPreviewForDate(currentDate)
        showDetoxPreview()
    }

    private fun setupHomeCardNavigation() {
        binding.cvGoToAlarm.setOnClickListener {
            findNavController().navigate(R.id.action_homeFragment_to_alarmListFragment)
        }

        binding.cardSleep.setOnClickListener {
            SleepFeatureGate.runIfEnabled(requireContext()) {
                SubscriptionHelper.checkPremiumAndRun(requireContext()) {
                    findNavController().navigate(R.id.action_homeFragment_to_sleepPlaylistDetailFragment)
                }
            }
        }

        binding.cardDetox.setOnClickListener {
            SubscriptionHelper.checkPremiumAndRun(requireContext()) {
                findNavController().navigate(R.id.action_homeFragment_to_detoxFragment)
            }
        }

        binding.cardGroup.setOnClickListener {
            findNavController().navigate(R.id.action_homeFragment_to_groupFragment)
        }

        binding.cardChallenge.setOnClickListener {
            findNavController().navigate(R.id.action_homeFragment_to_challengeFragment)
        }
    }

    private fun bindAlarmPreviewViews() {
        tvAlarmDate = binding.layoutAlarmPreview.tvAlarmDate
        tvAlarmTime = binding.layoutAlarmPreview.tvAlarmTime
        btnAlarmSetting = binding.layoutAlarmPreview.btnAlarmSetting

        btnAlarmSetting?.setOnClickListener {
            findNavController().navigate(R.id.action_homeFragment_to_alarmListFragment)
        }
    }

    private fun bindDetoxPreviewViews() {
        binding.layoutDetoxPreview.tvDetoxInfo.text = "현재 차단 앱"
        tvDetoxBlockedCount = binding.layoutDetoxPreview.tvDetoxTime
        btnDetox = binding.layoutDetoxPreview.btnDetox

        btnDetox?.setOnClickListener {
            SubscriptionHelper.checkPremiumAndRun(requireContext()) {
                findNavController().navigate(R.id.action_homeFragment_to_detoxFragment)
            }
        }
    }

    private fun bindChallengePreviewViews() {
        challengePreviewScrollView = binding.layoutChallengePreview.svChallengePreview
        challengePreviewContainer = binding.layoutChallengePreview.layoutChallengePreviewContainer
    }

    private fun setupIntrospectionPreviewClicks() {
        binding.cardIntrospection.setOnClickListener { goIntrospection("TODAY") }

        val todayCard = binding.layoutIntrospectionPreview.cardGoTodayDiary
        val thanksCard = binding.layoutIntrospectionPreview.cardGoThanks

        todayCard.setOnClickListener { goIntrospection("TODAY") }
        thanksCard.setOnClickListener { goIntrospection("THANKS") }
    }

    private fun goIntrospection(startTab: String) {
        val selectedDate = calendarVM.selectedDate.value ?: LocalDate.now()

        val args = Bundle().apply {
            putString("startTab", startTab)
            putString("selectedDate", selectedDate.toString())
        }

        findNavController().navigate(R.id.action_homeFragment_to_introspectionFragment, args)
    }

    private fun fetchRemoteData() {
        val selectedDate = calendarVM.selectedDate.value ?: LocalDate.now()
        toDoViewModel.getTodoItemsByDate(selectedDate.toTodoApiDate())
        pomodoroViewModel.getPomodoroAllItems()
    }

    private fun refreshCalendarFragment() {
        binding.containerCalendar.post {
            childFragmentManager.beginTransaction()
                .replace(R.id.container_calendar, CalendarFragment())
                .commit()
        }
    }

    private fun loadHomeConfiguration(): Pair<Set<String>, List<String>> {
        val prefs = requireContext().getSharedPreferences("home_pref", Context.MODE_PRIVATE)
        val visible = prefs.getStringSet("visible_components", null)
        val orderedString = prefs.getString("component_order", null)
        val finalVisible = visible ?: HomeConfig.DEFAULT_VISIBLE
        val finalOrdered = orderedString?.split(",") ?: HomeConfig.DEFAULT_ORDER
        return finalVisible to finalOrdered
    }

    private fun applyHomeLayout(visible: Set<String>, ordered: List<String>) {
        binding.cardCalendar.isVisible = true
        binding.cardTodo.isVisible = true

        val cardMap = mapOf(
            "sleep" to binding.cardSleep,
            "detox" to binding.cardDetox,
            "group" to binding.cardGroup,
            "introspection" to binding.cardIntrospection,
            "alarm" to binding.cvGoToAlarm,
            "challenge" to binding.cardChallenge
        )

        val parent =
            binding.root.findViewById<androidx.appcompat.widget.LinearLayoutCompat>(R.id.linearLayoutMain)
        parent.removeAllViews()

        parent.addView(binding.cardCalendar)
        parent.addView(binding.cardTodo)

        for (key in ordered) {
            val card = cardMap[key]
            if (card != null) {
                card.isVisible = visible.contains(key)
                if (visible.contains(key)) parent.addView(card)
            }
        }

        parent.addView(binding.tvHomeEdit)
    }

    private fun initViews() = with(binding) {
        todoRecyclerview.adapter = ToDoAdapter(
            context = requireContext(),
            onToggleClicked = { id ->
                toDoViewModel.toggleItem(id = id)
            },
            onEditClicked = { item ->
                todoEditDialog = ToDoDialog(origin = TODO.EDIT, item = item)
                todoEditDialog.show(childFragmentManager, ToDoDialog.TAG)
            },
            onDeleteClicked = { todoId ->
                pomodoroViewModel.deletePomodoroItemsByTodo(todoId = todoId)
            },
            onViewClicked = { item ->
                val action = HomeFragmentDirections.actionHomeFragmentToPomodoroFragment(todoItem = item)
                findNavController().navigate(action)
            }
        )

        val today = LocalDate.now()
        itemSleepPreview.tvAlarmDate.text = "${today.monthValue}월 ${today.dayOfMonth}일"
        itemSleepPreview.tvAlarmTime.text = "수면 기록 없음"
    }

    private fun initListeners() = with(binding) {
        btnAddTodoItem.setOnClickListener {
            val selectedDate = calendarVM.selectedDate.value ?: LocalDate.now()
            todoAddDialog = ToDoDialog(
                origin = TODO.ADD,
                selectedDate = selectedDate.toTodoApiDate()
            )
            todoAddDialog.show(childFragmentManager, ToDoDialog.TAG)
        }

        tvHomeEdit.setOnClickListener {
            SubscriptionHelper.checkPremiumAndRun(requireContext()) {
                val intent = Intent(requireContext(), HomeEditActivity::class.java)
                startActivity(intent)
            }
        }

        itemSleepPreview.btnSleepReport.setOnClickListener {
            SleepFeatureGate.runIfEnabled(requireContext()) {
                SubscriptionHelper.checkPremiumAndRun(requireContext()) {
                    val selectedDate = calendarVM.selectedDate.value ?: LocalDate.now()
                    val args = Bundle().apply {
                        putString("selectedDate", selectedDate.toString())
                    }
                    findNavController().navigate(R.id.action_homeFragment_to_sleepReportFragment, args)
                }
            }
        }
    }

    private fun initObservers() = with(binding) {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    toDoViewModel.newItem.collect { resource ->
                        when (resource) {
                            is DataResource.Error -> {
                                Toast.makeText(context, "네트워크 오류가 발생했습니다.", Toast.LENGTH_SHORT).show()
                            }
                            DataResource.Idle -> {}
                            DataResource.Loading -> {}
                            is DataResource.Success<TodoModel> -> {
                                Toast.makeText(context, "할일이 추가되었습니다.", Toast.LENGTH_SHORT).show()
                                if (::todoAddDialog.isInitialized) {
                                    todoAddDialog.dismiss()
                                }
                                val selectedDate = calendarVM.selectedDate.value ?: LocalDate.now()
                                toDoViewModel.getTodoItemsByDate(selectedDate.toTodoApiDate())
                                refreshCalendarFragment()
                            }
                        }
                    }
                }

                launch {
                    toDoViewModel.currentItems.combine(pomodoroViewModel.allPomodoroItems) { todoRes, pomoRes ->
                        todoRes to pomoRes
                    }.collect { (todoRes, pomoRes) ->
                        when (todoRes) {
                            is DataResource.Success<List<TodoModel>> -> {
                                val todoItems = todoRes.data

                                val allPomodoroItems: List<PomodoroModel> =
                                    if (pomoRes is DataResource.Success) {
                                        pomoRes.data
                                    } else {
                                        emptyList()
                                    }
                                val pomodoroTodoItems = todoItems.map { todoItem ->
                                    val pomodoroItems = allPomodoroItems.filter { it.todo?.id == todoItem.id }
                                    val timer25Number = pomodoroItems.count { it.focusTime == 25 }
                                    val timer50Number = pomodoroItems.count { it.focusTime == 50 }

                                    todoItem.copy(
                                        timer25Number = timer25Number,
                                        timer50Number = timer50Number
                                    )
                                }
                                todoRecyclerview.adapter?.let {
                                    (it as ToDoAdapter).submitList(pomodoroTodoItems)
                                }
                                showDetoxPreview()
                            }
                            is DataResource.Error -> {
                                todoRecyclerview.adapter?.let {
                                    (it as ToDoAdapter).submitList(emptyList())
                                }
                                Toast.makeText(
                                    context,
                                    "할일 목록을 불러오지 못했습니다.",
                                    Toast.LENGTH_SHORT
                                ).show()

                                showDetoxPreview()
                            }
                            DataResource.Idle -> {}
                            DataResource.Loading -> {}
                        }
                        if (pomoRes is DataResource.Error) {
                            showDetoxPreview()
                        }
                    }
                }

                launch {
                    toDoViewModel.deletionState.collect { resource ->
                        when (resource) {
                            is DataResource.Error -> {
                                Toast.makeText(context, "네트워크 오류가 발생했습니다.", Toast.LENGTH_SHORT).show()
                            }
                            DataResource.Idle -> {}
                            DataResource.Loading -> {}
                            is DataResource.Success<Int> -> {
                                val selectedDate = calendarVM.selectedDate.value ?: LocalDate.now()
                                toDoViewModel.getTodoItemsByDate(selectedDate.toTodoApiDate())
                                Toast.makeText(context, "할일이 삭제되었습니다.", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }

                launch {
                    toDoViewModel.updateItem.collect { resource ->
                        when (resource) {
                            is DataResource.Error -> {
                                Toast.makeText(context, "네트워크 오류가 발생했습니다.", Toast.LENGTH_SHORT).show()
                            }
                            DataResource.Idle -> {}
                            DataResource.Loading -> {}
                            is DataResource.Success<TodoModel> -> {
                                todoEditDialog.dismiss()
                                val selectedDate = calendarVM.selectedDate.value ?: LocalDate.now()
                                toDoViewModel.getTodoItemsByDate(selectedDate.toTodoApiDate())
                                Toast.makeText(context, "할일이 수정되었습니다.", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }

                launch {
                    toDoViewModel.toggleItem.collect { resource ->
                        when (resource) {
                            is DataResource.Error -> {
                                Toast.makeText(context, resources.getString(R.string.server_error_message), Toast.LENGTH_SHORT).show()
                            }
                            DataResource.Idle -> {}
                            DataResource.Loading -> {}
                            is DataResource.Success<TodoModel> -> {
                                val data = resource.data
                                val selectedDate = calendarVM.selectedDate.value ?: LocalDate.now()
                                toDoViewModel.getTodoItemsByDate(selectedDate.toTodoApiDate())
                                if (data.isCompleted) {
                                    Toast.makeText(context, "할일이 체크되었습니다.", Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(context, "할일이 해제되었습니다.", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    }
                }

                launch {
                    pomodoroViewModel.deletePomodoroItemsResult.collect { resource ->
                        when (resource) {
                            is DataResource.Error -> {}
                            DataResource.Idle -> {}
                            DataResource.Loading -> {}
                            is DataResource.Success<Int> -> {
                                val deleteTodoId = resource.data
                                toDoViewModel.deleteTodoItem(deleteId = deleteTodoId)
                            }
                        }
                    }
                }
            }
        }

        sleepViewModel.userSleepRecordList.observe(viewLifecycleOwner) { result ->
            when (result) {
                is Result.Success -> {
                    cachedSleepList = result.data
                    val selectedDate = calendarVM.selectedDate.value ?: LocalDate.now()
                    showSleepPreviewForDate(selectedDate)
                }
                is Result.Error -> {
                    cachedSleepList = emptyList()
                    showNoSleepForDate()
                }
                Result.Loading -> {}
            }
        }
    }

    // 그룹
    private fun setupHomeGroupPreviewRecycler() {
        homeGroupAdapter = HomeGroupPreviewAdapter { group ->
            val args = Bundle().apply {
                putLong("groupId", group.id)
                putString("groupName", group.name)
            }
            findNavController().navigate(R.id.action_homeFragment_to_groupStatsFragment, args)
        }

        binding.recyclerviewGroup.apply {
            layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
            adapter = homeGroupAdapter
            setHasFixedSize(false)
            isNestedScrollingEnabled = false
        }
    }

    private fun ensureHomeGroupRecyclerAttached() {
        if (!::homeGroupAdapter.isInitialized) return

        val rv = binding.recyclerviewGroup
        if (rv.adapter !== homeGroupAdapter) {
            rv.layoutManager =
                LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
            rv.adapter = homeGroupAdapter
        }
        rv.setHasFixedSize(false)
        rv.isNestedScrollingEnabled = false
        rv.requestLayout()
        rv.invalidate()
    }

    private fun fetchHomeJoinedGroups() {
        if (!::homeGroupAdapter.isInitialized) return

        val raw = TokenProvider.getBearerToken(requireContext())
        if (raw.isNullOrBlank()) {
            homeGroupAdapter.submitList(emptyList())
            return
        }

        val token = if (raw.startsWith("Bearer ")) raw else "Bearer $raw"

        viewLifecycleOwner.lifecycleScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    networkService.getMyGroups(token)
                }
            }.onSuccess { list ->
                val sortedList = list.sortedBy { it.id }

                homeGroupAdapter.submitList(sortedList)

                binding.cardGroup.requestLayout()
                binding.recyclerviewGroup.requestLayout()
                binding.recyclerviewGroup.invalidate()

                if (sortedList.isNotEmpty()) homeGroupAdapter.setSelectedByIndex(0)
            }.onFailure {
                homeGroupAdapter.submitList(emptyList())
            }
        }
    }

    private fun setupCalendarHeader() = with(binding) {
        if (calendarVM.mode.value == null) calendarVM.setMode(CalendarMode.MONTH)
        if (calendarVM.selectedDate.value == null) calendarVM.selectDate(LocalDate.now())

        btnMonth.setOnClickListener {
            calendarVM.setMode(CalendarMode.MONTH)
            updateModeButtons(CalendarMode.MONTH)
            updateSelectedDateText(CalendarMode.MONTH, calendarVM.selectedDate.value ?: LocalDate.now())
        }
        btnWeek.setOnClickListener {
            calendarVM.setMode(CalendarMode.WEEK)
            updateModeButtons(CalendarMode.WEEK)
            updateSelectedDateText(CalendarMode.WEEK, calendarVM.selectedDate.value ?: LocalDate.now())
        }
        btnDay.setOnClickListener {
            calendarVM.setMode(CalendarMode.DAY)
            updateModeButtons(CalendarMode.DAY)
            updateSelectedDateText(CalendarMode.DAY, calendarVM.selectedDate.value ?: LocalDate.now())
        }

        rebindCalendarHeaderUI()
    }

    private fun observeCalendarState() = with(binding) {
        calendarVM.selectedDate.observe(viewLifecycleOwner) { date ->
            val mode = calendarVM.mode.value ?: CalendarMode.MONTH
            updateSelectedDateText(mode, date)
            loadAlarmPreviewForDate(date)
            loadSleepPreviewForDate(date)
            toDoViewModel.getTodoItemsByDate(date.toTodoApiDate())
        }

        calendarVM.mode.observe(viewLifecycleOwner) { mode ->
            updateModeButtons(mode)
            updateSelectedDateText(mode, calendarVM.selectedDate.value ?: LocalDate.now())
        }
    }

    private fun rebindCalendarHeaderUI() {
        val mode = calendarVM.mode.value ?: CalendarMode.MONTH
        val date = calendarVM.selectedDate.value ?: LocalDate.now()
        updateModeButtons(mode)
        updateSelectedDateText(mode, date)
    }

    private fun updateModeButtons(selected: CalendarMode) = with(binding) {
        val selectedBtn = when (selected) {
            CalendarMode.MONTH -> btnMonth
            CalendarMode.WEEK -> btnWeek
            CalendarMode.DAY -> btnDay
        }
        val buttons = listOf(btnMonth, btnWeek, btnDay)
        buttons.forEach { btn ->
            if (btn == selectedBtn) {
                btn.setBackgroundResource(R.drawable.bg_round_and_mint)
                btn.setTextColor(ContextCompat.getColor(requireContext(), android.R.color.white))
            } else {
                btn.setBackgroundResource(R.drawable.bg_calendar_unselected)
                btn.setTextColor(ContextCompat.getColor(requireContext(), R.color.mint_60))
            }
        }
    }

    private fun updateSelectedDateText(mode: CalendarMode, date: LocalDate) {
        binding.tvSelectedDate.text = when (mode) {
            CalendarMode.MONTH -> "${date.monthValue}월"
            CalendarMode.WEEK -> "${date.monthValue}월 ${weekOfMonth(date)}째주"
            CalendarMode.DAY -> "${date.monthValue}월 ${date.dayOfMonth}일"
        }
    }

    private fun weekOfMonth(date: LocalDate): Int = ((date.dayOfMonth - 1) / 7) + 1

    // 알람 미리보기
    private fun fetchAlarmPreviewList() {
        viewLifecycleOwner.lifecycleScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    networkService.fetchAlarmList()
                }
            }.onSuccess { responseList ->
                cachedAlarmList = responseList.map { it.toPresentation() }
                val selectedDate = calendarVM.selectedDate.value ?: LocalDate.now()
                showAlarmPreviewForDate(selectedDate)
            }.onFailure {
                cachedAlarmList = emptyList()
                val selectedDate = calendarVM.selectedDate.value ?: LocalDate.now()
                showNoAlarmForDate(selectedDate)
            }
        }
    }

    private fun loadAlarmPreviewForDate(date: LocalDate) {
        tvAlarmDate?.text = "${date.monthValue}월 ${date.dayOfMonth}일"

        if (cachedAlarmList.isEmpty()) {
            showNoAlarmForDate(date)
            return
        }

        showAlarmPreviewForDate(date)
    }

    private fun showAlarmPreviewForDate(date: LocalDate) {
        val firstAlarm = cachedAlarmList
            .filter { it.switchOnOff }
            .filter { isAlarmScheduledOnDate(it, date) }
            .sortedWith(compareBy<AlarmModel> { it.hour }.thenBy { it.minute })
            .firstOrNull()

        if (firstAlarm == null) {
            showNoAlarmForDate(date)
            return
        }

        tvAlarmTime?.text = firstAlarm.toPreviewKoreanTime()
    }

    private fun isAlarmScheduledOnDate(alarm: AlarmModel, date: LocalDate): Boolean {
        return when (date.dayOfWeek) {
            DayOfWeek.MONDAY -> alarm.alarmMon
            DayOfWeek.TUESDAY -> alarm.alarmTue
            DayOfWeek.WEDNESDAY -> alarm.alarmWed
            DayOfWeek.THURSDAY -> alarm.alarmThu
            DayOfWeek.FRIDAY -> alarm.alarmFri
            DayOfWeek.SATURDAY -> alarm.alarmSat
            DayOfWeek.SUNDAY -> alarm.alarmSun
        }
    }

    private fun showNoAlarmForDate(date: LocalDate) {
        val isToday = date == LocalDate.now()
        tvAlarmTime?.text = if (isToday) "오늘 알람 없음" else "알람 없음"
    }

    private fun AlarmModel.toPreviewKoreanTime(): String {
        val hour24 = hour
        val minuteText = "%02d".format(minute)

        return when {
            hour24 == 0 -> "오전 12:$minuteText"
            hour24 < 12 -> "오전 $hour24:$minuteText"
            hour24 == 12 -> "오후 12:$minuteText"
            else -> "오후 ${hour24 - 12}:$minuteText"
        }
    }

    // 수면 미리보기
    private fun fetchSleepPreviewList() {
        val raw = TokenProvider.getBearerToken(requireContext())
        if (raw.isNullOrBlank()) {
            cachedSleepList = emptyList()
            showNoSleepForDate()
            return
        }

        val token = if (raw.startsWith("Bearer ")) raw else "Bearer $raw"

        viewLifecycleOwner.lifecycleScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    networkService.getMe(token)
                }
            }.onSuccess { response ->
                val me = response.body()
                val userId = me?.id

                if (userId != null) {
                    sleepViewModel.getUserSleepInfo(userId.toLong())
                } else {
                    cachedSleepList = emptyList()
                    showNoSleepForDate()
                }
            }.onFailure {
                cachedSleepList = emptyList()
                showNoSleepForDate()
            }
        }
    }

    private fun loadSleepPreviewForDate(date: LocalDate) {
        binding.itemSleepPreview.tvAlarmDate.text = "${date.monthValue}월 ${date.dayOfMonth}일"

        if (cachedSleepList.isEmpty()) {
            showNoSleepForDate()
            return
        }

        showSleepPreviewForDate(date)
    }

    private fun showSleepPreviewForDate(date: LocalDate) {
        val targetDate = date.toString()

        val sleep = cachedSleepList.firstOrNull { it.sleepDate == targetDate }

        if (sleep == null) {
            showNoSleepForDate()
            return
        }

        binding.itemSleepPreview.tvAlarmTime.text = "${sleep.sleepDurationText} 수면"
    }

    private fun showNoSleepForDate() {
        binding.itemSleepPreview.tvAlarmTime.text = "수면 기록 없음"
    }

    // 디톡스 미리보기
    private fun showDetoxPreview() {
        viewLifecycleOwner.lifecycleScope.launch {
            val blockedPackages = mutableSetOf<String>()

            // 영구잠금: 서버
            runCatching {
                withContext(Dispatchers.IO) {
                    val response = networkService.fetchPermanentLockItems()
                    val body = response.body()

                    if (response.isSuccessful) {
                        blockedPackages.addAll(body?.lockedAppPackageNames ?: emptyList())
                    }
                }
            }

            // 시간잠금: 서버 lock-status
            runCatching {
                withContext(Dispatchers.IO) {
                    val response = networkService.fetchTimeLockStatus()
                    val body = response.body()

                    if (response.isSuccessful && body?.locked == true) {
                        blockedPackages.addAll(body.lockedApps)
                    }
                }
            }

            // 반복잠금: 로컬
            val repeatInfos = DetoxRepeatLockLocalManager.loadRepeatBlockInfos(requireContext())

            repeatInfos.forEach { (_, info) ->
                val state = DetoxRepeatLockLocalManager.getRepeatLockState(
                    context = requireContext(),
                    id = info.id,
                    packageName = info.packageName,
                    sessionUsageLimit = info.sessionUsageLimit,
                    lockDuration = info.lockDuration,
                    dailyMaxUsageLimit = info.dailyMaxUsageLimit
                )

                if (state.locked) {
                    blockedPackages.add(info.packageName)
                }
            }

            tvDetoxBlockedCount?.text = "${blockedPackages.size}개"
        }
    }

    private fun Long.toDetoxTimeText(): String {
        val totalMinutes = this / 1000L / 60L
        val hour = totalMinutes / 60L
        val minute = totalMinutes % 60L
        return "${hour}시간 ${minute}분"
    }

    // 챌린지 미리보기
    private fun fetchMyChallengePreviewList() {
        val raw = TokenProvider.getBearerToken(requireContext())
        if (raw.isNullOrBlank()) {
            cachedChallengeList = emptyList()
            renderChallengePreview(emptyList())
            return
        }

        val token = if (raw.startsWith("Bearer ")) raw else "Bearer $raw"

        viewLifecycleOwner.lifecycleScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val myChallenges = networkService.getMyChallengeList(token)
                    val completedList = networkService.getTodayCompletedChallenges(token)

                    myChallenges to completedList
                }
            }.onSuccess { (myChallenges, completedList) ->
                val myIds = myChallenges.map { it.challId }.toSet()
                val completedMap = completedList.associateBy { it.challId }

                cachedChallengeList = myChallenges.map { dto ->
                    val completed = completedMap[dto.challId]

                    dto.toChallengePresentation(myIds).copy(
                        isCompleted = completed?.completed == true,
                        completionTime = completed?.completedAt?.toChallengePreviewTime()
                    )
                }

                renderChallengePreview(cachedChallengeList)
            }.onFailure { e ->
                Log.e("HomeFragment", "챌린지 미리보기 로드 실패", e)
                cachedChallengeList = emptyList()
                renderChallengePreview(emptyList())
            }
        }
    }

    private fun renderChallengePreview(challenges: List<ChallengeItem>) {
        val container = challengePreviewContainer ?: return

        container.removeAllViews()

        if (challenges.isEmpty()) {
            challengePreviewScrollView?.isVisible = false
            return
        }

        challengePreviewScrollView?.isVisible = true

        challenges.forEachIndexed { index, challenge ->
            val itemView = layoutInflater.inflate(
                R.layout.item_home_challenge_preview,
                container,
                false
            )

            val root = itemView.findViewById<LinearLayout>(R.id.layout_challenge_preview_item)
            val frame = itemView.findViewById<FrameLayout>(R.id.frame_challenge_preview_bg)
            val bg = itemView.findViewById<ImageView>(R.id.iv_challenge_preview_bg)
            val icon = itemView.findViewById<ImageView>(R.id.iv_challenge_preview_icon)
            val title = itemView.findViewById<TextView>(R.id.tv_challenge_preview_name)
            val overlay = itemView.findViewById<LinearLayout>(R.id.layout_completion_overlay)
            val checkmark = itemView.findViewById<ImageView>(R.id.iv_checkmark)
            val timeText = itemView.findViewById<TextView>(R.id.tv_completion_time)

            title.text = challenge.challName
            title.setTextColor(Color.parseColor("#BDBDBD"))

            bg.setImageResource(R.drawable.bg_circle_default)

            // 초기 상태 반영 (이미 완료된 경우 등)
            updateChallengeItemUI(challenge, icon, overlay, checkmark, timeText)

            if (challenge.challImg.isNotBlank()) {
                Glide.with(icon.context)
                    .load(challenge.challImg)
                    .error(getFallbackChallengeIconRes(challenge.challName))
                    .circleCrop()
                    .into(icon)
            } else {
                Glide.with(icon.context)
                    .load(getFallbackChallengeIconRes(challenge.challName))
                    .circleCrop()
                    .into(icon)
            }

            val toggleComplete = {
                if (challenge.isCompleted) {
                    Toast.makeText(requireContext(), "이미 오늘 완료한 챌린지입니다.", Toast.LENGTH_SHORT).show()
                } else {
                    val raw = TokenProvider.getBearerToken(requireContext())

                    if (raw.isNullOrBlank()) {
                        Toast.makeText(requireContext(), "로그인이 필요합니다.", Toast.LENGTH_SHORT).show()
                    } else {
                        val token = if (raw.startsWith("Bearer ")) raw else "Bearer $raw"
                        val request = ChallengeCompleteRequest(
                            challId = challenge.challId
                        )

                        Log.d("ChallengeComplete", "POST /challenge/complete challId=${request.challId}")

                        root.isEnabled = false
                        frame.isEnabled = false
                        itemView.isEnabled = false

                        viewLifecycleOwner.lifecycleScope.launch {
                            runCatching {
                                withContext(Dispatchers.IO) {
                                    networkService.completeChallenge(
                                        token = token,
                                        request = request
                                    )
                                }
                            }.onSuccess { response ->
                                if (response.isSuccessful) {
                                    val body = response.body()

                                    challenge.isCompleted = true
                                    challenge.completionTime =
                                        body?.completedAt?.toChallengePreviewTime()
                                            ?: LocalTime.now()
                                                .format(DateTimeFormatter.ofPattern("h:mma", Locale.ENGLISH))
                                                .lowercase(Locale.ENGLISH)

                                    cachedChallengeList = cachedChallengeList.map {
                                        if (it.challId == challenge.challId) {
                                            it.copy(
                                                isCompleted = true,
                                                completionTime = challenge.completionTime
                                            )
                                        } else {
                                            it
                                        }
                                    }

                                    updateChallengeItemUI(
                                        challenge = challenge,
                                        icon = icon,
                                        overlay = overlay,
                                        checkmark = checkmark,
                                        timeText = timeText
                                    )

                                    Toast.makeText(
                                        requireContext(),
                                        "${challenge.challName} 완료!",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                } else {
                                    val errorBody = response.errorBody()?.string()
                                    Log.e(
                                        "ChallengeComplete",
                                        "complete failed code=${response.code()}, body=$errorBody"
                                    )

                                    Toast.makeText(
                                        requireContext(),
                                        "챌린지 완료 실패 (${response.code()})",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }.onFailure { e ->
                                Log.e("ChallengeComplete", "complete error", e)

                                Toast.makeText(
                                    requireContext(),
                                    "네트워크 오류가 발생했습니다.",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }

                            root.isEnabled = true
                            frame.isEnabled = true
                            itemView.isEnabled = true
                        }
                    }
                }
            }

            root.setOnClickListener { toggleComplete() }
            frame.setOnClickListener { toggleComplete() }
            itemView.setOnClickListener { toggleComplete() }

            if (index == challenges.lastIndex) {
                val lp = itemView.layoutParams as? ViewGroup.MarginLayoutParams
                lp?.marginEnd = 0
                itemView.layoutParams = lp
            }

            container.addView(itemView)
        }
    }

    private fun updateChallengeItemUI(
        challenge: ChallengeItem,
        icon: ImageView,
        overlay: View?,
        checkmark: ImageView,
        timeText: TextView
    ) {
        if (challenge.isCompleted) {
            overlay?.visibility = View.VISIBLE
            checkmark.visibility = View.VISIBLE
            timeText.visibility = View.VISIBLE
            timeText.text = challenge.completionTime

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val blurEffect = RenderEffect.createBlurEffect(15f, 15f, Shader.TileMode.CLAMP)
                icon.setRenderEffect(blurEffect)
            } else {
                icon.alpha = 0.5f
            }
        } else {
            overlay?.visibility = View.GONE
            checkmark.visibility = View.GONE
            timeText.visibility = View.GONE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                icon.setRenderEffect(null)
            } else {
                icon.alpha = 1.0f
            }
        }
    }

    private fun getFallbackChallengeIconRes(challName: String): Int {
        val key = challName.lowercase()

        return when {
            key.contains("샤워") || key.contains("찬물") -> R.drawable.ic_shower
            key.contains("스트레칭") -> R.drawable.ic_stretching
            else -> R.drawable.ic_shower
        }
    }

    private fun String.toChallengePreviewTime(): String {
        return runCatching {
            OffsetDateTime.parse(this)
                .toLocalTime()
                .format(DateTimeFormatter.ofPattern("h:mma", Locale.ENGLISH))
                .lowercase(Locale.ENGLISH)
        }.getOrElse {
            this
        }
    }
}