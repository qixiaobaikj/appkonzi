package com.remote.controller.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.remote.controller.R
import com.remote.controller.model.DeviceInfo

class DeviceListAdapter(
    private var devices: List<DeviceInfo>,
    private val onConnectClick: (DeviceInfo) -> Unit
) : RecyclerView.Adapter<DeviceListAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvDeviceName: TextView = view.findViewById(R.id.tvDeviceName)
        val tvDeviceId: TextView = view.findViewById(R.id.tvDeviceId)
        val tvOnlineStatus: TextView = view.findViewById(R.id.tvOnlineStatus)
        val btnConnect: MaterialButton = view.findViewById(R.id.btnConnect)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_device, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val device = devices[position]
        holder.tvDeviceName.text = device.deviceName
        holder.tvDeviceId.text = holder.itemView.context.getString(R.string.label_device_id, device.deviceId)
        
        if (device.online) {
            holder.tvOnlineStatus.text = holder.itemView.context.getString(R.string.label_online)
            holder.tvOnlineStatus.setTextColor(holder.itemView.context.getColor(R.color.color_success))
            holder.btnConnect.isEnabled = true
        } else {
            holder.tvOnlineStatus.text = holder.itemView.context.getString(R.string.label_offline)
            holder.tvOnlineStatus.setTextColor(holder.itemView.context.getColor(R.color.color_text_secondary))
            holder.btnConnect.isEnabled = false
        }

        holder.btnConnect.setOnClickListener {
            onConnectClick(device)
        }
    }

    override fun getItemCount() = devices.size

    fun updateDevices(newDevices: List<DeviceInfo>) {
        this.devices = newDevices
        notifyDataSetChanged()
    }
}
