package net.raphaelgf11.ilo3manager.ui.host

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import net.raphaelgf11.ilo3manager.data.HostRepository
import net.raphaelgf11.ilo3manager.data.SshHost
import net.raphaelgf11.ilo3manager.ssh.HostSessionStore

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

    fun disconnectHost(id: String) {
        HostSessionStore.disconnectAll(id)
    }
}
