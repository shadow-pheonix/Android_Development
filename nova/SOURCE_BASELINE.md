# Nova source baseline

This directory is the source of truth for the custom Nova build on the `nova-pip-build` branch.

Final checked-in component trees:

- `Video`: `1bc28f7532b520658b1c6cf7482924a9ef121f8e`
- `MediaLib`: `14bbdf89b76725df73b31116c0bf5eedeb83f650`
- `native/avos`: `675e0e04707905a605797fc314181ccc363ca0c3`

Upstream bases used for this development line:

- Video: `cab25da088a28601795c795c9ad6375f2054f60a`
- MediaLib: `3e904a4e657c2abc9a6b12192a095d52714041b7`
- native/avos: `c594ce3b4c8c2d1dc7e1dd050812801e2040560f`

Current custom feature set: handheld PiP, exportable diagnostics, local subtitle-file loading, and HDR-to-SDR decoder tone mapping.

Feature source is checked in directly. CI must not regenerate or patch these features.
