package com.amethyst2213.operatorchat

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load

/**
 * Thread adapter.
 *
 * Differences from the version this replaces:
 *  - ListAdapter + DiffUtil, so a new message animates in and the list no
 *    longer re-binds every row on each poll (that is what made the thread
 *    feel janky and dropped the scroll position).
 *  - three view types (incoming / outgoing / day separator) instead of one
 *    layout that tried to be both.
 *  - the delivery state is part of the row, so "Sending…"/"Queued"/"Failed"
 *    survives a scroll instead of living in a status TextView above.
 */
class MessageAdapter(
    private val onImageClick: (ChatBridge.Message) -> Unit,
    private val onRetryClick: (ChatBridge.Message) -> Unit,
) : ListAdapter<ChatRow, RecyclerView.ViewHolder>(DIFF) {

    override fun getItemViewType(position: Int): Int = getItem(position).type

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            ChatRow.TYPE_DAY -> DayHolder(
                inflater.inflate(R.layout.item_day_separator, parent, false)
            )
            ChatRow.TYPE_OUTGOING -> MessageHolder(
                inflater.inflate(R.layout.item_message_out, parent, false)
            )
            else -> MessageHolder(
                inflater.inflate(R.layout.item_message_in, parent, false)
            )
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val row = getItem(position)
        when (holder) {
            is DayHolder -> holder.bind(row)
            is MessageHolder -> holder.bind(row, onImageClick, onRetryClick)
        }
    }

    class DayHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val label: TextView = view.findViewById(R.id.day_label)
        fun bind(row: ChatRow) {
            label.text = row.dayLabel.orEmpty()
        }
    }

    class MessageHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val bubble: TextView = view.findViewById(R.id.row_bubble)
        private val header: TextView = view.findViewById(R.id.row_header)
        private val meta: TextView = view.findViewById(R.id.row_meta)
        private val image: ImageView = view.findViewById(R.id.row_image)
        private val file: TextView = view.findViewById(R.id.row_file)

        fun bind(
            row: ChatRow,
            onImageClick: (ChatBridge.Message) -> Unit,
            onRetryClick: (ChatBridge.Message) -> Unit,
        ) {
            val m = row.message ?: return
            val ctx = itemView.context

            // --- header: only on the first row of a sender group -----------
            header.visibility = if (row.showHeader) View.VISIBLE else View.GONE
            header.text = when {
                m.senderRole.equals("operator", true) -> "You"
                m.senderRole.equals("user", true) -> "Member"
                else -> "AI"
            }

            // --- bubble -------------------------------------------------------
            bubble.text = m.message
            bubble.visibility = if (m.message.isBlank()) View.GONE else View.VISIBLE

            // Grouped rows keep the uniform radius; a group edge gets the tail.
            bubble.setBackgroundResource(
                if (row.isOutgoing) {
                    if (row.grouped) R.drawable.bg_bubble_out
                    else R.drawable.bg_bubble_out_last
                } else {
                    if (row.grouped) R.drawable.bg_bubble_in
                    else R.drawable.bg_bubble_in_last
                }
            )

            // --- attachment ---------------------------------------------------
            image.setImageDrawable(null)
            image.visibility = View.GONE
            image.setOnClickListener(null)
            file.visibility = View.GONE
            file.setOnClickListener(null)

            val hasName = !m.attachmentName.isNullOrBlank()
            if (hasName) {
                when {
                    m.mediaExpired -> {
                        file.text = ctx.getString(R.string.attachment_expired)
                        file.setCompoundDrawablesRelativeWithIntrinsicBounds(
                            R.drawable.ic_lock, 0, 0, 0
                        )
                        file.visibility = View.VISIBLE
                    }
                    !m.attachmentThumbUrl.isNullOrBlank() -> {
                        image.visibility = View.VISIBLE
                        image.contentDescription = ctx.getString(R.string.attachment_desc)
                        image.load(m.attachmentThumbUrl) {
                            crossfade(true)
                            placeholder(R.color.surface_elevated)
                        }
                        image.setOnClickListener { onImageClick(m) }
                    }
                    else -> {
                        // No thumbnail (e.g. a video without a poster): keep the
                        // file pill, but make it tappable so the attachment can
                        // still be opened and played.
                        file.text = m.attachmentName
                        file.setCompoundDrawablesRelativeWithIntrinsicBounds(
                            R.drawable.ic_folder, 0, 0, 0
                        )
                        file.contentDescription = ctx.getString(R.string.attachment_desc)
                        file.visibility = View.VISIBLE
                        file.setOnClickListener { onImageClick(m) }
                    }
                }
            }

            // --- meta line: clock + delivery state ----------------------------
            val parts = mutableListOf<String>()
            if (row.timeLabel.isNotEmpty()) parts += row.timeLabel
            when (row.delivery) {
                Delivery.SENDING -> parts += ctx.getString(R.string.delivery_sending)
                Delivery.QUEUED  -> parts += ctx.getString(R.string.delivery_queued)
                Delivery.FAILED   -> parts += ctx.getString(R.string.delivery_failed)
                Delivery.SENT    -> Unit
            }
            meta.text = parts.joinToString(" · ")
            meta.visibility = if (parts.isEmpty()) View.GONE else View.VISIBLE

            // A failed outgoing message is tappable to retry.
            val retryable = row.isOutgoing &&
                (row.delivery == Delivery.FAILED || row.delivery == Delivery.QUEUED)
            meta.setTextColor(
                if (row.delivery == Delivery.FAILED) {
                    androidx.core.content.ContextCompat.getColor(ctx, R.color.danger)
                } else {
                    androidx.core.content.ContextCompat.getColor(ctx, R.color.text_tertiary)
                }
            )
            if (retryable) {
                meta.setOnClickListener { onRetryClick(m) }
                // TalkBack: the meta line is a button when it retries.
                meta.isFocusable = true
                meta.isClickable = true
            } else {
                meta.setOnClickListener(null)
                meta.isFocusable = false
                meta.isClickable = false
            }
        }
    }

    private companion object {
        val DIFF = object : DiffUtil.ItemCallback<ChatRow>() {
            override fun areItemsTheSame(a: ChatRow, b: ChatRow): Boolean = a.key == b.key

            override fun areContentsTheSame(a: ChatRow, b: ChatRow): Boolean = a == b
        }
    }
}
