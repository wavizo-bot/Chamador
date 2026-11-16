package com.telefonechamador.app.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.telefonechamador.app.R
import com.telefonechamador.app.call.UiSegment
import com.telefonechamador.app.data.BlockedNumberEntity
import com.telefonechamador.app.data.CallEntity
import com.telefonechamador.app.data.SearchResultRow
import com.telefonechamador.app.util.Fmt

data class ContactRow(val name: String, val number: String)

// ---------- histórico de ligações ----------

class CallsAdapter(
    private val onClick: (CallEntity) -> Unit,
    private val onFav: (CallEntity) -> Unit,
) : ListAdapter<CallEntity, CallsAdapter.VH>(DIFF) {

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<CallEntity>() {
            override fun areItemsTheSame(a: CallEntity, b: CallEntity) = a.id == b.id
            override fun areContentsTheSame(a: CallEntity, b: CallEntity) = a == b
        }
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val icon: ImageButton = v.findViewById(R.id.icon_dir)
        val name: TextView = v.findViewById(R.id.tv_name)
        val time: TextView = v.findViewById(R.id.tv_time)
        val duration: TextView = v.findViewById(R.id.tv_duration)
        val fav: ImageButton = v.findViewById(R.id.btn_fav)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_call, parent, false))

    override fun onBindViewHolder(h: VH, pos: Int) {
        val item = getItem(pos)
        val c = h.itemView.context
        h.icon.setImageResource(
            when (item.direction) {
                "in" -> R.drawable.ic_call_in
                "out" -> R.drawable.ic_call_out
                else -> R.drawable.ic_call_missed
            }
        )
        h.icon.setColorFilter(ContextCompat.getColor(c, R.color.cc_green_deep))
        h.name.text = item.contactName?.takeIf { it.isNotBlank() } ?: item.number
        h.time.text = Fmt.whenLabel(c, item.startedAt)
        h.duration.text =
            if (item.direction == "missed") "Não atendida"
            else if (item.segmentCount > 0) "${item.segmentCount} falas"
            else Fmt.duration(item.durationSec)
        h.fav.setImageResource(if (item.favorite) R.drawable.ic_star_filled else R.drawable.ic_star)
        h.fav.setColorFilter(ContextCompat.getColor(c, if (item.favorite) R.color.cc_amber else R.color.cc_ink_faint))
        h.fav.setOnClickListener { onFav(item) }
        h.itemView.setOnClickListener { onClick(item) }
    }
}

// ---------- segmentos de transcrição ----------

class SegmentsAdapter : ListAdapter<UiSegment, SegmentsAdapter.VH>(DIFF) {

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<UiSegment>() {
            override fun areItemsTheSame(a: UiSegment, b: UiSegment) =
                a.offsetMs == b.offsetMs && a.text == b.text
            override fun areContentsTheSame(a: UiSegment, b: UiSegment) = a == b
        }
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val side: TextView = v.findViewById(R.id.tv_side)
        val time: TextView = v.findViewById(R.id.tv_time)
        val text: TextView = v.findViewById(R.id.tv_text)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_segment, parent, false))

    override fun onBindViewHolder(h: VH, pos: Int) {
        val s = getItem(pos)
        val c = h.itemView.context
        when (s.side) {
            "sent" -> {
                h.side.text = "Você"
                h.side.setTextColor(ContextCompat.getColor(c, R.color.cc_blue))
            }
            "mixed" -> {
                h.side.text = "—"
                h.side.setTextColor(ContextCompat.getColor(c, R.color.cc_ink_faint))
            }
            else -> {
                h.side.text = "Interlocutor"
                h.side.setTextColor(ContextCompat.getColor(c, R.color.cc_green))
            }
        }
        h.time.text = Fmt.mmss(s.offsetMs)
        h.text.text = s.text
    }
}

// ---------- contatos ----------

class ContactsAdapter(private val onClick: (ContactRow) -> Unit) :
    ListAdapter<ContactRow, ContactsAdapter.VH>(DIFF) {

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<ContactRow>() {
            override fun areItemsTheSame(a: ContactRow, b: ContactRow) =
                a.number == b.number && a.name == b.name
            override fun areContentsTheSame(a: ContactRow, b: ContactRow) = a == b
        }
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val name: TextView = v.findViewById(R.id.tv_name)
        val number: TextView = v.findViewById(R.id.tv_number)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_contact, parent, false))

    override fun onBindViewHolder(h: VH, pos: Int) {
        val item = getItem(pos)
        h.name.text = item.name
        h.number.text = item.number
        h.itemView.setOnClickListener { onClick(item) }
    }
}

// ---------- busca ----------

class SearchAdapter(private val onClick: (SearchResultRow) -> Unit) :
    ListAdapter<SearchResultRow, SearchAdapter.VH>(DIFF) {

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<SearchResultRow>() {
            override fun areItemsTheSame(a: SearchResultRow, b: SearchResultRow) =
                a.callId == b.callId && a.offsetMs == b.offsetMs && a.snippet == b.snippet
            override fun areContentsTheSame(a: SearchResultRow, b: SearchResultRow) = a == b
        }
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val where: TextView = v.findViewById(R.id.tv_where)
        val snippet: TextView = v.findViewById(R.id.tv_snippet)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_search, parent, false))

    override fun onBindViewHolder(h: VH, pos: Int) {
        val r = getItem(pos)
        h.where.text = buildString {
            append(r.contactName?.takeIf { it.isNotBlank() } ?: r.number)
            append(" • ")
            append(Fmt.whenLabel(h.itemView.context, r.startedAt))
        }
        h.snippet.text = buildString {
            append("[")
            append(Fmt.mmss(r.offsetMs))
            append("] ")
            append(r.snippet)
        }
        h.itemView.setOnClickListener { onClick(r) }
    }
}

// ---------- números bloqueados ----------

class BlockedAdapter(private val onRemove: (BlockedNumberEntity) -> Unit) :
    ListAdapter<BlockedNumberEntity, BlockedAdapter.VH>(DIFF) {

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<BlockedNumberEntity>() {
            override fun areItemsTheSame(a: BlockedNumberEntity, b: BlockedNumberEntity) =
                a.number == b.number
            override fun areContentsTheSame(a: BlockedNumberEntity, b: BlockedNumberEntity) = a == b
        }
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val number: TextView = v.findViewById(R.id.tv_number)
        val remove: ImageButton = v.findViewById(R.id.btn_remove)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_blocked, parent, false))

    override fun onBindViewHolder(h: VH, pos: Int) {
        val item = getItem(pos)
        h.number.text = item.number
        h.number.isVisible = true
        h.remove.setOnClickListener { onRemove(item) }
    }
}
