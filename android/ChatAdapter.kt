package com.bnn.app

import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ══════════════════════════════════════════════════════════════════
//  CHAT MESSAGE DATA CLASS
// ══════════════════════════════════════════════════════════════════

data class ChatMessage(
    val text:      String,
    val isOutgoing: Boolean,           // true = sent by user, false = received from AI
    val timestamp: Long = System.currentTimeMillis()
)

// ══════════════════════════════════════════════════════════════════
//  CHAT ADAPTER
// ══════════════════════════════════════════════════════════════════

class ChatAdapter : RecyclerView.Adapter<ChatAdapter.MessageViewHolder>() {

    private val messages = mutableListOf<ChatMessage>()
    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

    // ── ViewHolder ─────────────────────────────────────────────────
    inner class MessageViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val bubble:    TextView     = view.findViewById(R.id.tvBubble)
        val timestamp: TextView     = view.findViewById(R.id.tvTimestamp)
        val container: LinearLayout = view.findViewById(R.id.messageContainer)
    }

    // ── RecyclerView required overrides ───────────────────────────
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MessageViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_message, parent, false)
        return MessageViewHolder(view)
    }

    override fun getItemCount(): Int = messages.size

    override fun onBindViewHolder(holder: MessageViewHolder, position: Int) {
        val msg = messages[position]

        holder.bubble.text    = msg.text
        holder.timestamp.text = timeFormat.format(Date(msg.timestamp))

        if (msg.isOutgoing) {
            // User message — right side, accent colour
            holder.container.gravity = Gravity.END
            holder.bubble.setBackgroundResource(R.drawable.bubble_outgoing)
            holder.bubble.setTextColor(holder.bubble.context.getColor(R.color.bubble_outgoing_text))
            holder.timestamp.gravity = Gravity.END
        } else {
            // AI/server message — left side, surface colour
            holder.container.gravity = Gravity.START
            holder.bubble.setBackgroundResource(R.drawable.bubble_incoming)
            holder.bubble.setTextColor(holder.bubble.context.getColor(R.color.bubble_incoming_text))
            holder.timestamp.gravity = Gravity.START
        }
    }

    // ── Public API ─────────────────────────────────────────────────

    fun addMessage(message: ChatMessage) {
        messages.add(message)
        notifyItemInserted(messages.size - 1)
    }

    fun getLastIndex(): Int = messages.size - 1

    fun clear() {
        val size = messages.size
        messages.clear()
        notifyItemRangeRemoved(0, size)
    }
}
