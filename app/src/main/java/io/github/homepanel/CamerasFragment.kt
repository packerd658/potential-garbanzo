package io.github.homepanel

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import io.github.homepanel.databinding.FragmentCamerasBinding
import io.github.homepanel.databinding.ItemCameraBinding
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** All cameras, with snapshots refreshed every few seconds while the tab is open. */
class CamerasFragment : Fragment(R.layout.fragment_cameras) {
    private var _b: FragmentCamerasBinding? = null
    private val adapter = CamAdapter()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val b = FragmentCamerasBinding.bind(view).also { _b = it }
        val wide = resources.configuration.screenWidthDp >= 600
        b.list.layoutManager = GridLayoutManager(requireContext(), if (wide) 2 else 1)
        b.list.adapter = adapter
        b.list.itemAnimator = null

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { combine(HaClient.entities, Favourites.ids) { _, _ -> }.collect { render() } }
                launch { CameraImages.images.collect { adapter.notifyItemRangeChanged(0, adapter.itemCount, IMAGE) } }
                launch {
                    while (true) {
                        if (!isHidden) CameraImages.refresh(cameras(), resources.displayMetrics.widthPixels)
                        delay(5_000)
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        _b = null
        super.onDestroyView()
    }

    private fun cameras() = HaClient.entities.value.values.filter { it.domain == "camera" }.sortedBy { it.name.lowercase() }

    private fun render() {
        val b = _b ?: return
        val cams = cameras()
        adapter.submitList(cams.map { CamItem(it, Favourites.contains(it.id)) })
        b.empty.isVisible = cams.isEmpty() && HaClient.status.value is HaClient.Status.Connected
    }

    data class CamItem(val e: Entity, val favourite: Boolean) {
        val content get() = "${e.name}|${e.state}|$favourite"
    }

    inner class CamAdapter : ListAdapter<CamItem, CamAdapter.VH>(DIFF) {
        inner class VH(val b: ItemCameraBinding) : RecyclerView.ViewHolder(b.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(ItemCameraBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: VH, position: Int, payloads: List<Any>) {
            if (payloads.contains(IMAGE)) bindImage(holder, getItem(position).e) else super.onBindViewHolder(holder, position, payloads)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val e = getItem(position).e
            holder.b.name.text = e.name
            Ui.styleStar(holder.b.star, e.id)
            holder.b.star.setOnClickListener { Favourites.toggle(requireContext(), e.id) }
            bindImage(holder, e)
        }

        private fun bindImage(h: VH, e: Entity) {
            val bmp = CameraImages.images.value[e.id]
            h.b.image.setImageBitmap(bmp)
            h.b.placeholder.isVisible = bmp == null
        }
    }

    companion object {
        private const val IMAGE = "image"
        private val DIFF = object : DiffUtil.ItemCallback<CamItem>() {
            override fun areItemsTheSame(a: CamItem, b: CamItem) = a.e.id == b.e.id
            override fun areContentsTheSame(a: CamItem, b: CamItem) = a.content == b.content
        }
    }
}
