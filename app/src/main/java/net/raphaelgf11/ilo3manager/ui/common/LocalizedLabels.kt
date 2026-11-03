package net.raphaelgf11.ilo3manager.ui.common

import androidx.annotation.StringRes
import net.raphaelgf11.ilo3manager.R
import net.raphaelgf11.ilo3manager.data.HostTab
import net.raphaelgf11.ilo3manager.data.InstantAlertMode
import net.raphaelgf11.ilo3manager.data.NetworkType
import net.raphaelgf11.ilo3manager.ilo.HostIndicator
import net.raphaelgf11.ilo3manager.ipmi.IpmiPrivilege

@StringRes
fun HostTab.titleResource(): Int = when (this) {
    HostTab.POWER -> R.string.host_tab_power
    HostTab.VSP -> R.string.host_tab_vsp
    HostTab.CONSOLE -> R.string.host_tab_console
    HostTab.HARDWARE -> R.string.host_tab_hardware
    HostTab.WEB -> R.string.host_tab_web
}

@StringRes
fun NetworkType.labelResource(): Int = when (this) {
    NetworkType.WIREGUARD -> R.string.network_type_wireguard
    NetworkType.SSH_TUNNEL -> R.string.network_type_ssh_tunnel
    NetworkType.SECONDARY_IP -> R.string.network_type_secondary_ip
}

@StringRes
fun NetworkType.detailResource(): Int = when (this) {
    NetworkType.WIREGUARD -> R.string.network_type_wireguard_detail
    NetworkType.SSH_TUNNEL -> R.string.network_type_ssh_tunnel_detail
    NetworkType.SECONDARY_IP -> R.string.network_type_secondary_ip_detail
}

@StringRes
fun InstantAlertMode.labelResource(): Int = when (this) {
    InstantAlertMode.DISABLED -> R.string.alert_mode_disabled
    InstantAlertMode.SNMP_DIRECT -> R.string.alert_mode_snmp_direct
    InstantAlertMode.SNMP_GATEWAY -> R.string.alert_mode_snmp_gateway
    InstantAlertMode.FIREBASE -> R.string.alert_mode_firebase
}

@StringRes
fun InstantAlertMode.detailResource(): Int = when (this) {
    InstantAlertMode.DISABLED -> R.string.alert_mode_disabled_detail
    InstantAlertMode.SNMP_DIRECT -> R.string.alert_mode_snmp_direct_detail
    InstantAlertMode.SNMP_GATEWAY -> R.string.alert_mode_snmp_gateway_detail
    InstantAlertMode.FIREBASE -> R.string.alert_mode_firebase_detail
}

@StringRes
fun IpmiPrivilege.labelResource(): Int = when (this) {
    IpmiPrivilege.USER -> R.string.ipmi_privilege_user
    IpmiPrivilege.OPERATOR -> R.string.ipmi_privilege_operator
    IpmiPrivilege.ADMINISTRATOR -> R.string.ipmi_privilege_admin
}

@StringRes
fun HostIndicator.labelResource(): Int = when (this) {
    HostIndicator.UNKNOWN -> R.string.host_status_unknown
    HostIndicator.CONNECTING -> R.string.host_status_connecting
    HostIndicator.POWERED_OFF -> R.string.host_status_off
    HostIndicator.FAULT -> R.string.host_status_fault
    HostIndicator.CRITICAL -> R.string.host_status_critical
    HostIndicator.POWERED_ON -> R.string.host_status_on
    HostIndicator.UID -> R.string.host_status_uid
}