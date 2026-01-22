package org.archuser.mqttnotify

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton

class TopicAdapter(
    private val onRemove: (TopicConfig) -> Unit
) : RecyclerView.Adapter<TopicAdapter.TopicViewHolder>() {
    private val items = mutableListOf<TopicConfig>()

    fun submitList(topics: List<TopicConfig>) {
        items.clear()
        items.addAll(topics)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TopicViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_topic, parent, false)
        return TopicViewHolder(view)
    }

    override fun onBindViewHolder(holder: TopicViewHolder, position: Int) {
        val item = items[position]
        holder.bind(item, onRemove)
    }

    override fun getItemCount(): Int = items.size

    class TopicViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val name: TextView = itemView.findViewById(R.id.topic_name)
        private val remove: MaterialButton = itemView.findViewById(R.id.remove_topic)

        fun bind(item: TopicConfig, onRemove: (TopicConfig) -> Unit) {
            name.text = item.topic
            remove.setOnClickListener { onRemove(item) }
        }
    }
}
