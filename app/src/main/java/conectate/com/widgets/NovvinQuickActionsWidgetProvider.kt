package conectate.com.widgets

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.util.SizeF
import android.widget.RemoteViews

open class NovvinQuickActionsWidgetProvider : AppWidgetProvider() {
    protected open val minimalStyle = false
    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        appWidgetIds.forEach { appWidgetId ->
            val options = appWidgetManager.getAppWidgetOptions(appWidgetId)
            val scale = context.resources.configuration.fontScale
            val views = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) RemoteViews(mapOf(
                SizeF(212f, 64f) to quickWidgetViews(context, QuickWidgetLayout.COMPACT, minimalStyle),
                SizeF(300f, 132f) to quickWidgetViews(context, quickWidgetLayout(300, 132, scale), minimalStyle),
            )) else quickWidgetViews(context, quickWidgetLayout(
                options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 300),
                options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 132), scale), minimalStyle)
            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager,
        appWidgetId: Int, newOptions: Bundle) {
        onUpdate(context, manager, intArrayOf(appWidgetId))
    }

}

class NovvinMinimalActionsWidgetProvider : NovvinQuickActionsWidgetProvider() {
    override val minimalStyle = true
}
