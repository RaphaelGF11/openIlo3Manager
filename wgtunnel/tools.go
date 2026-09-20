//go:build tools

// Keeps gomobile's binding generator in the module graph. It is never imported by the tunnel
// itself, so without this `go mod tidy` drops it and `gomobile bind` refuses to run.
package wgtunnel

import _ "golang.org/x/mobile/bind"
