package com.zzq.calendar.ui

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.zzq.calendar.AppGraph
import com.zzq.calendar.R
import com.zzq.calendar.databinding.ActivityYearBinding
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import kotlinx.coroutines.launch

class YearActivity : AppCompatActivity() {

    private lateinit var binding: ActivityYearBinding
    private val repository by lazy { AppGraph.repository(this) }
    private val zone: ZoneId get() = ZoneId.systemDefault()

    private var year: Int = LocalDate.now().year
    private var loadGeneration = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemePrefs.applyAccent(this)
        super.onCreate(savedInstanceState)
        binding = ActivityYearBinding.inflate(layoutInflater)
        setContentView(binding.root)

        year = savedInstanceState?.getInt(KEY_YEAR, 0)?.takeIf { it > 0 }
            ?: intent.getIntExtra(EXTRA_YEAR, LocalDate.now(zone).year)

        binding.previousYearButton.setOnClickListener { shiftYear(-1) }
        binding.nextYearButton.setOnClickListener { shiftYear(1) }
        binding.yearGrid.onMonthClick = { month ->
            startActivity(MonthActivity.monthIntent(this, month))
            finish()
        }
        CalendarTabs.attach(
            this,
            binding.tabBar,
            CalendarTabs.Tab.YEAR,
            anchor = { CalendarTabs.yearAnchor(year, LocalDate.now(zone)) },
        )
        SwipeNavigator.attach(this, canNavigate = { CalendarRange.canShiftYear(year, it) }) {
            shiftYear(CalendarRange.deltaDays(it).toInt())
        }
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_YEAR, year)
    }

    private fun shiftYear(delta: Int) {
        year += delta
        load()
    }

    private fun load() {
        binding.yearTitle.text = getString(R.string.year_title, year)
        val generation = ++loadGeneration
        lifecycleScope.launch(AppGraph.mainDispatcher) {
            val days = runCatching {
                (1..12).flatMap { month ->
                    repository.loadMonth(YearMonth.of(year, month), zone).keys
                }.toSet()
            }.getOrNull()
            if (generation != loadGeneration) return@launch
            binding.yearGrid.submit(year, days.orEmpty(), LocalDate.now(zone))
        }
    }

    companion object {
        private const val KEY_YEAR = "year"
        private const val EXTRA_YEAR = "extra_year"

        fun yearIntent(context: android.content.Context, year: Int): Intent =
            Intent(context, YearActivity::class.java).putExtra(EXTRA_YEAR, year)
    }
}
