/*
 * Copyright (c) 2020, dekvall <https://github.com/dekvall>
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.endrit.improvedfullscreen;

import com.google.inject.Provides;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Frame;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.RenderingHints;
import java.awt.event.KeyEvent;
import java.awt.event.WindowEvent;
import java.awt.geom.Line2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import javax.inject.Inject;
import javax.swing.JFrame;
import javax.swing.JOptionPane;
import javax.swing.JRootPane;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.config.ConfigProfile;
import net.runelite.client.config.Keybind;
import net.runelite.client.config.RuneLiteConfig;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ClientShutdown;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.events.ProfileChanged;
import net.runelite.client.input.KeyManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.PluginManager;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.ClientUI;
import net.runelite.client.ui.ContainableFrame;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.util.HotkeyListener;
import net.runelite.client.util.ImageUtil;
import net.runelite.client.util.OSType;

@Slf4j
@PluginDescriptor(
	name = "Improved Fullscreen",
	description = "Borderless or exclusive fullscreen. Offers to turn off RuneLite's custom window chrome, and restores it when turned off.",
	tags = {"window", "monitor", "borderless", "exclusive"},
	internalName = "improvedfullscreen",
	conflicts = {"Fullscreen"},
	enabledByDefault = false
)
public class ImprovedFullscreenPlugin extends Plugin
{
	@Inject private Client client;
	@Inject private ClientUI clientUI;
	@Inject private ConfigManager configManager;
	@Inject private KeyManager keyManager;
	@Inject private ImprovedFullscreenConfig config;
	@Inject private ClientToolbar clientToolbar;
	@Inject private ClientThread clientThread;
	@Inject private PluginManager pluginManager;

	private Frame frame;
	private FullscreenWindow window;
	private FullscreenController controller;
	private HotkeyListener hotkeyListener;
	private NavigationButton button;
	/** Shown only while fullscreen, which has no title bar and so no close button. */
	private NavigationButton exitButton;
	private boolean chromeRestoredNoticePending;
	static final String EXIT_TOOLTIP = "Exit RuneLite";
	/** Above every other sidebar button (Configuration uses 0). */
	static final int EXIT_PRIORITY = -1000;
	private boolean enabled;
	private boolean closing;
	private boolean errorDialogOpen;
	private boolean updatingMonitorSelection;
	private boolean updatingModeSelection;
	/** Crash marker for the fullscreen change in progress (automatic startup or manual). */
	private StartupRecovery startupRecovery;
	private Timer startupTimer;
	private Timer windowReadyTimer;
	/** Profile whose Fullscreen settings this session applies; written on the profile-switch thread. */
	private volatile long sessionProfile;
	/** Only return focus to RuneLite when it was focused when the request began. */
	private boolean refocusAfterTransition;
	/** Re-enabled while a shutdown exit was still waiting; retry that exit once if it fails. */
	private boolean retryCloseOnSettle;
	/** Automatic entry reached settled fullscreen; later errors are not startup failures. */
	private boolean startupSettled;

	@Override
	protected void startUp()
	{
		onEdt(() ->
		{
			enabled = true;
			if (controller == null)
			{
				closing = false;
				initialize();
			}
			else if (closing && !controller.isTransitioning())
			{
				// A renderer timeout may have prevented safe shutdown. Keep its snapshot
				// and retry restoration before starting a new session.
				controller.close();
			}
			else if (closing)
			{
				// The pending shutdown exit may still time out. Without a retry the plugin
				// would stay enabled with no toolbar button or hotkey for the fullscreen window.
				retryCloseOnSettle = true;
			}
		});
	}

	private void initialize()
	{
		frame = findClientFrame();
		if (frame == null)
		{
			if (windowReadyTimer == null)
			{
				int[] attempts = {0};
				windowReadyTimer = new Timer(250, event ->
				{
					if (!enabled || closing)
					{
						stopWaitingForWindow();
					}
					else if (findClientFrame() != null)
					{
						stopWaitingForWindow();
						initialize();
					}
					else if (++attempts[0] >= 40)
					{
						stopWaitingForWindow();
						report(new IllegalStateException("RuneLite's main window could not be found."));
					}
				});
				windowReadyTimer.start();
			}
			return;
		}
		stopWaitingForWindow();
		closing = false;
		retryCloseOnSettle = false;
		sessionProfile = currentProfileId();
		reclaimCustomChromeRestoredThisSession();
		Frame sessionFrame = frame;
		window = new FullscreenWindow(frame, () ->
		{
			GraphicsEnvironment environment = GraphicsEnvironment.getLocalGraphicsEnvironment();
			return MonitorSelector.select(sessionFrame.getGraphicsConfiguration(), config.monitorTarget(),
				config.preferredMonitor(), environment.getScreenDevices(), environment.getDefaultScreenDevice());
		});
		controller = new FullscreenController(window,
			new RendererSuspender(pluginManager, clientThread, client),
			this::transitionChanged, this::report);
		FullscreenController session = controller;
		hotkeyListener = createHotkeyListener(config::fullscreenHotKey, () ->
		{
			if (controller == session)
			{
				toggle();
			}
		});
		keyManager.registerKeyListener(hotkeyListener);
		refreshControls();
		if (!recoverFromInterruptedChange() && config.fullscreenOnStartup())
		{
			// Windowed in the dropdown is the previous session's live state, not a
			// preference: toggle() then uses the last fullscreen mode that was entered,
			// and the dropdown is synchronized to it once entry settles.
			attemptAutomaticStartup();
		}
		showWindowedAtStartup();
	}

	/**
	 * A fullscreen mode saved by the previous session would otherwise stay selected while
	 * the window is windowed, and re-selecting it would do nothing (no change event).
	 * Only done when automatic entry is off, so this never changes startup behavior.
	 */
	private void showWindowedAtStartup()
	{
		Mode shown = config.fullscreenMode();
		if (controller == null || controller.isActive() || controller.isTransitioning() || config.fullscreenOnStartup()
			|| shown == null || shown == Mode.WINDOWED)
		{
			return;
		}
		if (config.lastFullscreenMode() != shown)
		{
			configManager.setConfiguration("improvedfullscreen", "lastFullscreenMode", shown);
		}
		updatingModeSelection = true;
		try
		{
			configManager.setConfiguration("improvedfullscreen", "fullscreenMode", Mode.WINDOWED);
		}
		finally
		{
			updatingModeSelection = false;
		}
	}

	private void stopWaitingForWindow()
	{
		if (windowReadyTimer != null)
		{
			windowReadyTimer.stop();
			windowReadyTimer = null;
		}
	}

	StartupRecovery createStartupRecovery() throws IOException
	{
		// .runelite/plugin-data/improvedfullscreen/startup-<profile>, through RuneLite's Filepath.
		return new StartupRecovery(getPluginDirectory().joinSegment("startup-" + currentProfileId()));
	}

	private long currentProfileId()
	{
		ConfigProfile currentProfile = configManager.getProfile();
		return currentProfile == null ? 0 : currentProfile.getId();
	}

	private void attemptAutomaticStartup()
	{
		ChromeBlock chrome = customChromeBlock(frame, runeLiteConfig().enableCustomChrome());
		if (chrome != ChromeBlock.NONE)
		{
			// Leave "Fullscreen on startup" on: the window is unchanged and nothing failed.
			if (chrome == ChromeBlock.SETTING_ON && !config.customChromeStartupOfferShown())
			{
				configManager.setConfiguration("improvedfullscreen", "customChromeStartupOfferShown", true);
				offerToDisableCustomChrome(true);
			}
			else
			{
				log.info("Fullscreen on startup skipped: RuneLite's custom window chrome is on");
			}
			return;
		}
		if (config.customChromeStartupOfferShown())
		{
			// Custom chrome is off now; if it is turned on again, offer once more.
			configManager.setConfiguration("improvedfullscreen", "customChromeStartupOfferShown", false);
		}
		try
		{
			startupSettled = false;
			startupRecovery = createStartupRecovery();
			StartupRecovery.Result result = startupRecovery.begin();
			if (result != StartupRecovery.Result.READY)
			{
				finishStartup(false);
				if (result == StartupRecovery.Result.RECOVERED)
				{
					// Only if the launch check raced with another client that left a marker.
					configManager.setConfiguration("improvedfullscreen", "fullscreenOnStartup", false);
					report(new IllegalStateException(INTERRUPTED_CHANGE + INTERRUPTED_STARTUP_OFF + INTERRUPTED_ADVICE));
				}
				else
				{
					report(new IllegalStateException("Another RuneLite client is starting fullscreen with this profile. "
						+ "This client will start windowed; you can toggle fullscreen manually."));
				}
				return;
			}
			toggle();
		}
		catch (IOException | RuntimeException ex)
		{
			report(new IllegalStateException("Automatic fullscreen could not start safely. "
				+ "You can toggle fullscreen manually. " + ex.getMessage(), ex));
		}
	}

	static final String INTERRUPTED_CHANGE = "RuneLite closed unexpectedly while changing fullscreen last time, "
		+ "so it started in a normal window.";
	static final String INTERRUPTED_STARTUP_OFF = "\nFullscreen on startup has been turned off. You can turn it back on "
		+ "in the Fullscreen settings once fullscreen works reliably.";
	static final String INTERRUPTED_ADVICE = "\nYou can still use the hotkey or the toolbar button. If this keeps "
		+ "happening, updating your graphics driver may help.";

	/**
	 * A marker left behind means RuneLite closed in the middle of a fullscreen change (for
	 * example a graphics-driver crash). Start windowed instead of repeating it at every launch.
	 */
	private boolean recoverFromInterruptedChange()
	{
		StartupRecovery.Result result;
		try
		{
			result = createStartupRecovery().check();
		}
		catch (IOException | RuntimeException ex)
		{
			log.warn("Unable to check the fullscreen recovery marker", ex);
			return false;
		}
		if (result != StartupRecovery.Result.RECOVERED)
		{
			return false;
		}
		boolean startup = config.fullscreenOnStartup();
		if (startup)
		{
			configManager.setConfiguration("improvedfullscreen", "fullscreenOnStartup", false);
		}
		report(new IllegalStateException(INTERRUPTED_CHANGE + (startup ? INTERRUPTED_STARTUP_OFF : "") + INTERRUPTED_ADVICE));
		return true;
	}

	/** Writes the marker before a manual change stops renderers or touches the window. */
	private void guardChange()
	{
		for (int attempt = 0; attempt < 2; attempt++)
		{
			try
			{
				StartupRecovery guard = createStartupRecovery();
				StartupRecovery.Result result = guard.begin();
				if (result == StartupRecovery.Result.READY)
				{
					startupRecovery = guard;
					// Errors in a manual change must never turn automatic startup off.
					startupSettled = true;
					return;
				}
				if (result == StartupRecovery.Result.BUSY)
				{
					// Another client using this profile holds the marker; it guards itself.
					return;
				}
				// RECOVERED: a stale marker was removed; write a fresh one.
			}
			catch (IOException | RuntimeException ex)
			{
				log.warn("Unable to write the fullscreen recovery marker; this change is unguarded", ex);
				return;
			}
		}
	}

	private void finishStartup(boolean clearMarker)
	{
		if (startupTimer != null)
		{
			startupTimer.stop();
			startupTimer = null;
		}
		StartupRecovery recovery = startupRecovery;
		startupRecovery = null;
		if (recovery != null)
		{
			try
			{
				recovery.finish(clearMarker);
			}
			catch (IOException ex)
			{
				log.warn("Unable to finish fullscreen startup recovery marker", ex);
			}
		}
	}

	static HotkeyListener createHotkeyListener(Supplier<Keybind> keybind, Runnable toggle)
	{
		HotkeyListener listener = new HotkeyListener(keybind)
		{
			private boolean toggleRequested;
			private Keybind previousBinding;
			/** Whether the most recent non-modifier key press was the hotkey itself. */
			private boolean typedBelongsToHotkey;

			@Override
			public void hotkeyPressed()
			{
				toggleRequested = true;
			}

			@Override
			public synchronized void keyPressed(KeyEvent event)
			{
				resetChangedBinding();
				if (Keybind.getModifierForKeyCode(event.getKeyCode()) == null)
				{
					// HotkeyListener consumes every KEY_TYPED until it sees the hotkey's
					// release. That release can go to a dialog, another window or an
					// earlier listener, so only suppress the character of the hotkey press
					// itself. Held state is untouched, so auto-repeat cannot re-toggle.
					typedBelongsToHotkey = keybind.get().matches(event);
				}
				super.keyPressed(event);
				if (toggleRequested)
				{
					toggleRequested = false;
					// Dispatch only after the base handler finishes, even if input arrives
					// on a thread other than the EDT.
					long queuedAt = System.nanoTime();
					SwingUtilities.invokeLater(() ->
					{
						long delayMillis = (System.nanoTime() - queuedAt) / 1000000;
						if (delayMillis > 250)
						{
							log.warn("Fullscreen hotkey waited {} ms for the UI thread", delayMillis);
						}
						toggle.run();
					});
				}
			}

			@Override
			public synchronized void keyReleased(KeyEvent event)
			{
				resetChangedBinding();
				super.keyReleased(event);
			}

			@Override
			public synchronized void keyTyped(KeyEvent event)
			{
				resetChangedBinding();
				if (typedBelongsToHotkey)
				{
					super.keyTyped(event);
				}
			}

			@Override
			public synchronized void focusLost()
			{
				super.focusLost();
			}

			private void resetChangedBinding()
			{
				Keybind binding = keybind.get();
				if (!Objects.equals(previousBinding, binding))
				{
					super.focusLost();
					previousBinding = binding;
				}
			}
		};
		listener.setEnabledOnLoginScreen(true);
		return listener;
	}

	private void transitionChanged()
	{
		refreshControls();
		syncModeSelection();
		if (startupRecovery == null && controller != null && controller.isTransitioning() && enabled && !closing)
		{
			// The controller reports a change before it stops renderers or touches the window.
			guardChange();
		}
		if (startupRecovery != null && controller != null)
		{
			if (startupTimer != null)
			{
				startupTimer.stop();
			}
			if (!controller.isTransitioning() && (controller.isActive() || startupSettled))
			{
				// Leave the flushed marker armed long enough to catch delayed driver crashes
				// while renderers restart, not just the window change itself. Leaving
				// fullscreen restarts renderers too, so it is guarded the same way.
				StartupRecovery attempt = startupRecovery;
				startupSettled = true;
				startupTimer = new Timer(10000, event -> clientThread.invokeLater((Runnable) () ->
					SwingUtilities.invokeLater(() ->
					{
						if (startupRecovery == attempt && controller != null && !controller.isTransitioning())
						{
							finishStartup(true);
						}
					})));
				startupTimer.setRepeats(false);
				startupTimer.start();
			}
			else if (!controller.isTransitioning())
			{
				StartupRecovery attempt = startupRecovery;
				// Failure callbacks run immediately after the state update. Give them
				// the chance to disable automatic startup before treating this as exit.
				SwingUtilities.invokeLater(() ->
				{
					if (startupRecovery == attempt && controller != null
						&& !controller.isTransitioning() && !controller.isActive())
					{
						finishStartup(true);
					}
				});
			}
		}
		if (enabled && !closing && controller != null && !controller.isTransitioning() && refocusAfterTransition)
		{
			// Return keyboard focus to the game after window changes, but never pull
			// RuneLite in front of an application the user switched to meanwhile.
			refocusAfterTransition = false;
			clientUI.forceFocus();
		}
	}

	private void beginRequest()
	{
		refocusAfterTransition = frame != null && frame.isFocused();
	}

	private void toggle()
	{
		if (!enabled || closing || controller == null || controller.isTransitioning())
		{
			return;
		}
		if (!controller.isActive() && !canEnter())
		{
			return;
		}
		Mode mode = config.fullscreenMode();
		if (mode == Mode.WINDOWED)
		{
			mode = config.lastFullscreenMode();
			if (mode == null || mode == Mode.WINDOWED)
			{
				mode = Mode.BORDERLESS;
			}
		}
		beginRequest();
		controller.toggle(mode);
	}

	private void syncModeSelection()
	{
		if (!enabled || closing || controller == null || controller.isTransitioning())
		{
			return;
		}
		Mode actual = controller.currentMode();
		if (actual != Mode.WINDOWED && config.lastFullscreenMode() != actual)
		{
			// Remember only modes that were actually entered. A rejected selection must
			// not become the mode the hotkey and toolbar retry.
			configManager.setConfiguration("improvedfullscreen", "lastFullscreenMode", actual);
		}
		if (actual != null && config.fullscreenMode() != actual)
		{
			updatingModeSelection = true;
			try
			{
				configManager.setConfiguration("improvedfullscreen", "fullscreenMode", actual);
			}
			finally
			{
				updatingModeSelection = false;
			}
		}
	}

	enum ChromeBlock
	{
		/** Custom window chrome is off on the running window. */
		NONE,
		/** "Enable custom window chrome" is on; it can be turned off for the next start. */
		SETTING_ON,
		/** The setting is already off but RuneLite has not restarted yet. */
		RESTART_PENDING
	}

	static final String TURN_OFF_CUSTOM_CHROME = "Turn off custom chrome (applies after restart)";

	private RuneLiteConfig runeLiteConfig()
	{
		return configManager.getConfig(RuneLiteConfig.class);
	}

	private boolean canEnter()
	{
		ChromeBlock chrome = customChromeBlock(frame, runeLiteConfig().enableCustomChrome());
		if (chrome == ChromeBlock.SETTING_ON)
		{
			offerToDisableCustomChrome(false);
			return false;
		}
		if (chrome == ChromeBlock.RESTART_PENDING)
		{
			report(new IllegalStateException(
				"Custom window chrome is still active. Restart RuneLite to finish turning it off, then enter fullscreen."));
			return false;
		}
		return true;
	}

	/**
	 * The RuneLite setting only takes effect after a restart, so check both the saved
	 * setting and the decorations actually installed on the running window.
	 */
	static ChromeBlock customChromeBlock(Frame frame, boolean configured)
	{
		if (configured)
		{
			return ChromeBlock.SETTING_ON;
		}
		if (frame instanceof JFrame)
		{
			JFrame swingFrame = (JFrame) frame;
			JRootPane root = swingFrame.getRootPane();
			// RuneLite installs its toolbar as a menu bar only for custom chrome, and FlatLaf
			// marks the root pane when it replaces the native title bar.
			boolean active = swingFrame.getJMenuBar() != null || (root != null
				&& (Boolean.TRUE.equals(root.getClientProperty("JRootPane.useWindowDecorations"))
				|| root.getWindowDecorationStyle() != JRootPane.NONE));
			if (active)
			{
				return ChromeBlock.RESTART_PENDING;
			}
		}
		return ChromeBlock.NONE;
	}

	/**
	 * Fullscreen needs RuneLite's custom window chrome off. Offer to turn it off; the
	 * plugin records that it made the change so turning the plugin off can restore it.
	 */
	private void offerToDisableCustomChrome(boolean atStartup)
	{
		if (!enabled || errorDialogOpen)
		{
			return;
		}
		errorDialogOpen = true;
		String message = (atStartup ? "Fullscreen on startup is waiting for RuneLite's custom window chrome to be turned off."
			: "Fullscreen needs RuneLite's custom window chrome turned off.")
			+ "\nThe change applies after you restart RuneLite. Turning this plugin off turns custom chrome back on.";
		SwingUtilities.invokeLater(() ->
		{
			try
			{
				if (enabled && askToDisableCustomChrome(message) && enabled)
				{
					disableCustomChrome();
					showRestartReminder(CHROME_TURNED_OFF);
				}
			}
			finally
			{
				errorDialogOpen = false;
				if (enabled && frame != null && frame.isFocused())
				{
					clientUI.forceFocus();
				}
			}
		});
	}

	/** True when the user chose to turn custom chrome off. Overridden in tests. */
	boolean askToDisableCustomChrome(String message)
	{
		Object[] options = {TURN_OFF_CUSTOM_CHROME, "Cancel"};
		return JOptionPane.showOptionDialog(frame, message, "Improved Fullscreen", JOptionPane.DEFAULT_OPTION,
			JOptionPane.WARNING_MESSAGE, null, options, options[0]) == 0;
	}

	/** Overridden in tests. */
	void showRestartReminder(String message)
	{
		JOptionPane.showMessageDialog(frame, message, "Improved Fullscreen", JOptionPane.INFORMATION_MESSAGE);
	}

	void disableCustomChrome()
	{
		configManager.setConfiguration("improvedfullscreen", "customChromeDisabledByPlugin", true);
		configManager.setConfiguration("runelite", "uiEnableCustomChrome", false);
		log.info("Turned off RuneLite's custom window chrome for fullscreen; it applies after a restart");
	}

	/**
	 * Turning the plugin off (disable or uninstall) undoes the plugin's own change, but only
	 * while the setting is still off: a user who turned custom chrome on again is respected.
	 */
	private void restoreCustomChrome()
	{
		if (!config.customChromeDisabledByPlugin())
		{
			return;
		}
		configManager.setConfiguration("improvedfullscreen", "customChromeDisabledByPlugin", false);
		if (!runeLiteConfig().enableCustomChrome())
		{
			configManager.setConfiguration("runelite", "uiEnableCustomChrome", true);
			configManager.setConfiguration("improvedfullscreen", "customChromeRestoredSession", clientSession());
			log.info("Restored RuneLite's custom window chrome; it applies after a restart");
			// Shown once fullscreen has exited: leaving borderless recreates the window,
			// which would also close a dialog it owns.
			chromeRestoredNoticePending = true;
		}
	}

	/** RuneLite saves settings when it closes normally; ending its task can lose the change. */
	static final String CLOSE_NORMALLY = "When you restart, please close RuneLite with its X button (or Alt+F4) "
		+ "rather than ending it in Task Manager,\nso the setting is saved properly.";

	static final String CHROME_TURNED_OFF = "Custom window chrome is turned off. Restart RuneLite to use fullscreen.\n\n"
		+ CLOSE_NORMALLY;

	static final String CHROME_RESTORED = "Fullscreen was turned off, so RuneLite's custom window chrome has been "
		+ "turned back on.\nThe change applies after you restart RuneLite.\n\n" + CLOSE_NORMALLY;

	private void showPendingChromeRestoredNotice()
	{
		if (!chromeRestoredNoticePending)
		{
			return;
		}
		chromeRestoredNoticePending = false;
		if (!enabled)
		{
			// Re-enabled before this point: the restore has already been taken back.
			SwingUtilities.invokeLater(() -> showChromeRestoredNotice(CHROME_RESTORED));
		}
	}

	/** Overridden in tests. */
	void showChromeRestoredNotice(String message)
	{
		Frame owner = frame != null && frame.isDisplayable() ? frame : findClientFrame();
		JOptionPane.showMessageDialog(owner, message, "Improved Fullscreen", JOptionPane.INFORMATION_MESSAGE);
	}

	/**
	 * Turning the plugin off and on again without restarting RuneLite: the restore never
	 * took effect, so put the plugin's own "off" back silently instead of asking again.
	 * After a restart the restored chrome is live, and the normal offer applies.
	 */
	private void reclaimCustomChromeRestoredThisSession()
	{
		long restoredIn = config.customChromeRestoredSession();
		if (restoredIn == 0)
		{
			return;
		}
		configManager.setConfiguration("improvedfullscreen", "customChromeRestoredSession", 0L);
		if (restoredIn == clientSession() && runeLiteConfig().enableCustomChrome())
		{
			disableCustomChrome();
		}
	}

	/** Identifies this RuneLite run: the JVM start time. Overridden in tests. */
	long clientSession()
	{
		return ManagementFactory.getRuntimeMXBean().getStartTime();
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if ("runelite".equals(event.getGroup()) && "uiEnableCustomChrome".equals(event.getKey())
			&& "true".equals(event.getNewValue()) && config.customChromeDisabledByPlugin())
		{
			// The user turned custom chrome on again themselves; never undo their choice later.
			configManager.setConfiguration("improvedfullscreen", "customChromeDisabledByPlugin", false);
			return;
		}
		if ("runelite".equals(event.getGroup()) && !"clientBounds".equals(event.getKey())
			&& !"clientMaximized".equals(event.getKey()))
		{
			// ClientUI re-applies "Always on top" for any of its settings, and "Lock window
			// size" via Frame.setResizable, which re-adds a native resize border. Both run in
			// an invokeLater from its own ConfigChanged handler; queue behind that.
			boolean resizeLock = "lockWindowSize".equals(event.getKey());
			SwingUtilities.invokeLater(() -> SwingUtilities.invokeLater(() ->
			{
				if (enabled && !closing && window != null && controller != null && controller.isActive()
					&& !controller.isTransitioning())
				{
					try
					{
						window.windowSettingsChanged(resizeLock);
					}
					catch (RuntimeException | LinkageError ex)
					{
						log.warn("Unable to keep fullscreen decorations after a RuneLite window setting changed", ex);
					}
				}
			}));
			return;
		}
		if ("improvedfullscreen".equals(event.getGroup()) && "fullscreenMode".equals(event.getKey()) && updatingModeSelection)
		{
			return;
		}
		boolean windowKey = "fullscreenMode".equals(event.getKey()) || "monitorTarget".equals(event.getKey())
			|| "preferredMonitor".equals(event.getKey());
		if ("improvedfullscreen".equals(event.getGroup()) && windowKey && currentProfileId() != sessionProfile)
		{
			// ConfigManager.switchProfile posts the new profile's differing values before
			// ProfileChanged. Those are saved preferences, not requests to change the window.
			log.debug("Ignoring {} from profile switch", event.getKey());
			return;
		}
		boolean modeChanged = "fullscreenMode".equals(event.getKey());
		boolean monitorChanged = "monitorTarget".equals(event.getKey()) || "preferredMonitor".equals(event.getKey());
		if ("improvedfullscreen".equals(event.getGroup()) && (modeChanged || monitorChanged))
		{
			onEdt(() ->
			{
				if (enabled && !closing && controller != null && !updatingMonitorSelection)
				{
					if (monitorChanged)
					{
						beginRequest();
						controller.changeMonitor();
					}
					else
					{
						Mode requested = config.fullscreenMode();
						if (requested != Mode.WINDOWED && !controller.isActive() && !canEnter())
						{
							syncModeSelection();
							return;
						}
						beginRequest();
						controller.changeMode(requested);
					}
				}
			});
		}
	}

	@Subscribe
	public void onProfileChanged(ProfileChanged event)
	{
		sessionProfile = currentProfileId();
	}

	private void refreshControls()
	{
		if (button != null)
		{
			clientToolbar.removeNavigation(button);
			button = null;
		}
		if (exitButton != null)
		{
			clientToolbar.removeNavigation(exitButton);
			exitButton = null;
		}
		if (controller == null)
		{
			return;
		}
		if (closing && enabled && retryCloseOnSettle && !controller.isTransitioning() && controller.isActive())
		{
			retryCloseOnSettle = false;
			controller.close();
			return;
		}
		if (closing && !controller.isTransitioning() && !controller.isActive())
		{
			window.detach();
			controller = null;
			window = null;
			showPendingChromeRestoredNotice();
			if (enabled)
			{
				initialize();
			}
			return;
		}
		if (!enabled || closing || controller.isTransitioning())
		{
			return;
		}
		boolean active = controller.isActive();
		FullscreenController session = controller;
		button = NavigationButton.builder()
			.tooltip((active ? "Disable fullscreen" : "Enable fullscreen") + " (right-click to choose monitor)")
			.popup(Collections.singletonMap("Choose fullscreen monitor", () -> onEdt(() -> chooseMonitor(session))))
			.icon(ImageUtil.loadImageResource(getClass(), active ? "fullscreen_off.png" : "fullscreen_on.png"))
			.onClick(() -> onEdt(() ->
			{
				if (controller == session)
				{
					toggle();
				}
			}))
			.build();
		clientToolbar.addNavigation(button);
		if (active)
		{
			exitButton = NavigationButton.builder()
				.tooltip(EXIT_TOOLTIP)
				.icon(exitIcon())
				.priority(EXIT_PRIORITY)
				.onClick(() -> onEdt(this::requestClientClose))
				.build();
			clientToolbar.addNavigation(exitButton);
		}
	}

	/**
	 * Same as clicking the title bar's X: RuneLite's own close handling runs, including
	 * its exit confirmation and window-bounds save.
	 */
	void requestClientClose()
	{
		Frame target = frame != null ? frame : findClientFrame();
		if (target != null && target.isDisplayable())
		{
			target.dispatchEvent(new WindowEvent(target, WindowEvent.WINDOW_CLOSING));
		}
	}

	static BufferedImage exitIcon()
	{
		BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = image.createGraphics();
		try
		{
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			g.setColor(new Color(0xC8C8C8));
			// RuneLite scales toolbar icons to 16x16; use nearly all of it.
			g.setStroke(new BasicStroke(2.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
			g.draw(new Line2D.Float(2.5f, 2.5f, 13.5f, 13.5f));
			g.draw(new Line2D.Float(13.5f, 2.5f, 2.5f, 13.5f));
		}
		finally
		{
			g.dispose();
		}
		return image;
	}

	private void chooseMonitor(FullscreenController session)
	{
		if (!enabled || closing || controller != session || controller.isTransitioning())
		{
			return;
		}
		GraphicsEnvironment environment = GraphicsEnvironment.getLocalGraphicsEnvironment();
		List<MonitorSelector.Choice> choices = MonitorSelector.choices(
			environment.getScreenDevices(), environment.getDefaultScreenDevice());
		MonitorSelector.Choice current = choices.stream()
			.filter(choice -> choice.matches(config.monitorTarget(), config.preferredMonitor()))
			.findFirst().orElse(choices.get(0));
		Object answer = JOptionPane.showInputDialog(frame, "Enter fullscreen on:", "Fullscreen monitor",
			JOptionPane.QUESTION_MESSAGE, null, choices.toArray(), current);
		if (answer instanceof MonitorSelector.Choice && enabled && !closing && controller == session)
		{
			MonitorSelector.Choice choice = (MonitorSelector.Choice) answer;
			updatingMonitorSelection = true;
			try
			{
				configManager.setConfiguration("improvedfullscreen", "preferredMonitor", choice.deviceId);
				configManager.setConfiguration("improvedfullscreen", "monitorTarget", choice.target);
			}
			finally
			{
				updatingMonitorSelection = false;
			}
			// The user just answered RuneLite's own dialog.
			refocusAfterTransition = true;
			controller.changeMonitor();
		}
		if (enabled && controller == session)
		{
			clientUI.forceFocus();
		}
	}

	private void report(Exception ex)
	{
		if (startupRecovery != null && !startupSettled)
		{
			// Only failures of the automatic entry itself disable it. Once fullscreen has
			// settled, the marker keeps watching for native crashes, but an unrelated error
			// (for example a manual mode change) must not turn startup entry off.
			finishStartup(true);
			configManager.setConfiguration("improvedfullscreen", "fullscreenOnStartup", false);
			ex = new IllegalStateException(ex.getMessage() + "\nFullscreen on startup has been turned off.", ex);
		}
		log.warn("Unable to change fullscreen mode", ex);
		Exception failure = ex;
		if (enabled && !errorDialogOpen)
		{
			errorDialogOpen = true;
			SwingUtilities.invokeLater(() ->
			{
				try
				{
					if (enabled)
					{
						JOptionPane.showMessageDialog(frame, failure.getMessage(),
							"Unable to change fullscreen mode", JOptionPane.ERROR_MESSAGE);
					}
				}
				finally
				{
					errorDialogOpen = false;
					if (enabled)
					{
						clientUI.forceFocus();
					}
				}
			});
		}
	}

	static Frame findClientFrame()
	{
		Frame result = null;
		for (Frame candidate : Frame.getFrames())
		{
			if (candidate instanceof ContainableFrame && candidate.isDisplayable())
			{
				if (candidate.isActive())
				{
					return candidate;
				}
				result = candidate;
			}
		}
		return result;
	}

	/**
	 * RuneLite exits without stopping plugins. Its window-bounds save has already run
	 * and recorded the fullscreen rectangle as the normal window; replace that with
	 * the placement fullscreen will restore, before ConfigManager flushes (priority).
	 */
	@Subscribe(priority = 100)
	public void onClientShutdown(ClientShutdown event)
	{
		Runnable cleanup = () ->
		{
			// A clean exit during the startup grace period is not an interrupted startup.
			finishStartup(true);
			FullscreenWindow.Placement placement = window == null || controller == null || !controller.isActive()
				? null : window.restorePlacement();
			if (placement != null)
			{
				configManager.setConfiguration("runelite", "clientBounds", placement.clientBoundsConfig());
				if (placement.maximized)
				{
					configManager.setConfiguration("runelite", "clientMaximized", true);
				}
				else
				{
					configManager.unsetConfiguration("runelite", "clientMaximized");
				}
			}
		};
		if (SwingUtilities.isEventDispatchThread())
		{
			cleanup.run();
		}
		else
		{
			// ClientUI posts this from the EDT; anything else is best effort.
			SwingUtilities.invokeLater(cleanup);
		}
	}

	@Override
	protected void shutDown()
	{
		onEdt(() ->
		{
			enabled = false;
			closing = true;
			retryCloseOnSettle = false;
			stopWaitingForWindow();
			finishStartup(true);
			restoreCustomChrome();
			if (hotkeyListener != null)
			{
				keyManager.unregisterKeyListener(hotkeyListener);
				hotkeyListener = null;
			}
			if (controller != null)
			{
				controller.close();
			}
			refreshControls();
			if (controller == null)
			{
				// Already windowed, or the window was never found.
				showPendingChromeRestoredNotice();
			}
		});
	}

	private static void onEdt(Runnable action)
	{
		if (SwingUtilities.isEventDispatchThread())
		{
			action.run();
		}
		else
		{
			SwingUtilities.invokeLater(action);
		}
	}

	@Provides
	ImprovedFullscreenConfig provideConfig(ConfigManager manager)
	{
		return manager.getConfig(ImprovedFullscreenConfig.class);
	}
}
