package conectate.com.widgets

import org.junit.Assert.assertEquals
import org.junit.Test

class NovvinWidgetLayoutPolicyTest {
    @Test fun `quick actions fit width and height instead of testing height alone`() {
        assertEquals(QuickWidgetLayout.FULL, quickWidgetLayout(300, 132, 1f))
        assertEquals(QuickWidgetLayout.COMPACT, quickWidgetLayout(212, 132, 1f))
        assertEquals(QuickWidgetLayout.COMPACT, quickWidgetLayout(400, 64, 1f))
    }
    @Test fun `large system fonts use labelled accessible icons without clipping text`() {
        assertEquals(QuickWidgetLayout.COMPACT, quickWidgetLayout(300, 132, 2f))
    }
    @Test fun `individual destinations support icon row and card sizes`() {
        assertEquals(DestinationWidgetLayout.ICON, destinationWidgetLayout(64, 64))
        assertEquals(DestinationWidgetLayout.ROW, destinationWidgetLayout(170, 64))
        assertEquals(DestinationWidgetLayout.CARD, destinationWidgetLayout(170, 132))
    }
}
