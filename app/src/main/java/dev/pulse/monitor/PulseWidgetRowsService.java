package dev.pulse.monitor;

import android.content.Intent;
import android.content.Context;
import android.widget.RemoteViews;
import android.widget.RemoteViewsService;
import java.util.Collections;
import java.util.List;

/** Remote collection fallback for Android 8–11; Android 12+ receives inline items. */
public final class PulseWidgetRowsService extends RemoteViewsService {
    @Override public RemoteViewsFactory onGetViewFactory(Intent intent) {
        return new Factory(this);
    }
    static final class Factory implements RemoteViewsFactory {
            private final Context context;
            Factory(Context context){this.context=context;}
            private volatile List<MetricsSnapshot.Metric> rows=Collections.emptyList();
            @Override public void onCreate(){onDataSetChanged();}
            @Override public void onDataSetChanged(){rows=PulseWidgetProvider.selected(context,PulseWidgetProvider.sample(context));}
            @Override public void onDestroy(){rows=Collections.emptyList();}
            @Override public int getCount(){return rows.size();}
            @Override public RemoteViews getViewAt(int position){List<MetricsSnapshot.Metric> current=rows;return position<0||position>=current.size()?null:PulseWidgetProvider.row(context,current.get(position));}
            @Override public RemoteViews getLoadingView(){return null;}
            @Override public int getViewTypeCount(){return 1;}
            @Override public long getItemId(int position){List<MetricsSnapshot.Metric> current=rows;return position<0||position>=current.size()?0:current.get(position).id.hashCode();}
            @Override public boolean hasStableIds(){return true;}
    }
}
