package conectate.com.widgets

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.util.SizeF
import android.widget.RemoteViews
import conectate.com.R

abstract class NovvinDestinationWidgetProvider : AppWidgetProvider() {
    abstract val destination: String
    abstract val titleRes: Int
    abstract val subtitleRes: Int
    abstract val iconRes: Int

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { widgetId ->
            fun render(layout: DestinationWidgetLayout) = destinationWidgetViews(context, layout,
                destination, titleRes, subtitleRes, iconRes, widgetId)
            val options = manager.getAppWidgetOptions(widgetId)
            val views = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) RemoteViews(mapOf(
                SizeF(64f, 64f) to render(DestinationWidgetLayout.ICON),
                SizeF(170f, 64f) to render(DestinationWidgetLayout.ROW),
                SizeF(170f, 132f) to render(DestinationWidgetLayout.CARD),
            )) else render(destinationWidgetLayout(
                options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 170),
                options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 64)))
            manager.updateAppWidget(widgetId, views)
        }
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager,
        appWidgetId: Int, newOptions: Bundle) {
        onUpdate(context, manager, intArrayOf(appWidgetId))
    }
}

class NovvinChatWidgetProvider : NovvinDestinationWidgetProvider() {
    override val iconRes = R.drawable.widget_icon_messages
    override val destination = "messages"
    override val titleRes = R.string.widget_chat_title
    override val subtitleRes = R.string.widget_chat_subtitle
}

class NovvinVideosWidgetProvider : NovvinDestinationWidgetProvider() {
    override val iconRes = R.drawable.widget_icon_videos
    override val destination = "videos"
    override val titleRes = R.string.widget_videos_title
    override val subtitleRes = R.string.widget_videos_subtitle
}

class NovvinMomentsWidgetProvider : NovvinDestinationWidgetProvider() {
    override val iconRes = R.drawable.widget_icon_moment
    override val destination = "create-moment"
    override val titleRes = R.string.widget_moments_title
    override val subtitleRes = R.string.widget_moments_subtitle
}
