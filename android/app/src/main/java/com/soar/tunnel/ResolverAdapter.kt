package com.soar.tunnel

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.soar.tunnel.databinding.ItemResolverCardBinding

data class ResolverItem(
    var addr: String,
    var rttMs: Double = 0.0,
    var score: Double = 0.0,
    var successCount: Int = 0,
    var failCount: Int = 0,
    var maxEdns0: Int = 1400,
    var isTested: Boolean = false
) {
    val isIran: Boolean
        get() {
            val ip = addr.substringBefore(":")
            val iranPrefixes = listOf(
                "178.22.", "185.51.", "10.", "78.157.", "217.218.", "85.15.", "185.212.",
                "46.100.", "5.202.", "93.114.", "2.188.", "87.107.", "91.92.", "81.29.",
                "188.136.", "185.83.", "94.182.", "178.252.", "85.185.", "79.127.",
                "80.191.", "31.214.", "2.144.", "194.225.", "94.183.", "5.160.", "5.161."
            )
            return iranPrefixes.any { ip.startsWith(it) }
        }
}

class ResolverAdapter(
    private var items: List<ResolverItem>,
    private val onCardClick: (ResolverItem) -> Unit,
    private val onOptionsClick: (ResolverItem, View) -> Unit
) : RecyclerView.Adapter<ResolverAdapter.ViewHolder>() {

    class ViewHolder(val binding: ItemResolverCardBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemResolverCardBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        val context = holder.itemView.context

        holder.binding.tvResolverAddr.text = item.addr

        if (item.isIran) {
            holder.binding.tvRegionBadge.text = "🇮🇷 IRAN"
            holder.binding.tvRegionBadge.setTextColor(Color.parseColor("#38BDF8"))
        } else {
            holder.binding.tvRegionBadge.text = "🌐 GLOBAL"
            holder.binding.tvRegionBadge.setTextColor(Color.parseColor("#94A3B8"))
        }

        holder.binding.tvEdnsBadge.text = "${item.maxEdns0}B"

        if (!item.isTested && item.successCount == 0 && item.failCount == 0) {
            holder.binding.viewStatusDot.setBackgroundColor(Color.parseColor("#64748B"))
            holder.binding.tvLatencyBadge.text = "UNTESTED"
            holder.binding.tvLatencyBadge.setTextColor(Color.parseColor("#94A3B8"))
            holder.binding.tvResolverStats.text = "Tap card to test latency"
        } else if (item.failCount > 0 && item.successCount == 0) {
            holder.binding.viewStatusDot.setBackgroundColor(ContextCompat.getColor(context, R.color.status_red))
            holder.binding.tvLatencyBadge.text = "FAIL"
            holder.binding.tvLatencyBadge.setTextColor(ContextCompat.getColor(context, R.color.status_red))
            holder.binding.tvResolverStats.text = "Offline (${item.failCount} errors)"
        } else {
            val color = when {
                item.rttMs < 120 -> ContextCompat.getColor(context, R.color.status_green)
                item.rttMs < 300 -> ContextCompat.getColor(context, R.color.terminal_cyan)
                else -> ContextCompat.getColor(context, R.color.status_amber)
            }
            holder.binding.viewStatusDot.setBackgroundColor(color)
            holder.binding.tvLatencyBadge.text = "${item.rttMs.toInt()} ms"
            holder.binding.tvLatencyBadge.setTextColor(color)
            holder.binding.tvResolverStats.text = "Score ${String.format(java.util.Locale.US, "%.0f", item.score)} • ${item.successCount} ok"
        }

        holder.binding.root.setOnClickListener { onCardClick(item) }
        holder.binding.root.setOnLongClickListener {
            onOptionsClick(item, holder.binding.btnCardMenu)
            true
        }
        holder.binding.btnCardMenu.setOnClickListener { view ->
            onOptionsClick(item, view)
        }
    }

    override fun getItemCount(): Int = items.size

    fun updateList(newItems: List<ResolverItem>) {
        items = newItems
        notifyDataSetChanged()
    }
}
