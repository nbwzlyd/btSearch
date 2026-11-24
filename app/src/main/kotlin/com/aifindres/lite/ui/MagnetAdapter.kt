package com.aifindres.lite.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.aifindres.lite.R
import com.aifindres.lite.addon.QualityTag
import com.aifindres.lite.data.MagnetItem
import com.google.android.material.card.MaterialCardView

/** Magnet result list adapter with peers/quality badge line. */
class MagnetAdapter(
    private val click: (MagnetItem) -> Unit,
    private val longClick: (MagnetItem) -> Unit,
) : RecyclerView.Adapter<MagnetAdapter.VH>() {

    val items = ArrayList<MagnetItem>()

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val card: MaterialCardView = v as MaterialCardView
        val title: TextView = v.findViewById(R.id.tvTitle)
        val peers: TextView = v.findViewById(R.id.tvPeers)
        val magnet: TextView = v.findViewById(R.id.tvMagnet)
        val site: TextView = v.findViewById(R.id.tvSite)
        val date: TextView = v.findViewById(R.id.tvDate)
        val size: TextView = v.findViewById(R.id.tvSize)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_magnet, parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.title.text = item.title
        val badge = if (item.probing) "探测中…" else QualityTag.peersBadge(item.seeders, item.leechers)
        val q = item.quality ?: ""
        if (badge.isEmpty() && q.isEmpty()) {
            holder.peers.visibility = View.GONE
        } else {
            holder.peers.visibility = View.VISIBLE
            holder.peers.text = listOf(badge, q).filter { s -> s.isNotEmpty() }.joinToString(" · ")
        }
        val m = when {
            item.magnet.isNotEmpty() -> item.magnet
            item.url.isNotEmpty() -> item.url
            else -> "（无直链，点开看详情）"
        }
        holder.magnet.text = m
        holder.site.text = item.siteName
        holder.date.text = item.date ?: ""
        holder.size.text = item.size ?: ""
        holder.card.setOnClickListener { click(item) }
        holder.card.setOnLongClickListener { longClick(item); true }
    }

    override fun getItemCount(): Int = items.size
}
