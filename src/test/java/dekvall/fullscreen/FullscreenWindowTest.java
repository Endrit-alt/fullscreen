package dekvall.fullscreen;

import java.awt.Frame;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsDevice;
import java.awt.Rectangle;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class FullscreenWindowTest
{
	private Frame frame;
	private GraphicsDevice device;
	private GraphicsConfiguration configuration;
	private FullscreenWindow window;
	private final Rectangle original = new Rectangle(-1700, 80, 900, 700);
	private final Rectangle monitor = new Rectangle(-1920, 0, 1920, 1080);

	@Before
	public void setUp() throws Exception
	{
		frame = mock(Frame.class);
		device = mock(GraphicsDevice.class);
		configuration = mock(GraphicsConfiguration.class);
		when(frame.getBounds()).thenReturn(original);
		when(frame.getGraphicsConfiguration()).thenReturn(configuration);
		when(frame.isDisplayable()).thenReturn(true);
		when(frame.isVisible()).thenReturn(true);
		when(frame.isResizable()).thenReturn(false);
		when(configuration.getBounds()).thenReturn(monitor);
		when(configuration.getDevice()).thenReturn(device);
		when(device.isFullScreenSupported()).thenReturn(true);
		FullscreenControllerTest.edt(() -> window = new FullscreenWindow(frame));
	}

	@Test
	public void bothModesStopGpuRenderersBecauseNoNativeWindowCodeIsUsed()
	{
		assertTrue(window.needsRendererRestart(Mode.BORDERLESS));
		assertTrue(window.needsRendererRestart(Mode.EXCLUSIVE));
	}

	@Test
	public void borderlessMonitorMovesKeepTheUndecoratedWindowAndOriginalPlacement() throws Exception
	{
		GraphicsConfiguration other = mock(GraphicsConfiguration.class);
		GraphicsDevice otherDevice = mock(GraphicsDevice.class);
		Rectangle otherBounds = new Rectangle(0, -300, 2560, 1440);
		when(other.getBounds()).thenReturn(otherBounds);
		when(other.getDevice()).thenReturn(otherDevice);
		when(otherDevice.getDefaultConfiguration()).thenReturn(other);
		when(device.getDefaultConfiguration()).thenReturn(configuration);
		when(frame.getInsets()).thenReturn(new java.awt.Insets(31, 8, 8, 8));
		java.util.concurrent.atomic.AtomicBoolean undecorated = new java.util.concurrent.atomic.AtomicBoolean(false);
		when(frame.isUndecorated()).thenAnswer(call -> undecorated.get());
		doAnswer(call ->
		{
			undecorated.set(call.getArgument(0));
			return null;
		}).when(frame).setUndecorated(anyBoolean());
		java.util.concurrent.atomic.AtomicReference<Rectangle> bounds = new java.util.concurrent.atomic.AtomicReference<>(original);
		when(frame.getBounds()).thenAnswer(call -> new Rectangle(bounds.get()));
		doAnswer(call ->
		{
			bounds.set(new Rectangle((Rectangle) call.getArgument(0)));
			return null;
		}).when(frame).setBounds(any(Rectangle.class));
		java.util.concurrent.atomic.AtomicReference<GraphicsConfiguration> target =
			new java.util.concurrent.atomic.AtomicReference<>(configuration);
		FullscreenControllerTest.edt(() ->
		{
			window.detach();
			window = new FullscreenWindow(frame, target::get);
			window.setScreens(() -> new GraphicsDevice[]{device, otherDevice});
			assertNull(window.restorePlacement());
			window.enter(Mode.BORDERLESS);
			for (int i = 0; i < 3; i++)
			{
				target.set(i % 2 == 0 ? other : configuration);
				window.validate(Mode.BORDERLESS);
				assertTrue(window.moveToMonitor(Mode.BORDERLESS));
				assertEquals(target.get().getBounds(), bounds.get());
			}
			FullscreenWindow.Placement placement = window.restorePlacement();
			assertEquals(new Rectangle(-1692, 111, 884, 661), placement.content);
			assertEquals("-1692:111:884:661:c", placement.clientBoundsConfig());
			assertFalse(placement.maximized);
			window.exit();
			assertNull(window.restorePlacement());
		});
		// One peer replacement to enter and one to exit; monitor moves only change bounds.
		verify(frame, times(2)).dispose();
		assertEquals(original, bounds.get());
	}

	@Test
	public void windowMaximizedAllSessionRestoresItsRealNormalSize() throws Exception
	{
		// RuneLite often starts maximized, so the plugin never saw the normal size.
		Rectangle maximizedBounds = new Rectangle(-1928, -8, 1936, 1056);
		Rectangle restoredByWindows = new Rectangle(-1500, 120, 1000, 650);
		java.util.concurrent.atomic.AtomicReference<Rectangle> bounds = new java.util.concurrent.atomic.AtomicReference<>(maximizedBounds);
		java.util.concurrent.atomic.AtomicInteger state = new java.util.concurrent.atomic.AtomicInteger(Frame.MAXIMIZED_BOTH);
		when(frame.getBounds()).thenAnswer(call -> new Rectangle(bounds.get()));
		doAnswer(call ->
		{
			bounds.set(new Rectangle((Rectangle) call.getArgument(0)));
			return null;
		}).when(frame).setBounds(any(Rectangle.class));
		when(frame.getExtendedState()).thenAnswer(call -> state.get());
		doAnswer(call ->
		{
			int newState = call.getArgument(0);
			if (state.get() == Frame.MAXIMIZED_BOTH && newState == Frame.NORMAL)
			{
				bounds.set(restoredByWindows); // Windows reports this synchronously
			}
			state.set(newState);
			return null;
		}).when(frame).setExtendedState(anyInt());
		FullscreenControllerTest.edt(() ->
		{
			window.detach();
			window = new FullscreenWindow(frame);
			window.enter(Mode.BORDERLESS);
			assertTrue(window.restorePlacement().maximized);
			assertEquals(restoredByWindows, window.restorePlacement().content);
			window.exit();
		});
		org.mockito.InOrder order = inOrder(frame);
		order.verify(frame).setBounds(restoredByWindows);
		order.verify(frame).setExtendedState(Frame.MAXIMIZED_BOTH);
		assertEquals(Frame.MAXIMIZED_BOTH, state.get());
	}

	@Test
	public void alwaysOnTopChangedWhileFullscreenIsKeptForExit() throws Exception
	{
		java.util.concurrent.atomic.AtomicBoolean topmost = new java.util.concurrent.atomic.AtomicBoolean(false);
		when(frame.isAlwaysOnTop()).thenAnswer(call -> topmost.get());
		FullscreenControllerTest.edt(() ->
		{
			window.enter(Mode.EXCLUSIVE);
			topmost.set(true);
			window.windowSettingsChanged(false);
			window.exit();
		});
		verify(frame).setAlwaysOnTop(true);
	}

	@Test
	public void undecoratedBorderlessStaysNonResizableAfterRuneLiteUnlocksSize() throws Exception
	{
		java.util.concurrent.atomic.AtomicBoolean resizable = new java.util.concurrent.atomic.AtomicBoolean(false);
		when(frame.isResizable()).thenAnswer(call -> resizable.get());
		doAnswer(call ->
		{
			resizable.set(call.getArgument(0));
			return null;
		}).when(frame).setResizable(anyBoolean());
		FullscreenControllerTest.edt(() ->
		{
			window.enter(Mode.BORDERLESS);
			resizable.set(true);
			window.windowSettingsChanged(true);
			assertFalse(resizable.get());
			window.exit();
		});
		assertTrue("Exit honours the newly unlocked size", resizable.get());
	}

	@Test
	public void maximizedEntryReportsMaximizedRestorePlacement() throws Exception
	{
		when(frame.getExtendedState()).thenReturn(Frame.MAXIMIZED_BOTH);
		FullscreenControllerTest.edt(() ->
		{
			window.enter(Mode.EXCLUSIVE);
			assertTrue(window.restorePlacement().maximized);
			window.exit();
		});
	}

	@Test
	public void borderlessUsesWholeMonitorAndNeverPacks() throws Exception
	{
		FullscreenControllerTest.edt(() -> window.enter(Mode.BORDERLESS));
		verify(frame, atLeastOnce()).setBounds(monitor);
		verify(frame, never()).pack();
		verify(frame, never()).setExtendedState(anyInt()); // already in the normal state
		assertTrue(window.needsRendererRestart(Mode.BORDERLESS));
	}

	@Test
	public void preferredMonitorIsUsedForExclusiveEntryAndOriginalWindowIsRestored() throws Exception
	{
		GraphicsConfiguration preferred = mock(GraphicsConfiguration.class);
		GraphicsDevice preferredDevice = mock(GraphicsDevice.class);
		when(preferred.getBounds()).thenReturn(new Rectangle(0, 0, 2560, 1440));
		when(preferred.getDevice()).thenReturn(preferredDevice);
		when(preferredDevice.isFullScreenSupported()).thenReturn(true);
		when(preferredDevice.getFullScreenWindow()).thenReturn(frame);
		FullscreenControllerTest.edt(() ->
		{
			window.detach();
			window = new FullscreenWindow(frame, () -> preferred);
			window.enter(Mode.EXCLUSIVE);
			window.exit();
		});
		verify(preferredDevice).setFullScreenWindow(frame);
		verify(preferredDevice).setFullScreenWindow(null);
		verify(device, never()).setFullScreenWindow(any());
		verify(frame).setBounds(original);
	}

	@Test
	public void modeSwitchKeepsMonitorChosenBeforeOriginalWindowIsRestored() throws Exception
	{
		GraphicsConfiguration secondary = mock(GraphicsConfiguration.class);
		GraphicsDevice secondaryDevice = mock(GraphicsDevice.class);
		when(secondary.getBounds()).thenReturn(new Rectangle(1920, 0, 2560, 1440));
		when(secondary.getDevice()).thenReturn(secondaryDevice);
		when(secondaryDevice.isFullScreenSupported()).thenReturn(true);
		when(secondaryDevice.getFullScreenWindow()).thenReturn(frame);
		java.util.concurrent.atomic.AtomicReference<GraphicsConfiguration> current =
			new java.util.concurrent.atomic.AtomicReference<>(secondary);
		FullscreenControllerTest.edt(() ->
		{
			window.detach();
			window = new FullscreenWindow(frame, current::get);
			window.enter(Mode.BORDERLESS);
			window.validate(Mode.EXCLUSIVE);
			window.exit();
			current.set(configuration); // The original window belongs to a different monitor.
			window.enter(Mode.EXCLUSIVE);
			window.exit();
		});
		verify(secondaryDevice).setFullScreenWindow(frame);
		verify(device, never()).setFullScreenWindow(frame);
	}

	@Test
	public void exitRestoresOriginalBoundsAndResizeLock() throws Exception
	{
		FullscreenControllerTest.edt(() ->
		{
			window.enter(Mode.BORDERLESS);
			window.exit();
			window.exit();
		});
		verify(frame).setBounds(original);
		verify(frame, atLeastOnce()).setResizable(false);
	}

	@Test
	public void exclusiveExitUsesOriginalDeviceAfterMonitorChanges() throws Exception
	{
		FullscreenControllerTest.edt(() -> window.enter(Mode.EXCLUSIVE));
		when(device.getFullScreenWindow()).thenReturn(frame);
		GraphicsDevice otherDevice = mock(GraphicsDevice.class);
		when(configuration.getDevice()).thenReturn(otherDevice);
		FullscreenControllerTest.edt(window::exit);
		verify(device).setFullScreenWindow(frame);
		verify(device).setFullScreenWindow(null);
		verify(otherDevice, never()).setFullScreenWindow(any());
	}

	@Test
	public void exclusiveExitDoesNotReleaseAnotherApplicationsWindow() throws Exception
	{
		FullscreenControllerTest.edt(() -> window.enter(Mode.EXCLUSIVE));
		when(device.getFullScreenWindow()).thenReturn(mock(Frame.class));
		FullscreenControllerTest.edt(window::exit);
		verify(device, never()).setFullScreenWindow(null);
	}

	@Test
	public void unsupportedExclusiveDoesNotChangeWindow() throws Exception
	{
		when(device.isFullScreenSupported()).thenReturn(false);
		FullscreenControllerTest.edt(() ->
			assertThrows(IllegalStateException.class, () -> window.enter(Mode.EXCLUSIVE)));
		verify(frame, never()).setExtendedState(anyInt());
		verify(device, never()).setFullScreenWindow(any());
	}

	@Test
	public void closedClientIsNotRecreatedOnExit() throws Exception
	{
		FullscreenControllerTest.edt(() -> window.enter(Mode.BORDERLESS));
		clearInvocations(frame);
		when(frame.isDisplayable()).thenReturn(false);
		FullscreenControllerTest.edt(window::exit);
		verify(frame, never()).setVisible(true);
		verify(frame, never()).setBounds(any(Rectangle.class));
	}

	@Test
	public void entryRequiresPreviousSnapshotToBeRestored() throws Exception
	{
		FullscreenControllerTest.edt(() ->
		{
			window.enter(Mode.BORDERLESS);
			assertThrows(IllegalStateException.class, () -> window.enter(Mode.EXCLUSIVE));
			window.exit();
		});
		verify(device, never()).setFullScreenWindow(frame);
	}

	@Test
	public void failedDecorationRestoreCanRetryWithoutLosingHiddenWindow() throws Exception
	{
		AtomicBoolean displayable = new AtomicBoolean(true);
		AtomicBoolean visible = new AtomicBoolean(true);
		AtomicBoolean undecorated = new AtomicBoolean(false);
		AtomicBoolean failRestore = new AtomicBoolean(true);
		when(frame.isDisplayable()).thenAnswer(call -> displayable.get());
		when(frame.isVisible()).thenAnswer(call -> visible.get());
		when(frame.isUndecorated()).thenAnswer(call -> undecorated.get());
		doAnswer(call ->
		{
			displayable.set(false);
			visible.set(false);
			return null;
		}).when(frame).dispose();
		doAnswer(call ->
		{
			boolean value = call.getArgument(0);
			if (!value && failRestore.getAndSet(false))
			{
				throw new IllegalStateException("Decorations could not be restored");
			}
			undecorated.set(value);
			return null;
		}).when(frame).setUndecorated(anyBoolean());
		doAnswer(call ->
		{
			visible.set(call.getArgument(0));
			displayable.set(true);
			return null;
		}).when(frame).setVisible(anyBoolean());
		FullscreenControllerTest.edt(() ->
		{
			window.enter(Mode.BORDERLESS);
			assertThrows(IllegalStateException.class, window::exit);
			assertFalse(visible.get());
			window.exit();
		});
		assertTrue(visible.get());
		assertTrue(displayable.get());
		assertFalse(undecorated.get());
		verify(frame).setBounds(original);
	}
}
