// Package wgtunnel embeds a WireGuard peer that lives entirely inside the process.
//
// It exists because Android's VpnService would impose exactly what this app must avoid: a system
// TUN interface, a consent dialog, and capture of every other app's traffic. Running the tunnel on
// a userspace TCP/IP stack (gVisor, via wireguard-go's netstack binding) needs none of those — the
// only socket the OS sees is an ordinary UDP one to the peer.
//
// The API is shaped for gomobile, which can only export basic types: instead of handing Kotlin a
// connection object, each service is exposed as a local forwarded port, exactly like an SSH -L
// forward. That also lets the Kotlin side treat WireGuard and the SSH jump host identically, as
// plain address translation.
package wgtunnel

import (
	"bytes"
	"errors"
	"fmt"
	"io"
	"net"
	"net/netip"
	"strings"
	"sync"
	"time"

	"golang.org/x/net/icmp"
	"golang.org/x/net/ipv4"
	"golang.zx2c4.com/wireguard/conn"
	"golang.zx2c4.com/wireguard/device"
	"golang.zx2c4.com/wireguard/tun/netstack"
)

// protocolICMP is the IANA number for ICMPv4, which icmp.ParseMessage needs to decode a reply.
const protocolICMP = 1

// Tunnel is a running WireGuard peer plus the local forwarders opened through it.
type Tunnel struct {
	tnet      *netstack.Net
	dev       *device.Device
	mu        sync.Mutex
	listeners []io.Closer
	closed    bool
}

// Start brings up a tunnel.
//
// ipcConfig is wireguard-go's UAPI configuration (keys in hex, not base64 — the caller converts),
// localAddresses is the comma-separated list of addresses this peer holds, and dnsServers may be
// empty since this app always dials literal addresses.
func Start(ipcConfig string, localAddresses string, dnsServers string, mtu int) (*Tunnel, error) {
	addrs, err := parseAddrs(localAddresses)
	if err != nil {
		return nil, err
	}
	if len(addrs) == 0 {
		return nil, errors.New("aucune adresse locale dans la configuration")
	}
	dns, err := parseAddrs(dnsServers)
	if err != nil {
		return nil, err
	}
	if mtu <= 0 {
		mtu = 1420
	}

	tun, tnet, err := netstack.CreateNetTUN(addrs, dns, mtu)
	if err != nil {
		return nil, fmt.Errorf("création de la pile réseau : %w", err)
	}

	dev := device.NewDevice(tun, conn.NewDefaultBind(), device.NewLogger(device.LogLevelError, "wg "))
	if err := dev.IpcSet(ipcConfig); err != nil {
		dev.Close()
		return nil, fmt.Errorf("configuration du tunnel : %w", err)
	}
	if err := dev.Up(); err != nil {
		dev.Close()
		return nil, fmt.Errorf("démarrage du tunnel : %w", err)
	}

	return &Tunnel{tnet: tnet, dev: dev}, nil
}

// ForwardTCP listens on a free local TCP port and relays every connection to host:port through the
// tunnel. Returns the local port to dial.
func (t *Tunnel) ForwardTCP(host string, port int) (int, error) {
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		return 0, err
	}
	if err := t.track(listener); err != nil {
		listener.Close()
		return 0, err
	}

	target := net.JoinHostPort(host, fmt.Sprint(port))
	go func() {
		for {
			local, err := listener.Accept()
			if err != nil {
				return // listener closed
			}
			go func() {
				defer local.Close()
				remote, err := t.tnet.Dial("tcp", target)
				if err != nil {
					return
				}
				defer remote.Close()
				done := make(chan struct{}, 2)
				go func() { io.Copy(remote, local); done <- struct{}{} }()
				go func() { io.Copy(local, remote); done <- struct{}{} }()
				<-done
			}()
		}
	}()
	return listener.Addr().(*net.TCPAddr).Port, nil
}

// ForwardUDP does the same for UDP, which is what makes IPMI usable through the tunnel — unlike an
// SSH jump host, which can only carry TCP. Each local source address gets its own tunnelled socket
// so replies find their way back to the right caller.
func (t *Tunnel) ForwardUDP(host string, port int) (int, error) {
	local, err := net.ListenUDP("udp", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1), Port: 0})
	if err != nil {
		return 0, err
	}
	if err := t.track(local); err != nil {
		local.Close()
		return 0, err
	}

	target := net.JoinHostPort(host, fmt.Sprint(port))
	go func() {
		type peer struct{ conn net.Conn }
		peers := map[string]*peer{}
		var mu sync.Mutex
		buf := make([]byte, 65535)
		for {
			n, from, err := local.ReadFromUDP(buf)
			if err != nil {
				return // socket closed
			}
			key := from.String()
			mu.Lock()
			p := peers[key]
			if p == nil {
				remote, err := t.tnet.Dial("udp", target)
				if err != nil {
					mu.Unlock()
					continue
				}
				p = &peer{conn: remote}
				peers[key] = p
				replyTo := *from
				go func() {
					reply := make([]byte, 65535)
					for {
						n, err := remote.Read(reply)
						if err != nil {
							mu.Lock()
							delete(peers, key)
							mu.Unlock()
							remote.Close()
							return
						}
						local.WriteToUDP(reply[:n], &replyTo)
					}
				}()
			}
			mu.Unlock()
			p.conn.Write(buf[:n])
		}
	}()
	return local.LocalAddr().(*net.UDPAddr).Port, nil
}

// Ping sends ICMP echo requests through the tunnel and reports whether any reply came back.
//
// This cannot be done from Kotlin. The tunnel has no system network interface, so the platform's
// own ping command would leave by the Wi-Fi and never see it; the echo has to originate inside this
// userspace stack. Returns true on the first reply rather than timing every attempt — the caller
// wants to know whether the address answers, not how fast.
func (t *Tunnel) Ping(address string, count int, timeoutMillis int) (bool, error) {
	addr, err := netip.ParseAddr(trimSpace(address))
	if err != nil {
		return false, fmt.Errorf("adresse invalide %q: %w", address, err)
	}
	if addr.Is6() {
		return false, errors.New("ICMPv6 non pris en charge")
	}

	socket, err := t.tnet.DialPingAddr(netip.Addr{}, addr)
	if err != nil {
		return false, err
	}
	defer socket.Close()

	// The reply is matched on sequence number and payload, never on the identifier: gVisor rewrites
	// the ICMP id with its own endpoint port on the way out, so the id that comes back is not the
	// one that was sent.
	payload := []byte("ilo3manager-echo")
	id := int(time.Now().UnixNano() & 0xFFFF)
	buf := make([]byte, 1500)
	for seq := 0; seq < count; seq++ {
		request, err := (&icmp.Message{
			Type: ipv4.ICMPTypeEcho,
			Code: 0,
			Body: &icmp.Echo{ID: id, Seq: seq, Data: payload},
		}).Marshal(nil)
		if err != nil {
			return false, err
		}
		if _, err := socket.Write(request); err != nil {
			return false, err
		}

		deadline := time.Now().Add(time.Duration(timeoutMillis) * time.Millisecond)
		if err := socket.SetReadDeadline(deadline); err != nil {
			return false, err
		}
		for time.Now().Before(deadline) {
			n, err := socket.Read(buf)
			if err != nil {
				break // Timed out on this attempt; try the next one.
			}
			reply, err := icmp.ParseMessage(protocolICMP, buf[:n])
			if err != nil {
				continue
			}
			if echo, ok := reply.Body.(*icmp.Echo); ok &&
				echo.Seq == seq && bytes.Equal(echo.Data, payload) {
				return true, nil
			}
		}
	}
	return false, nil
}

// Status returns the device's UAPI state. The field that matters for diagnosis is
// last_handshake_time_sec: zero means the peer never answered, which distinguishes a tunnel that
// failed to establish from one that is up but cannot reach the target. wireguard-go's own logs go
// to stderr, which gomobile does not forward to logcat, so this is the only view from Kotlin.
func (t *Tunnel) Status() (string, error) {
	var sb strings.Builder
	if err := t.dev.IpcGetOperation(&sb); err != nil {
		return "", err
	}
	return sb.String(), nil
}

// Close tears down every forwarder and the tunnel itself.
func (t *Tunnel) Close() error {
	t.mu.Lock()
	if t.closed {
		t.mu.Unlock()
		return nil
	}
	t.closed = true
	listeners := t.listeners
	t.listeners = nil
	t.mu.Unlock()

	for _, l := range listeners {
		l.Close()
	}
	t.dev.Close()
	return nil
}

func (t *Tunnel) track(c io.Closer) error {
	t.mu.Lock()
	defer t.mu.Unlock()
	if t.closed {
		return errors.New("tunnel fermé")
	}
	t.listeners = append(t.listeners, c)
	return nil
}

func parseAddrs(list string) ([]netip.Addr, error) {
	var out []netip.Addr
	for _, raw := range splitAndTrim(list) {
		// Accept both "10.0.0.2" and "10.0.0.2/32": the prefix length is the peer's business.
		if prefix, err := netip.ParsePrefix(raw); err == nil {
			out = append(out, prefix.Addr())
			continue
		}
		addr, err := netip.ParseAddr(raw)
		if err != nil {
			return nil, fmt.Errorf("adresse invalide %q", raw)
		}
		out = append(out, addr)
	}
	return out, nil
}

func splitAndTrim(list string) []string {
	var out []string
	start := 0
	for i := 0; i <= len(list); i++ {
		if i == len(list) || list[i] == ',' {
			piece := trimSpace(list[start:i])
			if piece != "" {
				out = append(out, piece)
			}
			start = i + 1
		}
	}
	return out
}

func trimSpace(s string) string {
	for len(s) > 0 && (s[0] == ' ' || s[0] == '\t') {
		s = s[1:]
	}
	for len(s) > 0 && (s[len(s)-1] == ' ' || s[len(s)-1] == '\t') {
		s = s[:len(s)-1]
	}
	return s
}
