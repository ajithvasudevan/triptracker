package dev.ajithv.triptracker

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class TripAdapter(
    private val onToggle: (Trip) -> Unit,
    private val onMenu: (Trip) -> Unit,   // admin only: 3-dot button opens the per-track action popup
) : RecyclerView.Adapter<TripAdapter.VH>() {

    private var items: List<Trip> = emptyList()
    private val selected = HashSet<Int>()
    private var admin = false

    fun submit(list: List<Trip>, sel: Set<Int>, isAdmin: Boolean) {
        items = list; selected.clear(); selected.addAll(sel); admin = isAdmin
        notifyDataSetChanged()
    }

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_trip, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(h: VH, pos: Int) {
        val t = items[pos]
        val color = parseColor(t.color)
        (h.swatch.background as? GradientDrawable ?: GradientDrawable().also { it.shape = GradientDrawable.OVAL; h.swatch.background = it })
            .setColor(color)
        h.name.text = t.name ?: "Trip ${t.id}"
        h.name.setTypeface(null, if (t.hidden) Typeface.ITALIC else Typeface.BOLD)
        h.name.alpha = if (t.hidden) 0.45f else 1f
        h.live.visibility = if (t.active) View.VISIBLE else View.GONE
        h.sub.text = "${fmtDate(t.startedAt)} · ${fmtDist(t.distanceM)} · ${t.photos} 📷"
        h.selBar.setBackgroundColor(if (selected.contains(t.id)) color else Color.TRANSPARENT)
        h.itemView.setBackgroundColor(if (selected.contains(t.id)) 0x22FFFFFF else Color.TRANSPARENT)

        h.main.setOnClickListener { onToggle(t) }
        h.actMenu.visibility = if (admin) View.VISIBLE else View.GONE   // 3-dot menu: admin only
        h.actMenu.setOnClickListener { onMenu(t) }
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val selBar: View = v.findViewById(R.id.sel_bar)
        val swatch: View = v.findViewById(R.id.swatch)
        val name: TextView = v.findViewById(R.id.name)
        val live: TextView = v.findViewById(R.id.live)
        val sub: TextView = v.findViewById(R.id.sub)
        val main: View = v.findViewById(R.id.t_main)
        val actMenu: ImageButton = v.findViewById(R.id.act_menu)
    }

    companion object {
        private val fmtIso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        private val fmtOut = SimpleDateFormat("d MMM HH:mm", Locale.US)

        fun parseColor(c: String?): Int = try { Color.parseColor(c ?: "#1565c0") } catch (e: Exception) { Color.parseColor("#1565c0") }

        fun fmtDist(m: Double): String =
            if (m >= 1000) String.format(Locale.US, "%.1f km", m / 1000.0) else "${Math.round(m)} m"

        fun fmtDate(iso: String?): String {
            if (iso == null) return ""
            return try {
                val clean = iso.substringBefore('.').substringBefore('+').removeSuffix("Z")
                fmtOut.format(fmtIso.parse(clean)!!)
            } catch (e: Exception) { iso }
        }
    }
}
