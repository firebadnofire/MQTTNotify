package org.archuser.mqttnotify.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import org.archuser.mqttnotify.config.TopicConfig
import org.archuser.mqttnotify.databinding.ItemTopicBinding

class TopicAdapter(
    private val topics: MutableList<TopicConfig>,
    private val onEdit: (Int) -> Unit,
    private val onDelete: (Int) -> Unit
) : RecyclerView.Adapter<TopicAdapter.TopicViewHolder>() {

    class TopicViewHolder(val binding: ItemTopicBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TopicViewHolder {
        val binding = ItemTopicBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return TopicViewHolder(binding)
    }

    override fun onBindViewHolder(holder: TopicViewHolder, position: Int) {
        val topic = topics[position]
        holder.binding.topicName.text = topic.topic
        holder.binding.topicQos.text = holder.binding.root.context.getString(
            org.archuser.mqttnotify.R.string.topic_qos_format,
            topic.qos
        )
        holder.binding.editButton.setOnClickListener { onEdit(position) }
        holder.binding.deleteButton.setOnClickListener { onDelete(position) }
    }

    override fun getItemCount(): Int = topics.size
}
