package com.lumatv.app

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/** A remote-navigable channel manager with group filters and persistent hide/show state. */
class ChannelManagerActivity : Activity() {
    private lateinit var storage: Storage
    private lateinit var list: RecyclerView
    private lateinit var groupButton: TextView
    private lateinit var countView: TextView
    private lateinit var groupToggle: TextView
    private val channels by lazy { AppData.entries.filter { it.type == ContentType.LIVE } }
    private var group = "All groups"
    private var query = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        storage = Storage(this)
        val root = Ui.vertical(this).apply {
            setPadding(dp(18), dp(18), dp(18), dp(10))
            setBackgroundColor(Ui.BG)
            isFocusableInTouchMode = true
        }
        val header = Ui.horizontal(this)
        header.addView(Ui.button(this, "‹  Back", false) { finish() })
        header.addView(Ui.text(this, "CHANNEL VISIBILITY", 23f, Ui.WHITE, true).apply {
            setPadding(dp(15), 0, 0, 0)
        })
        root.addView(header, Ui.columnParams(this, 13))
        root.addView(Ui.text(this,
            "Choose which live channels appear in LumaTV. Your changes are saved on this device.",
            14f, Ui.MUTED), Ui.columnParams(this, 12))

        val search = EditText(this).apply {
            hint = "Search channels to hide or show"
            setHintTextColor(Ui.MUTED)
            setTextColor(Ui.WHITE)
            isSingleLine = true
            background = Ui.bg(Ui.SURFACE, dp(12).toFloat())
            setPadding(dp(14), dp(10), dp(14), dp(10))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    query = s?.toString().orEmpty()
                    redraw()
                }
                override fun afterTextChanged(s: Editable?) {}
            })
        }
        root.addView(search, Ui.columnParams(this, 12))
        groupButton = Ui.button(this, "Group: All groups  ▾", false) { chooseGroup() }
        root.addView(groupButton, Ui.columnParams(this, 10))
        val controls = Ui.horizontal(this)
        groupToggle = Ui.button(this, "Hide this group", false) {
            val candidates = groupChannels()
            if (candidates.isNotEmpty()) {
                val hide = candidates.any { !storage.isChannelHidden(it.id) }
                val applyChange = {
                    storage.setHiddenChannels(candidates.map { it.id }, hide)
                    redraw()
                }
                if (group == "All groups" && hide) {
                    AlertDialog.Builder(this).setTitle("Hide every live channel?")
                        .setMessage("All channels will disappear from Live TV until you unhide them here.")
                        .setNegativeButton("Cancel", null)
                        .setPositiveButton("Hide all") { _, _ -> applyChange() }.show()
                } else applyChange()
            }
        }
        controls.addView(groupToggle, LinearLayout.LayoutParams(0, -2, 1f))
        controls.addView(Ui.button(this, "Show all", false) {
            storage.setHiddenChannels(channels.map { it.id }, false)
            redraw()
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(9) })
        root.addView(controls, Ui.columnParams(this, 9))
        countView = Ui.text(this, "", 12f, Ui.MUTED)
        root.addView(countView, Ui.columnParams(this, 10))
        list = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@ChannelManagerActivity)
            clipToPadding = false
            setPadding(0, dp(4), 0, dp(12))
        }
        root.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        redraw()
    }

    private fun groupChannels(): List<MediaEntry> = if (group == "All groups") channels else channels.filter { it.group == group }

    private fun chooseGroup() {
        val groups = listOf("All groups") + channels.map { it.group }.distinct().sorted()
        AlertDialog.Builder(this).setTitle("Choose channel category")
            .setItems(groups.toTypedArray()) { _, which ->
                group = groups[which]
                redraw()
            }.show()
    }

    private fun redraw() {
        if (!::list.isInitialized) return
        val all = groupChannels()
        val filtered = all.filter { it.title.contains(query, ignoreCase = true) }
        val hiddenIds = storage.hiddenChannelIds()
        val hidden = all.count { it.id in hiddenIds }
        groupButton.text = "Group: $group  ▾"
        countView.text = "${all.size - hidden} visible · $hidden hidden · ${filtered.size} matches"
        groupToggle.text = if (all.any { it.id !in hiddenIds }) "Hide group" else "Show group"
        list.adapter = ChannelAdapter(filtered)
    }

    private inner class ChannelAdapter(private val items: List<MediaEntry>) : RecyclerView.Adapter<ChannelAdapter.Holder>() {
        inner class Holder(val line: LinearLayout, val title: TextView, val flag: TextView) : RecyclerView.ViewHolder(line)
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val line = Ui.horizontal(this@ChannelManagerActivity).apply {
                setPadding(dp(13), dp(12), dp(13), dp(12))
                background = Ui.bg(Ui.SURFACE, dp(10).toFloat())
                isClickable = true
                isFocusable = true
                Ui.tvFocus(this)
            }
            val title = Ui.text(this@ChannelManagerActivity, "", 15f, Ui.WHITE, true).apply { maxLines = 2 }
            val flag = Ui.text(this@ChannelManagerActivity, "", 13f, Ui.MINT, true).apply { gravity = Gravity.END }
            line.addView(title, LinearLayout.LayoutParams(0, -2, 1f))
            line.addView(flag, LinearLayout.LayoutParams(dp(90), -2))
            line.layoutParams = RecyclerView.LayoutParams(-1, -2).apply { bottomMargin = dp(8) }
            return Holder(line, title, flag)
        }
        override fun onBindViewHolder(holder: Holder, position: Int) {
            val item = items[position]
            holder.title.text = item.title
            fun paint() {
                val hidden = storage.isChannelHidden(item.id)
                holder.flag.text = if (hidden) "HIDDEN  ✕" else "VISIBLE  ✓"
                holder.flag.setTextColor(if (hidden) Ui.MUTED else Ui.MINT)
                holder.title.alpha = if (hidden) 0.55f else 1f
            }
            paint()
            holder.line.setOnClickListener {
                storage.setChannelHidden(item.id, !storage.isChannelHidden(item.id))
                paint()
                val all = groupChannels()
                val hiddenIds = storage.hiddenChannelIds()
                val hidden = all.count { it.id in hiddenIds }
                countView.text = "${all.size - hidden} visible · $hidden hidden · ${items.size} matches"
                groupToggle.text = if (all.any { it.id !in hiddenIds }) "Hide group" else "Show group"
            }
        }
        override fun getItemCount() = items.size
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()
}
