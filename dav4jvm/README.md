# dav4jvm (vendored)

A copy of [bitfireAT/dav4jvm](https://github.com/bitfireAT/dav4jvm) at commit
`02fe1a95e6b86e323bec3784d7d2fe2d4081dde6` (2025-08-13), the last release line built on OkHttp;
every tagged release from 3.0 on is built on Ktor, which the WebDAV provider does not use. It was
previously pulled from JitPack by commit hash. Licensed under the Mozilla Public License 2.0; see
`LICENSE` and `AUTHORS`.

Modifications from upstream (MPL 2.0 section 3.2 asks that they be stated):

- `DavResource.checkStatus(Response)` and `DavResource.followRedirects(...)` are `public` instead of
  `internal`, so that the app's `DavResourceCompat` can reuse them for ranged GET, streaming PUT
  and PATCH without reaching into mangled internals.
- `Response.parse(...)` collects the properties of *all* propstat elements with `flatMap` instead of
  `map`, which dropped them into a list of lists; `filterIsInstance<ResourceType>()` then never
  matched and a collection's href lost the trailing slash the KDoc promises.
- Dead members removed: the deprecated `DavResource.get(accept, callback)` (superseded by
  `get(accept, headers)`) and the `DeprecationLevel.ERROR` overload of
  `HrefListProperty.Factory.create(parser, list)`.
- Long methods split into named private helpers, with no change in behaviour:
  `BasicDigestAuthHandler.authenticateRequest` (`isAllowedHost`, `updateChallenges`, `digestA1`,
  `digestA2`), `DavCalendar.calendarQuery` (`insertTimeRange`), `DavResource.checkStatus`
  (`exceptionFor`) and `DavResource.processMultiStatus` (`parseMultiStatus`), `Response.parse`
  (`resolvableHref`, `parseStatus`, `relation`), `DavException` (`parseErrors`) and
  `QuotedStringUtils.asQuotedString`.
- Commented-out logging and stale TODO comments removed.
