package net.raphaelgf11.ilo3manager.ui.host

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import net.raphaelgf11.ilo3manager.data.HostRepository
import net.raphaelgf11.ilo3manager.data.SshHost
import android.content.Context
import net.raphaelgf11.ilo3manager.ssh.HostSessionStore
import net.raphaelgf11.ilo3manager.webgateway.WebGatewayManager

class HostListViewModel(private val repository: HostRepository) : ViewModel() {

    private val _hosts = MutableStateFlow<List<SshHost>>(emptyList())
    val hosts: StateFlow<List<SshHost>> = _hosts

    init {
        refresh()
    }

    fun refresh() {
        _hosts.value = repository.getHosts()
    }

    fun deleteHost(id: String) {
        repository.deleteHost(id)
        HostSessionStore.dropSessionsFor(id)
        refresh()
    }

    fun moveHost(fromIndex: Int, toIndex: Int) {
        val reordered = _hosts.value.toMutableList()
        if (toIndex !in reordered.indices) return
        val item = reordered.removeAt(fromIndex)
        reordered.add(toIndex, item)
        _hosts.value = reordered
        repository.reorderHosts(reordered)
    }

    /**
     * Ends everything the app holds open for a host, including the web gateway: that runs in a
     * foreground service, so leaving it behind would keep a notification and an open listening
     * port after the user asked to disconnect.
     */
    fun disconnectHost(context: Context, id: String) {
        HostSessionStore.disconnectAll(id)
        WebGatewayManager.stop(context, id)
    }
}
