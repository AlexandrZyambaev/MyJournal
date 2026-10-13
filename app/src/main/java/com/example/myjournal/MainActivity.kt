package com.example.myjournal

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.myjournal.ui.theme.MyJournalTheme
import java.math.BigDecimal
import java.text.NumberFormat
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

private val russian = Locale.forLanguageTag("ru-RU")
private val weekdays = listOf("Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс")
private fun money(value: Long) = NumberFormat.getCurrencyInstance(russian).format(BigDecimal(value).movePointLeft(2))
private fun parseMoney(text: String): Long? = runCatching {
    BigDecimal(text.replace(',', '.').trim()).takeIf { it.signum() >= 0 && it.scale() <= 2 && it <= BigDecimal("10000000") }
        ?.movePointRight(2)?.longValueExact()
}.getOrNull()
private fun validTime(text: String): Boolean = text.matches(Regex("([01][0-9]|2[0-3]):[0-5][0-9]"))
private fun parseDate(text: String): LocalDate? = runCatching { LocalDate.parse(text.trim()) }.getOrNull()
private fun Lesson.timeRange(): String = if (category == Category.MASTER.name && endTime.isNotBlank()) "$time–$endTime" else time

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { MyJournalTheme { JournalApp() } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun JournalApp(vm: JournalViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val currentYear = academicYear(LocalDate.now())
    var year by rememberSaveable { mutableIntStateOf(currentYear) }
    var month by rememberSaveable { mutableIntStateOf(LocalDate.now().monthValue.takeIf { it in 1..5 || it in 9..12 } ?: 9) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var selectedDay by rememberSaveable { mutableStateOf<String?>(null) }
    var yearPicker by remember { mutableStateOf(false) }
    val ym = YearMonth.of(if (month >= 9) year else year + 1, month)
    val monthly = state.lessons.filter { it.date.startsWith(ym.toString()) }
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(title = { Text("МойЖурнал", fontWeight = FontWeight.Bold) },
                actions = { TextButton(onClick = { yearPicker = true }) { Text("$year–${year + 1}") } })
        },
        bottomBar = {
            NavigationBar {
                listOf("Календарь", "Расписание", "Итоги").forEachIndexed { index, label ->
                    NavigationBarItem(selected = tab == index, onClick = { tab = index },
                        icon = { Text(listOf("▦", "≡", "₽")[index], fontSize = 24.sp) }, label = { Text(label) })
                }
            }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (!state.ready) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            else when (tab) {
                0 -> Column(Modifier.verticalScroll(rememberScrollState()).padding(12.dp)) {
                    MonthBar(ym, { if (month == 9) month = 5 else if (month == 1) month = 12 else month-- },
                        { if (month == 5) month = 9 else if (month == 12) month = 1 else month++ })
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Text("Прогноз за месяц", style = MaterialTheme.typography.labelLarge)
                            Text(money(Salary.total(monthly)), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                            Text("${monthly.sumOf { it.count }} занятий", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    Calendar(ym, monthly, state.excluded) { selectedDay = it.toString() }
                    if (state.versions.none { it.year == year }) {
                        Text("Начните с расписания: задайте пары и ставку, чтобы заполнить учебный год.", modifier = Modifier.padding(8.dp))
                        Button(onClick = { tab = 1 }) { Text("Настроить расписание") }
                    }

                }
                1 -> ScheduleScreen(year, state, busy) { date, rate, experience, entries ->
                    vm.execute { vm.repository.saveSchedule(year, date, rate, experience, entries) }
                }
                2 -> Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
                    MonthBar(ym, { if (month == 9) month = 5 else if (month == 1) month = 12 else month-- },
                        { if (month == 5) month = 9 else if (month == 12) month = 1 else month++ })
                    Summary(monthly)
                }
            }
        }
    }
    if (yearPicker) AlertDialog(onDismissRequest = { yearPicker = false }, title = { Text("Учебный год") }, text = {
        Column(Modifier.heightIn(max = 350.dp).verticalScroll(rememberScrollState())) {
            (state.versions.map { it.year } + listOf(year - 1, year, year + 1, currentYear)).distinct().sortedDescending().forEach { option ->
                TextButton(onClick = { year = option; yearPicker = false }) { Text("$option–${option + 1}") }
            }
        }
    }, confirmButton = { TextButton(onClick = { yearPicker = false }) { Text("Закрыть") } })
    selectedDay?.let { date -> DayDialog(LocalDate.parse(date), state, busy, vm, { selectedDay = null }) }
    error?.let { message -> AlertDialog(onDismissRequest = { vm.error.value = null }, title = { Text("Ошибка сохранения") },
        text = { Text(message) }, confirmButton = { TextButton(onClick = { vm.error.value = null }) { Text("Понятно") } }) }
}

@Composable
private fun MonthBar(month: YearMonth, previous: () -> Unit, next: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        TextButton(onClick = previous) { Text("‹", fontSize = 28.sp) }
        Text(month.format(DateTimeFormatter.ofPattern("LLLL yyyy", russian)).replaceFirstChar { it.titlecase(russian) },
            style = MaterialTheme.typography.titleLarge)
        TextButton(onClick = next) { Text("›", fontSize = 28.sp) }
    }
}

@Composable
private fun Calendar(month: YearMonth, lessons: List<Lesson>, excluded: Set<String>, onDay: (LocalDate) -> Unit) {
    val grouped = lessons.groupBy { it.date }
    Row(Modifier.fillMaxWidth()) { weekdays.forEach { Text(it, Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        color = MaterialTheme.colorScheme.onSurfaceVariant) } }
    val offset = month.atDay(1).dayOfWeek.value - 1
    val weeks = (offset + month.lengthOfMonth() + 6) / 7
    repeat(weeks) { week ->
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            repeat(7) { column ->
                val number = week * 7 + column - offset + 1
                if (number !in 1..month.lengthOfMonth()) Spacer(Modifier.weight(1f).height(92.dp))
                else {
                    val date = month.atDay(number)
                    val dayLessons = grouped[date.toString()].orEmpty()
                    val today = date == LocalDate.now()
                    val color = when {
                        today -> MaterialTheme.colorScheme.primaryContainer
                        dayLessons.isNotEmpty() -> MaterialTheme.colorScheme.secondaryContainer
                        else -> MaterialTheme.colorScheme.surfaceContainerLow
                    }
                    Box(Modifier.weight(1f).height(92.dp).background(color, RoundedCornerShape(8.dp)).clickable { onDay(date) }.padding(4.dp)) {
                        if (dayLessons.isEmpty()) Text(number.toString(), Modifier.align(Alignment.Center), fontSize = 18.sp,
                            color = if (date.toString() in excluded) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurface)
                        else Column {
                            Text(number.toString(), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            Text(dayLessons.first().timeRange(), fontSize = 10.sp, maxLines = 1)
                            Text(dayLessons.first().title, fontSize = 10.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 12.sp)
                            if (dayLessons.first().count > 1) Text("×${dayLessons.first().count}", fontSize = 10.sp)
                            if (dayLessons.size > 1) Text("+${dayLessons.drop(1).sumOf { it.count }} зан.", fontSize = 10.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Summary(lessons: List<Lesson>) {
    val gross = lessons.filter { it.category != Category.OTHER.name }.sumOf { it.amountKopecks }
    val other = lessons.filter { it.category == Category.OTHER.name }.sumOf { it.amountKopecks }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Прогноз", style = MaterialTheme.typography.titleMedium)
            Text(money(Salary.afterTax(gross) + other), style = MaterialTheme.typography.headlineMedium)
            Text("Начислено: ${money(gross)}")
            Text("Налог 13%: ${money(gross - Salary.afterTax(gross))}")
        }
    }
    Spacer(Modifier.height(20.dp))
    Text("По категориям", style = MaterialTheme.typography.titleLarge)
    Category.entries.forEach { category ->
        val items = lessons.filter { it.category == category.name }
        Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) { Text(category.label); Text("${items.sumOf { it.count }} занятий", style = MaterialTheme.typography.bodySmall) }
            Text(money(items.sumOf { it.amountKopecks }), fontWeight = FontWeight.SemiBold)
        }
        HorizontalDivider()
    }
}

@Composable
private fun DayDialog(date: LocalDate, state: JournalState, busy: Boolean, vm: JournalViewModel, close: () -> Unit) {
    val lessons = state.lessons.filter { it.date == date.toString() }
    var editor by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Lesson?>(null) }
    var deleting by remember { mutableStateOf<Lesson?>(null) }
    var clearDay by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = close) {
        Surface(shape = RoundedCornerShape(24.dp)) {
            Column(Modifier.padding(20.dp).heightIn(max = 560.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(date.format(DateTimeFormatter.ofPattern("d MMMM, EEEE", russian)), style = MaterialTheme.typography.titleLarge)
                if (date.toString() in state.excluded) Text("Нерабочий день · можно добавить занятие", style = MaterialTheme.typography.bodySmall)
                if (lessons.isEmpty()) Text("Занятий нет", modifier = Modifier.padding(vertical = 16.dp))
                lessons.forEach { lesson ->
                    OutlinedCard(Modifier.fillMaxWidth().clickable { editing = lesson; editor = true }) {
                        Column(Modifier.padding(12.dp)) {
                            Text("${lesson.timeRange()} · ${lesson.title}", fontWeight = FontWeight.Bold)
                            val quantity = if (lesson.category == Category.MASTER.name) " × ${lesson.count}" else ""
                            Text("${Category.valueOf(lesson.category).label}$quantity · ${money(lesson.amountKopecks)}", style = MaterialTheme.typography.bodySmall)
                            if (lesson.note.isNotBlank()) Text(lesson.note, style = MaterialTheme.typography.bodySmall)
                            TextButton(enabled = !busy, onClick = { deleting = lesson }) { Text("Удалить") }
                        }
                    }
                }
                Button(enabled = !busy, onClick = { editing = null; editor = true }, modifier = Modifier.fillMaxWidth()) { Text("Добавить занятие") }
                if (date.toString() !in state.excluded) TextButton(enabled = !busy, onClick = { clearDay = true }) { Text("Сделать день нерабочим") }
                else TextButton(enabled = !busy, onClick = { vm.execute { vm.repository.setExcluded(date, date, false) } }) { Text("Восстановить расписание дня") }
                TextButton(onClick = close, modifier = Modifier.align(Alignment.End)) { Text("Закрыть") }
            }
        }
    }
    if (editor) LessonEditor(date, editing, state, busy, { editor = false }) { lesson ->
        vm.execute { vm.repository.dao.saveLesson(lesson) }; editor = false
    }
    deleting?.let { lesson -> AlertDialog(onDismissRequest = { deleting = null }, title = { Text("Удалить занятие?") },
        text = { Text("${lesson.title}, ${lesson.timeRange()}." +
            (if (lesson.count > 1) " Будет удалена вся запись (${lesson.count} шт.)." else "") +
            " Остальные дни и базовое расписание сохранятся.") },
        confirmButton = { TextButton(enabled = !busy, onClick = { vm.execute { vm.repository.delete(lesson) }; deleting = null }) { Text("Удалить") } },
        dismissButton = { TextButton(onClick = { deleting = null }) { Text("Отмена") } }) }
    if (clearDay) AlertDialog(onDismissRequest = { clearDay = false }, title = { Text("Сделать день нерабочим?") },
        text = { Text("Все занятия ${date} будут удалены. После этого можно добавить занятия вручную.") },
        confirmButton = { TextButton(enabled = !busy, onClick = { vm.execute { vm.repository.setExcluded(date, date, true) }; clearDay = false }) { Text("Очистить день") } },
        dismissButton = { TextButton(onClick = { clearDay = false }) { Text("Отмена") } })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LessonEditor(date: LocalDate, lesson: Lesson?, state: JournalState, busy: Boolean, close: () -> Unit, save: (Lesson) -> Unit) {
    var title by remember { mutableStateOf(lesson?.title.orEmpty()) }
    var time by remember { mutableStateOf(lesson?.time ?: "09:00") }
    var endTime by remember { mutableStateOf(lesson?.endTime.orEmpty()) }
    var countText by remember { mutableStateOf((lesson?.count ?: 1).toString()) }
    var note by remember { mutableStateOf(lesson?.note.orEmpty()) }
    var category by remember { mutableStateOf(lesson?.let { Category.valueOf(it.category) } ?: Category.PAIR) }
    var categoryExpanded by remember { mutableStateOf(false) }
    val version = state.versions.lastOrNull { it.year == academicYear(date) && it.effectiveDate <= date.toString() }
    val base = lesson?.baseKopecks ?: version?.baseKopecks ?: 97000
    val years = lesson?.experience ?: version?.experience ?: 5
    var payment by remember { mutableStateOf(lesson?.takeIf { it.category == Category.OTHER.name }?.let { BigDecimal(it.amountKopecks).movePointLeft(2).toPlainString() }.orEmpty()) }
    val count = if (category == Category.MASTER) countText.toIntOrNull()?.takeIf { it in 1..1000 } else 1
    // Legacy single master classes have no end time; keep them editable until a range is provided.
    val unchangedLegacyTime = lesson?.category == Category.MASTER.name && lesson.endTime.isBlank() &&
        time == lesson.time && count == lesson.count
    val rangeValid = category != Category.MASTER ||
        (validTime(endTime) && validTime(time) && endTime > time) || (endTime.isBlank() && unchangedLegacyTime)
    val amount = if (category == Category.OTHER) parseMoney(payment)
        else count?.let { Salary.amount(base, years, category, it) }
    // Preserve historical pay when only the title, time or note is edited.
    val finalAmount = if (lesson != null && category != Category.OTHER && category.name == lesson.category && count == lesson.count) lesson.amountKopecks else amount
    AlertDialog(onDismissRequest = close, title = { Text(if (lesson == null) "Новое занятие" else "Изменить занятие") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Field(title, { title = it }, "Название")
            Field(time, { time = it }, "Время начала · ЧЧ:ММ", error = !validTime(time))
            ExposedDropdownMenuBox(expanded = categoryExpanded, onExpandedChange = { categoryExpanded = it }) {
                OutlinedTextField(
                    value = category.label, onValueChange = {}, readOnly = true, singleLine = true,
                    label = { Text("Тип занятия") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = categoryExpanded) },
                    modifier = Modifier.menuAnchor().fillMaxWidth()
                )
                ExposedDropdownMenu(expanded = categoryExpanded, onDismissRequest = { categoryExpanded = false }) {
                    Category.entries.forEach { item ->
                        DropdownMenuItem(text = { Text(item.label) }, onClick = {
                            category = item
                            categoryExpanded = false
                        })
                    }
                }
            }
            if (category == Category.MASTER) {
                Field(countText, { countText = it }, "Количество мастер-классов", numeric = true, error = count == null)
                Field(endTime, { endTime = it }, "Время окончания · ЧЧ:ММ", error = !rangeValid)
            }
            if (category == Category.OTHER) Field(payment, { payment = it }, "Итоговая оплата, ₽", numeric = true, error = amount == null)
            else {
                if (category == Category.MASTER) Text("Начислено ${finalAmount?.let { money(it) } ?: "—"}")
                else Text("${category.hours} акад. ч. · начислено ${finalAmount?.let { money(it) } ?: "—"}")
            }
            Field(note, { note = it }, "Заметка", singleLine = false)
        }
    }, confirmButton = {
        TextButton(enabled = !busy && title.isNotBlank() && validTime(time) && rangeValid && count != null && finalAmount != null, onClick = {
            save(Lesson(lesson?.id ?: UUID.randomUUID().toString(), date.toString(), title.trim(), time, category.name, note.trim(),
                finalAmount!!, lesson?.generated ?: false, lesson?.generated ?: false, base, years, count!!,
                if (category == Category.MASTER) endTime else ""))
        }) { Text("Сохранить") }
    }, dismissButton = { TextButton(onClick = close) { Text("Отмена") } })
}

@Composable
private fun Field(value: String, change: (String) -> Unit, label: String, numeric: Boolean = false, error: Boolean = false, singleLine: Boolean = true, modifier: Modifier = Modifier) {
    OutlinedTextField(value, change, label = { Text(label) }, modifier = modifier.fillMaxWidth(), singleLine = singleLine,
        isError = error, keyboardOptions = KeyboardOptions(keyboardType = if (numeric) KeyboardType.Decimal else KeyboardType.Text))
}

@Composable
private fun ScheduleScreen(year: Int, state: JournalState, busy: Boolean,
    save: (LocalDate, Long, Int, List<ScheduleEntry>) -> Unit) {
    val latest = state.versions.lastOrNull { it.year == year }
    var effective by rememberSaveable(year, latest?.id) { mutableStateOf(latest?.effectiveDate ?: "$year-09-01") }
    var rate by rememberSaveable(year, latest?.id) { mutableStateOf(BigDecimal(latest?.baseKopecks ?: 97000).movePointLeft(2).toPlainString()) }
    var experience by rememberSaveable(year, latest?.id) { mutableStateOf((latest?.experience ?: 5).toString()) }
    var entries by remember(year, latest?.id) { mutableStateOf(state.entries.filter { it.versionId == latest?.id }) }
    LaunchedEffect(year, latest?.id, state.entries) {
        entries = state.entries.filter { it.versionId == latest?.id }
    }
    var entryEditor by remember { mutableStateOf(false) }
    var editedIndex by remember { mutableIntStateOf(-1) }
    var confirm by remember { mutableStateOf(false) }
    val date = parseDate(effective)
    val dateValid = date != null && !date.isBefore(LocalDate.of(year, 9, 1)) && !date.isAfter(LocalDate.of(year + 1, 5, 31))
    val base = parseMoney(rate)
    val years = experience.toIntOrNull()?.takeIf { it in 0..80 }
    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Недельное расписание", style = MaterialTheme.typography.headlineSmall)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Field(rate, { rate = it }, "Ставка, ₽ / акад. час", numeric = true, error = base == null, modifier = Modifier.weight(2f))
            Field(experience, { experience = it }, "Стаж, лет", numeric = true, error = years == null, modifier = Modifier.weight(1f))
        }
        if (base != null && years != null) Text("Надбавка ${Salary.experiencePercent(years)}% · пара ${money(Salary.amount(base, years, Category.PAIR))} до налога")
        Field(effective, { effective = it }, "Применить с даты · ГГГГ-ММ-ДД", error = !dateValid)
        if (latest != null) Text("Сохранено ${state.versions.count { it.year == year }} версий. Новые изменения действуют с выбранной даты.", style = MaterialTheme.typography.bodySmall)
        weekdays.forEachIndexed { day, name ->
            Text(name, style = MaterialTheme.typography.titleMedium)
            val indexes = entries.indices.filter { entries[it].weekday == day + 1 }
            if (indexes.isEmpty()) Text("Нет пар", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
            indexes.forEach { index ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { editedIndex = index; entryEditor = true }, modifier = Modifier.weight(1f)) {
                        Text("${entries[index].time} · ${entries[index].title}")
                    }
                    TextButton(onClick = { entries = entries.filterIndexed { i, _ -> i != index } }) { Text("×") }
                }
            }
        }
        OutlinedButton(onClick = { editedIndex = -1; entryEditor = true }, modifier = Modifier.fillMaxWidth()) { Text("Добавить пару в расписание") }
        Button(enabled = !busy && dateValid && base != null && years != null, onClick = { confirm = true }, modifier = Modifier.fillMaxWidth()) { Text("Сохранить и заполнить журнал") }
    }
    if (entryEditor) ScheduleEntryDialog(entries.getOrNull(editedIndex), { entryEditor = false }) { entry ->
        val duplicate = entries.withIndex().any { (i, other) -> i != editedIndex && other.weekday == entry.weekday && other.time == entry.time }
        if (!duplicate) {
            entries = if (editedIndex == -1) entries + entry else entries.mapIndexed { i, old -> if (i == editedIndex) entry else old }
            entryEditor = false
        }
    }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false }, title = { Text("Применить расписание?") },
        text = { Text("Журнал будет заполнен с $effective по ${year + 1}-05-31. Ручные записи, отмены и изменения отдельных занятий сохранятся. Пустое расписание уберет оставшиеся автоматические занятия.") },
        confirmButton = { TextButton(onClick = { save(date!!, base!!, years!!, entries); confirm = false }) { Text("Применить") } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("Отмена") } })
}

@Composable
private fun ScheduleEntryDialog(entry: ScheduleEntry?, close: () -> Unit, save: (ScheduleEntry) -> Unit) {
    var weekday by remember { mutableIntStateOf(entry?.weekday ?: 1) }
    var title by remember { mutableStateOf(entry?.title.orEmpty()) }
    var time by remember { mutableStateOf(entry?.time ?: "09:00") }
    var hint by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = close, title = { Text("Пара в расписании") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row { weekdays.forEachIndexed { index, label ->
                Text(label, Modifier.weight(1f).background(if (weekday == index + 1) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)
                    .clickable { weekday = index + 1 }.padding(vertical = 12.dp), fontSize = 12.sp)
            } }
            Field(title, { title = it }, "Название")
            Field(time, { time = it }, "Время начала · ЧЧ:ММ", error = !validTime(time))
            if (hint) Text("Измените время.", style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = { TextButton(enabled = title.isNotBlank() && validTime(time), onClick = {
        hint = true; save(ScheduleEntry(weekday = weekday, title = title.trim(), time = time))
    }) { Text("Сохранить") } }, dismissButton = { TextButton(onClick = close) { Text("Отмена") } })
}
