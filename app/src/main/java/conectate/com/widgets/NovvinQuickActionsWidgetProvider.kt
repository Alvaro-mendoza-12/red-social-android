package conectate.com.widgets

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.RemoteViews
import conectate.com.MainActivity
import conectate.com.R

class NovvinQuickActionsWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        appWidgetIds.forEach { appWidgetId ->
            val views = RemoteViews(context.packageName, R.layout.widget_novvin_quick_actions)
            views.setOnClickPendingIntent(R.id.widget_root, open(context, "social", 10))
            views.setOnClickPendingIntent(R.id.widget_videos, open(context, "videos", 11))
            views.setOnClickPendingIntent(R.id.widget_moments, open(context, "create-moment", 12))
            views.setOnClickPendingIntent(R.id.widget_messages, open(context, "messages", 13))
            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }

    private fun open(context: Context, destination: String, requestCode: Int): PendingIntent {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("conectate://$destination"), context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
