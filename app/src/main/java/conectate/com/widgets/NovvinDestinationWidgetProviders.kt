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

abstract class NovvinDestinationWidgetProvider : AppWidgetProvider() {
    abstract val destination: String
    abstract val titleRes: Int
    abstract val subtitleRes: Int

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { widgetId ->
            val views = RemoteViews(context.packageName, R.layout.widget_novvin_destination)
            views.setTextViewText(R.id.widget_destination_title, context.getString(titleRes))
            views.setTextViewText(R.id.widget_destination_subtitle, context.getString(subtitleRes))
            views.setOnClickPendingIntent(R.id.widget_destination_root, openDestination(context, widgetId))
            manager.updateAppWidget(widgetId, views)
        }
    }

    private fun openDestination(context: Context, requestCode: Int): PendingIntent {
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

class NovvinChatWidgetProvider : NovvinDestinationWidgetProvider() {
    override val destination = "messages"
    override val titleRes = R.string.widget_chat_title
    override val subtitleRes = R.string.widget_chat_subtitle
}

class NovvinVideosWidgetProvider : NovvinDestinationWidgetProvider() {
    override val destination = "videos"
    override val titleRes = R.string.widget_videos_title
    override val subtitleRes = R.string.widget_videos_subtitle
}

class NovvinMomentsWidgetProvider : NovvinDestinationWidgetProvider() {
    override val destination = "create-moment"
    override val titleRes = R.string.widget_moments_title
    override val subtitleRes = R.string.widget_moments_subtitle
}
