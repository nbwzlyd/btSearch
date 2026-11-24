package com.aifindres.lite.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.aifindres.lite.R
import com.aifindres.lite.data.Prefs
import com.aifindres.lite.data.Site
import com.aifindres.lite.data.SiteRepository
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.checkbox.MaterialCheckBox

class SitesSheet : BottomSheetDialogFragment() {
    var onChanged: (() -> Unit)? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.dialog_sites, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val ctx = requireContext()
        val all = SiteRepository.all(ctx)
        val prefs = Prefs(ctx)
        val enabled = prefs.enabledIds(all).toMutableSet()

        val tvSub = view.findViewById<TextView>(R.id.tvSub)
        val rv = view.findViewById<RecyclerView>(R.id.rvSites)
        val btnAll = view.findViewById<MaterialButton>(R.id.btnAll)
        val btnNone = view.findViewById<MaterialButton>(R.id.btnNone)
        val btnOk = view.findViewById<MaterialButton>(R.id.btnOk)

        fun refreshSub() {
            tvSub.text = "共 ${all.size} 个站点 · 已选 ${enabled.size} 个"
        }

        val adapter = object : RecyclerView.Adapter<SiteVH>() {
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
                SiteVH(LayoutInflater.from(parent.context).inflate(R.layout.item_site, parent, false))
            override fun getItemCount() = all.size
            override fun onBindViewHolder(h: SiteVH, pos: Int) {
                val vh = h
                val site = all[pos]
                vh.cb.setOnCheckedChangeListener(null)
                vh.cb.isChecked = enabled.contains(site.id)
                vh.name.text = site.name
                vh.info.text = site.request?.searchUrl?.takeIf { it.isNotEmpty() } ?: site.info
                vh.cb.setOnCheckedChangeListener { _, checked ->
                    if (checked) enabled.add(site.id) else enabled.remove(site.id)
                    refreshSub()
                }
                vh.itemView.setOnClickListener { vh.cb.toggle() }
            }
        }
        rv.layoutManager = LinearLayoutManager(ctx)
        rv.adapter = adapter
        refreshSub()

        btnAll.setOnClickListener {
            enabled.clear(); all.forEach { enabled.add(it.id) }; adapter.notifyDataSetChanged(); refreshSub()
        }
        btnNone.setOnClickListener {
            enabled.clear(); adapter.notifyDataSetChanged(); refreshSub()
        }
        btnOk.setOnClickListener {
            prefs.setEnabledIds(enabled)
            onChanged?.invoke()
            dismiss()
        }
    }

    private class SiteVH(v: View) : RecyclerView.ViewHolder(v) {
        val cb: MaterialCheckBox = v.findViewById(R.id.cb)
        val name: TextView = v.findViewById(R.id.tvName)
        val info: TextView = v.findViewById(R.id.tvInfo)
    }
}
