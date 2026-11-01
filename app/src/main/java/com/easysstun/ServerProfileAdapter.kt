package com.easysstun

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.easysstun.databinding.ItemServerProfileBinding

internal data class ServerProfileItem(val profile: Profile, val isActive: Boolean)

internal class ServerProfileAdapter(
    private val onClick: (Profile) -> Unit,
) : ListAdapter<ServerProfileItem, ServerProfileAdapter.ViewHolder>(DIFF_CALLBACK) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
        ViewHolder(
            ItemServerProfileBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false,
            )
        )

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(
        private val binding: ItemServerProfileBinding,
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: ServerProfileItem) {
            val context = binding.root.context
            val profile = item.profile
            binding.serverName.text = profile.name.ifBlank { profile.server }
            binding.serverAddress.text = if (item.isActive) {
                context.getString(R.string.server_address_active, profile.server)
            } else {
                profile.server
            }
            binding.serverActiveBadge.visibility = if (item.isActive) View.VISIBLE else View.GONE
            // 行会被回收：非 active 背景必须显式重置，不能依赖新建卡片的默认值。
            binding.root.setCardBackgroundColor(
                context.getColor(
                    if (item.isActive) {
                        R.color.home_card_background_color_active
                    } else {
                        R.color.home_card_background_color
                    }
                )
            )
            binding.root.setOnClickListener { onClick(profile) }
        }
    }

    private companion object {
        val DIFF_CALLBACK = object : DiffUtil.ItemCallback<ServerProfileItem>() {
            override fun areItemsTheSame(
                oldItem: ServerProfileItem,
                newItem: ServerProfileItem,
            ): Boolean = oldItem.profile.id == newItem.profile.id

            override fun areContentsTheSame(
                oldItem: ServerProfileItem,
                newItem: ServerProfileItem,
            ): Boolean = oldItem == newItem
        }
    }
}
