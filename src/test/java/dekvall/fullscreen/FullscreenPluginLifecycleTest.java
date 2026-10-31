package dekvall.fullscreen;

import java.awt.Canvas;
import java.awt.Frame;
import java.awt.Rectangle;
import java.awt.event.KeyEvent;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.Timer;
import java.awt.event.ActionEvent;
import net.runelite.api.Client;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.config.Keybind;
import net.runelite.client.config.RuneLiteConfig;
import net.runelite.client.input.KeyListener;
import net.runelite.client.input.KeyManager;
import net.runelite.client.plugins.PluginManager;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.ClientUI;
import net.runelite.client.ui.ContainableFrame;
import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import static org.mockito.Mockito.*;
import static org.junit.Assert.*;

public class FullscreenPluginLifecycleTest
{
	@Rule
	public TemporaryFolder temporary = new TemporaryFolder();

	@Test
	public void interruptedAutomaticStartupStaysWindowedAndDisablesFutureAutoEntry() throws Exception
	{
		Path directory = temporary.newFolder().toPath();
		StartupRecovery interrupted = new StartupRecovery(net.runelite.client.util.Filepath.Unchecked.getRooted(directory));
		interrupted.begin();
		interrupted.finish(false);
		ConfigManager manager = mock(ConfigManager.class);
		FullscreenConfig config = mock(FullscreenConfig.class);
		when(config.fullscreenOnStartup()).thenReturn(true);
		when(manager.getConfig(RuneLiteConfig.class)).thenReturn(mock(RuneLiteConfig.class));
		FullscreenPlugin plugin = automaticPlugin(directory, manager, config);
		ContainableFrame frame = mock(ContainableFrame.class);
		when(frame.isDisplayable()).thenReturn(true);
		when(frame.getBounds()).thenReturn(new Rectangle(100, 100, 800, 600));
		FullscreenControllerTest.edt(() ->
		{
			try (MockedStatic<Frame> frames = mockStatic(Frame.class))
			{
				frames.when(Frame::getFrames).thenReturn(new Frame[]{frame});
				plugin.startUp();
				verify(manager).setConfiguration("Fullscreen", "fullscreenOnStartup", false);
				verify(frame, never()).setBounds(any(Rectangle.class));
				// Suppress the queued informational dialog during this headless unit test.
				plugin.shutDown();
			}
		});
		StartupRecovery next = new StartupRecovery(net.runelite.client.util.Filepath.Unchecked.getRooted(directory));
		assertEquals(StartupRecovery.Result.READY, next.begin());
		next.finish(true);
	}

	@Test
	public void interruptedManualChangeIsReportedAtNextLaunchEvenWithStartupOff() throws Exception
	{
		Path directory = temporary.newFolder().toPath();
		StartupRecovery interrupted = new StartupRecovery(net.runelite.client.util.Filepath.Unchecked.getRooted(directory));
		interrupted.begin();
		interrupted.finish(false); // RuneLite died during a hotkey change
		ConfigManager manager = mock(ConfigManager.class);
		FullscreenConfig config = mock(FullscreenConfig.class);
		when(config.fullscreenOnStartup()).thenReturn(false);
		when(manager.getConfig(RuneLiteConfig.class)).thenReturn(mock(RuneLiteConfig.class));
		FullscreenPlugin plugin = automaticPlugin(directory, manager, config);
		ContainableFrame frame = mock(ContainableFrame.class);
		when(frame.isDisplayable()).thenReturn(true);
		when(frame.getBounds()).thenReturn(new Rectangle(100, 100, 800, 600));
		FullscreenControllerTest.edt(() ->
		{
			try (MockedStatic<Frame> frames = mockStatic(Frame.class))
			{
				frames.when(Frame::getFrames).thenReturn(new Frame[]{frame});
				plugin.startUp();
				plugin.shutDown(); // suppress the queued informational dialog
			}
		});
		verify(manager, never()).setConfiguration("Fullscreen", "fullscreenOnStartup", false);
		assertFalse("The leftover marker is removed", Files.exists(directory.resolve("startup.pending")));
		StartupRecovery next = new StartupRecovery(net.runelite.client.util.Filepath.Unchecked.getRooted(directory));
		assertEquals(StartupRecovery.Result.READY, next.check());
	}

	@Test
	public void customChromeAtStartupOffersOnceAndKeepsFullscreenOnStartup() throws Exception
	{
		Path directory = temporary.newFolder().toPath();
		ConfigManager manager = mock(ConfigManager.class);
		FullscreenConfig config = mock(FullscreenConfig.class);
		RuneLiteConfig runelite = mock(RuneLiteConfig.class);
		java.util.concurrent.atomic.AtomicBoolean offerShown = new java.util.concurrent.atomic.AtomicBoolean(false);
		when(config.fullscreenOnStartup()).thenReturn(true);
		when(config.customChromeStartupOfferShown()).thenAnswer(call -> offerShown.get());
		doAnswer(call ->
		{
			offerShown.set(call.getArgument(2));
			return null;
		}).when(manager).setConfiguration(eq("Fullscreen"), eq("customChromeStartupOfferShown"), any(Object.class));
		when(manager.getConfig(RuneLiteConfig.class)).thenReturn(runelite);
		when(runelite.enableCustomChrome()).thenReturn(true);
		ContainableFrame frame = mock(ContainableFrame.class);
		when(frame.isDisplayable()).thenReturn(true);
		when(frame.getBounds()).thenReturn(new Rectangle(100, 100, 800, 600));
		for (int launch = 0; launch < 2; launch++)
		{
			FullscreenPlugin plugin = automaticPlugin(directory, manager, config);
			FullscreenControllerTest.edt(() ->
			{
				try (MockedStatic<Frame> frames = mockStatic(Frame.class))
				{
					frames.when(Frame::getFrames).thenReturn(new Frame[]{frame});
					plugin.startUp();
				}
			});
			FullscreenControllerTest.edt(() -> {}); // let the offer dialog run
			FullscreenControllerTest.edt(plugin::shutDown);
		}
		assertEquals("Offered on the first blocked startup only", 1, chromeOffers.size());
		assertTrue(chromeOffers.get(0).contains("Fullscreen on startup"));
		verify(manager, never()).setConfiguration("Fullscreen", "fullscreenOnStartup", false);
		verify(frame, never()).setBounds(any(Rectangle.class));
		StartupRecovery next = new StartupRecovery(net.runelite.client.util.Filepath.Unchecked.getRooted(directory));
		assertEquals("No recovery marker is left behind", StartupRecovery.Result.READY, next.begin());
		next.finish(true);
	}

	@Test
	public void startupOfferIsResetOnceCustomChromeIsOff() throws Exception
	{
		Path directory = temporary.newFolder().toPath();
		ConfigManager manager = mock(ConfigManager.class);
		FullscreenConfig config = mock(FullscreenConfig.class);
		when(config.fullscreenOnStartup()).thenReturn(true);
		when(config.customChromeStartupOfferShown()).thenReturn(true);
		when(manager.getConfig(RuneLiteConfig.class)).thenReturn(mock(RuneLiteConfig.class));
		FullscreenPlugin plugin = automaticPlugin(directory, manager, config);
		ContainableFrame frame = mock(ContainableFrame.class);
		when(frame.isDisplayable()).thenReturn(true);
		when(frame.getBounds()).thenReturn(new Rectangle(100, 100, 800, 600));
		FullscreenControllerTest.edt(() ->
		{
			try (MockedStatic<Frame> frames = mockStatic(Frame.class))
			{
				frames.when(Frame::getFrames).thenReturn(new Frame[]{frame});
				plugin.startUp();
				plugin.shutDown(); // headless: entry itself cannot reach a display here
			}
		});
		verify(manager).setConfiguration("Fullscreen", "customChromeStartupOfferShown", false);
		assertTrue(chromeOffers.isEmpty());
	}

	@Test
	public void reEnablingWithoutRestartPutsThePluginsOwnChromeSettingBackSilently() throws Exception
	{
		// Custom chrome was turned off by the plugin, but RuneLite was not restarted, so
		// the window still has custom chrome.
		assertFalse("Silently turned off again", toggleOffAndOn(true, 42));
		assertTrue("Without asking", chromeOffers.isEmpty());
	}

	@Test
	public void reEnablingAfterTheRestoreTookEffectOnRestartOffersAgain() throws Exception
	{
		assertTrue("The restored setting is left alone", toggleOffAndOn(true, 43));
		assertEquals(1, chromeOffers.size());
	}

	@Test
	public void reEnablingWithoutRestartAfterChromeWasAlreadyOffKeepsItOff() throws Exception
	{
		// Restarted after turning it off (no chrome on the window), then toggled the plugin.
		assertFalse(toggleOffAndOn(false, 42));
		assertTrue(chromeOffers.isEmpty());
	}

	/** Returns RuneLite's custom chrome setting after the plugin is turned off, then on in session {@code secondSession}. */
	private boolean toggleOffAndOn(boolean chromeOnWindow, long secondSession) throws Exception
	{
		Path directory = temporary.newFolder().toPath();
		ConfigManager manager = mock(ConfigManager.class);
		FullscreenConfig config = mock(FullscreenConfig.class);
		RuneLiteConfig runelite = mock(RuneLiteConfig.class);
		java.util.Map<String, Object> values = new java.util.HashMap<>();
		values.put("uiEnableCustomChrome", false);
		values.put("customChromeDisabledByPlugin", true);
		values.put("customChromeRestoredSession", 0L);
		values.put("customChromeStartupOfferShown", false);
		doAnswer(call ->
		{
			values.put(call.getArgument(1), call.getArgument(2));
			return null;
		}).when(manager).setConfiguration(anyString(), anyString(), any(Object.class));
		when(manager.getConfig(RuneLiteConfig.class)).thenReturn(runelite);
		when(runelite.enableCustomChrome()).thenAnswer(call -> values.get("uiEnableCustomChrome"));
		when(config.customChromeDisabledByPlugin()).thenAnswer(call -> values.get("customChromeDisabledByPlugin"));
		when(config.customChromeRestoredSession()).thenAnswer(call -> values.get("customChromeRestoredSession"));
		when(config.customChromeStartupOfferShown()).thenAnswer(call -> values.get("customChromeStartupOfferShown"));
		when(config.fullscreenOnStartup()).thenReturn(true);
		ContainableFrame frame = mock(ContainableFrame.class);
		when(frame.isDisplayable()).thenReturn(true);
		when(frame.getBounds()).thenReturn(new Rectangle(100, 100, 800, 600));
		if (chromeOnWindow)
		{
			when(frame.getJMenuBar()).thenReturn(new javax.swing.JMenuBar());
		}
		clientSession = 42;
		FullscreenPlugin first = automaticPlugin(directory, manager, config);
		run(frame, first::startUp);
		FullscreenControllerTest.edt(first::shutDown);
		assertEquals("Turning the plugin off restores custom chrome", true, values.get("uiEnableCustomChrome"));
		FullscreenControllerTest.edt(() -> {});
		assertEquals("And says so", java.util.Collections.singletonList(FullscreenPlugin.CHROME_RESTORED), chromeNotices);
		clientSession = secondSession;
		FullscreenPlugin second = automaticPlugin(directory, manager, config);
		run(frame, second::startUp);
		FullscreenControllerTest.edt(() -> {}); // let any offer dialog run
		boolean whileEnabled = (Boolean) values.get("uiEnableCustomChrome");
		assertEquals("The plugin owns the change exactly when it turned chrome off", !whileEnabled,
			values.get("customChromeDisabledByPlugin"));
		assertEquals(0L, values.get("customChromeRestoredSession"));
		FullscreenControllerTest.edt(second::shutDown);
		if (!whileEnabled)
		{
			assertEquals("Turning it off again still restores", true, values.get("uiEnableCustomChrome"));
		}
		return whileEnabled;
	}

	private static void run(Frame frame, ThrowingRunnable action) throws Exception
	{
		FullscreenControllerTest.edt(() ->
		{
			try (MockedStatic<Frame> frames = mockStatic(Frame.class))
			{
				frames.when(Frame::getFrames).thenReturn(new Frame[]{frame});
				action.run();
			}
			catch (Exception ex)
			{
				throw new AssertionError(ex);
			}
		});
	}

	private interface ThrowingRunnable
	{
		void run() throws Exception;
	}

	private long clientSession = 42;
	private final java.util.List<String> chromeNotices = new java.util.ArrayList<>();
	private final java.util.List<String> chromeOffers = new java.util.ArrayList<>();

	private FullscreenPlugin automaticPlugin(Path directory, ConfigManager manager, FullscreenConfig config) throws Exception
	{
		FullscreenPlugin plugin = new FullscreenPlugin()
		{
			@Override
			StartupRecovery createStartupRecovery()
			{
				return new StartupRecovery(net.runelite.client.util.Filepath.Unchecked.getRooted(directory));
			}

			@Override
			boolean askToDisableCustomChrome(String message)
			{
				chromeOffers.add(message);
				return false;
			}

			@Override
			long clientSession()
			{
				return clientSession;
			}

			@Override
			void showChromeRestoredNotice(String message)
			{
				chromeNotices.add(message);
			}
		};
		inject(plugin, "client", mock(Client.class));
		inject(plugin, "clientUI", mock(ClientUI.class));
		inject(plugin, "configManager", manager);
		inject(plugin, "keyManager", mock(KeyManager.class));
		inject(plugin, "config", config);
		inject(plugin, "clientToolbar", mock(ClientToolbar.class));
		inject(plugin, "clientThread", mock(ClientThread.class));
		inject(plugin, "pluginManager", mock(PluginManager.class));
		return plugin;
	}

	@Test
	public void stableStartupMarkerClearsOnlyAfterClientThreadResponds() throws Exception
	{
		Path directory = temporary.newFolder().toPath();
		StartupRecovery recovery = new StartupRecovery(net.runelite.client.util.Filepath.Unchecked.getRooted(directory));
		recovery.begin();
		FullscreenPlugin plugin = automaticPlugin(directory, mock(ConfigManager.class), mock(FullscreenConfig.class));
		FullscreenController controller = mock(FullscreenController.class);
		when(controller.isActive()).thenReturn(true);
		ClientThread clientThread = mock(ClientThread.class);
		AtomicReference<Runnable> heartbeat = new AtomicReference<>();
		doAnswer(call ->
		{
			heartbeat.set(call.getArgument(0));
			return null;
		}).when(clientThread).invokeLater(any(Runnable.class));
		inject(plugin, "controller", controller);
		inject(plugin, "clientThread", clientThread);
		inject(plugin, "enabled", true);
		inject(plugin, "startupRecovery", recovery);
		try
		{
			FullscreenControllerTest.edt(() ->
			{
				try
				{
					java.lang.reflect.Method changed = FullscreenPlugin.class.getDeclaredMethod("transitionChanged");
					changed.setAccessible(true);
					changed.invoke(plugin);
					Field timerField = FullscreenPlugin.class.getDeclaredField("startupTimer");
					timerField.setAccessible(true);
					Timer timer = (Timer) timerField.get(plugin);
					timer.getActionListeners()[0].actionPerformed(new ActionEvent(timer, 0, "grace elapsed"));
				}
				catch (ReflectiveOperationException ex)
				{
					throw new AssertionError(ex);
				}
			});
			assertTrue(Files.exists(directory.resolve("startup.pending")));
			assertNotNull(heartbeat.get());
			heartbeat.get().run();
			FullscreenControllerTest.edt(() -> {});
			assertFalse(Files.exists(directory.resolve("startup.pending")));
		}
		finally
		{
			FullscreenControllerTest.edt(plugin::shutDown);
			recovery.finish(true);
		}
	}
	@Test
	public void queuedHotkeyFromOldSessionDoesNotToggleNewSession() throws Exception
	{
		FullscreenPlugin plugin = new FullscreenPlugin();
		KeyManager keys = mock(KeyManager.class);
		ConfigManager configurations = mock(ConfigManager.class);
		FullscreenConfig config = mock(FullscreenConfig.class);
		ContainableFrame frame = mock(ContainableFrame.class);
		when(frame.isDisplayable()).thenReturn(true);
		when(frame.getBounds()).thenReturn(new Rectangle(100, 100, 800, 600));
		when(config.fullscreenHotKey()).thenReturn(new Keybind(KeyEvent.VK_F11, 0));
		inject(plugin, "client", mock(Client.class));
		inject(plugin, "clientUI", mock(ClientUI.class));
		inject(plugin, "configManager", configurations);
		inject(plugin, "keyManager", keys);
		inject(plugin, "config", config);
		inject(plugin, "clientToolbar", mock(ClientToolbar.class));
		inject(plugin, "clientThread", mock(ClientThread.class));
		inject(plugin, "pluginManager", mock(PluginManager.class));
		ArgumentCaptor<KeyListener> listeners = ArgumentCaptor.forClass(KeyListener.class);
		FullscreenControllerTest.edt(() ->
		{
			try (MockedStatic<Frame> frames = mockStatic(Frame.class))
			{
				frames.when(Frame::getFrames).thenReturn(new Frame[]{frame});
				plugin.startUp();
				verify(keys).registerKeyListener(listeners.capture());
				listeners.getValue().keyPressed(new KeyEvent(new Canvas(), KeyEvent.KEY_PRESSED, 0, 0,
					KeyEvent.VK_F11, KeyEvent.CHAR_UNDEFINED)
				{
					@Override
					public int getExtendedKeyCode()
					{
						return KeyEvent.VK_F11;
					}
				});
				plugin.shutDown();
				plugin.startUp();
			}
		});
		try
		{
			FullscreenControllerTest.edt(() -> {});
			// Even entry validation would mean the old key press reached the new session.
			// (Session setup itself only reads the active profile.)
			verify(configurations, never()).getConfig(any());
			verify(configurations, never()).setConfiguration(anyString(), anyString(), any(Object.class));
			verify(keys, times(2)).registerKeyListener(any());
			verify(keys).unregisterKeyListener(listeners.getValue());
		}
		finally
		{
			FullscreenControllerTest.edt(plugin::shutDown);
		}
	}

	@Test
	public void staleFullscreenModeIsShownAsWindowedWhenStartupEntryIsOff() throws Exception
	{
		ConfigManager manager = mock(ConfigManager.class);
		FullscreenConfig config = mock(FullscreenConfig.class);
		when(config.fullscreenOnStartup()).thenReturn(false);
		when(config.fullscreenMode()).thenReturn(Mode.EXCLUSIVE);
		when(config.lastFullscreenMode()).thenReturn(Mode.BORDERLESS);
		FullscreenPlugin plugin = automaticPlugin(temporary.newFolder().toPath(), manager, config);
		ContainableFrame frame = mock(ContainableFrame.class);
		when(frame.isDisplayable()).thenReturn(true);
		when(frame.getBounds()).thenReturn(new Rectangle(100, 100, 800, 600));
		FullscreenControllerTest.edt(() ->
		{
			try (MockedStatic<Frame> frames = mockStatic(Frame.class))
			{
				frames.when(Frame::getFrames).thenReturn(new Frame[]{frame});
				plugin.startUp();
				plugin.shutDown();
			}
		});
		// The hotkey and toolbar still return to the mode the dropdown showed.
		verify(manager).setConfiguration("Fullscreen", "lastFullscreenMode", Mode.EXCLUSIVE);
		verify(manager).setConfiguration("Fullscreen", "fullscreenMode", Mode.WINDOWED);
		verify(manager, never()).setConfiguration("Fullscreen", "fullscreenOnStartup", false);
	}

	@Test
	public void startupEntryIsAttemptedWhenPreviousSessionEndedWindowed() throws Exception
	{
		Path directory = temporary.newFolder().toPath();
		ConfigManager manager = mock(ConfigManager.class);
		FullscreenConfig config = mock(FullscreenConfig.class);
		when(config.fullscreenOnStartup()).thenReturn(true);
		when(config.fullscreenMode()).thenReturn(Mode.WINDOWED);
		when(config.lastFullscreenMode()).thenReturn(Mode.EXCLUSIVE);
		when(manager.getConfig(RuneLiteConfig.class)).thenReturn(mock(RuneLiteConfig.class));
		FullscreenPlugin plugin = automaticPlugin(directory, manager, config);
		ContainableFrame frame = mock(ContainableFrame.class);
		when(frame.isDisplayable()).thenReturn(true);
		when(frame.getBounds()).thenReturn(new Rectangle(100, 100, 800, 600));
		FullscreenControllerTest.edt(() ->
		{
			try (MockedStatic<Frame> frames = mockStatic(Frame.class))
			{
				frames.when(Frame::getFrames).thenReturn(new Frame[]{frame});
				plugin.startUp();
				plugin.shutDown(); // headless: entry itself cannot reach a display here
			}
		});
		assertTrue("Automatic entry must be attempted (recovery armed)", Files.exists(directory.resolve("startup.lock")));
		verify(manager, never()).setConfiguration("Fullscreen", "fullscreenMode", Mode.WINDOWED);
	}

	@Test
	public void pluginWaitsForWindowDuringClientStartup() throws Exception
	{
		FullscreenPlugin plugin = automaticPlugin(temporary.newFolder().toPath(),
			mock(ConfigManager.class), mock(FullscreenConfig.class));
		KeyManager keys = mock(KeyManager.class);
		inject(plugin, "keyManager", keys);
		ContainableFrame frame = mock(ContainableFrame.class);
		when(frame.isDisplayable()).thenReturn(true);
		when(frame.getBounds()).thenReturn(new Rectangle(100, 100, 800, 600));
		FullscreenControllerTest.edt(() ->
		{
			try (MockedStatic<Frame> frames = mockStatic(Frame.class))
			{
				frames.when(Frame::getFrames).thenReturn(new Frame[0]);
				plugin.startUp();
				verify(keys, never()).registerKeyListener(any());
				try
				{
					Field field = FullscreenPlugin.class.getDeclaredField("windowReadyTimer");
					field.setAccessible(true);
					Timer timer = (Timer) field.get(plugin);
					assertNotNull(timer);
					frames.when(Frame::getFrames).thenReturn(new Frame[]{frame});
					timer.getActionListeners()[0].actionPerformed(new ActionEvent(timer, 0, "window ready"));
					assertNull(field.get(plugin));
					verify(keys).registerKeyListener(any());
				}
				catch (ReflectiveOperationException ex)
				{
					throw new AssertionError(ex);
				}
				finally
				{
					plugin.shutDown();
				}
			}
		});
	}

	@Test
	public void dropdownAndHotkeySwitchModesWithoutFeedbackToggles() throws Exception
	{
		ConfigManager manager = mock(ConfigManager.class);
		FullscreenConfig config = mock(FullscreenConfig.class);
		AtomicReference<Mode> selected = new AtomicReference<>(Mode.WINDOWED);
		AtomicReference<Mode> last = new AtomicReference<>(Mode.BORDERLESS);
		when(config.fullscreenMode()).thenAnswer(call -> selected.get());
		when(config.lastFullscreenMode()).thenAnswer(call -> last.get());
		when(manager.getConfig(RuneLiteConfig.class)).thenReturn(mock(RuneLiteConfig.class));
		FullscreenPlugin plugin = automaticPlugin(temporary.newFolder().toPath(), manager, config);
		java.lang.reflect.Method changed = FullscreenPlugin.class.getDeclaredMethod("transitionChanged");
		java.lang.reflect.Method toggle = FullscreenPlugin.class.getDeclaredMethod("toggle");
		changed.setAccessible(true);
		toggle.setAccessible(true);
		FullscreenController.Window window = mock(FullscreenController.Window.class);
		FullscreenController controller = new FullscreenController(window,
			mock(FullscreenController.Renderers.class), () ->
			{
				try { changed.invoke(plugin); }
				catch (ReflectiveOperationException ex) { throw new AssertionError(ex); }
			}, ex -> { throw new AssertionError(ex); });
		inject(plugin, "controller", controller);
		inject(plugin, "enabled", true);
		doAnswer(call ->
		{
			String key = call.getArgument(1);
			if ("fullscreenMode".equals(key)) { selected.set(call.getArgument(2)); }
			if ("lastFullscreenMode".equals(key)) { last.set(call.getArgument(2)); }
			net.runelite.client.events.ConfigChanged event = mock(net.runelite.client.events.ConfigChanged.class);
			when(event.getGroup()).thenReturn("Fullscreen");
			when(event.getKey()).thenReturn(key);
			plugin.onConfigChanged(event);
			return null;
		}).when(manager).setConfiguration(eq("Fullscreen"), anyString(), any(Mode.class));
		FullscreenControllerTest.edt(() ->
		{
			manager.setConfiguration("Fullscreen", "fullscreenMode", Mode.BORDERLESS);
			assertEquals(Mode.BORDERLESS, controller.currentMode());
			manager.setConfiguration("Fullscreen", "fullscreenMode", Mode.WINDOWED);
			assertEquals(Mode.WINDOWED, controller.currentMode());
			try
			{
				toggle.invoke(plugin);
				assertEquals(Mode.BORDERLESS, selected.get());
				toggle.invoke(plugin);
				assertEquals(Mode.WINDOWED, selected.get());
			}
			catch (ReflectiveOperationException ex) { throw new AssertionError(ex); }
			verify(window, times(2)).enter(Mode.BORDERLESS);
			verify(window, times(2)).exit();
		});
	}

	private static void inject(Object target, String name, Object value) throws Exception
	{
		Field field = FullscreenPlugin.class.getDeclaredField(name);
		field.setAccessible(true);
		field.set(target, value);
	}
}
