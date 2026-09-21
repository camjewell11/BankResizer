# Bank Resizer

Widens the bank so it shows more columns of items.

The bank already grows taller with your client window, but never wider — it stays
eight items across no matter how much room there is. This plugin lets you choose
how many columns it uses, so a larger client window means less scrolling.

Item icons stay their normal size, and spacing between them stays exactly as the
game draws it. Only the number of columns changes.

## Using it

Set **Columns** to the number of items you want per row. That's it.

The plugin does nothing until you change that setting. At 8 columns — the
default — it leaves the bank completely alone, so installing it changes nothing
until you ask for more.

**Changes apply the next time you open the bank.** Setting the column count
while the bank is open would leave it half redrawn, so the new value waits for
the next open. Close the bank and open it again to see it.

### Settings

| Setting | Default | What it does |
| --- | --- | --- |
| Columns | 8 | Items per row. 8 is the normal game layout. |
| Fit to window width | off | Use as many columns as fit. Ignores the column count above. |

Eight is the minimum. The bank is never made narrower than the game draws it.

The maximum is 28, which is as many as the game will draw — past that it stops
drawing the bank at all, however large your monitor is. Below that the limit is
your client window: the bank never grows past the edge of the game area, and
asking for more columns than fit simply gives you as many as fit.

**This plugin only works in resizable mode.** In fixed mode the bank is left
exactly as the game draws it, whatever the column count is set to.

## What to expect

**Resizing your client** puts the bank back to 8 columns until you next open it.
Everything the plugin measured belongs to the old window size, so it steps out of
the way rather than risk leaving the bank in a broken state. Reopen the bank and
your column count returns, fitted to the new size.

**Bank tag layouts and Inventory Setups** arrange bank items themselves. When one
of those is showing, the bank frame still widens but the items keep the
arrangement you gave them, at their usual 8 columns. Dragging items into the
extra columns is not possible, because those layouts store positions on a fixed
eight-per-row grid that the plugin cannot change.

**Everything else in the bank** — tabs, the "view all items" tab and its
separators, the potion store, group storage, the buttons along the bottom —
keeps working and stays where it belongs as the bank widens.

## Turning it off

The bank goes back to normal on its own, and the plugin can be removed at any
time.

If the bank or the potion store still looks wrong afterwards, with columns out
of line or labels cut off, another plugin is arranging it from positions of its
own that no longer suit the width. To clear it:

1. Turn off the other plugins that arrange the bank: Potion Storage Customizer,
   Expanded Bank, Bank Tag Layouts, Bank Tags, Inventory Setups, and any other
   plugin that arranges what is in the bank.
2. Open the bank once.
3. Turn those plugins back on.

Restarting the client does not clear it, because those positions are saved
between sessions. There is also a **Put the bank back now** setting, which undoes
everything this plugin has done without turning it off, though it cannot undo
what another plugin has done.

## Compatibility

Tested alongside bank tags, bank tag layouts, Inventory Setups and group
storage, and with the bank's own search, tabs, the "view all items" tab and the
potion store.

Tested in resizable mode with and without stretched mode, across client sizes
from small windows to full screen, and while resizing the client with the bank
open. Fixed mode is left alone.

**Overlays from other plugins** are drawn where they were put, which for some of
them is on top of any interface. A wider bank reaches into space that used to be
empty, so an overlay that never met the bank before may now sit over it. Which
plugins draw above interfaces is their own choice and nothing here can change
it. Hold alt and drag the overlay somewhere the bank does not reach, or turn it
off while banking.

**Expanded Bank** works alongside this one. It grows the bank downwards into
the space the chatbox leaves, and this one widens it, so the two add room in
different directions.

Opening or closing the chatbox while the bank is open leaves the bank looking
odd until it is opened again, the same as resizing the client does. Close the
bank and open it and it will be right.

**Fixed Resizable Hybrid** restyles the interface to look like fixed mode, and
puts the bank inside a frame of its own that it holds to a set width. There is
no room to widen the bank inside it, so this plugin stands aside and leaves the
bank exactly as that one draws it, whatever the column count is set to. Nothing
breaks; the bank simply stays eight across while that plugin is on.

**Potion Storage Customizer** saves where each potion sits as an exact spot on
screen, not a place in a list, and puts them back there every time the store is
drawn. Those spots only suit the bank width they were saved at, so the two
plugins end up moving the same potions in turn, and turning this one off leaves
the store looking scrambled until that plugin's saved order is reset. Arranging
your potions with the bank at 8 columns avoids saving spots that do not fit.
This has been reported to that plugin, and cannot be put right from here.

No automation, no input is sent, and nothing is sent over the network.

## License

BSD 2-Clause. See `LICENSE`.
