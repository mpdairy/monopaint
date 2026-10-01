# Contributing to MonoPaint

Bug reports, documentation improvements, and pull requests are welcome. For bugs,
include the tablet model, firmware version, app version, steps to reproduce, and
what you expected to happen. Remove device serial numbers and personal drawings
from logs and screenshots before sharing them.

See [the development guide](docs/BUILDING.md) for prerequisites and build commands,
and [the code structure](docs/ARCHITECTURE.md) for an overview and how to add a tool.
Before submitting code, run the host checks and build/lint checks documented there.
For changes to pen input, display refresh, or orientation, also describe what you
tested on a tablet. If you cannot run device checks, say so in the pull request.
Use the isolated test install to keep tests separate from your drawing library.

Keep changes focused and explain the user-visible behavior and validation. Preserve
existing drawing formats and Android upgrade compatibility. Source packages use
`io.github.mpdairy.monopaint`; the legacy installed app ID is intentional.

Do not commit signing keys, passwords, local SDK paths, or personal device data.
Contributions are covered by the repository's [MIT license](LICENSE); preserve
the notices for any third-party code you use.
