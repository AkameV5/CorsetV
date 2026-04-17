package com.akamev.corset;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import java.util.ArrayList;
import java.util.List;

public class ChatAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private final List<ChatMessage> messages = new ArrayList<>();
    private static final int VIEW_TYPE_USER = 1;
    private static final int VIEW_TYPE_AI = 2;

    public static class ChatMessage {
        String text;
        boolean isUser;
        public ChatMessage(String text, boolean isUser) {
            this.text = text;
            this.isUser = isUser;
        }
    }

    public void addMessage(String text, boolean isUser) {
        messages.add(new ChatMessage(text, isUser));
        notifyItemInserted(messages.size() - 1);
    }

    @Override
    public int getItemViewType(int position) {
        return messages.get(position).isUser ? VIEW_TYPE_USER : VIEW_TYPE_AI;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        if (viewType == VIEW_TYPE_USER) {
            View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_message_user, parent, false);
            return new MessageViewHolder(view);
        } else {
            View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_message_ai, parent, false);
            return new MessageViewHolder(view);
        }
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        ((MessageViewHolder) holder).bind(messages.get(position));
    }

    @Override
    public int getItemCount() { return messages.size(); }

    static class MessageViewHolder extends RecyclerView.ViewHolder {
        TextView textView;
        MessageViewHolder(View itemView) {
            super(itemView);
            textView = itemView.findViewById(R.id.text_message);
        }
        void bind(ChatMessage message) {
            textView.setText(message.text);
        }
    }
}