# Requested features

Collected from the [release thread on r/2007scape](https://www.reddit.com/r/2007scape/comments/1wknhs7/introducing_bank_resizer_plugin/),
September 2026. None of these are committed to. They are written down so that the
work already done to understand them is not repeated, and so the cost of each is
known if the plugin gets enough use to justify it.

Each entry says what was asked for, what is already known about it, and what
would actually have to change.

## Dragging items past the eighth column with bank tag layouts

**Asked for:** bank tag layouts stop at eight columns per row even when the bank
is wider, so the extra columns cannot be used for arranging items.

**Status:** looked at briefly during development and set aside.

**What is known.** A layout stores an item's place as a single index, and turns
that index into a position by dividing by a fixed eight per row. That eight is a
compile time constant in the bank tags plugin, `BANK_ITEMS_PER_ROW`. Nothing
this plugin can do changes it: reading or writing it from here would need
reflection, which the plugin hub forbids.

This plugin already detects when a layout owns the item positions, in
`itemsOwnedByAnotherPlugin`, and leaves the items alone while still widening the
frame around them. That is deliberate, and it is why layouts are merely limited
rather than broken.

**What it would take.** A change where the constant lives, not here. Either bank
tags in the RuneLite client learns a configurable row width, or the bank tag
layouts plugin does, and then exposes it. Coordination with those maintainers is
the first step, not code in this repository.

## Compatibility with Potion Storage Bars

**Asked for:** works alongside
[Potion Storage Bars](https://github.com/Hannamber/potion-bars), which "adds a
progress bar to each potion in the Potion Storage interface".

**Status:** never tested. Nobody here runs it.

**What is known.** This plugin repositions the entries in the potion store when
the bank is widened, in `spreadPotionEntries`. It works out what each widget in
an entry is from its size and type, because the entries are dynamic children
that all share one widget id and so cannot be told apart by id.

A third party bar is an extra child of a size this classifier has never seen. The
plausible failure is not a crash but bars that stay where the game first drew
them while the potion rows move out from under them. Whether that happens
depends on how the bars are anchored, which the README does not say.

**What it would take.** Install it, open the potion store at more than eight
columns, and look. If the bars do lag behind, the fix is most likely to treat an
unrecognised child of an entry as part of that entry and move it by the same
offset, rather than to special case this plugin.

## Compatibility with Expanded Bank

**Asked for:** works alongside
[Expanded Bank](https://github.com/VolkezXO/Expanded-bank), which "dynamically
expands the Bank and Seed Vault interfaces to utilize all available vertical
space when the chatbox is minimized".

**Status:** not tested, but a conflict is already visible in this plugin's own
code.

**What is known.** The two plugins are complementary in intent, one widening and
one heightening, and a user would reasonably want both. They are not
complementary in implementation.

When this plugin widens the bank it also pins the height of every ancestor it
resizes to the height of the play area, in `resizeChrome`:

```java
if (i > 0 && playAreaHeight > 0)
{
    node.setHeightMode(WidgetSizeMode.ABSOLUTE);
    node.setOriginalHeight(playAreaHeight);
}
```

That height comes from `BankRoom.getHeightLimit`, which is the viewport's own
height. The pinning exists for a reason: revalidating the interface root grew it
from the play area to the full canvas, leaving the bank in a container far too
tall with its lower rows off screen.

Expanded Bank wants the bank to be taller than the viewport. This plugin
explicitly clamps it to the viewport. Whichever writes last wins, so the likely
symptom is the bank losing its extra height as soon as a column change or a
client resize makes this plugin lay out again.

**What it would take.** Stop clamping height while still avoiding the overgrown
container that the clamp was added to prevent. That means finding out what
actually made the root grow, rather than capping the result, and only fixing the
width. This is the most invasive of the three requests and the one most likely to
reintroduce a bug that was already fixed once.
