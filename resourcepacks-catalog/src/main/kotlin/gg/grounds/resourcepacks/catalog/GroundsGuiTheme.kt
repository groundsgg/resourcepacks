package gg.grounds.resourcepacks.catalog

import gg.grounds.gui.theme.PackFormat
import gg.grounds.gui.theme.Theme
import gg.grounds.gui.theme.theme

object GroundsGuiTheme {
    val theme: Theme =
        theme(GroundsGuiIds.NAMESPACE, PackFormat(88)) {
            description = "Grounds platform UI"
            panel(GroundsGuiIds.PANEL_MENU, "panels/menu.png", 176, 166, offsetY = -6)
            icon(GroundsGuiIds.ICON_CLOSE, "icons/close.png")
            icon(GroundsGuiIds.ICON_BACK, "icons/back.png")
            icon(GroundsGuiIds.ICON_NEXT, "icons/next.png")
            icon(GroundsGuiIds.ICON_BLANK, "icons/blank.png")
            tooltip(GroundsGuiIds.TOOLTIP_DEFAULT, "tooltips/default_bg.png", "tooltips/default_frame.png")
            frame(GroundsGuiIds.FRAME_HOVER, "frames/hover.png")
        }
}
