package dekvall.fullscreen;

import java.awt.Canvas;
import java.awt.Rectangle;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.awt.event.KeyEvent;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import net.runelite.api.Client;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.config.ConfigProfile;
import net.runelite.client.config.Keybind;
import net.runelite.client.config.RuneLiteConfig;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.input.KeyManager;
import net.runelite.client.plugins.PluginManager;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.ClientUI;
import net.runelite.client.ui.ContainableFrame;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.util.HotkeyListener;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/** Plugin-level regressions driven through the real controller and config events. */
public class FullscreenPluginBehaviorTest
{
	private ConfigManager manager;
	private FullscreenConfig config;
	private ClientUI clientUI;
	private ClientToolbar toolbar;
	private java.nio.file.Path markerDirectory;
	private final List<Runnable> clientThreadTasks = new ArrayList<>();
	private ContainableFrame frame;
	private FullscreenController.Window window;
	private FullscreenController controller;
	private FullscreenPlugin plugin;
	private final AtomicReference<Mode> selected = new AtomicReference<>(Mode.WINDOWED);
	private final AtomicReference<Mode> last = new AtomicReference<>(Mode.BORDERLESS);
	private final AtomicReference<ConfigProfile> profile = new AtomicReference<>();
	private final List<Exception> errors = new ArrayList<>();
	private boolean focused = true;
	private final java.util.concurrent.atomic.AtomicBoolean customChrome = new java.util.concurrent.atomic.AtomicBoolean(false);
	private final java.util.concurrent.atomic.AtomicBoolean disabledByPlugin = new java.util.concurrent.atomic.AtomicBoolean(false);
	private final java.util.concurrent.atomic.AtomicBoolean acceptChromeOffer = new java.util.concurrent.atomic.AtomicBoolean(false);
	private final List<String> chromeOffers = new ArrayList<>();
	private final List<String> reminders = new ArrayList<>();
	private final List<String> chromeNotices = new ArrayList<>();
	private final List<Boolean> noticeShownWhileFullscreen = new ArrayList<>();
	private final java.util.concurrent.atomic.AtomicInteger exitCalls = new java.util.concurrent.atomic.AtomicInteger();

	@Before
	public void setUp() throws Exception
	{
		manager = mock(ConfigManager.class);
		config = mock(FullscreenConfig.class);
		clientUI = mock(ClientUI.class);
		frame = mock(ContainableFrame.class);
		window = mock(FullscreenController.Window.class);
		when(frame.isDisplayable()).thenReturn(true);
		when(frame.isFocused()).thenAnswer(call -> focused);
		when(frame.getBounds()).thenReturn(new Rectangle(100, 100, 800, 600));
		when(config.fullscreenMode()).thenAnswer(call -> selected.get());
		when(config.lastFullscreenMode()).thenAnswer(call -> last.get());
		when(config.monitorTarget()).thenReturn(MonitorTarget.CURRENT);
		when(config.preferredMonitor()).thenReturn("");
		RuneLiteConfig runelite = mock(RuneLiteConfig.class);
		when(runelite.enableCustomChrome()).thenAnswer(call -> customChrome.get());
		when(manager.getConfig(RuneLiteConfig.class)).thenReturn(runelite);
		when(config.customChromeDisabledByPlugin()).thenAnswer(call -> disabledByPlugin.get());
		doAnswer(call ->
		{
			customChrome.set(call.getArgument(2));
			return null;
		}).when(manager).setConfiguration(eq("runelite"), eq("uiEnableCustomChrome"), any(Object.class));
		profile.set(profile(1));
		when(manager.getProfile()).thenAnswer(call -> profile.get());

		markerDirectory = java.nio.file.Files.createTempDirectory("fullscreen-startup");
		java.nio.file.Path startupDirectory = markerDirectory;
		plugin = new FullscreenPlugin()
		{
			@Override
			StartupRecovery createStartupRecovery()
			{
				return new StartupRecovery(net.runelite.client.util.Filepath.Unchecked.getRooted(startupDirectory));
			}

			@Override
			boolean askToDisableCustomChrome(String message)
			{
				chromeOffers.add(message);
				return acceptChromeOffer.get();
			}

			@Override
			void showRestartReminder(String message)
			{
				reminders.add(message);
			}

			@Override
			void showChromeRestoredNotice(String message)
			{
				chromeNotices.add(message);
				noticeShownWhileFullscreen.add(exitCalls.get() == 0);
			}
		};
		inject("client", mock(Client.class));
		inject("clientUI", clientUI);
		inject("configManager", manager);
		inject("keyManager", mock(KeyManager.class));
		inject("config", config);
		toolbar = mock(ClientToolbar.class);
		inject("clientToolbar", toolbar);
		ClientThread clientThread = mock(ClientThread.class);
		doAnswer(call ->
		{
			clientThreadTasks.add(call.getArgument(0));
			return null;
		}).when(clientThread).invokeLater(any(Runnable.class));
		inject("clientThread", clientThread);
		inject("pluginManager", mock(PluginManager.class));
		inject("frame", frame);
		inject("enabled", true);
		injectIfPresent("sessionProfile", 1L);

		Method changed = FullscreenPlugin.class.getDeclaredMethod("transitionChanged");
		changed.setAccessible(true);
		controller = new FullscreenController(window, mock(FullscreenController.Renderers.class), () ->
		{
			try
			{
				changed.invoke(plugin);
			}
			catch (ReflectiveOperationException ex)
			{
				throw new AssertionError(ex);
			}
		}, errors::add);
		inject("controller", controller);

		doAnswer(call ->
		{
			String key = call.getArgument(1);
			Object value = call.getArgument(2);
			if ("fullscreenMode".equals(key))
			{
				selected.set((Mode) value);
			}
			if ("lastFullscreenMode".equals(key))
			{
				last.set((Mode) value);
			}
			if ("customChromeDisabledByPlugin".equals(key))
			{
				disabledByPlugin.set((Boolean) value);
			}
			plugin.onConfigChanged(event(key));
			return null;
		}).when(manager).setConfiguration(eq("Fullscreen"), anyString(), any(Object.class));
	}

	@Test
	public void exitButtonIsShownOnTopOnlyWhileFullscreen() throws Exception
	{
		FullscreenControllerTest.edt(() -> manager.setConfiguration("Fullscreen", "fullscreenMode", Mode.BORDERLESS));
		NavigationButton exit = exitButton();
		assertNotNull("Fullscreen shows an exit button", exit);
		assertTrue("Above Configuration (priority 0)", exit.getPriority() < 0);
		BufferedImage icon = exit.getIcon();
		assertEquals(16, icon.getWidth());
		assertNotEquals("The X is drawn", 0, icon.getRGB(8, 8) >>> 24);

		FullscreenControllerTest.edt(() -> manager.setConfiguration("Fullscreen", "fullscreenMode", Mode.WINDOWED));
		assertFalse(controller.isActive());
		verify(toolbar).removeNavigation(exit);
		assertEquals("Windowed has its own close button", 1, addedExitButtons());
	}

	@Test
	public void exitButtonClosesThroughRuneLitesOwnCloseHandling() throws Exception
	{
		FullscreenControllerTest.edt(() -> manager.setConfiguration("Fullscreen", "fullscreenMode", Mode.BORDERLESS));
		NavigationButton exit = exitButton();
		FullscreenControllerTest.edt(() -> exit.getOnClick().run());
		FullscreenControllerTest.edt(() -> {});
		ArgumentCaptor<java.awt.AWTEvent> events = ArgumentCaptor.forClass(java.awt.AWTEvent.class);
		verify(frame).dispatchEvent(events.capture());
		assertEquals(WindowEvent.WINDOW_CLOSING, events.getValue().getID());
		assertSame(frame, events.getValue().getSource());
	}

	private NavigationButton exitButton()
	{
		ArgumentCaptor<NavigationButton> added = ArgumentCaptor.forClass(NavigationButton.class);
		verify(toolbar, atLeastOnce()).addNavigation(added.capture());
		NavigationButton result = null;
		for (NavigationButton button : added.getAllValues())
		{
			if (FullscreenPlugin.EXIT_TOOLTIP.equals(button.getTooltip()))
			{
				result = button;
			}
		}
		return result;
	}

	private long addedExitButtons()
	{
		ArgumentCaptor<NavigationButton> added = ArgumentCaptor.forClass(NavigationButton.class);
		verify(toolbar, atLeastOnce()).addNavigation(added.capture());
		return added.getAllValues().stream().filter(b -> FullscreenPlugin.EXIT_TOOLTIP.equals(b.getTooltip())).count();
	}

	@Test
	public void everyManualChangeIsGuardedByTheCrashMarker() throws Exception
	{
		List<Boolean> markedDuringWindowChange = new ArrayList<>();
		doAnswer(call -> markedDuringWindowChange.add(marked())).when(window).enter(any());
		doAnswer(call -> markedDuringWindowChange.add(marked())).when(window).exit();
		inject("window", mock(FullscreenWindow.class));
		assertFalse(marked());

		FullscreenControllerTest.edt(() -> manager.setConfiguration("Fullscreen", "fullscreenMode", Mode.BORDERLESS));
		assertTrue(controller.isActive());
		assertTrue("Still guarded right after settling: renderers may still be restarting", marked());
		settleGuard();
		assertFalse("Cleared once the change settled and the game responded", marked());

		FullscreenControllerTest.edt(() -> manager.setConfiguration("Fullscreen", "fullscreenMode", Mode.WINDOWED));
		assertFalse(controller.isActive());
		assertTrue("Leaving fullscreen restarts renderers too", marked());
		settleGuard();
		assertFalse(marked());
		assertEquals("Marked before entering and before leaving", java.util.Arrays.asList(true, true),
			markedDuringWindowChange);
	}

	@Test
	public void failedManualChangeKeepsFullscreenOnStartup() throws Exception
	{
		doThrow(new IllegalStateException("Monitor unavailable")).when(window).enter(any());
		inject("window", mock(FullscreenWindow.class));
		FullscreenControllerTest.edt(() ->
		{
			manager.setConfiguration("Fullscreen", "fullscreenMode", Mode.BORDERLESS);
			plugin.shutDown(); // suppress the queued headless dialog
		});
		assertFalse(controller.isActive());
		verify(manager, never()).setConfiguration("Fullscreen", "fullscreenOnStartup", false);
		assertFalse("Turning the plugin off clears the marker", marked());
	}

	@Test
	public void manualChangeProceedsWhileAnotherClientHoldsTheMarker() throws Exception
	{
		StartupRecovery other = new StartupRecovery(net.runelite.client.util.Filepath.Unchecked.getRooted(markerDirectory));
		assertEquals(StartupRecovery.Result.READY, other.begin());
		try
		{
			inject("window", mock(FullscreenWindow.class));
			FullscreenControllerTest.edt(() -> manager.setConfiguration("Fullscreen", "fullscreenMode", Mode.BORDERLESS));
			assertTrue(controller.isActive());
		}
		finally
		{
			other.finish(true);
		}
	}

	private boolean marked()
	{
		return java.nio.file.Files.exists(markerDirectory.resolve("startup.pending"));
	}

	/** Fires the 10 s grace timer, then answers its game-thread check. */
	private void settleGuard() throws Exception
	{
		FullscreenControllerTest.edt(() ->
		{
			try
			{
				java.lang.reflect.Field field = FullscreenPlugin.class.getDeclaredField("startupTimer");
				field.setAccessible(true);
				javax.swing.Timer timer = (javax.swing.Timer) field.get(plugin);
				assertNotNull("The grace timer is running", timer);
				timer.stop();
				timer.getActionListeners()[0].actionPerformed(new java.awt.event.ActionEvent(timer, 0, "elapsed"));
			}
			catch (ReflectiveOperationException ex)
			{
				throw new AssertionError(ex);
			}
		});
		List<Runnable> tasks = new ArrayList<>(clientThreadTasks);
		clientThreadTasks.clear();
		tasks.forEach(Runnable::run);
		FullscreenControllerTest.edt(() -> {});
	}

	@Test
	public void profileSwitchDoesNotApplyAnotherProfilesStaleMode() throws Exception
	{
		FullscreenControllerTest.edt(() ->
		{
			manager.setConfiguration("Fullscreen", "fullscreenMode", Mode.BORDERLESS);
			assertTrue(controller.isActive());
			// ConfigManager.switchProfile swaps the profile, then posts ConfigChanged for
			// every differing key before ProfileChanged.
			profile.set(profile(2));
			selected.set(Mode.WINDOWED);
			plugin.onConfigChanged(event("fullscreenMode"));
			plugin.onConfigChanged(event("monitorTarget"));
		});
		assertTrue("Switching profiles must not exit fullscreen", controller.isActive());
		verify(window, never()).exit();
		verify(window, times(1)).enter(any());
	}

	@Test
	public void monitorChangeUsesActiveModeNotStaleDropdown() throws Exception
	{
		FullscreenControllerTest.edt(() ->
		{
			manager.setConfiguration("Fullscreen", "fullscreenMode", Mode.BORDERLESS);
			selected.set(Mode.WINDOWED); // dropdown out of sync with the live state
			plugin.onConfigChanged(event("monitorTarget"));
		});
		assertTrue("A monitor change must not exit fullscreen", controller.isActive());
		assertEquals(Mode.BORDERLESS, controller.currentMode());
	}

	@Test
	public void transitionsDoNotStealForegroundFromAnotherApplication() throws Exception
	{
		focused = false;
		FullscreenControllerTest.edt(() -> manager.setConfiguration("Fullscreen", "fullscreenMode", Mode.BORDERLESS));
		assertTrue(controller.isActive());
		verify(clientUI, never()).forceFocus();
	}

	@Test
	public void focusedTransitionReturnsFocusToGameOnce() throws Exception
	{
		FullscreenControllerTest.edt(() -> manager.setConfiguration("Fullscreen", "fullscreenMode", Mode.BORDERLESS));
		verify(clientUI, times(1)).forceFocus();
	}

	@Test
	public void rejectedModeDoesNotBecomeTheHotkeyMode() throws Exception
	{
		doThrow(new IllegalStateException("Exclusive fullscreen is unavailable")).when(window).validate(Mode.EXCLUSIVE);
		FullscreenControllerTest.edt(() -> manager.setConfiguration("Fullscreen", "fullscreenMode", Mode.EXCLUSIVE));
		assertFalse(controller.isActive());
		assertEquals(Mode.WINDOWED, selected.get());
		assertEquals("A rejected mode must not replace the last working fullscreen mode", Mode.BORDERLESS, last.get());
		FullscreenControllerTest.edt(() -> invoke("toggle"));
		verify(window).enter(Mode.BORDERLESS);
		assertEquals(Mode.BORDERLESS, selected.get());
	}

	@Test
	public void successfulModeBecomesTheHotkeyMode() throws Exception
	{
		FullscreenControllerTest.edt(() ->
		{
			manager.setConfiguration("Fullscreen", "fullscreenMode", Mode.EXCLUSIVE);
			manager.setConfiguration("Fullscreen", "fullscreenMode", Mode.WINDOWED);
			invoke("toggle");
		});
		assertEquals(Mode.EXCLUSIVE, last.get());
		assertEquals(Mode.EXCLUSIVE, controller.currentMode());
	}

	@Test
	public void hotkeyWithMissedReleaseDoesNotSwallowLaterTyping() throws Exception
	{
		Canvas canvas = new Canvas();
		FullscreenControllerTest.edt(() ->
		{
			HotkeyListener listener = FullscreenPlugin.createHotkeyListener(() -> new Keybind(KeyEvent.VK_F11, 0), () -> {});
			listener.keyPressed(key(canvas, KeyEvent.KEY_PRESSED, KeyEvent.VK_F11));
			// The F11 release went to another window or listener and was never delivered.
			listener.keyPressed(key(canvas, KeyEvent.KEY_PRESSED, KeyEvent.VK_A));
			KeyEvent typed = key(canvas, KeyEvent.KEY_TYPED, KeyEvent.VK_UNDEFINED);
			listener.keyTyped(typed);
			assertFalse("Chat and bank search must receive the next typed character", typed.isConsumed());
		});
	}

	@Test
	public void typingWhileHotkeyHeldDoesNotRetriggerOnAutoRepeat() throws Exception
	{
		Canvas canvas = new Canvas();
		java.util.concurrent.atomic.AtomicInteger toggles = new java.util.concurrent.atomic.AtomicInteger();
		HotkeyListener listener = FullscreenPlugin.createHotkeyListener(
			() -> new Keybind(KeyEvent.VK_F11, 0), toggles::incrementAndGet);
		FullscreenControllerTest.edt(() ->
		{
			listener.keyPressed(key(canvas, KeyEvent.KEY_PRESSED, KeyEvent.VK_F11));
			listener.keyPressed(key(canvas, KeyEvent.KEY_PRESSED, KeyEvent.VK_A));
			KeyEvent typed = key(canvas, KeyEvent.KEY_TYPED, KeyEvent.VK_UNDEFINED);
			listener.keyTyped(typed);
			assertFalse(typed.isConsumed());
			listener.keyPressed(key(canvas, KeyEvent.KEY_PRESSED, KeyEvent.VK_F11)); // auto-repeat
		});
		FullscreenControllerTest.edt(() -> {});
		assertEquals("Auto-repeat of a held hotkey must not toggle again", 1, toggles.get());
	}

	@Test
	public void heldModifierDoesNotClearHotkeyTypedSuppression() throws Exception
	{
		Canvas canvas = new Canvas();
		FullscreenControllerTest.edt(() ->
		{
			HotkeyListener listener = FullscreenPlugin.createHotkeyListener(() -> new Keybind(KeyEvent.VK_F, 0), () -> {});
			listener.keyPressed(key(canvas, KeyEvent.KEY_PRESSED, KeyEvent.VK_F));
			listener.keyPressed(key(canvas, KeyEvent.KEY_PRESSED, KeyEvent.VK_SHIFT));
			KeyEvent typed = key(canvas, KeyEvent.KEY_TYPED, KeyEvent.VK_UNDEFINED);
			listener.keyTyped(typed);
			assertTrue("The hotkey's own character is still suppressed", typed.isConsumed());
		});
	}

	@Test
	public void clientExitWhileFullscreenSavesWindowedPlacementAndClearsStartupMarker() throws Exception
	{
		java.nio.file.Path directory = java.nio.file.Files.createTempDirectory("fullscreen-exit");
		StartupRecovery recovery = new StartupRecovery(net.runelite.client.util.Filepath.Unchecked.getRooted(directory));
		assertEquals(StartupRecovery.Result.READY, recovery.begin());
		FullscreenWindow fullscreenWindow = mock(FullscreenWindow.class);
		when(fullscreenWindow.restorePlacement()).thenReturn(
			new FullscreenWindow.Placement(new Rectangle(-1700, 111, 884, 661), true));
		inject("window", fullscreenWindow);
		FullscreenControllerTest.edt(() -> manager.setConfiguration("Fullscreen", "fullscreenMode", Mode.BORDERLESS));
		inject("startupRecovery", recovery);
		FullscreenControllerTest.edt(() -> plugin.onClientShutdown(new net.runelite.client.events.ClientShutdown()));
		verify(manager).setConfiguration("runelite", "clientBounds", "-1700:111:884:661:c");
		verify(manager).setConfiguration("runelite", "clientMaximized", true);
		assertFalse("A clean exit is not an interrupted startup",
			java.nio.file.Files.exists(directory.resolve("startup.pending")));
		StartupRecovery next = new StartupRecovery(net.runelite.client.util.Filepath.Unchecked.getRooted(directory));
		assertEquals(StartupRecovery.Result.READY, next.begin());
		next.finish(true);
	}

	@Test
	public void clientExitWhileWindowedLeavesRuneLiteBoundsAlone() throws Exception
	{
		inject("window", mock(FullscreenWindow.class));
		FullscreenControllerTest.edt(() -> plugin.onClientShutdown(new net.runelite.client.events.ClientShutdown()));
		verify(manager, never()).setConfiguration(eq("runelite"), anyString(), any(Object.class));
		verify(manager, never()).unsetConfiguration(eq("runelite"), anyString());
	}

	@Test
	public void reenablingDuringShutdownExitRetriesAfterRendererTimeout() throws Exception
	{
		List<Runnable> ready = new ArrayList<>();
		List<Consumer<Exception>> failed = new ArrayList<>();
		FullscreenController.Renderers renderers = new FullscreenController.Renderers()
		{
			@Override
			public void suspend(Runnable onReady, Consumer<Exception> onFailed)
			{
				ready.add(onReady);
				failed.add(onFailed);
			}

			@Override
			public void resume()
			{
			}
		};
		when(window.needsRendererRestart(any())).thenReturn(true);
		Method changed = FullscreenPlugin.class.getDeclaredMethod("transitionChanged");
		changed.setAccessible(true);
		controller = new FullscreenController(window, renderers, () ->
		{
			try
			{
				changed.invoke(plugin);
			}
			catch (ReflectiveOperationException ex)
			{
				throw new AssertionError(ex);
			}
		}, errors::add);
		inject("controller", controller);
		inject("window", mock(FullscreenWindow.class));
		FullscreenControllerTest.edt(() ->
		{
			manager.setConfiguration("Fullscreen", "fullscreenMode", Mode.EXCLUSIVE);
			ready.get(0).run();
			assertTrue(controller.isActive());
			plugin.shutDown();
			plugin.startUp();
			failed.get(1).accept(new IllegalStateException("The GPU renderer did not stop"));
			assertEquals("Re-enabling must retry the failed shutdown exit", 3, ready.size());
			ready.get(2).run();
			assertFalse(controller.isActive());
			plugin.shutDown(); // also suppresses the queued headless error dialog
		});
		verify(window).exit();
	}

	@Test
	public void customChromeIsDetectedFromSettingAndRunningWindow()
	{
		assertEquals(FullscreenPlugin.ChromeBlock.SETTING_ON, FullscreenPlugin.customChromeBlock(frame, true));
		assertEquals(FullscreenPlugin.ChromeBlock.NONE, FullscreenPlugin.customChromeBlock(frame, false));
		when(frame.getJMenuBar()).thenReturn(mock(javax.swing.JMenuBar.class));
		assertEquals(FullscreenPlugin.ChromeBlock.RESTART_PENDING, FullscreenPlugin.customChromeBlock(frame, false));
		when(frame.getJMenuBar()).thenReturn(null);
		javax.swing.JRootPane root = mock(javax.swing.JRootPane.class);
		when(frame.getRootPane()).thenReturn(root);
		when(root.getClientProperty("JRootPane.useWindowDecorations")).thenReturn(Boolean.TRUE);
		assertEquals(FullscreenPlugin.ChromeBlock.RESTART_PENDING, FullscreenPlugin.customChromeBlock(frame, false));
		when(root.getClientProperty("JRootPane.useWindowDecorations")).thenReturn(null);
		when(root.getWindowDecorationStyle()).thenReturn(javax.swing.JRootPane.FRAME);
		assertEquals(FullscreenPlugin.ChromeBlock.RESTART_PENDING, FullscreenPlugin.customChromeBlock(frame, false));
		when(root.getWindowDecorationStyle()).thenReturn(javax.swing.JRootPane.NONE);
		assertEquals(FullscreenPlugin.ChromeBlock.NONE, FullscreenPlugin.customChromeBlock(frame, false));
	}

	@Test
	public void customChromeStillActiveAfterSettingChangeBlocksEntry() throws Exception
	{
		inject("window", mock(FullscreenWindow.class));
		when(frame.getJMenuBar()).thenReturn(mock(javax.swing.JMenuBar.class));
		FullscreenControllerTest.edt(() ->
		{
			manager.setConfiguration("Fullscreen", "fullscreenMode", Mode.BORDERLESS);
			plugin.shutDown(); // suppress the queued headless error dialog
		});
		verify(window, never()).enter(any());
		assertEquals(Mode.WINDOWED, selected.get());
	}

	@Test
	public void errorAfterAutomaticEntrySettledKeepsFullscreenOnStartup() throws Exception
	{
		java.nio.file.Path directory = java.nio.file.Files.createTempDirectory("fullscreen-settled");
		StartupRecovery recovery = new StartupRecovery(net.runelite.client.util.Filepath.Unchecked.getRooted(directory));
		assertEquals(StartupRecovery.Result.READY, recovery.begin());
		inject("window", mock(FullscreenWindow.class));
		inject("startupRecovery", recovery);
		injectIfPresent("startupSettled", false);
		FullscreenControllerTest.edt(() ->
		{
			invoke("toggle");
			assertTrue(controller.isActive());
			try
			{
				Method report = FullscreenPlugin.class.getDeclaredMethod("report", Exception.class);
				report.setAccessible(true);
				report.invoke(plugin, new IllegalStateException("Monitor change failed"));
			}
			catch (ReflectiveOperationException ex)
			{
				throw new AssertionError(ex);
			}
			plugin.shutDown(); // suppress the queued headless dialog
		});
		verify(manager, never()).setConfiguration("Fullscreen", "fullscreenOnStartup", false);
	}

	@Test
	public void runeLiteResizeLockChangeWhileFullscreenIsForwardedToWindow() throws Exception
	{
		FullscreenWindow fullscreenWindow = mock(FullscreenWindow.class);
		inject("window", fullscreenWindow);
		FullscreenControllerTest.edt(() -> manager.setConfiguration("Fullscreen", "fullscreenMode", Mode.BORDERLESS));
		ConfigChanged lock = new ConfigChanged();
		lock.setGroup("runelite");
		lock.setKey("lockWindowSize");
		ConfigChanged bounds = new ConfigChanged();
		bounds.setGroup("runelite");
		bounds.setKey("clientBounds");
		plugin.onConfigChanged(lock);
		plugin.onConfigChanged(bounds);
		FullscreenControllerTest.edt(() -> {});
		FullscreenControllerTest.edt(() -> {});
		verify(fullscreenWindow, times(1)).windowSettingsChanged(true);
		verify(fullscreenWindow, never()).windowSettingsChanged(false);
	}

	@Test
	public void startupRecoveryUsesThePluginHubDataDirectory()
	{
		net.runelite.client.plugins.PluginDescriptor descriptor =
			FullscreenPlugin.class.getAnnotation(net.runelite.client.plugins.PluginDescriptor.class);
		// getPluginDirectory() resolves .runelite/plugin-data/<internalName>; keeps the old marker location.
		assertEquals("fullscreen", descriptor.internalName());
	}

	@Test
	public void startupAfterManualWindowedEntersLastFullscreenMode() throws Exception
	{
		when(config.fullscreenOnStartup()).thenReturn(true);
		last.set(Mode.EXCLUSIVE);   // entered earlier, then the user switched to Windowed
		selected.set(Mode.WINDOWED);
		FullscreenControllerTest.edt(() -> invoke("attemptAutomaticStartup"));
		verify(window).enter(Mode.EXCLUSIVE);
		assertEquals(Mode.EXCLUSIVE, controller.currentMode());
		assertEquals("The dropdown follows the restored mode", Mode.EXCLUSIVE, selected.get());
		verify(manager, never()).setConfiguration("Fullscreen", "fullscreenOnStartup", false);
	}

	@Test
	public void startupWithSelectedFullscreenModeUsesIt() throws Exception
	{
		when(config.fullscreenOnStartup()).thenReturn(true);
		last.set(Mode.EXCLUSIVE);
		selected.set(Mode.BORDERLESS);
		FullscreenControllerTest.edt(() -> invoke("attemptAutomaticStartup"));
		verify(window).enter(Mode.BORDERLESS);
		assertEquals(Mode.BORDERLESS, last.get());
	}

	@Test
	public void blockedEntryOffersToTurnOffCustomChromeAndRecordsTheChange() throws Exception
	{
		customChrome.set(true);
		acceptChromeOffer.set(true);
		FullscreenControllerTest.edt(() -> manager.setConfiguration("Fullscreen", "fullscreenMode", Mode.BORDERLESS));
		FullscreenControllerTest.edt(() -> {}); // the offer is shown after the config event returns
		assertEquals(1, chromeOffers.size());
		assertTrue(chromeOffers.get(0).contains("applies after you restart RuneLite"));
		assertTrue("Asks for a normal close", reminders.get(0).contains("X button"));
		verify(manager).setConfiguration("runelite", "uiEnableCustomChrome", false);
		assertFalse(customChrome.get());
		assertTrue("The plugin records that it made the change", disabledByPlugin.get());
		assertEquals(1, reminders.size());
		verify(window, never()).enter(any());
		assertEquals(Mode.WINDOWED, selected.get());
	}

	@Test
	public void cancellingTheOfferChangesNothing() throws Exception
	{
		customChrome.set(true);
		FullscreenControllerTest.edt(() -> invoke("toggle"));
		FullscreenControllerTest.edt(() -> {});
		assertEquals(1, chromeOffers.size());
		verify(manager, never()).setConfiguration(eq("runelite"), anyString(), any(Object.class));
		assertFalse(disabledByPlugin.get());
		assertTrue(reminders.isEmpty());
		verify(window, never()).enter(any());
	}

	@Test
	public void customChromeWaitingForRestartIsExplainedWithoutAnOffer() throws Exception
	{
		inject("window", mock(FullscreenWindow.class));
		when(frame.getJMenuBar()).thenReturn(mock(javax.swing.JMenuBar.class));
		FullscreenControllerTest.edt(() ->
		{
			invoke("toggle");
			plugin.shutDown(); // suppress the queued headless error dialog
		});
		assertTrue(chromeOffers.isEmpty());
		verify(window, never()).enter(any());
	}

	@Test
	public void turningThePluginOffRestoresCustomChromeItTurnedOff() throws Exception
	{
		inject("window", mock(FullscreenWindow.class));
		disabledByPlugin.set(true);
		FullscreenControllerTest.edt(plugin::shutDown);
		verify(manager).setConfiguration("runelite", "uiEnableCustomChrome", true);
		assertTrue(customChrome.get());
		assertFalse(disabledByPlugin.get());
		FullscreenControllerTest.edt(() -> {});
		assertEquals("The restore is announced", 1, chromeNotices.size());
		assertTrue("Asks for a normal close", chromeNotices.get(0).contains("rather than ending it in Task Manager"));
	}

	@Test
	public void restoreNoticeWaitsUntilFullscreenHasExited() throws Exception
	{
		// Leaving borderless recreates the window, which closes any dialog it owns.
		doAnswer(call -> exitCalls.incrementAndGet()).when(window).exit();
		inject("window", mock(FullscreenWindow.class));
		// As in the client: GPU plugins release the window later, so exit is asynchronous.
		when(window.needsRendererRestart(any())).thenReturn(true);
		FullscreenController.Renderers gpu = mock(FullscreenController.Renderers.class);
		doAnswer(call ->
		{
			Runnable ready = call.getArgument(0);
			javax.swing.SwingUtilities.invokeLater(ready);
			return null;
		}).when(gpu).suspend(any(), any());
		Method changed = FullscreenPlugin.class.getDeclaredMethod("transitionChanged");
		changed.setAccessible(true);
		controller = new FullscreenController(window, gpu, () ->
		{
			try
			{
				changed.invoke(plugin);
			}
			catch (ReflectiveOperationException ex)
			{
				throw new AssertionError(ex);
			}
		}, errors::add);
		inject("controller", controller);
		FullscreenControllerTest.edt(() -> manager.setConfiguration("Fullscreen", "fullscreenMode", Mode.BORDERLESS));
		FullscreenControllerTest.edt(() -> {});
		assertTrue(controller.isActive());
		disabledByPlugin.set(true);
		FullscreenControllerTest.edt(plugin::shutDown);
		FullscreenControllerTest.edt(() -> {});
		FullscreenControllerTest.edt(() -> {});
		assertEquals(1, exitCalls.get());
		assertEquals(java.util.Collections.singletonList(false), noticeShownWhileFullscreen);
	}

	@Test
	public void customChromeTheUserTurnedBackOnIsNotTouched() throws Exception
	{
		inject("window", mock(FullscreenWindow.class));
		disabledByPlugin.set(true);
		customChrome.set(true);
		FullscreenControllerTest.edt(plugin::shutDown);
		verify(manager, never()).setConfiguration(eq("runelite"), anyString(), any(Object.class));
		assertFalse("Ownership is cleared", disabledByPlugin.get());
		FullscreenControllerTest.edt(() -> {});
		assertTrue("Nothing restored, nothing announced", chromeNotices.isEmpty());
	}

	@Test
	public void customChromeTheUserTurnedOffIsNeverRestored() throws Exception
	{
		inject("window", mock(FullscreenWindow.class));
		FullscreenControllerTest.edt(plugin::shutDown);
		verify(manager, never()).setConfiguration(eq("runelite"), anyString(), any(Object.class));
		assertFalse(customChrome.get());
		FullscreenControllerTest.edt(() -> {});
		assertTrue("Nothing restored, nothing announced", chromeNotices.isEmpty());
	}

	@Test
	public void userTurningCustomChromeOnAgainTakesOverTheSetting() throws Exception
	{
		disabledByPlugin.set(true);
		ConfigChanged changed = new ConfigChanged();
		changed.setGroup("runelite");
		changed.setKey("uiEnableCustomChrome");
		changed.setNewValue("true");
		plugin.onConfigChanged(changed);
		assertFalse(disabledByPlugin.get());
		// A later manual change to off is the user's own, so turning the plugin off keeps it.
		customChrome.set(false);
		inject("window", mock(FullscreenWindow.class));
		FullscreenControllerTest.edt(plugin::shutDown);
		verify(manager, never()).setConfiguration(eq("runelite"), anyString(), any(Object.class));
	}

	static KeyEvent key(Canvas canvas, int id, int code)
	{
		return new KeyEvent(canvas, id, System.currentTimeMillis(), 0, code,
			id == KeyEvent.KEY_TYPED ? 'a' : KeyEvent.CHAR_UNDEFINED)
		{
			@Override
			public int getExtendedKeyCode()
			{
				return code;
			}
		};
	}

	private static ConfigChanged event(String key)
	{
		ConfigChanged event = new ConfigChanged();
		event.setGroup("Fullscreen");
		event.setKey(key);
		return event;
	}

	private static ConfigProfile profile(long id)
	{
		ConfigProfile profile = mock(ConfigProfile.class);
		when(profile.getId()).thenReturn(id);
		return profile;
	}

	private void invoke(String name)
	{
		try
		{
			Method method = FullscreenPlugin.class.getDeclaredMethod(name);
			method.setAccessible(true);
			method.invoke(plugin);
		}
		catch (ReflectiveOperationException ex)
		{
			throw new AssertionError(ex);
		}
	}

	private void inject(String name, Object value) throws Exception
	{
		Field field = FullscreenPlugin.class.getDeclaredField(name);
		field.setAccessible(true);
		field.set(plugin, value);
	}

	private void injectIfPresent(String name, Object value) throws Exception
	{
		try
		{
			inject(name, value);
		}
		catch (NoSuchFieldException ignored)
		{
			// The pre-fix plugin has no session profile.
		}
	}
}
