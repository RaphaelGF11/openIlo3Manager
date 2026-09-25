package wgtunnel

import (
	"net"
	"net/netip"
	"strings"
	"testing"
	"time"

	"golang.zx2c4.com/wireguard/tun/netstack"
)

// The inbound listeners rest on two claims that are easy to assert and expensive to discover wrong
// on a phone, so they are pinned here. No WireGuard peer is involved: netstack builds a usable
// stack on its own, which is exactly the layer under test.
func newTestNet(t *testing.T) (*netstack.Net, netip.Addr) {
	t.Helper()
	addr := netip.MustParseAddr("10.9.0.2")
	_, tnet, err := netstack.CreateNetTUN([]netip.Addr{addr}, nil, 1420)
	if err != nil {
		t.Fatalf("création de la pile : %v", err)
	}
	return tnet, addr
}

// Port 162 is reserved to root for an ordinary socket. Inside this stack the kernel is never asked,
// so it must bind freely — the whole premise of receiving SNMP traps on an unrooted phone.
func TestPrivilegedPortNeedsNoPrivilege(t *testing.T) {
	tnet, addr := newTestNet(t)
	conn, err := tnet.ListenUDPAddrPort(netip.AddrPortFrom(addr, 162))
	if err != nil {
		t.Fatalf("écoute sur le port 162 refusée : %v", err)
	}
	defer conn.Close()
}

// netstack reads the address family from the address itself, so binding the unspecified address
// yields an IPv6 listener that silently never sees IPv4 traffic. listenAddr must therefore always
// return one of the tunnel's own IPv4 addresses.
func TestListenAddrIsAlwaysIPv4(t *testing.T) {
	tunnel := &Tunnel{addrs: []netip.Addr{
		netip.MustParseAddr("fd00::2"),
		netip.MustParseAddr("10.9.0.2"),
	}}

	bind, err := tunnel.listenAddr(162)
	if err != nil {
		t.Fatalf("listenAddr : %v", err)
	}
	if !bind.Addr().Is4() {
		t.Fatalf("adresse de liaison %s : attendue en IPv4", bind)
	}
	if bind.Port() != 162 {
		t.Fatalf("port de liaison %d, attendu 162", bind.Port())
	}
}

func TestListenAddrRejectsIPv6OnlyTunnel(t *testing.T) {
	tunnel := &Tunnel{addrs: []netip.Addr{netip.MustParseAddr("fd00::2")}}
	if _, err := tunnel.listenAddr(162); err == nil {
		t.Fatal("un tunnel sans adresse IPv4 devrait être refusé, pas lié en silence")
	}
}

// Carries a payload the whole way: in through the tunnel's listener, out to an ordinary local
// server, and back. Dialling the stack's own address loops inside it, which stands in for the peer
// without needing one.
func TestReverseTCPRelaysBothWays(t *testing.T) {
	tnet, addr := newTestNet(t)
	tunnel := &Tunnel{tnet: tnet, addrs: []netip.Addr{addr}}
	defer tunnel.closeListeners()

	// The local server Kotlin would run: echoes back in upper case so the reply is distinguishable
	// from the request having merely been reflected somewhere along the way.
	local, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("serveur local : %v", err)
	}
	defer local.Close()
	go func() {
		conn, err := local.Accept()
		if err != nil {
			return
		}
		defer conn.Close()
		buf := make([]byte, 64)
		n, err := conn.Read(buf)
		if err != nil {
			return
		}
		conn.Write([]byte(strings.ToUpper(string(buf[:n]))))
	}()

	if err := tunnel.ReverseTCP(8080, local.Addr().(*net.TCPAddr).Port); err != nil {
		t.Fatalf("ReverseTCP : %v", err)
	}

	client, err := tnet.DialTCPAddrPort(netip.AddrPortFrom(addr, 8080))
	if err != nil {
		t.Fatalf("connexion depuis le tunnel : %v", err)
	}
	defer client.Close()
	client.SetDeadline(time.Now().Add(5 * time.Second))

	if _, err := client.Write([]byte("bonjour")); err != nil {
		t.Fatalf("envoi : %v", err)
	}
	buf := make([]byte, 64)
	n, err := client.Read(buf)
	if err != nil {
		t.Fatalf("réception : %v", err)
	}
	if got := string(buf[:n]); got != "BONJOUR" {
		t.Fatalf("reçu %q, attendu %q", got, "BONJOUR")
	}
}

// The SNMP trap path: a datagram arriving inside the tunnel must reach the local socket intact.
func TestReverseUDPDeliversDatagram(t *testing.T) {
	tnet, addr := newTestNet(t)
	tunnel := &Tunnel{tnet: tnet, addrs: []netip.Addr{addr}}
	defer tunnel.closeListeners()

	local, err := net.ListenUDP("udp", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if err != nil {
		t.Fatalf("socket locale : %v", err)
	}
	defer local.Close()

	if err := tunnel.ReverseUDP(162, local.LocalAddr().(*net.UDPAddr).Port); err != nil {
		t.Fatalf("ReverseUDP : %v", err)
	}

	sender, err := tnet.DialUDPAddrPort(netip.AddrPort{}, netip.AddrPortFrom(addr, 162))
	if err != nil {
		t.Fatalf("envoi depuis le tunnel : %v", err)
	}
	defer sender.Close()
	if _, err := sender.Write([]byte("trap")); err != nil {
		t.Fatalf("envoi : %v", err)
	}

	local.SetReadDeadline(time.Now().Add(5 * time.Second))
	buf := make([]byte, 64)
	n, _, err := local.ReadFrom(buf)
	if err != nil {
		t.Fatalf("le datagramme n'est pas arrivé : %v", err)
	}
	if got := string(buf[:n]); got != "trap" {
		t.Fatalf("reçu %q, attendu %q", got, "trap")
	}
}
