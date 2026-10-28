package dev.ajithv.triptracker

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class GridAdapter(
    private val api: Api,
    private val repo: PhotoRepo,
    private val onClick: (Photo) -> Unit,
    private val tagsOf: (Photo) -> Set<String> = { emptySet() },
    private val onTag: (Photo, String, Boolean) -> Unit = { _, _, _ -> },
) : RecyclerView.Adapter<GridAdapter.VH>() {

    private var items: List<Photo> = emptyList()
    private var currentAsset: String? = null
    var admin = false                 // only admins may tag
    private var tagMode = false       // the grid top-bar "Tag" switch

    fun submit(list: List<Photo>) { items = list; notifyDataSetChanged() }
    fun setTagMode(on: Boolean) { if (tagMode != on) { tagMode = on; notifyDataSetChanged() } }

    fun setCurrent(asset: String?) {
        if (asset == currentAsset) return
        val old = indexOfAsset(currentAsset)
        currentAsset = asset
        val neu = indexOfAsset(asset)
        if (old >= 0) notifyItemChanged(old)
        if (neu >= 0) notifyItemChanged(neu)
    }

    fun indexOfAsset(asset: String?): Int = items.indexOfFirst { it.asset == asset }

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_grid, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(h: VH, pos: Int) {
        val p = items[pos]
        repo.show(h.img, api.thumbUrl(p.asset), maxDim = 512)
        h.play.visibility = if (p.type == "video") View.VISIBLE else View.GONE
        h.hl.visibility = if (p.asset == currentAsset) View.VISIBLE else View.GONE
        h.itemView.setOnClickListener { onClick(p) }
        // detach listeners before mutating checked state so recycled rows don't fire spurious tag calls
        h.cbRel.setOnCheckedChangeListener(null); h.cbWorld.setOnCheckedChangeListener(null)
        if (tagMode && admin) {
            val tags = tagsOf(p)
            h.cbRel.isChecked = tags.contains("Relatives")
            h.cbWorld.isChecked = tags.contains("World")
            h.cbRel.visibility = View.VISIBLE; h.cbWorld.visibility = View.VISIBLE
            h.cbRel.setOnCheckedChangeListener { _, on -> onTag(p, "Relatives", on) }
            h.cbWorld.setOnCheckedChangeListener { _, on -> onTag(p, "World", on) }
        } else {
            h.cbRel.visibility = View.GONE; h.cbWorld.visibility = View.GONE
        }
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val img: ImageView = v.findViewById(R.id.g_img)
        val play: TextView = v.findViewById(R.id.g_play)
        val hl: View = v.findViewById(R.id.g_hl)
        val cbRel: CheckBox = v.findViewById(R.id.g_cb_rel)
        val cbWorld: CheckBox = v.findViewById(R.id.g_cb_world)
    }
}
