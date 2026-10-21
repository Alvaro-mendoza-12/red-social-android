package conectate.com.widgets

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.View
import android.widget.RemoteViews
import conectate.com.MainActivity
import conectate.com.R

internal enum class QuickWidgetLayout { FULL, COMPACT }
internal enum class DestinationWidgetLayout { ICON, ROW, CARD }

internal fun quickWidgetLayout(width: Int, height: Int, fontScale: Float): QuickWidgetLayout =
    if (width >= 300 && height >= 132 && fontScale <= 1.3f) QuickWidgetLayout.FULL else QuickWidgetLayout.COMPACT

internal fun destinationWidgetLayout(width: Int, height: Int): DestinationWidgetLayout = when {
    width < 170 -> DestinationWidgetLayout.ICON
    height >= 132 -> DestinationWidgetLayout.CARD
    else -> DestinationWidgetLayout.ROW
}

internal fun widgetDestinationIntent(context: Context, destination: String, requestCode: Int): PendingIntent {
    require(destination in setOf("social", "messages", "videos", "create-moment"))
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("conectate://$destination"), context, MainActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    return PendingIntent.getActivity(context, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
}

internal fun quickWidgetViews(context: Context, layout: QuickWidgetLayout, minimal: Boolean): RemoteViews {
    val views = RemoteViews(context.packageName, if (layout == QuickWidgetLayout.FULL)
        R.layout.widget_novvin_quick_actions else R.layout.widget_novvin_quick_actions_compact)
    if (minimal) views.setInt(R.id.widget_root, "setBackgroundResource", R.drawable.widget_novvin_minimal_background)
    views.setOnClickPendingIntent(R.id.widget_root, widgetDestinationIntent(context, "social", 10))
    views.setOnClickPendingIntent(R.id.widget_videos, widgetDestinationIntent(context, "videos", 11))
    views.setOnClickPendingIntent(R.id.widget_moments, widgetDestinationIntent(context, "create-moment", 12))
    views.setOnClickPendingIntent(R.id.widget_messages, widgetDestinationIntent(context, "messages", 13))
    return views
}

internal fun destinationWidgetViews(context: Context, layout: DestinationWidgetLayout, destination: String,
    titleRes: Int, subtitleRes: Int, iconRes: Int, widgetId: Int): RemoteViews {
    val resource = when (layout) {
        DestinationWidgetLayout.ICON -> R.layout.widget_novvin_destination_icon
        DestinationWidgetLayout.ROW -> R.layout.widget_novvin_destination
        DestinationWidgetLayout.CARD -> R.layout.widget_novvin_destination_card
    }
    val views = RemoteViews(context.packageName, resource)
    if (layout != DestinationWidgetLayout.ICON) {
        views.setTextViewText(R.id.widget_destination_title, context.getString(titleRes))
        views.setTextViewText(R.id.widget_destination_subtitle, context.getString(subtitleRes))
        views.setViewVisibility(R.id.widget_destination_subtitle,
            if (context.resources.configuration.fontScale > 1.3f) View.GONE else View.VISIBLE)
    }
    views.setImageViewResource(R.id.widget_destination_icon, iconRes)
    views.setContentDescription(R.id.widget_destination_root, context.getString(titleRes) + " · Novvin")
    views.setOnClickPendingIntent(R.id.widget_destination_root, widgetDestinationIntent(context, destination, widgetId))
    return views
}
