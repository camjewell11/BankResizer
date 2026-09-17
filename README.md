# Bank Resizer

A RuneLite plugin that widens the Old School RuneScape bank interface so it shows
more columns of items.

The bank already grows taller with the client window, because the game lays its
item container out with a height of `parent - 81`. It never grows wider, because
that same layout step fixes the width at an absolute 460 pixels. This plugin
changes only the width, and leaves item icons at their normal size.

## Configuration

| Setting | Default | Effect |
| --- | --- | --- |
| Fit to window width | off | Use as many columns as the client window allows. Overrides the column count. |
| Columns | 8 | Items per row. 8 is the unmodified game layout. |

The plugin ships doing nothing. At 8 columns it touches no widget at all, because
the game has already drawn that layout correctly, so installing it changes
nothing until you raise the column count. Turning the count back down to 8 undoes
its own changes and then goes idle again.

The column count is always capped at what fits inside the client window, so the
bank cannot be pushed off screen. In fixed mode the game area is only 765 pixels
wide, so expect few or no extra columns there. Resizable mode is where this
plugin is useful.

## How it works

The game builds the bank in client script 277, `[proc,bankmain_build]`. Three
lines of that script decide the grid:

```
def_int $int22 = calc(8 - 1);                               // columns - 1
def_int $int23 = calc(if_getwidth($component2) - 51 - 35);  // usable width
def_int $int24 = calc(($int23 - 8 * 36) / $int22);          // horizontal padding
cc_setposition(calc(51 + $int34 * (36 + $int24)), calc($int35 * 36), ...)
```

The column count is a hardcoded local, so it cannot be overridden by passing
different arguments to the script. Widening the container alone does not add
columns either. It only makes the padding term larger, spreading the same eight
items further apart.

So the plugin lets the script run, then lays the grid out again using the same
formula with a different column count. That keeps spacing identical to vanilla at
every width. Script 277 calls `[proc,bankmain_finishbuilding]` as its final
statement, so one hook on the build script completing is enough to run after both
the layout and the scroll size have settled.

Widths are always assigned as a captured original plus a delta, never accumulated,
because the outer frame is sized once when the interface initialises while the
item container is resized on every rebuild.

The scrollbar rebuild is deferred with `clientThread.invokeLater`. The layout runs
from a script event, so the script VM is still on the stack, and calling back into
it directly throws `scripts are not reentrant` and takes the client down.

## Building

Requires JDK 11 or newer. The compile target is Java 11 to match the client.

```
./gradlew build      # compile and run unit tests
./gradlew run        # launch a developer-mode client with the plugin loaded
```

The geometry lives in `BankLayout`, isolated from the client API so it can be
unit tested directly. Those tests pin the maths to the game script's behaviour,
and are the thing that should fail first if Jagex ever changes the bank layout.

## Status

The layout maths is verified by unit tests. Still unconfirmed against a running
client: the set of chrome widgets that get widened, the chrome width used to cap
the column count, and tab separator placement. Enable debug logging to get a dump
of the real widget geometry on each layout pass.

## Compliance

Jagex's third party client guidelines prohibit moving or resizing click zones for
3D components, and for the combat options, inventory, worn equipment, spellbook
and prayer book interfaces. The bank is not among them, and the Plugin Hub
already carries plugins that resize the bank and the chatbox. This plugin does
not automate anything, send input, or make network calls.

## License

BSD 2-Clause. See `LICENSE`.
