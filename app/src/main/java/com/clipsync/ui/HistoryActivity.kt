package com.clipsync.ui

import android.os.Bundle
import android.text.format.DateUtils
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.clipsync.history.HistoryStore

/**
 * 历史记录页。顶部筛选：全部 / 短信 / 剪贴板。
 */
class HistoryActivity : AppCompatActivity() {

    private var filter: String = FILTER_ALL
    private lateinit var filterBar: LinearLayout
    private lateinit var btnAll: Button
    private lateinit var btnSms: Button
    private lateinit var btnClip: Button
    private lateinit var listView: ListView
    private lateinit var emptyText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "历史记录"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        // 筛选栏
        filterBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(24, 16, 24, 8)
        }
        btnAll = filterButton("全部") { setFilter(FILTER_ALL) }
        btnSms = filterButton("短信") { setFilter(FILTER_SMS) }
        btnClip = filterButton("剪贴板") { setFilter(FILTER_CLIP) }
        filterBar.addView(btnAll, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        filterBar.addView(btnSms, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        filterBar.addView(btnClip, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(filterBar)

        emptyText = TextView(this).apply {
            text = "暂无记录"
            setPadding(48, 96, 48, 48)
            textSize = 16f
            visibility = View.GONE
        }
        listView = ListView(this)
        root.addView(emptyText)
        root.addView(listView)
        setContentView(root)

        updateFilterStyles()
        refresh()

        listView.setOnItemClickListener { _, _, position, _ ->
            val item = (listView.adapter as HistoryAdapter).getItem(position)
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
            clipboard.setPrimaryClip(
                android.content.ClipData.newPlainText("ClipSync", item.text)
            )
            Toast.makeText(this, "已复制到剪贴板", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setFilter(f: String) {
        filter = f
        updateFilterStyles()
        refresh()
    }

    private fun updateFilterStyles() {
        val active = 0xFF3B82F6.toInt()   // 蓝色
        val inactive = 0xFFE5E7EB.toInt() // 浅灰
        val activeText = 0xFFFFFFFF.toInt()
        val inactiveText = 0xFF6B7280.toInt()
        listOf(btnAll to (filter == FILTER_ALL),
               btnSms to (filter == FILTER_SMS),
               btnClip to (filter == FILTER_CLIP)).forEach { (btn, isActive) ->
            btn.setBackgroundColor(if (isActive) active else inactive)
            btn.setTextColor(if (isActive) activeText else inactiveText)
        }
    }

    private fun filterButton(text: String, onClick: () -> Unit): Button = Button(this).apply {
        this.text = text
        setOnClickListener { onClick() }
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menu?.add(0, 1, 0, "清空")
        return super.onCreateOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            1 -> {
                when (filter) {
                    FILTER_SMS -> HistoryStore.clearSms(this)
                    FILTER_CLIP -> HistoryStore.clearClip(this)
                    else -> HistoryStore.clearAll(this)
                }
                refresh()
                Toast.makeText(this, "已清空", Toast.LENGTH_SHORT).show()
                true
            }
            android.R.id.home -> { finish(); true }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun refresh() {
        val list = when (filter) {
            FILTER_SMS -> HistoryStore.listSms(this)
            FILTER_CLIP -> HistoryStore.listClip(this)
            else -> HistoryStore.listAll(this)
        }
        listView.adapter = HistoryAdapter(list)
        emptyText.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
    }

    private inner class HistoryAdapter(
        private val items: List<HistoryStore.HistoryItem>
    ) : BaseAdapter() {
        override fun getCount(): Int = items.size
        override fun getItem(position: Int): HistoryStore.HistoryItem = items[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val row = (convertView as? LinearLayout) ?: LinearLayout(this@HistoryActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(36, 24, 36, 24)
                addView(TextView(context).apply { id = 1001; textSize = 15f; setTextColor(0xFF111111.toInt()) })
                addView(TextView(context).apply { id = 1002; textSize = 12f; setTextColor(0xFF888888.toInt()) })
            }
            val item = items[position]
            (row.findViewById<TextView>(1001)).text = item.text
            val dirLabel = if (item.direction == "in") "收到" else "发出"
            val time = DateUtils.getRelativeTimeSpanString(
                item.ts * 1000, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS
            )
            val typeLabel = if (item.kind == "sms" || item.kind == "sms_code") "短信" else "剪贴板"
            (row.findViewById<TextView>(1002)).text = "$typeLabel · $dirLabel · $time"
            return row
        }
    }

    companion object {
        private const val FILTER_ALL = "all"
        private const val FILTER_SMS = "sms"
        private const val FILTER_CLIP = "clip"
    }
}
