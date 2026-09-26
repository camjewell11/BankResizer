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
vertical space) then we'd really be cooking".

**Status:** done. The two work together.

**What was found.** They add room in different directions and do not contend for
the same thing. The bank is held to the play area as the game reports it, and
that grows and shrinks as the chatbox comes and goes, so the extra height is
kept.

Worth recording, because it was nearly got wrong: the height this plugin fixes
on the bank's ancestors was suspected of clamping the extra height away, and was
taken out to see. That was worse, not better, and it was put straight back. The
clamp is load bearing: without it, revalidating grows the interface root to the
whole canvas and the bank's lower rows go off screen. The report that prompted
the suspicion turned out to be about a different plugin entirely.

What is left is [following the chatbox](#following-the-chatbox-without-reopening-the-bank),
which is not specific to this pairing.

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

**Status:** done.

**What it was.** The bank is given the height of the play area, and was given it
once, when the bank was opened. Nothing afterwards was watched for except the
client being resized, so anything else that changed the play area went unnoticed
and the bank kept a height that no longer fitted. Asking how many to withdraw
was the clearest case: the prompt puts the chatbox up, the space is taken back,
and the bank was left hanging over it.

**What was done.** The height of the play area counts towards whether the bank
is already laid out, so a change to it is followed. The bank is also laid out
again in the cycle the chatbox goes up, rather than waiting for the next size
check, which would leave it wrong for up to a tick.

Only plugins that grow the bank into the chatbox space make this visible. On its
own the game keeps the bank inside the play area and the chatbox plays no part.

Whether the space is taken back at all is the other plugin's to decide, and it
is not consistent from one prompt to the next. Both ways are followed.
