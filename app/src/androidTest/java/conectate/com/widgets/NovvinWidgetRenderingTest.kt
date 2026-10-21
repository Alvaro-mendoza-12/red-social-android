package conectate.com.widgets

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.widget.FrameLayout
import android.widget.RemoteViews
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import conectate.com.R
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NovvinWidgetRenderingTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test fun allQuickActionDesignsRenderAndKeepTheirThreeActions() {
        instrumentation.runOnMainSync {
            for (minimal in listOf(false, true)) for (layout in QuickWidgetLayout.entries) {
                val height = if (layout == QuickWidgetLayout.FULL) 132 else 64
                val width = if (layout == QuickWidgetLayout.FULL) 300 else 212
                val view = render(context, quickWidgetViews(context, layout, minimal), width, height, "quick-$layout-$minimal")
                for (id in listOf(R.id.widget_messages, R.id.widget_videos, R.id.widget_moments)) {
                    val action = view.findViewById<View>(id)
                    assertTrue(action.hasOnClickListeners())
                    assertTrue(action.height >= dp(context,48))
                    assertTrue(action.width >= dp(context,48))
                    assertTrue(action.bottom <= view.height)
                }
            }
        }
    }
    @Test fun destinationDesignsRenderAndStayAccessibleAtLargeFont() {
        val large = context.createConfigurationContext(Configuration(context.resources.configuration).apply { fontScale=2f })
        instrumentation.runOnMainSync {
            for (layout in DestinationWidgetLayout.entries) {
                val width = if (layout == DestinationWidgetLayout.ICON) 64 else 170
                val height = if (layout == DestinationWidgetLayout.CARD) 132 else 64
                val view = render(large, destinationWidgetViews(large,layout,"messages",R.string.widget_chat_title,
                    R.string.widget_chat_subtitle,R.drawable.widget_icon_messages,301),width,height,"destination-$layout-large")
                assertTrue(view.hasOnClickListeners())
                assertTrue(view.contentDescription.toString().contains("Mensajes"))
                if (layout != DestinationWidgetLayout.ICON) assertEquals(View.GONE,view.findViewById<View>(R.id.widget_destination_subtitle).visibility)
            }
        }
    }
    @Test fun pendingActionsAreImmutable() {
        assertTrue(widgetDestinationIntent(context,"messages",302).isImmutable)
    }
    private fun render(ctx:Context,views:RemoteViews,width:Int,height:Int,name:String):View {
        val view=views.apply(ctx,FrameLayout(ctx))
        val w=dp(ctx,width);val h=dp(ctx,height)
        view.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(h,View.MeasureSpec.EXACTLY))
        view.layout(0,0,w,h)
        val bitmap=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);view.draw(Canvas(bitmap))
        File(context.getExternalFilesDir(null),"widget-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
        bitmap.recycle()
        return view
    }
    private fun dp(context:Context,value:Int)=(value*context.resources.displayMetrics.density).toInt()
}
