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

The maximum depends on your client window: the bank will not grow past the edge
of the game area, and the count is capped to whatever actually fits. Asking for
more columns than there is room for simply gives you as many as fit.

In fixed mode there is very little spare room, so expect one or two extra columns
at most. Resizable mode is where this is worth using.

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

## Compatibility

Tested alongside bank tags, bank tag layouts, Inventory Setups, potion storage
customisation and group storage. No automation, no input is sent, and nothing is
sent over the network.

## License

BSD 2-Clause. See `LICENSE`.
