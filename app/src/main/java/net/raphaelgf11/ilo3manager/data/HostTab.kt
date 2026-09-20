package net.raphaelgf11.ilo3manager.data

/** The tabs of a host screen, in display order. */
enum class HostTab(val title: String) {
    POWER("Alim"),
    VSP("VSP"),
    CONSOLE("SSH"),
    HARDWARE("HW"),
    WEB("Web"),
}
