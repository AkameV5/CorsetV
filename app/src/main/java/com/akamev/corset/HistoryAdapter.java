package com.akamev.corset;

import android.text.format.DateUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class HistoryAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    // Два типа элементов: Заголовок и Сама запись
    private static final int TYPE_HEADER = 0;
    private static final int TYPE_ITEM = 1;

    // Вспомогательный класс для отображения
    private static class DisplayItem {
        int type;
        String headerTitle; // Для заголовка
        ChatHistoryManager.HistoryItem historyItem; // Для записи

        DisplayItem(String title) {
            this.type = TYPE_HEADER;
            this.headerTitle = title;
        }
        DisplayItem(ChatHistoryManager.HistoryItem item) {
            this.type = TYPE_ITEM;
            this.historyItem = item;
        }
    }

    private final List<DisplayItem> displayList = new ArrayList<>();
    private final OnItemClickListener listener;

    public interface OnItemClickListener {
        void onItemClick(String query, String answer);
    }

    public HistoryAdapter(List<ChatHistoryManager.HistoryItem> rawItems, OnItemClickListener listener) {
        this.listener = listener;
        processData(rawItems);
    }

    // Магия группировки по датам
    private void processData(List<ChatHistoryManager.HistoryItem> rawItems) {
        displayList.clear();
        if (rawItems.isEmpty()) return;

        SimpleDateFormat dateSdf = new SimpleDateFormat("d MMMM", new Locale("ru"));
        String lastDate = "";

        for (ChatHistoryManager.HistoryItem item : rawItems) {
            String dateStr;
            if (DateUtils.isToday(item.timestamp)) {
                dateStr = "Сегодня";
            } else if (DateUtils.isToday(item.timestamp + DateUtils.DAY_IN_MILLIS)) {
                dateStr = "Вчера";
            } else {
                dateStr = dateSdf.format(new Date(item.timestamp));
            }

            // Если дата изменилась по сравнению с предыдущим элементом -> добавляем заголовок
            if (!dateStr.equals(lastDate)) {
                displayList.add(new DisplayItem(dateStr));
                lastDate = dateStr;
            }
            // Добавляем саму запись
            displayList.add(new DisplayItem(item));
        }
    }

    @Override
    public int getItemViewType(int position) {
        return displayList.get(position).type;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        if (viewType == TYPE_HEADER) {
            View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_history_header, parent, false);
            return new HeaderViewHolder(view);
        } else {
            View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_history, parent, false);
            return new ItemViewHolder(view);
        }
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        DisplayItem item = displayList.get(position);
        if (holder instanceof HeaderViewHolder) {
            ((HeaderViewHolder) holder).title.setText(item.headerTitle);
        } else if (holder instanceof ItemViewHolder) {
            ((ItemViewHolder) holder).bind(item.historyItem, listener);
        }
    }

    @Override
    public int getItemCount() { return displayList.size(); }

    // Холдер для Заголовка
    static class HeaderViewHolder extends RecyclerView.ViewHolder {
        TextView title;
        HeaderViewHolder(View itemView) {
            super(itemView);
            title = itemView.findViewById(R.id.header_title);
        }
    }

    // Холдер для Записи (Вопрос + Ответ)
    static class ItemViewHolder extends RecyclerView.ViewHolder {
        TextView query, answer;
        ItemViewHolder(View itemView) {
            super(itemView);
            query = itemView.findViewById(R.id.history_query);
            answer = itemView.findViewById(R.id.history_answer);
        }

        void bind(ChatHistoryManager.HistoryItem item, OnItemClickListener listener) {
            query.setText(item.query);
            answer.setText(item.answer);
            itemView.setOnClickListener(v -> listener.onItemClick(item.query, item.answer));
        }
    }
}