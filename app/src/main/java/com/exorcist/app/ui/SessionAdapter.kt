package com.exorcist.app.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.exorcist.app.R
import com.exorcist.app.data.SensorSession
import java.text.SimpleDateFormat
import java.util.Locale

class SessionAdapter : ListAdapter<SensorSession, SessionAdapter.SessionViewHolder>(SessionDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SessionViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_session, parent, false)
        return SessionViewHolder(view)
    }

    override fun onBindViewHolder(holder: SessionViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class SessionViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvLocation: TextView = itemView.findViewById(R.id.tvSessionLocation)
        private val tvTime: TextView = itemView.findViewById(R.id.tvSessionTime)
        private val tvStats: TextView = itemView.findViewById(R.id.tvSessionStats)

        private val dateFormat = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())

        fun bind(session: SensorSession) {
            tvLocation.text = if (session.locationName.isNotBlank()) session.locationName else "Неизвестное место"
            tvTime.text = dateFormat.format(session.startTime)
            tvStats.text = "${session.readingCount} замеров"
        }
    }

    class SessionDiffCallback : DiffUtil.ItemCallback<SensorSession>() {
        override fun areItemsTheSame(oldItem: SensorSession, newItem: SensorSession): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: SensorSession, newItem: SensorSession): Boolean {
            return oldItem == newItem
        }
    }
}
