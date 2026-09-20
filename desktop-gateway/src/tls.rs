//! Connecting to an iLO 3, which no current TLS library will talk to unaided.
//!
//! An iLO 3 offers TLS 1.0 and 1.1 with 3DES or RC4, and presents a self-signed certificate. Every
//! default in a modern OpenSSL build refuses all three, which is precisely why browsers stopped
//! opening these pages and why this proxy exists.

use openssl::ssl::{SslConnector, SslMethod, SslOptions, SslStream, SslVerifyMode, SslVersion};
use std::io;
use std::net::TcpStream;
use std::time::Duration;

const CONNECT_TIMEOUT: Duration = Duration::from_secs(10);
const IO_TIMEOUT: Duration = Duration::from_secs(30);

pub fn connect(host: &str, port: u16) -> io::Result<SslStream<TcpStream>> {
    let address = format!("{host}:{port}");
    let socket = TcpStream::connect(&address)?;
    socket.set_read_timeout(Some(IO_TIMEOUT))?;
    socket.set_write_timeout(Some(IO_TIMEOUT))?;
    // Nagle would hold back the small request that follows until the socket had more to say.
    socket.set_nodelay(true)?;

    let mut builder = SslConnector::builder(SslMethod::tls_client())
        .map_err(|e| io::Error::other(format!("initialisation TLS : {e}")))?;

    // The certificate is self-signed and its subject rarely matches the address dialled, so the
    // check could only ever fail. The connection is to a device on the user's own network, reached
    // by an address they typed.
    builder.set_verify(SslVerifyMode::NONE);

    builder
        .set_min_proto_version(Some(SslVersion::TLS1))
        .map_err(|e| io::Error::other(format!("TLS 1.0 indisponible : {e}")))?;
    // Pinned to TLS 1.0 rather than merely allowed. Offering later versions makes the ClientHello
    // carry the extensions that go with them, and an iLO 3 answers a hello it finds too elaborate
    // with an internal error rather than negotiating down.
    builder
        .set_max_proto_version(Some(SslVersion::TLS1))
        .map_err(|e| io::Error::other(format!("plafond TLS 1.0 : {e}")))?;

    // OpenSSL 3 rejects everything an iLO 3 offers at its default security level, and refuses to
    // renegotiate with servers that predate RFC 5746.
    builder.set_security_level(0);
    builder
        .set_cipher_list("ALL:COMPLEMENTOFALL:@SECLEVEL=0")
        .map_err(|e| io::Error::other(format!("liste de chiffrements : {e}")))?;
    // Unsafe legacy renegotiation: iLO 3 predates RFC 5746, and OpenSSL 3 refuses such peers
    // outright unless told otherwise.
    builder.set_options(SslOptions::ALLOW_UNSAFE_LEGACY_RENEGOTIATION);

    let connector = builder.build();
    let configured = connector
        .configure()
        .map_err(|e| io::Error::other(format!("configuration TLS : {e}")))?
        .use_server_name_indication(false)
        .verify_hostname(false);

    configured
        .connect(host, socket)
        .map_err(|e| io::Error::other(format!("connexion TLS à {address} : {e}")))
}

pub fn connect_timeout() -> Duration {
    CONNECT_TIMEOUT
}
