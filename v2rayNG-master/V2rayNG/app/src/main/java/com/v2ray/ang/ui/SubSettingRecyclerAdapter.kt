package com.v2ray.ang.ui

import android.graphics.Color
import android.text.TextUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.v2ray.ang.contracts.BaseAdapterListener
import com.v2ray.ang.databinding.ItemRecyclerSubSettingBinding
import com.v2ray.ang.helper.ItemTouchHelperAdapter
import com.v2ray.ang.helper.ItemTouchHelperViewHolder
import com.v2ray.ang.util.Utils
import com.v2ray.ang.viewmodel.SubscriptionsViewModel

class SubSettingRecyclerAdapter(
    private val viewModel: SubscriptionsViewModel,
    private val adapterListener: BaseAdapterListener?
) : RecyclerView.Adapter<SubSettingRecyclerAdapter.MainViewHolder>(), ItemTouchHelperAdapter {

    private fun getFilteredList() = viewModel.getAll().filter {
        !TextUtils.isEmpty(it.subscription.url)
    }

    override fun getItemCount() = getFilteredList().size

    override fun onBindViewHolder(holder: MainViewHolder, position: Int) {
        val subscriptions = getFilteredList()
        val subId = subscriptions[position].guid
        val subItem = subscriptions[position].subscription
        holder.itemSubSettingBinding.tvName.text = subItem.remarks
        holder.itemSubSettingBinding.tvUrl.text = maskUrl(subItem.url)
        holder.itemSubSettingBinding.chkEnable.isChecked = subItem.enabled
        holder.itemSubSettingBinding.tvLastUpdated.text = Utils.formatTimestamp(subItem.lastUpdated)
        holder.itemView.setBackgroundColor(Color.TRANSPARENT)

        holder.itemSubSettingBinding.layoutEdit.setOnClickListener {
            OtpDialog.show(holder.itemView.context) {
                adapterListener?.onEdit(subId, position)
            }
        }

        holder.itemSubSettingBinding.layoutRemove.setOnClickListener {
            OtpDialog.show(holder.itemView.context) {
                adapterListener?.onRemove(subId, position)
            }
        }

        holder.itemSubSettingBinding.chkEnable.setOnCheckedChangeListener { switchView, isChecked ->
            if (!switchView.isPressed) return@setOnCheckedChangeListener

            // Revert ngay, chờ OTP xác nhận
            switchView.isChecked = !isChecked

            OtpDialog.show(holder.itemView.context) {
                // ✅ OTP đúng → thực sự toggle
                switchView.isChecked = isChecked
                subItem.enabled = isChecked
                viewModel.update(subId, subItem)
            }
        }

        holder.itemSubSettingBinding.layoutUrl.visibility = View.VISIBLE
        holder.itemSubSettingBinding.layoutShare.visibility = View.GONE
        holder.itemSubSettingBinding.chkEnable.visibility = View.VISIBLE
        holder.itemSubSettingBinding.layoutLastUpdated.visibility = View.VISIBLE
        holder.itemSubSettingBinding.layoutShare.setOnClickListener {
            adapterListener?.onShare(subItem.url)
        }
    }

    private fun maskUrl(url: String): String {
        return try {
            val uri = java.net.URI(url)
            val host = uri.host ?: return url

            val maskedHost = host.replace(Regex("(\\w{3})(\\.[^.]+)$")) { match ->
                "***" + match.groupValues[2]
            }

            val maskedPath = uri.path?.replace(Regex("(/client/)(.+)")) {
                it.groupValues[1] + "***"
            } ?: uri.path

            "${uri.scheme}://$maskedHost$maskedPath"
        } catch (e: Exception) {
            url
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MainViewHolder {
        return MainViewHolder(
            ItemRecyclerSubSettingBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            )
        )
    }

    class MainViewHolder(val itemSubSettingBinding: ItemRecyclerSubSettingBinding) :
        BaseViewHolder(itemSubSettingBinding.root), ItemTouchHelperViewHolder

    open class BaseViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        fun onItemSelected() {
            itemView.setBackgroundColor(Color.LTGRAY)
        }

        fun onItemClear() {
            itemView.setBackgroundColor(0)
        }
    }

    override fun onItemMove(fromPosition: Int, toPosition: Int): Boolean {
        viewModel.swap(fromPosition, toPosition)
        notifyItemMoved(fromPosition, toPosition)
        return true
    }

    override fun onItemMoveCompleted() {
        adapterListener?.onRefreshData()
    }

    override fun onItemDismiss(position: Int) {
    }
}