# Requested features

Collected from the [release thread on r/2007scape](https://www.reddit.com/r/2007scape/comments/1wknhs7/introducing_bank_resizer_plugin/),
September 2026. None of these are committed to. They are written down so that the
work already done to understand them is not repeated, and so the cost of each is
known if the plugin gets enough use to justify it.

Each entry says what was asked for, what is already known about it, and what
would actually have to change.

## Dragging items past the eighth column with bank tag layouts

**Asked for:** the most reported limitation, with a screenshot. "doesn't seem
like bank tags work with this plugin unless I'm missing something", and asked
whether items can be moved within that interface, "nah can't move to the right".

**Status:** looked at briefly during development and set aside. Answered in the
thread as needing the bank tags developer.

**What is known.** A layout stores its items in a flat array, one entry per
position: `Layout` in `banktags.tabs` holds an `int[]` reached through
`getItemAtPos(int)` and `setItemAtPos(int, int)`. A position becomes a row and a
column by dividing by `BankTagsPlugin.BANK_ITEMS_PER_ROW`, which is a compile
time constant of 8. Nothing this plugin can do changes it: reading or writing it
from here would need reflection, which the plugin hub forbids.

One guess in the thread was that bank tags stores an x and y per item rather than
a single counter. It is the other way round, and that is the harder case. Because
a position is one number interpreted against the row width, changing the row
width does not widen an existing layout, it reinterprets it: an item at index 12
sits in a different cell at ten columns than it does at eight. Every layout a
user has already arranged would move. Any real fix has to decide what happens to
those, which is a migration question, not a rendering one.

This plugin already detects when a layout owns the item positions, in
`itemsOwnedByAnotherPlugin`, and leaves the items alone while still widening the
frame around them. That is deliberate, and it is why layouts are merely limited
rather than broken.

**What it would take.** A change where the constant lives, not here. Either bank
tags in the RuneLite client learns a configurable row width, or the bank tag
layouts plugin does, along with an answer for existing layouts. Coordination with
those maintainers is the first step, not code in this repository.

## Compatibility with Expanded Bank

**Asked for:** "If this could also work with the Expanded Bank plugin (increases
vertical space) then we'd really be cooking". Asked what that plugin adds over
the game's own vertical growth, the answer was that "it expands it all the way
down to the edge of the screen".

**Status:** not tested, but a conflict is already visible in this plugin's own
code.

**What is known.** The two are complementary in intent, one widening and one
heightening, and a user would reasonably want both. They are not complementary in
implementation.

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

Expanded Bank takes the bank to the edge of the screen, which is past the
viewport. This plugin clamps it to the viewport. Whichever writes last wins, so
the likely symptom is the bank losing its extra height as soon as a column change
or a client resize makes this plugin lay out again.

**What it would take.** Stop clamping height while still avoiding the overgrown
container the clamp was added to prevent. That means finding what actually made
the root grow, rather than capping the result, and only fixing the width. This is
the most invasive of these requests and the one most likely to reintroduce a bug
that was already fixed once.

## Compatibility with Potion Storage Bars

**Asked for:** "Unfortunately not compatible with Potion Storage Bars although I
might deactivate it for this".
[Potion Storage Bars](https://github.com/Hannamber/potion-bars) "adds a progress
bar to each potion in the Potion Storage interface".

**Status:** never tested, and no symptom was given. The reporter said only that
it is not compatible.

**What is known.** This plugin repositions the entries in the potion store when
the bank is widened, in `spreadPotionEntries`. It works out what each widget in
an entry is from its size and type, because the entries are dynamic children that
all share one widget id and so cannot be told apart by id.

A third party bar is an extra child of a size this classifier has never seen. The
plausible failure is not a crash but bars that stay where the game first drew
them while the potion rows move out from under them. That is a guess. Nobody has
described what actually goes wrong.

**What it would take.** First, ask the reporter what they see, because the guess
above may be wrong. Then install it, open the potion store at more than eight
columns and look. If the bars do lag behind, the fix is most likely to treat an
unrecognised child of an entry as part of that entry and move it by the same
offset, rather than to special case this plugin.

## Moving the bank interface

**Asked for:** "Can we also have the ability to move it around?"

**Status:** not investigated. Out of scope as the plugin stands.

**What is known.** Nothing yet, beyond that this is repositioning rather than
resizing, so none of the existing measuring and layout code applies.

Worth checking before any work starts: Jagex's third party client guidelines
forbid "repositioning or resizing click zones" for several named interfaces. The
bank is not among those named, and this plugin already moves bank widgets, so
there is no obvious rule against it. That is an argument from the list being
silent, not from permission, and it is a reviewer's judgement rather than
something the text settles.

## Following the chatbox without reopening the bank

**Asked for:** nobody yet. Found while checking this against Expanded Bank.

**Status:** known, and left alone on purpose.

**What is known.** Opening or closing the chatbox changes the height of the play
area without changing the canvas at all. The bank is laid out from a reading
taken when it was opened, and the only thing watched for afterwards is a change
in canvas size, so a chatbox toggle goes unnoticed and the bank is left laid out
for a play area that is no longer that shape. Opening the bank again fixes it,
because that takes a fresh reading.

The same is true of anything else that changes the play area while the bank is
open. Resizing the client is already handled, by giving up and going back to
eight columns until the bank is next opened.

**What it would take.** Watch the play area rather than the canvas, and lay out
again when it changes. The pieces are there: the play area is measured already.
The care needed is in not doing it on every frame, and in deciding whether to
relayout in place or to step back to eight columns as the client resize does.

