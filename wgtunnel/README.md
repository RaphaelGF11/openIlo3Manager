# wgtunnel

A WireGuard peer that runs entirely inside the app's own process.

Android's `VpnService` would impose exactly what this app must avoid: a system TUN interface, a
consent dialog, and capture of every other app's traffic. Running the tunnel on a userspace TCP/IP
stack (gVisor, through wireguard-go's netstack binding) needs none of those — the only socket the
operating system sees is an ordinary UDP one to the peer.

Each service is published as a **local forwarded port**, like `ssh -L`, rather than as a connection
object: gomobile can only export basic types, and it lets the Kotlin side treat WireGuard and an SSH
jump host identically, as plain address translation (see `HostTunnelManager`).

Unlike an SSH jump host, this carries UDP, which is what makes IPMI usable through the tunnel.

## Rebuilding

The compiled `app/libs/wgtunnel.aar` is checked in so that building the app needs neither Go nor the
Android NDK. Rebuild it only when this package changes:

```sh
export ANDROID_HOME=~/Android/Sdk
export ANDROID_NDK_HOME=$ANDROID_HOME/ndk/26.3.11579264
go install golang.org/x/mobile/cmd/gomobile@latest
gomobile init
cd wgtunnel
gomobile bind -target=android/arm64,android/amd64 -androidapi 21 -o ../app/libs/wgtunnel.aar .
```

Roughly 9 MB for both architectures. The app ships one APK per architecture, so a device only
downloads the one it needs.
