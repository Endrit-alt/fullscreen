# Improved Fullscreen

Maintained by [Endrit](https://github.com/Endrit-alt/fullscreen), based on the
Fullscreen plugin originally created by [dekvall](https://github.com/dekvall).
Original copyright notices and the BSD 2-Clause license are retained.

Borderless and exclusive fullscreen for RuneLite. This checkout has been updated
from the old standalone plugin to the maintained
[fullscreen branch](https://github.com/dekvall/runelite-external-plugins/tree/fullscreen),
commit `4fa741157312d6d3fa9958c91580feadcdf6664a`, with the fixes below.
The build targets Java 11 and pins RuneLite 1.13.3, the current release resolved
during this update.

## Controls

Fullscreen needs RuneLite's **Enable custom window chrome** turned off (it is on by
default on Windows). When fullscreen is blocked by it, the plugin shows a dialog with
a **Turn off custom chrome (applies after restart)** button; restart RuneLite
afterwards. See [Custom window chrome](#custom-window-chrome).
Enable the Improved Fullscreen plugin, then select a mode, use its toolbar button or configure a hotkey.
The configured hotkey also works on the login screen.
Borderless Window is the default and the first dropdown option. **Fullscreen on startup** is
on by default for new configurations. Existing saved preferences are preserved.
**Monitor** appears directly below **Mode**, followed by **Hotkey** and the startup option.
The mode choices are **Exclusive Fullscreen**, **Borderless Window**, and
**Windowed**. Selecting either fullscreen mode enters it immediately, including
from Windowed. The dropdown follows hotkey and toolbar changes too. Select
**Windowed** to exit fullscreen or cancel a pending entry. The hotkey and toolbar
button re-enter the last fullscreen mode that was actually entered; a rejected
selection (for example Exclusive on an unsupported display) does not replace it.
With **Fullscreen on startup** on, RuneLite starts fullscreen even if you switched
to Windowed before closing it: it uses the selected mode, or the last fullscreen
mode you entered when Windowed is selected, and the dropdown then shows that mode.
Turn **Fullscreen on startup** off to start windowed. When it is off and a previous
session left a fullscreen mode selected, the dropdown shows Windowed at launch
(the hotkey and toolbar still return to that mode). Switching RuneLite
configuration profiles never enters, exits or moves fullscreen by itself.

The plugin contains no native code (no JNA or JNI); everything uses Java's own
window APIs. Cursor confinement and the native dark title bar were removed for this.

### Custom window chrome

- **Offer.** When fullscreen is blocked because custom window chrome is on, a dialog
  offers **Turn off custom chrome (applies after restart)**. Choosing it turns the
  RuneLite setting off and records, in this plugin's own settings, that the plugin
  made the change. **Cancel** changes nothing.
- **Startup.** With **Fullscreen on startup** on, the first blocked startup makes the
  same offer once instead of turning startup fullscreen off. Later startups that are
  still blocked stay windowed quietly; once custom chrome is off, startup fullscreen
  works again, and the one-time offer is re-armed in case it is turned back on.
- **Restore.** Turning the plugin off, by disabling or uninstalling it (RuneLite
  stops a plugin when it is uninstalled), turns custom chrome back on automatically
  if this plugin made the change and the setting is still off, and a message says so.
  It applies after the next restart. If you turned custom chrome back on yourself in
  the meantime, the plugin forgets its change and never touches the setting again.
- **Turning the plugin back on without restarting.** The restore has not taken
  effect yet, so the plugin silently turns custom chrome off again instead of asking.
  After a restart the restored chrome is live, and the normal offer applies.
- **Not covered.** If RuneLite crashes, or the plugin's jar is removed without
  RuneLite stopping the plugin, the restore cannot run; turn **Enable custom window
  chrome** back on manually in RuneLite's settings.
- If the setting is already off but RuneLite has not restarted yet, the plugin only
  asks you to restart.

### Closing RuneLite while fullscreen

Fullscreen has no title bar, so while it is active an **X** (**Exit RuneLite**)
appears at the top of the sidebar buttons, above Configuration. Clicking it does
the same as the window's X: RuneLite's own close handling runs, including its exit
confirmation if that is enabled and the window-placement save. The button
disappears in windowed mode. Alt+F4 also works.

### Monitor selection

Right-click the fullscreen toolbar button and select **Choose fullscreen monitor**.
Choose **Current monitor**, **Primary monitor**, **Secondary monitor**, or a connected display listed with
its resolution and desktop position. The compact **Monitor** setting offers
**Current**, **Primary**, **Secondary**, and **Selected**; chooser instructions are in its tooltip
instead of the dropdown text. Selecting a different monitor
while fullscreen moves fullscreen there and preserves the original window placement
for exit. Display numbering in the chooser follows Java's display list, not the
Windows Settings numbering.
Secondary uses the first connected non-primary display, falling back to the current
display when only one monitor is available. Use Selected to choose a specific
display when there are more than two monitors.

Borderless monitor changes keep the existing undecorated fullscreen window instead
of exiting and re-entering fullscreen. Selecting the same display leaves its bounds
unchanged. This avoids temporary decoration changes that can disturb game-canvas
alignment.

The selected display identifier is saved with the RuneLite configuration profile.
If it is unavailable, entry uses the current monitor; reconnecting it makes the
preference available on the next entry. The default remains the current monitor.

### Crash recovery

Every fullscreen change (automatic entry at startup, the hotkey, the toolbar button,
the Mode dropdown and monitor changes) writes and flushes a recovery marker before
GPU renderers are stopped or the window changes. The marker is cleared ten seconds
after the change has settled and the game's client thread has responded, so it also
covers renderers restarting after the window change. A native crash, for example in
the graphics driver while the window is rebuilt, leaves the marker behind.

At the next launch the plugin finds it, starts in a normal window, and explains why.
If **Fullscreen on startup** was on, it is turned off, so a crash cannot repeat at
every launch; turn it back on once fullscreen works reliably. Manual fullscreen stays
available. An error during a manual change never turns automatic entry off.

Markers live in `.runelite/plugin-data/fullscreen` and are accessed only through
RuneLite's `Filepath` API. They are separated by configuration profile and locked
while a change is in progress, so another running client is not mistaken for a
crash. Closing RuneLite normally, or turning the plugin off, clears the marker. If
the marker cannot be written, the change still happens, without this protection
(automatic entry at startup is skipped instead). The plugin cannot prevent the
first crash; this only stops a crash from repeating.

Borderless keeps the desktop display mode and permits switching to other apps.
Exclusive is optional, requires display support, and is unavailable on macOS.
The custom chrome check also looks at the running window, because changing that
setting only takes effect after a RuneLite restart; a rejected toggle leaves game
typing functional.

## Fixes

- Borderless uses Java's own API on every platform: the window is replaced by an
  undecorated one covering the whole monitor, taskbar area included. Because that
  replaces the window's native surface, GPU/117 HD are always stopped first and
  restarted afterwards (see below). Moving borderless fullscreen to another monitor
  only moves the already-undecorated window.
- Sidebar resizing is constrained back to the monitor instead of spilling onto
  another screen, and display resolution changes are followed.
- Exit restores decorations, normal window size and position, maximized state,
  resize lock and always-on-top. Fullscreen does not force topmost. A window that
  was maximized all session restores its real normal size, which Windows reports as
  soon as the window leaves the maximized state.
- All window and renderer lifecycle operations run on Swing's event thread.
  Repeated toggle requests cannot overwrite the saved window state.
- Shutdown during an asynchronous transition cancels entry and restores stopped
  renderers without registering controls again.
- For every fullscreen change, only previously active GPU plugins are stopped and
  restored. The wait is bounded
  to five seconds; failure leaves the window unchanged instead of disposing a
  GPU-owned surface. Disabled or removed renderers are not restarted.
- Exclusive exit releases the device captured on entry, and never releases another
  application's fullscreen window.
- Hotkey actions run after the key handler returns. Error dialogs cannot interrupt
  its pressed/typed bookkeeping. Only the hotkey's own character is suppressed, so
  a release that never reaches the plugin cannot block chat or bank-search typing.
- Focus returns to the game after a transition only when RuneLite was focused when
  it was requested; automatic or delayed transitions never pull RuneLite in front
  of another application.
- Custom window chrome is checked both in settings and on the running window, so a
  setting change that is waiting for a restart is explained instead of failing.
- Monitor changes re-apply the active fullscreen mode instead of reading the mode
  dropdown, and do not override a queued mode change.
- Changing RuneLite's **Lock window size** or **Always on top** while fullscreen keeps
  the borderless window non-resizable, and the new choice is applied on exit.
- A GPU/117 HD renderer that RuneLite restarts while it is stopped for a transition
  (for example during a profile switch) is stopped again before the window changes.
- A renderer is never stopped while it is still starting (for example when
  **Fullscreen on startup** runs while RuneLite is starting the GPU plugin). The plugin
  waits until the renderer has finished starting, then stops it. Stopping it mid-start
  let its pending setup run on the new window beside the restarted renderer; one of
  them failed, and the GPU plugin then turned itself off in RuneLite's settings. If a
  renderer never finishes starting within five seconds, fullscreen is left unchanged
  and the renderer is not touched.
- Closing RuneLite while fullscreen saves the original window placement (and
  maximized state) as RuneLite's window bounds instead of the fullscreen rectangle,
  and clears the crash recovery marker.
- Mode support is checked before leaving working fullscreen or stopping renderers.
  A partly failed entry retains an exit control so restoration can be retried.
- Rebinding or disabling a held hotkey clears its input state. Hotkey dispatch is
  safe for input arriving off the EDT, and queued actions from a disabled plugin
  session cannot toggle a newly enabled session.
- Borderless refreshes monitor bounds while active and falls back to the window's
  current display if the original monitor disconnects. It avoids issuing repeated
  resize calls when the window already covers the monitor.
- Cleanup never recreates a client window that RuneLite already closed. Renderer
  shutdown failures also retain the renderer for recovery when RuneLite removed
  it from the active plugin list before the failure.
- GPU restart failures produce a recovery message rather than only a log entry.
  Recovery still attempts the remaining stopped renderers, preserves the original
  error, and keeps fullscreen controls usable. A failed decoration restore can
  retry without discarding the hidden window's saved state.

Reports reviewed include
[borderless crashes (#113)](https://github.com/dekvall/runelite-external-plugins/issues/113),
[always-on-top (#118)](https://github.com/dekvall/runelite-external-plugins/issues/118),
[invisible cursor (#121)](https://github.com/dekvall/runelite-external-plugins/issues/121),
[borderless bounds (#124)](https://github.com/dekvall/runelite-external-plugins/issues/124),
[multiple monitors (#43)](https://github.com/dekvall/runelite-external-plugins/issues/43),
[black screen (#23)](https://github.com/dekvall/runelite-external-plugins/issues/23),
and [typing after F11/errors (RuneLite #20600)](https://github.com/runelite/runelite/issues/20600).
The shared issue tracker also contains unrelated plugins; those are outside this
Fullscreen repository.

## Build

Use a JDK, Java 11 or newer:

```powershell
.\gradlew.bat clean build
```

The plugin JAR is written to `build/libs/fullscreen-1.5.jar`.

## Validation limits and recovery

Live gameplay with GPU/117 HD, mixed-DPI multi-monitor hardware, ShaderGlass,
macOS, and Linux still need manual validation. Borderless replaces the window's
native surface, which is the operation historically associated with graphics-driver
crashes (for example #113); the plugin only does it after GPU renderers have
released the window, but a native driver crash cannot be caught as a Java exception
and not every driver combination has been verified.

If a renderer refuses to release GPU mode, the operation reports an error and
keeps the existing window. If this prevents plugin shutdown while already
fullscreen, re-enabling the plugin retries restoration using the retained
snapshot, including when it is re-enabled while that shutdown is still waiting. Check the RuneLite log for renderer stop/restart failures.

This source update and JAR do not update the public Plugin Hub installation.
Publishing requires the Plugin Hub entry to be updated to the reviewed source.
