# Public TLS test fixtures

These PKCS#12 files contain public, disposable test keys. Their password is `test-only`.
Never use these certificates or keys outside local automated tests.

All leaves are signed by the same test CA, embedded as alias `ca`. The private server key
uses alias `server`. `server.p12` has a localhost SAN, `wrong-host.p12` has a wrong.example
SAN, and `expired.p12` expired in January 2010. The other certificates are valid from
January 2020 through December 2039. Tests trust the embedded CA only for their local
fixture; production FTPS uses the platform trust store.

The fixtures were generated with JDK 21 keytool (`-genkeypair`, `-certreq`, `-gencert`,
`-importcert`). Renew the non-expired leaves and CA together before their expiry.
